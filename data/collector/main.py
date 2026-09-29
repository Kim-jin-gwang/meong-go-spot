"""수집기 CLI — 구조동물 전량을 받아 JSONL로 저장하고 증분을 가려낸다.

사용법:
    DATA_GO_KR_SERVICE_KEY=<키> python main.py --out ./out --state ./out/state.json

종료 코드: 0 = 성공(건수 대조 통과), 1 = 실패.
"""

import argparse
import datetime
import json
import os
import sys
import time
from pathlib import Path

import api
import state as state_mod

# 창 밖 재조회 기본 일수 — 접수 6개월 안의 보호중 건이면 상태 변화가 늦어도 하루 안에 따라온다.
# 비용: 월 슬라이스당 8쪽 안팎 × 5개월 ≈ 40호출/일 (일 쿼터 10,000, 기본 창 수집 8호출).
DEFAULT_RESYNC_DAYS = 180


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):  # Windows 콘솔(cp949)에서도 한글 출력 보장
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="구조동물 수집기 (파이프라인 2단계)")
    parser.add_argument("--out", required=True, help="JSONL 출력 디렉터리")
    parser.add_argument("--state", required=True, help="증분 상태 파일 경로")
    parser.add_argument(
        "--kafka",
        default="",
        help="Kafka bootstrap servers — 지정하면 증분(delta)을 shelter.raw 토픽에 발행",
    )
    parser.add_argument(
        "--resync-days",
        type=int,
        default=DEFAULT_RESYNC_DAYS,
        help=(
            "API 기본 창(접수일 최근 31일) 밖을 이 일수까지 거슬러 다시 받는다 — 창을 벗어난 뒤 바뀐 "
            f"상태(보호중→종료)를 따라잡는다. 0 이면 끈다 (기본 {DEFAULT_RESYNC_DAYS})"
        ),
    )
    args = parser.parse_args()

    service_key = os.environ.get("DATA_GO_KR_SERVICE_KEY", "")
    if not service_key:
        print("DATA_GO_KR_SERVICE_KEY 환경변수가 없습니다", file=sys.stderr)
        return 1

    records, total = api.fetch_all(service_key)
    if len(records) != total:
        print(f"건수 불일치: 수집 {len(records)} != totalCount {total}", file=sys.stderr)
        return 1

    today_date = datetime.date.today()
    previous = state_mod.load(Path(args.state))
    resync_fetched = resync_before_window(service_key, today_date, args.resync_days)
    resync_records = known_only(resync_fetched, previous)
    all_records = merge_records(records, resync_records)
    new_records, changed_records = state_mod.diff(all_records, previous)

    today = today_date.isoformat()
    out_dir = Path(args.out) / f"dt={today}"
    out_dir.mkdir(parents=True, exist_ok=True)

    snapshot_path = out_dir / "records.jsonl"
    with snapshot_path.open("w", encoding="utf-8") as fp:
        for record in records:
            fp.write(json.dumps(record, ensure_ascii=False) + "\n")

    delta_path = out_dir / "delta.jsonl"
    with delta_path.open("w", encoding="utf-8") as fp:
        for record in new_records + changed_records:
            fp.write(json.dumps(record, ensure_ascii=False) + "\n")

    published = 0
    if args.kafka and (new_records or changed_records):
        import producer  # confluent-kafka 의존 — --kafka를 쓸 때만 로드

        published = producer.publish(args.kafka, new_records + changed_records)

    # 발행까지 성공한 뒤에만 상태를 확정한다 — 발행 실패 시 다음 실행이 같은 증분을 재시도
    state_mod.save(Path(args.state), state_mod.apply(all_records, previous))

    kafka_note = f" | Kafka 발행 {published}건" if args.kafka else ""
    resync_note = (
        f" | 창 밖 재조회 {len(resync_fetched)}건(상태 파일에 있는 {len(resync_records)}건 반영)" if resync_fetched else ""
    )
    print(
        f"수집 {len(records)}건 (totalCount {total} 일치){resync_note} | "
        f"신규 {len(new_records)} · 변경 {len(changed_records)}{kafka_note} | "
        f"저장: {snapshot_path}"
    )
    return 0


def resync_before_window(service_key: str, today: datetime.date, resync_days: int) -> list[dict]:
    """기본 창 밖을 월 슬라이스로 다시 받는다 (api.resync_slices).

    슬라이스 하나가 실패하거나 건수가 허용치보다 어긋나면 그 슬라이스만 경고와 함께 건너뛴다 — 나머지 슬라이스와
    당일 증분은 그대로 나가고, 빠진 달은 다음 날 다시 받는다. 소량의 건수 차이(api.count_tolerance)는 API 의
    알려진 어긋남이라 그대로 쓴다.
    """
    records: list[dict] = []
    for bgnde, endde in api.resync_slices(today, resync_days):
        try:
            time.sleep(api.RESYNC_INTERVAL_SEC)
            slice_records, total = api.fetch_pages(
                service_key, {"bgnde": bgnde, "endde": endde}, interval_sec=api.RESYNC_INTERVAL_SEC
            )
        except Exception as error:  # noqa: BLE001 — 재조회 한 달의 실패가 당일 증분을 막지 않는다
            print(f"창 밖 재조회 {bgnde}~{endde} 실패(건너뜀, 내일 재시도): {error}", file=sys.stderr)
            continue
        if abs(total - len(slice_records)) > api.count_tolerance(total):
            print(
                f"창 밖 재조회 {bgnde}~{endde} 건수 불일치: 수집 {len(slice_records)} != totalCount {total} "
                f"(허용 {api.count_tolerance(total)}) — 건너뜀, 내일 재시도",
                file=sys.stderr,
            )
            continue
        records.extend(slice_records)
    return records


def known_only(records: list[dict], state: dict[str, str]) -> list[dict]:
    """상태 파일에 있는 desertionNo 만 남긴다.

    재조회 구간에는 일일 수집이 시작되기 전에 접수된 동물이 훨씬 많다(2026-09-25 드라이런: 39,387건 중 34,167건이
    미지). 그것까지 신규로 흘리면 하루에 사진 수만 장이 이미지·임베딩 태스크로 쏟아져 DAG 시한(30분)을 넘긴다.
    창 밖 재조회의 목적은 "받았던 동물의 뒤늦은 상태 변화"이므로 받았던 것만 본다. 과거 전량은 백필의 몫이다.
    """
    return [record for record in records if str(record.get("desertionNo", "")) in state]


def merge_records(base: list[dict], extra: list[dict]) -> list[dict]:
    """기본 창 레코드 뒤에 재조회 레코드를 붙인다. 같은 desertionNo 는 기본 창 것을 우선한다."""
    seen = {str(record.get("desertionNo", "")) for record in base}
    merged = list(base)
    for record in extra:
        key = str(record.get("desertionNo", ""))
        if key and key not in seen:
            seen.add(key)
            merged.append(record)
    return merged


if __name__ == "__main__":
    sys.exit(main())
