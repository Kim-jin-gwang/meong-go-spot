"""공공데이터포털 구조동물 조회 API 클라이언트 (표준 라이브러리만 사용)."""

import datetime
import json
import time
import urllib.parse
import urllib.request

BASE_URL = (
    "https://apis.data.go.kr/1543061/abandonmentPublicService_v2/abandonmentPublic_v2"
)
# bgnde/endde 없이 호출하면 접수일(happenDt) 기준 오늘-31일 ~ 오늘만 온다 (2026-09-24 스냅샷 실측: 08-24~09-24).
DEFAULT_WINDOW_DAYS = 31
RESYNC_INTERVAL_SEC = 0.2  # 창 밖 재조회 호출 간격 — 백필과 같은 초당 5호출 상한
# 분실동물 조회 (#145). 기간·지역 파라미터가 없고 최근 1개월치만 준다.
LOST_BASE_URL = "https://apis.data.go.kr/1543061/lossInfoService/lossInfo"
LOST_PAGE_SIZE = 100
PAGE_SIZE = 1000
MAX_RETRIES = 3
TIMEOUT_SEC = 30


class ApiError(RuntimeError):
    """API가 정상 응답(resultCode 00)을 주지 않은 경우."""


def _raise_if_gateway_error(body: str) -> None:
    try:
        payload = json.loads(body)
    except ValueError:
        return
    gateway_error = payload.get("OpenAPI_ServiceResponse")
    if gateway_error:  # 키 미등록 등 영구 오류 — 재시도 무의미
        message = (gateway_error.get("cmmMsgHeader") or {}).get("returnAuthMsg")
        raise ApiError(f"게이트웨이 오류: {message}")


def _request(url: str) -> dict:
    last_error: Exception | None = None
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            with urllib.request.urlopen(url, timeout=TIMEOUT_SEC) as res:
                body = res.read().decode("utf-8")
            payload = json.loads(body)
            _raise_if_gateway_error(body)
            response = payload.get("response") or {}
            header = response.get("header") or {}
            if header.get("resultCode") != "00":
                raise ApiError(f"resultCode={header.get('resultCode')} {header.get('resultMsg')}")
            return response.get("body") or {}
        except ApiError:
            raise  # 인증·파라미터 문제는 재시도해도 같은 결과
        except urllib.error.HTTPError as error:
            # 4xx는 본문에 게이트웨이 오류 JSON이 실려 온다 (예: 403 + 키 미등록)
            _raise_if_gateway_error(error.read().decode("utf-8", errors="replace"))
            if error.code < 500:
                raise ApiError(f"HTTP {error.code}: 재시도로 해결되지 않는 요청 오류") from error
            last_error = error  # 5xx는 일시 장애로 보고 재시도
            time.sleep(2**attempt)
        except Exception as error:  # noqa: BLE001 — 네트워크 계열은 전부 재시도
            last_error = error
            time.sleep(2**attempt)
    raise RuntimeError(f"{MAX_RETRIES}회 재시도 실패: {last_error}")


def _normalize_key(service_key: str) -> str:
    # 포털의 'Encoding 키'(%2B 등 포함)가 들어와도 이중 인코딩되지 않게 원형으로 되돌린다.
    # 'Decoding 키'(base64 계열 문자만)는 %가 없어 그대로 통과한다.
    return urllib.parse.unquote(service_key) if "%" in service_key else service_key


def fetch_page(
    service_key: str, page: int, extra: dict[str, str] | None = None
) -> tuple[list[dict], int]:
    """한 페이지를 받아 (레코드 목록, 전체 건수)를 반환한다.

    extra: 기간 슬라이스 등 추가 파라미터 (예: {"bgnde": "20220101", "endde": "20220131"}) —
    역사 백필이 월 단위로 조회할 때 사용한다.
    """
    query = urllib.parse.urlencode(
        {
            "serviceKey": _normalize_key(service_key),
            "numOfRows": str(PAGE_SIZE),
            "pageNo": str(page),
            "_type": "json",
            **(extra or {}),
        }
    )
    body = _request(f"{BASE_URL}?{query}")
    total = int(body.get("totalCount") or 0)
    items = body.get("items") or {}
    records = items.get("item") or []
    if isinstance(records, dict):  # 결과 1건이면 dict로 옴
        records = [records]
    return records, total


def fetch_pages(
    service_key: str, extra: dict[str, str] | None = None, interval_sec: float = 0.0
) -> tuple[list[dict], int]:
    """전 페이지를 순회해 (전체 레코드, API가 알린 totalCount)를 반환한다."""
    records: list[dict] = []
    page = 1
    total = 0
    while True:
        if page > 1 and interval_sec > 0:
            time.sleep(interval_sec)
        page_records, total = fetch_page(service_key, page, extra)
        if not page_records:
            break
        records.extend(page_records)
        if len(records) >= total:
            break
        page += 1
    return records, total


def fetch_all(service_key: str) -> tuple[list[dict], int]:
    """기간 없이 전 페이지를 받는다 — API 기본 창(접수일 최근 [DEFAULT_WINDOW_DAYS]일)만 온다."""
    return fetch_pages(service_key)


def month_slices(start: datetime.date, end: datetime.date) -> list[tuple[str, str]]:
    """[start, end] 를 달력 월 경계로 잘라 (bgnde, endde) 목록으로 만든다. 양 끝은 start·end 로 자른다."""
    slices: list[tuple[str, str]] = []
    cursor = start
    while cursor <= end:
        next_month = datetime.date(cursor.year + (cursor.month == 12), cursor.month % 12 + 1, 1)
        slice_end = min(next_month - datetime.timedelta(days=1), end)
        slices.append((cursor.strftime("%Y%m%d"), slice_end.strftime("%Y%m%d")))
        cursor = slice_end + datetime.timedelta(days=1)
    return slices


def resync_slices(today: datetime.date, lookback_days: int) -> list[tuple[str, str]]:
    """기본 창 밖 재조회 구간(접수일 오늘-lookback_days ~ 오늘-DEFAULT_WINDOW_DAYS-1)의 월 슬라이스. 창 안이면 빈 목록."""
    if lookback_days <= DEFAULT_WINDOW_DAYS:
        return []
    start = today - datetime.timedelta(days=lookback_days)
    end = today - datetime.timedelta(days=DEFAULT_WINDOW_DAYS + 1)
    return month_slices(start, end)


def count_tolerance(total: int) -> int:
    """월 슬라이스에서 허용하는 totalCount 와 실제 항목 수의 차이.

    과거 월은 API 의 totalCount 와 항목 수가 몇 건씩 어긋난다(페이지 사이 갱신·삭제된 레코드, 2026-09-03 백필
    실측 94개월에서 1~11건, 2026-09-25 재조회 2026-04 은 7318 대 7317). 유령 레코드는 받아올 방법이 없으므로
    소량은 허용하고, 큰 차이만 부분 수집으로 본다 — backfill.py 와 같은 기준.
    """
    return max(20, int(total * 0.005))


def fetch_lost_page(service_key: str, page: int) -> tuple[list[dict], int]:
    """분실동물 한 페이지 — (레코드 목록, API 가 알린 totalCount)."""
    query = urllib.parse.urlencode(
        {
            "serviceKey": _normalize_key(service_key),
            "numOfRows": str(LOST_PAGE_SIZE),
            "pageNo": str(page),
            "_type": "json",
        }
    )
    body = _request(f"{LOST_BASE_URL}?{query}")
    items = body.get("items") or {}
    records = items.get("item") or []
    if isinstance(records, dict):
        records = [records]
    return records, int(body.get("totalCount") or 0)


def fetch_lost_all(service_key: str, max_pages: int = 50) -> tuple[list[dict], int]:
    """분실동물 전량 — **빈 페이지가 나올 때까지** 돈다.

    `totalCount` 를 종료 조건으로 쓰지 않는다. 실측(2026-09-13·15)에서 totalCount 는 308·303 을 알렸지만 실제
    페이지 합은 172·163 이었다 — totalCount 로 끊으면 없는 페이지를 더 부르거나(무해) 값이 작게 오면 뒤를
    잃는다. max_pages 는 API 가 빈 페이지를 영영 안 주는 사고에 대한 상한이다(최근 1개월치가 2~4페이지).
    """
    records: list[dict] = []
    total = 0
    for page in range(1, max_pages + 1):
        page_records, total = fetch_lost_page(service_key, page)
        if not page_records:
            break
        records.extend(page_records)
    else:
        raise RuntimeError(f"분실동물 API 가 {max_pages}페이지 안에 빈 페이지를 주지 않았다")
    return records, total
