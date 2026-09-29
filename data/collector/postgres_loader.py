"""Kafka ``shelter.raw`` → PostgreSQL 공공 보호동물 적재기.

HDFS loader와 독립한 consumer group을 써서 한쪽 장애가 다른 원본 보관을 막지 않는다.
DB의 성공 commit 뒤에만 Kafka offset을 확정하므로 재전달은 public_ingestion의 멱등 규칙으로 흡수한다.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
from pathlib import Path

from confluent_kafka import Consumer

from loader import consume_all
from public_ingestion import RegionCatalog, ingest_records

GROUP_ID = "postgres-public-ingestion"


def parse_date(value: str) -> dt.date:
    try:
        return dt.date.fromisoformat(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("날짜는 YYYY-MM-DD 형식이어야 합니다") from error


def main() -> int:
    # systemd·cron 은 로케일을 물려주지 않아 stdout 이 ASCII 가 된다. 한글 진행 메시지를 찍는 순간
    # UnicodeEncodeError 로 적재가 실패하므로 loader.py 와 같이 UTF-8 을 고정한다.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Kafka → PostgreSQL 공공 보호동물 멱등 적재")
    parser.add_argument("--bootstrap", help="Kafka bootstrap servers (--input 을 쓰지 않으면 필수)")
    parser.add_argument("--dsn", default=os.environ.get("SHELTER_POSTGRES_DSN", ""))
    parser.add_argument("--region-catalog", default=os.environ.get("REGION_CODE_DATA_PATH", ""))
    parser.add_argument("--region-version", default=os.environ.get("REGION_CODE_DATA_VERSION", ""))
    parser.add_argument("--region-sha256", default=os.environ.get("REGION_CODE_DATA_SHA256", ""))
    # 옛 시도명 별칭 — 없어도 돌지만 보호소 주소의 6% 가량이 LOCATION_NOT_MAPPED 로 빠진다 (2026-09-14 실측)
    parser.add_argument("--region-aliases", default=os.environ.get("REGION_ALIAS_DATA_PATH", ""))
    parser.add_argument("--region-aliases-sha256", default=os.environ.get("REGION_ALIAS_DATA_SHA256", ""))
    parser.add_argument("--run-type", choices=("INITIAL_FULL", "DAILY_INCREMENTAL", "BACKFILL"), default="DAILY_INCREMENTAL")
    parser.add_argument("--requested-from", type=parse_date)
    parser.add_argument("--requested-to", type=parse_date)
    # Kafka 대신 JSONL 파일을 읽는다 — 초기 전량 적재·재적재용. Kafka 는 신규·변경분만 흐르므로
    # 이미 지나간 원문(예: 지역 해석 결함으로 버려진 11,313건)은 이 경로로만 다시 넣을 수 있다.
    # 파일은 수집기의 api.fetch_all 결과(JSON 배열 또는 JSONL)다. 이 모드에서는 Kafka offset 을 건드리지 않는다.
    parser.add_argument("--input", help="원문 JSON 배열 또는 JSONL 파일. 주면 Kafka 를 읽지 않는다")
    args = parser.parse_args()
    if args.requested_from and args.requested_to and args.requested_from > args.requested_to:
        parser.error("--requested-from은 --requested-to보다 늦을 수 없습니다")
    if not args.input and not args.bootstrap:
        parser.error("--bootstrap 또는 --input 중 하나가 필요합니다")
    if not all((args.dsn, args.region_catalog, args.region_version, args.region_sha256)):
        print("PostgreSQL DSN과 승인된 지역 기준 데이터 설정이 필요합니다", file=sys.stderr)
        return 2
    try:
        import psycopg

        regions = RegionCatalog.from_csv(
            Path(args.region_catalog), args.region_version, args.region_sha256,
            aliases_path=Path(args.region_aliases) if args.region_aliases else None,
            aliases_checksum=args.region_aliases_sha256 or None,
        )
    except (ImportError, OSError, ValueError) as error:
        print(f"적재기 설정 오류: {type(error).__name__}", file=sys.stderr)
        return 2
    if args.input:
        return ingest_messages(read_input(Path(args.input)), args, regions, source=args.input)
    consumer = Consumer({"bootstrap.servers": args.bootstrap, "group.id": GROUP_ID, "auto.offset.reset": "earliest", "enable.auto.commit": False})
    try:
        messages = consume_all(consumer)
        if not messages:
            print("새 메시지 없음 — PostgreSQL 적재 생략")
            return 0
        code = ingest_messages(messages, args, regions, source="kafka")
        consumer.commit(asynchronous=False)  # DB commit 이 끝난 뒤에만 offset 을 확정한다
        return code
    finally:
        consumer.close()


def read_input(path: Path) -> list[bytes]:
    """JSON 배열(api.fetch_all 저장 형식) 또는 JSONL 을 메시지 목록으로 읽는다. 빈 줄은 건너뛴다."""
    raw = path.read_bytes()
    text = raw.decode("utf-8").lstrip()
    if text.startswith("["):
        items = json.loads(text)
        if not isinstance(items, list):
            raise ValueError("JSON 배열이어야 합니다")
        return [json.dumps(item, ensure_ascii=False).encode("utf-8") for item in items]
    return [line.encode("utf-8") for line in text.splitlines() if line.strip()]


def ingest_messages(messages: list[bytes], args: argparse.Namespace, regions: RegionCatalog, source: str) -> int:
    """원문 메시지 묶음을 한 ingestion_run 으로 적재하고 결과를 찍는다. 실패 건수도 함께 찍는다 —
    2026-09-14 까지 '적재 완료' 만 보고 97% 가 LOCATION_NOT_MAPPED 로 버려지는 것을 놓쳤다."""
    import psycopg

    records = []
    malformed = 0
    for message in messages:
        try:
            value = json.loads(message)
            if not isinstance(value, dict):
                raise ValueError
            records.append(value)
        except (UnicodeDecodeError, ValueError, TypeError):
            malformed += 1
            records.append({})  # 실행 이력 failed_count에 반영한다. 손상 원문은 저장·출력하지 않는다.
    with psycopg.connect(args.dsn) as connection:
        run_id = ingest_records(
            connection,
            records,
            regions,
            args.run_type,
            args.requested_from,
            args.requested_to,
            dt.datetime.now(dt.timezone.utc),
        )
        row = connection.execute(
            "SELECT inserted_count, updated_count, failed_count, error_summary FROM ingestion_run WHERE id=%s", (run_id,)
        ).fetchone()
    inserted, updated, failed, summary = row if row else (None, None, None, None)
    print(
        f"PostgreSQL 적재 완료: run={run_id} · 입력 {source} · 수신 {len(messages)} · 손상 메시지 {malformed} · "
        f"신규 {inserted} · 갱신 {updated} · 실패 {failed}" + (f" ({summary})" if failed else "")
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
