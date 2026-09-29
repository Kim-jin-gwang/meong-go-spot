"""공공 구조동물 원문을 서비스 PostgreSQL로 멱등 적재한다.

Kafka/HDFS 원본 보관과 별도 소비자 그룹으로 동작한다. 원문이 at-least-once로 전달되므로
``desertionNo``와 ``updTm``을 기준으로 오래된 재전달은 건너뛴다. 위치는 승인된 행정구역
CSV에서만 해석한다. 추측한 지역 코드를 저장하지 않는 것이 이 모듈의 중요한 안전 장치다.
"""

from __future__ import annotations

import csv
import datetime as dt
import hashlib
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable
from zoneinfo import ZoneInfo

# 백엔드 D1/D2 가 같은 문자열로 조회한다 (IngestionStatusService.SOURCE_SYSTEM, docs/api-spec.md D1 sourceSystem).
# 2026-09-11~15 에는 "animal-protection-api" 로 적혀 D2 일일 요약이 늘 null 이었다 — 대소문자·구분자까지 계약이다.
SOURCE_SYSTEM = "ANIMAL_PROTECTION_API"
RUN_TYPES = {"INITIAL_FULL", "DAILY_INCREMENTAL", "BACKFILL"}
# 새 원천 값은 검증 전까지 종료로 취급해 공개·문의 가능 상태로 노출하지 않는다.
ACTIVE_PROCESS_STATES = frozenset({"공고중", "보호중"})
_SPACE = re.compile(r"\s+")
KST = ZoneInfo("Asia/Seoul")


class RecordError(ValueError):
    """한 원문의 정규화·참조 데이터 검증 실패. 원문 값은 로그에 남기지 않는다."""


@dataclass(frozen=True)
class Location:
    region_code: str
    emd_code: str | None
    public_location: str


@dataclass(frozen=True)
class NormalizedRecord:
    desertion_no: str
    care_reg_no: str
    care_name: str
    care_phone: str | None
    care_address: str | None
    jurisdiction: str | None
    name: str | None
    species: str
    breed_name: str | None
    sex: str
    color: str | None
    event_date: dt.date
    feature_text: str | None
    listed_at: dt.datetime
    status: str
    is_matchable: bool
    event_location: Location
    current_location: Location
    notice_no: str | None
    notice_start_date: dt.date | None
    notice_end_date: dt.date | None
    process_state: str | None
    end_reason: str | None
    age: str | None
    weight: str | None
    neuter_status: str
    source_updated_at: dt.datetime | None
    photo_urls: tuple[str, ...]


@dataclass
class RunCounts:
    fetched: int = 0
    inserted: int = 0
    updated: int = 0
    failed: int = 0
    newest_source_updated_at: dt.datetime | None = None
    new_animal_shelters: set[int] | None = None

    def __post_init__(self) -> None:
        if self.new_animal_shelters is None:
            self.new_animal_shelters = set()


def _text(value: Any, limit: int | None = None, required: bool = False) -> str | None:
    if value is None:
        value = ""
    if not isinstance(value, str):
        raise RecordError("INVALID_FIELD")
    value = _SPACE.sub(" ", value).strip()
    if any(ord(char) < 32 or 0x200C <= ord(char) <= 0x200F for char in value):
        raise RecordError("INVALID_FIELD")
    if required and not value:
        raise RecordError("MISSING_FIELD")
    if limit is not None and len(value) > limit:
        raise RecordError("FIELD_TOO_LONG")
    return value or None


def _https(url: str) -> str:
    """사진 URL 을 https 로, 그리고 RFC 3986 에 맞는 형태로 저장한다.

    Android 는 targetSdk 28 부터 평문 통신이 기본 차단이므로 ``http://`` 로 저장하면 앱에서 썸네일이
    반드시 깨진다. 공공 API 이미지 호스트는 같은 경로를 https 로도 동일하게 제공한다(2026-09-11 실측:
    같은 파일 518,341 바이트 동일). 승격이 실패하는 호스트가 나타나도 앱 기준으로는 평문과 마찬가지로
    표시되지 않을 뿐이라 손해가 없다. 백엔드도 https 만 내려보낸다
    (``global/common/photo/PublicPhotoUrl.java``).

    대괄호를 퍼센트 인코딩하는 이유: 공공 API 파일명의 약 9% 가 ``…518[1].jpg`` 처럼 ``[`` ``]`` 를
    담는다(2026-09-14 실측 15,520장 중 1,398장). 대괄호는 URI 경로에서 허용되지 않아 백엔드의
    ``java.net.URI`` 가 파싱을 거부하고 썸네일을 null 로 내렸다 — ACTIVE 게시물 452건이 사진 없이
    보였다. 파일 서버는 ``%5B`` ``%5D`` 로도 같은 파일을 준다(실측 200, 바이트 동일).
    """
    if url.startswith("http://"):
        url = "https://" + url[len("http://") :]
    return url.replace("[", "%5B").replace("]", "%5D")


def _date(value: Any, required: bool = False) -> dt.date | None:
    value = _text(value, 10, required)
    if value is None:
        return None
    for pattern in ("%Y%m%d", "%Y-%m-%d"):
        try:
            return dt.datetime.strptime(value, pattern).date()
        except ValueError:
            pass
    raise RecordError("INVALID_DATE")


def _timestamp(value: Any) -> dt.datetime | None:
    value = _text(value, 40)
    if value is None:
        return None
    value = value.replace("Z", "+00:00")
    for pattern in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S"):
        try:
            # 공공 API의 timezone 없는 updTm은 한국 표준시다. UTC를 바로 붙이면 9시간 어긋난다.
            return dt.datetime.strptime(value, pattern).replace(tzinfo=KST).astimezone(dt.timezone.utc)
        except ValueError:
            pass
    try:
        parsed = dt.datetime.fromisoformat(value)
    except ValueError as error:
        raise RecordError("INVALID_TIMESTAMP") from error
    return parsed.replace(tzinfo=parsed.tzinfo or KST).astimezone(dt.timezone.utc)


class RegionCatalog:
    """Spring의 version/sha 고정 CSV와 같은 형식을 읽어 원문 주소를 안전하게 매핑한다.

    별칭(aliases)은 폐지된 옛 이름 → 현재 시군구 표시명이다. 보호소 주소(careAddr)는 개편 전 이름을
    그대로 쓰는 경우가 많다("강원도 화천군", "전라북도 임실군", "광주광역시 북구"). 별칭도 같은 Location 으로
    풀리며 저장되는 값은 항상 현재 표시명·코드다. 별칭 파일은 build_region_codes.py 가 폐지 행에서 기계적으로
    만들고(infra/reference/region-aliases.csv) 사람이 검수한다 — 여기서 추측하지 않는다.
    """

    def __init__(self, locations: Iterable[Location], aliases: dict[str, str] | None = None):
        self._locations = tuple(sorted(locations, key=lambda item: len(item.public_location), reverse=True))
        if not self._locations:
            raise ValueError("지역 기준 데이터가 비어 있습니다")
        by_name = {item.public_location: item for item in self._locations}
        keyed: list[tuple[str, Location]] = [(item.public_location, item) for item in self._locations]
        for alias, canonical in (aliases or {}).items():
            if canonical not in by_name:
                raise ValueError("별칭이 가리키는 지역이 기준 데이터에 없습니다")
            keyed.append((alias, by_name[canonical]))
        # 긴 이름 우선 — "서울특별시 강남구 역삼동" 이 "서울특별시 강남구" 보다 먼저 맞아야 한다
        self._keyed = tuple(sorted(keyed, key=lambda pair: len(pair[0]), reverse=True))

    @classmethod
    def from_csv(cls, path: Path, version: str, checksum: str,
                 aliases_path: Path | None = None, aliases_checksum: str | None = None) -> "RegionCatalog":
        raw = path.read_bytes()
        if not re.fullmatch(r"[A-Za-z0-9._-]{1,100}", version) or not re.fullmatch(r"[0-9a-fA-F]{64}", checksum):
            raise ValueError("지역 기준 데이터 설정이 올바르지 않습니다")
        if hashlib.sha256(raw).hexdigest() != checksum.lower():
            raise ValueError("지역 기준 데이터 checksum이 일치하지 않습니다")
        try:
            rows = list(csv.DictReader(raw.decode("utf-8").splitlines()))
        except (UnicodeDecodeError, csv.Error) as error:
            raise ValueError("지역 기준 데이터를 읽을 수 없습니다") from error
        expected = {"version", "regionCode", "emdCode", "publicLocation", "active"}
        if not rows or set(rows[0]) != expected:
            raise ValueError("지역 기준 데이터 형식이 올바르지 않습니다")
        locations: list[Location] = []
        for row in rows:
            if row["version"] != version or row["active"] != "true":
                continue
            region, emd, display = row["regionCode"], row["emdCode"] or None, _text(row["publicLocation"], 200, True)
            if not re.fullmatch(r"\d{5}", region) or (emd and (not re.fullmatch(r"\d{10}", emd) or not emd.startswith(region))):
                raise ValueError("지역 기준 데이터 코드가 올바르지 않습니다")
            locations.append(Location(region, emd, display))
        aliases = cls._read_aliases(aliases_path, version, aliases_checksum) if aliases_path else None
        return cls(locations, aliases)

    @staticmethod
    def _read_aliases(path: Path, version: str, checksum: str | None) -> dict[str, str]:
        """`version,alias,publicLocation` — 기준 CSV 와 같은 version 이어야 하고 sha256 으로 고정한다."""
        raw = path.read_bytes()
        if not checksum or not re.fullmatch(r"[0-9a-fA-F]{64}", checksum):
            raise ValueError("지역 별칭 데이터 설정이 올바르지 않습니다")
        if hashlib.sha256(raw).hexdigest() != checksum.lower():
            raise ValueError("지역 별칭 데이터 checksum이 일치하지 않습니다")
        try:
            rows = list(csv.DictReader(raw.decode("utf-8").splitlines()))
        except (UnicodeDecodeError, csv.Error) as error:
            raise ValueError("지역 별칭 데이터를 읽을 수 없습니다") from error
        if rows and set(rows[0]) != {"version", "alias", "publicLocation"}:
            raise ValueError("지역 별칭 데이터 형식이 올바르지 않습니다")
        aliases: dict[str, str] = {}
        for row in rows:
            if row["version"] != version:
                continue
            alias = _text(row["alias"], 200, True)
            if alias in aliases:
                raise ValueError("지역 별칭이 중복됩니다")
            aliases[alias] = _text(row["publicLocation"], 200, True)
        return aliases

    def resolve(self, address: Any) -> Location:
        text = _text(address, 500, True)
        normalized = _SPACE.sub("", text)
        matches = [(name, item) for name, item in self._keyed if _SPACE.sub("", name) in normalized]
        if not matches:
            raise RecordError("LOCATION_NOT_MAPPED")
        # 가장 긴 이름과 같은 길이로 맞은 후보들이 서로 다른 행정구역이면 임의 선택하지 않는다.
        # 별칭과 본명이 같은 Location 을 가리키면 모호하지 않다. 앞 두 개만 비교하면 안 된다 —
        # [본명 A, 별칭 A, 다른 지역 B] 처럼 세 개 이상이 동률일 때 B 를 놓친다 (MR !164 리뷰).
        longest = len(matches[0][0])
        winner = matches[0][1]
        if any(item != winner for name, item in matches if len(name) == longest):
            raise RecordError("LOCATION_AMBIGUOUS")
        return winner


def resolve_public_locations(record: dict[str, Any], regions: RegionCatalog) -> tuple[Location, Location]:
    """공공 레코드의 EVENT(발견 지역)·CURRENT(보호 지역) 를 푼다.

    EVENT 는 `orgNm`(관할 시군구) 을 먼저 본다. `happenPlace` 는 "봉정삼거리", "○○아파트 앞" 같은 자유 지명이라
    시군구가 거의 안 들어 있다 — 2026-09-14 실측으로 happenPlace 는 1~10%, orgNm 은 95~98% 가 풀렸다.
    orgNm 이 시도명만인 경우(제주특별자치도)는 happenPlace → careAddr 순으로 내려간다.
    CURRENT 는 `careAddr` 를 먼저 보고 안 풀리면 orgNm 으로 내려간다 — 보호소는 관할 시군구 안에 있는 것이 보통이다.

    어느 단계도 못 풀면 여전히 LOCATION_NOT_MAPPED 다. 추측한 지역을 저장하지 않는 원칙은 그대로다.
    """

    def first(*fields: str) -> Location:
        for field in fields:
            value = record.get(field)
            if not value:
                continue
            try:
                return regions.resolve(value)
            except RecordError:
                continue
        raise RecordError("LOCATION_NOT_MAPPED")

    return first("orgNm", "happenPlace", "careAddr"), first("careAddr", "orgNm")


# 공공 API upKindCd. upKindNm 이 비어 코드만 올 때도 개·고양이를 알아본다 — 이름 검사만 있으면 코드는 전부
# OTHER 로 떨어져 적재에서 빠진다. 429900(기타)은 의도대로 OTHER.
_SPECIES_BY_CODE = {"417000": "DOG", "422400": "CAT"}


def _species(source: str) -> str:
    if source in _SPECIES_BY_CODE:
        return _SPECIES_BY_CODE[source]
    if "개" in source or source.upper() == "DOG":
        return "DOG"
    if "고양" in source or source.upper() == "CAT":
        return "CAT"
    return "OTHER"


def normalize(record: dict[str, Any], regions: RegionCatalog, now: dt.datetime) -> NormalizedRecord:
    if not isinstance(record, dict):
        raise RecordError("INVALID_RECORD")
    desertion_no = _text(record.get("desertionNo"), 50, True)
    care_reg_no = _text(record.get("careRegNo"), 50, True)
    care_name = _text(record.get("careNm"), 150, True)
    event_date = _date(record.get("happenDt"), True)
    notice_start = _date(record.get("noticeSdt"))
    notice_end = _date(record.get("noticeEdt"))
    source_updated = _timestamp(record.get("updTm"))
    if notice_start and notice_end and notice_start > notice_end:
        raise RecordError("INVALID_NOTICE_DATES")
    photos = tuple(
        _https(url) for index in range(1, 9)
        if (url := _text(record.get(f"popfile{index}"), 2000)) is not None
        and re.fullmatch(r"https?://[^\s/@]+(?:/[^\s]*)?", url)
    )
    if not photos:
        raise RecordError("MISSING_PUBLIC_PHOTO")
    process_state = _text(record.get("processState"), 100)
    active = process_state in ACTIVE_PROCESS_STATES
    species = _species(_text(record.get("upKindNm") or record.get("upKindCd"), 100) or "")
    if species == "OTHER":
        # 서비스는 개·고양이만 다룬다 (2026-09-14 결정). 토끼·닭·햄스터 등 기타 축종(공공 API 의 ~2%)은
        # 저장하지 않는다 — 조회 단계에서 가리는 대신 적재에서 뺀다. 실행 이력의 error_summary 에
        # 사유가 남으므로 "버려진 것"이 아니라 "정책으로 뺀 것"임을 실행마다 확인할 수 있다.
        raise RecordError("SPECIES_NOT_SUPPORTED")
    sex_source = (_text(record.get("sexCd"), 20) or "").upper()
    sex = "MALE" if sex_source in {"M", "MALE", "수"} else "FEMALE" if sex_source in {"F", "FEMALE", "암"} else "UNKNOWN"
    neuter_source = (_text(record.get("neuterYn"), 20) or "").upper()
    neuter = "YES" if neuter_source == "Y" else "NO" if neuter_source == "N" else "UNKNOWN"
    listed_date = notice_start or event_date
    listed_at = dt.datetime.combine(listed_date, dt.time.min, tzinfo=KST).astimezone(dt.timezone.utc)
    return NormalizedRecord(
        desertion_no, care_reg_no, care_name, _text(record.get("careTel"), 50), _text(record.get("careAddr"), 2000),
        _text(record.get("orgNm"), 150), _text(record.get("name"), 50), species,
        _text(record.get("kindFullNm") or record.get("kindNm"), 100), sex, _text(record.get("colorCd"), 100),
        event_date, _text(record.get("specialMark"), 2000), listed_at, "ACTIVE" if active else "CLOSED", True,
        *resolve_public_locations(record, regions), _text(record.get("noticeNo"), 100),
        notice_start, notice_end, process_state, _text(record.get("endReason"), 255), _text(record.get("age"), 100),
        _text(record.get("weight"), 100), neuter, source_updated, photos,
    )


def _upsert_location(cursor: Any, case_id: int, role: str, location: Location) -> None:
    cursor.execute(
        """
        INSERT INTO animal_case_location(animal_case_id,location_type,region_code,emd_code,public_location,
                                         exact_location_ciphertext,exact_location_visible)
        VALUES (%s,%s,%s,%s,%s,NULL,false)
        ON CONFLICT (animal_case_id,location_type) DO UPDATE SET
          region_code=EXCLUDED.region_code, emd_code=EXCLUDED.emd_code, public_location=EXCLUDED.public_location,
          exact_location_ciphertext=NULL, exact_location_visible=false, disclosure_policy_version=NULL,
          disclosure_consented_at=NULL, latitude=NULL, longitude=NULL
        """,
        (case_id, role, location.region_code, location.emd_code, location.public_location),
    )


def _upsert_record(cursor: Any, item: NormalizedRecord, run_id: int, now: dt.datetime) -> tuple[str, int | None]:
    cursor.execute("SELECT animal_case_id, source_updated_at FROM shelter_animal WHERE desertion_no=%s FOR UPDATE", (item.desertion_no,))
    existing = cursor.fetchone()
    if existing and (existing[1] is not None and (item.source_updated_at is None or item.source_updated_at <= existing[1])):
        return "SKIPPED", None
    cursor.execute(
        """
        INSERT INTO shelter(care_reg_no,name,phone,address,jurisdiction,created_at,updated_at)
        VALUES (%s,%s,%s,%s,%s,%s,%s)
        ON CONFLICT (care_reg_no) DO UPDATE SET name=EXCLUDED.name,phone=EXCLUDED.phone,address=EXCLUDED.address,
          jurisdiction=EXCLUDED.jurisdiction,updated_at=EXCLUDED.updated_at
        RETURNING id
        """,
        (item.care_reg_no, item.care_name, item.care_phone, item.care_address, item.jurisdiction, now, now),
    )
    shelter_id = cursor.fetchone()[0]
    if existing:
        case_id = existing[0]
        cursor.execute(
            """
            UPDATE animal_case SET status=%s,is_matchable=%s,listed_at=%s,name=%s,species=%s,breed_name=%s,sex=%s,
              color=%s,event_date=%s,feature_text=%s,
              closed_at=CASE WHEN %s='CLOSED' THEN COALESCE(closed_at,%s) ELSE NULL END,updated_at=%s
            WHERE id=%s
            """,
            (item.status, item.is_matchable, item.listed_at, item.name, item.species, item.breed_name, item.sex,
             item.color, item.event_date, item.feature_text, item.status, now, now, case_id),
        )
        result = "UPDATED"
    else:
        cursor.execute(
            """
            INSERT INTO animal_case(case_type,source_type,status,is_matchable,version,listed_at,name,species,breed_name,
              sex,color,event_date,feature_text,closed_at,created_at,updated_at)
            VALUES (
              'SHELTERING','PUBLIC',%s,%s,0,
              %s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s
            )
            RETURNING id
            """,
            (item.status, item.is_matchable, item.listed_at, item.name, item.species, item.breed_name, item.sex,
             item.color, item.event_date, item.feature_text, now if item.status == "CLOSED" else None, now, now),
        )
        case_id = cursor.fetchone()[0]
        result = "INSERTED"
    _upsert_location(cursor, case_id, "EVENT", item.event_location)
    _upsert_location(cursor, case_id, "CURRENT", item.current_location)
    cursor.execute(
        """
        INSERT INTO shelter_animal(animal_case_id,desertion_no,shelter_id,ingestion_run_id,notice_no,notice_start_date,
          notice_end_date,process_state_raw,end_reason_raw,age_text,weight_text,neuter_status,source_updated_at,last_synced_at)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
        ON CONFLICT (desertion_no) DO UPDATE SET shelter_id=EXCLUDED.shelter_id,ingestion_run_id=EXCLUDED.ingestion_run_id,
          notice_no=EXCLUDED.notice_no,notice_start_date=EXCLUDED.notice_start_date,notice_end_date=EXCLUDED.notice_end_date,
          process_state_raw=EXCLUDED.process_state_raw,end_reason_raw=EXCLUDED.end_reason_raw,age_text=EXCLUDED.age_text,
          weight_text=EXCLUDED.weight_text,neuter_status=EXCLUDED.neuter_status,source_updated_at=EXCLUDED.source_updated_at,
          last_synced_at=EXCLUDED.last_synced_at
        """,
        (case_id, item.desertion_no, shelter_id, run_id, item.notice_no, item.notice_start_date, item.notice_end_date,
         item.process_state, item.end_reason, item.age, item.weight, item.neuter_status, item.source_updated_at, now),
    )
    for order, url in enumerate(item.photo_urls):
        cursor.execute(
            """
            INSERT INTO animal_photo(animal_case_id,storage_type,storage_uri,sort_order,created_at)
            VALUES (%s,'PUBLIC_URL',%s,%s,%s)
            ON CONFLICT ON CONSTRAINT uk_animal_photo_case_sort_order
            DO UPDATE SET storage_type=EXCLUDED.storage_type,storage_uri=EXCLUDED.storage_uri
            """,
            (case_id, url, order, now),
        )
    cursor.execute("DELETE FROM animal_photo WHERE animal_case_id=%s AND sort_order >= %s", (case_id, len(item.photo_urls)))
    return result, shelter_id if result == "INSERTED" else None


def ingest_records(connection: Any, records: Iterable[dict[str, Any]], regions: RegionCatalog, run_type: str,
                   requested_from: dt.date | None = None, requested_to: dt.date | None = None,
                   now: dt.datetime | None = None) -> int:
    """한 실행을 원자 적재한다. 레코드 형식 오류만 격리하고 DB 장애는 실행 전체를 FAILED로 남긴다."""
    if run_type not in RUN_TYPES:
        raise ValueError("허용하지 않는 run_type")
    now = now or dt.datetime.now(dt.timezone.utc)
    with connection.cursor() as cursor:
        cursor.execute(
            """INSERT INTO ingestion_run(source_system,run_type,status,requested_from_date,requested_to_date,started_at)
               VALUES (%s,%s,'RUNNING',%s,%s,%s) RETURNING id""",
            (SOURCE_SYSTEM, run_type, requested_from, requested_to, now),
        )
        run_id = cursor.fetchone()[0]
    connection.commit()  # 이후 치명 오류에도 FAILED 실행 이력은 보존한다.
    counts, errors = RunCounts(), Counter()
    try:
        with connection.transaction():
            with connection.cursor() as cursor:
                for raw in records:
                    counts.fetched += 1
                    try:
                        item = normalize(raw, regions, now)
                        with connection.transaction():
                            action, new_shelter = _upsert_record(cursor, item, run_id, now)
                        if action == "INSERTED":
                            counts.inserted += 1
                            counts.new_animal_shelters.add(new_shelter)
                        elif action == "UPDATED":
                            counts.updated += 1
                        if item.source_updated_at and (counts.newest_source_updated_at is None or item.source_updated_at > counts.newest_source_updated_at):
                            counts.newest_source_updated_at = item.source_updated_at
                    except RecordError as error:
                        counts.failed += 1
                        errors[str(error)] += 1
                summary = ", ".join(f"{code}:{count}" for code, count in sorted(errors.items())) or None
                cursor.execute(
                    """UPDATE ingestion_run SET status='SUCCEEDED',fetched_count=%s,inserted_count=%s,updated_count=%s,
                       shelter_count=%s,failed_count=%s,last_source_updated_at=%s,error_summary=%s,completed_at=%s WHERE id=%s""",
                    (counts.fetched, counts.inserted, counts.updated, len(counts.new_animal_shelters), counts.failed,
                     counts.newest_source_updated_at, summary, now, run_id),
                )
        return run_id
    except Exception:  # SQL/연결 장애 원문은 error_summary나 로그로 흘리지 않는다.
        connection.rollback()
        with connection.cursor() as cursor:
            cursor.execute("UPDATE ingestion_run SET status='FAILED',error_summary='PERSISTENCE_FAILURE',completed_at=%s WHERE id=%s", (now, run_id))
        connection.commit()
        raise
