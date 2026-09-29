"""분실동물 일일 스냅샷(HDFS) → PostgreSQL 공공 LOST 적재기 (#145).

원천은 `lost_snapshot.py` 가 매일 22:30 뒤 `/data/lost/raw/dt=YYYY-MM-DD/records.jsonl` 에 남긴 **정제된** 스냅샷이다
(신고자 연락처·상세 주소는 이미 없다). API 를 다시 부르지 않는 이유: 원장(HDFS)과 DB 가 같은 데이터를 보게 하고,
서버 1 에는 공공 API 키를 두지 않기 위해서다. 서버 1 호스트에서 WebHDFS 로 읽는다(`user.name=ubuntu`).

적재 규칙
    - animal_case: source_type=PUBLIC, case_type=LOST, is_matchable=false (매칭 기준은 사용자 LOST 만, 후보는 SHELTERING 만).
      listed_at = 실종 일시(KST → UTC), event_date/event_time = happenDt.
    - 위치: EVENT 하나 — 스냅샷의 시·군·구 주소(`happenAddr`)를 RegionCatalog 로 풀어 region_code·public_location.
      CURRENT 는 없다 (docs/erd.md 불변식 5 와 같은 모양).
    - 사진: popfile 1장, https 정규화 (`public_ingestion._https`).
    - lost_report: lostKey 로 멱등. 처음 보면 first_seen_date, 볼 때마다 last_seen_date 갱신.
    - 종료: 스냅샷에 없는 ACTIVE 건은 CLOSED (closed_at=지금). 원천에 상태가 없어 "목록에서 사라짐" 이 유일한 신호다.
      다음 날 다시 나타나면(드묾) ACTIVE 로 되살린다.
    - 축종이 DOG/CAT 이 아니면(OTHER·미상 27건, 17%) SPECIES_NOT_SUPPORTED 로 실패 집계 — 서비스 범위 밖.

실행 이력은 ingestion_run(source_system='LOSS_INFO_API', run_type=DAILY_INCREMENTAL) 에 남긴다. 마지막 줄:
    분실 적재 완료: run=12 · 스냅샷 163 · 신규 5 · 갱신 150 · 종료 3 · 실패 8 (SPECIES_NOT_SUPPORTED:8)
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import urllib.parse
import urllib.request
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable
from zoneinfo import ZoneInfo

from public_ingestion import KST, Location, RecordError, RegionCatalog, _https, _text

SOURCE_SYSTEM = "LOSS_INFO_API"  # 보호동물 ANIMAL_PROTECTION_API 와 같은 표기 규칙 — D1/D2 는 이 값을 조회하지 않는다
SEX = {"M": "MALE", "F": "FEMALE"}


@dataclass(frozen=True)
class LostRecord:
    lost_key: str
    species: str
    breed_name: str | None
    sex: str
    color: str | None
    event_date: dt.date
    event_time: dt.time | None
    listed_at: dt.datetime
    feature_text: str | None
    org_name: str | None
    happen_place: str | None
    rfid_code: str | None
    location: Location
    photo_url: str


def _happen(value: Any) -> tuple[dt.date, dt.time | None]:
    """`2026-09-14 13:00:00.0` 또는 `20260914` → (날짜, 시각)."""
    text = str(value or "").strip()
    for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d"):
        try:
            parsed = dt.datetime.strptime(text, fmt)
            return parsed.date(), (parsed.time() if "%H" in fmt else None)
        except ValueError:
            continue
    try:
        return dt.datetime.strptime(text, "%Y%m%d").date(), None
    except ValueError as error:
        raise RecordError("INVALID_DATE") from error


def normalize_lost(record: dict[str, Any], regions: RegionCatalog) -> LostRecord:
    if not isinstance(record, dict):
        raise RecordError("INVALID_RECORD")
    key = str(record.get("lostKey") or "").strip().lower()  # DB 제약은 소문자 hex 다
    if len(key) != 64 or any(c not in "0123456789abcdef" for c in key):
        raise RecordError("MISSING_LOST_KEY")
    species = record.get("species")
    if species not in ("DOG", "CAT"):
        raise RecordError("SPECIES_NOT_SUPPORTED")  # OTHER·미상 — 서비스 범위 밖(2026-09-14 결정)
    photo = _text(record.get("popfile"), 2000)
    if not photo or not photo.startswith(("http://", "https://")):
        raise RecordError("MISSING_PUBLIC_PHOTO")
    event_date, event_time = _happen(record.get("happenDt"))
    location = regions.resolve(record.get("happenAddr"))  # 시·군·구까지 잘린 주소 — LOCATION_NOT_MAPPED 면 실패
    listed_local = dt.datetime.combine(event_date, event_time or dt.time.min, tzinfo=KST)
    return LostRecord(
        lost_key=key,
        species=species,
        breed_name=_text(record.get("kindCd"), 100),
        sex=SEX.get((_text(record.get("sexCd"), 20) or "").upper(), "UNKNOWN"),
        color=_text(record.get("colorCd"), 100),
        event_date=event_date,
        event_time=event_time,
        listed_at=listed_local.astimezone(dt.timezone.utc),
        feature_text=_text(record.get("specialMark"), 2000),
        org_name=_text(record.get("orgNm"), 150),
        happen_place=_text(record.get("happenPlace"), 500),
        rfid_code=_text(record.get("rfidCd"), 50),
        location=location,
        photo_url=_https(photo),
    )


def _upsert(cursor: Any, item: LostRecord, run_id: int, seen: dt.date, now: dt.datetime) -> str:
    cursor.execute("SELECT animal_case_id FROM lost_report WHERE lost_key=%s FOR UPDATE", (item.lost_key,))
    existing = cursor.fetchone()
    if existing:
        case_id = existing[0]
        cursor.execute(
            """
            UPDATE animal_case SET status='ACTIVE',closed_at=NULL,listed_at=%s,species=%s,breed_name=%s,sex=%s,color=%s,
              event_date=%s,event_time=%s,feature_text=%s,updated_at=%s
            WHERE id=%s
            """,
            (item.listed_at, item.species, item.breed_name, item.sex, item.color, item.event_date, item.event_time,
             item.feature_text, now, case_id),
        )
        result = "UPDATED"
    else:
        cursor.execute(
            """
            INSERT INTO animal_case(case_type,source_type,status,is_matchable,version,listed_at,name,species,breed_name,
              sex,color,event_date,event_time,feature_text,created_at,updated_at)
            VALUES ('LOST','PUBLIC','ACTIVE',false,0,%s,NULL,%s,%s,%s,%s,%s,%s,%s,%s,%s)
            RETURNING id
            """,
            (item.listed_at, item.species, item.breed_name, item.sex, item.color, item.event_date, item.event_time,
             item.feature_text, now, now),
        )
        case_id = cursor.fetchone()[0]
        result = "INSERTED"
    cursor.execute(
        """
        INSERT INTO animal_case_location(animal_case_id,location_type,region_code,emd_code,public_location,
                                         exact_location_ciphertext,exact_location_visible)
        VALUES (%s,'EVENT',%s,%s,%s,NULL,false)
        ON CONFLICT (animal_case_id,location_type) DO UPDATE SET
          region_code=EXCLUDED.region_code, emd_code=EXCLUDED.emd_code, public_location=EXCLUDED.public_location,
          exact_location_ciphertext=NULL, exact_location_visible=false, disclosure_policy_version=NULL,
          disclosure_consented_at=NULL, latitude=NULL, longitude=NULL
        """,
        (case_id, item.location.region_code, item.location.emd_code, item.location.public_location),
    )
    cursor.execute(
        """
        INSERT INTO animal_photo(animal_case_id,storage_type,storage_uri,sort_order,created_at)
        VALUES (%s,'PUBLIC_URL',%s,0,%s)
        ON CONFLICT ON CONSTRAINT uk_animal_photo_case_sort_order
        DO UPDATE SET storage_type=EXCLUDED.storage_type,storage_uri=EXCLUDED.storage_uri
        """,
        (case_id, item.photo_url, now),
    )
    cursor.execute("DELETE FROM animal_photo WHERE animal_case_id=%s AND sort_order >= 1", (case_id,))
    cursor.execute(
        """
        INSERT INTO lost_report(animal_case_id,lost_key,rfid_code,org_name,happen_place,ingestion_run_id,
          first_seen_date,last_seen_date,last_synced_at)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
        ON CONFLICT (lost_key) DO UPDATE SET rfid_code=EXCLUDED.rfid_code,org_name=EXCLUDED.org_name,
          happen_place=EXCLUDED.happen_place,ingestion_run_id=EXCLUDED.ingestion_run_id,
          last_seen_date=GREATEST(lost_report.last_seen_date,EXCLUDED.last_seen_date),last_synced_at=EXCLUDED.last_synced_at
        """,
        (case_id, item.lost_key, item.rfid_code, item.org_name, item.happen_place, run_id, seen, seen, now),
    )
    return result


def _close_missing(cursor: Any, seen_keys: set[str], now: dt.datetime) -> int:
    """스냅샷에 없는 ACTIVE 공공 LOST 를 CLOSED 로. 스냅샷이 비어 있으면(API 장애) 아무것도 닫지 않는다 — 호출자가 막는다."""
    cursor.execute(
        """
        UPDATE animal_case c SET status='CLOSED',closed_at=%s,updated_at=%s
        FROM lost_report lr
        WHERE lr.animal_case_id=c.id AND c.source_type='PUBLIC' AND c.case_type='LOST' AND c.status='ACTIVE'
          AND NOT (lr.lost_key = ANY(%s))
        """,
        (now, now, sorted(seen_keys)),
    )
    return cursor.rowcount if cursor.rowcount is not None and cursor.rowcount >= 0 else 0


def ingest_lost(connection: Any, records: Iterable[dict[str, Any]], regions: RegionCatalog, snapshot_date: dt.date,
                now: dt.datetime | None = None) -> tuple[int, dict[str, int], str | None]:
    """한 스냅샷을 한 ingestion_run 으로 적재한다. 반환: (run_id, 건수, 실패 요약)."""
    now = now or dt.datetime.now(dt.timezone.utc)
    records = list(records)
    with connection.cursor() as cursor:
        cursor.execute(
            """INSERT INTO ingestion_run(source_system,run_type,status,requested_from_date,requested_to_date,started_at)
               VALUES (%s,'DAILY_INCREMENTAL','RUNNING',%s,%s,%s) RETURNING id""",
            (SOURCE_SYSTEM, snapshot_date, snapshot_date, now),
        )
        run_id = cursor.fetchone()[0]
    connection.commit()
    counts = {"fetched": len(records), "inserted": 0, "updated": 0, "closed": 0, "failed": 0}
    errors: Counter[str] = Counter()
    try:
        with connection.transaction():
            with connection.cursor() as cursor:
                seen: set[str] = set()
                for raw in records:
                    try:
                        item = normalize_lost(raw, regions)
                        with connection.transaction():
                            action = _upsert(cursor, item, run_id, snapshot_date, now)
                        counts["inserted" if action == "INSERTED" else "updated"] += 1
                        seen.add(item.lost_key)
                    except RecordError as error:
                        counts["failed"] += 1
                        errors[str(error)] += 1
                # 정상 키가 하나도 없으면(빈 스냅샷, 또는 전량 정규화 실패) "다 사라졌다" 가 아니라 "못 읽었다" 다 —
                # 이때 닫으면 기존 ACTIVE 전부가 CLOSED 가 된다 (AI 리뷰 !176 지적).
                if seen:
                    counts["closed"] = _close_missing(cursor, seen, now)
                summary = ", ".join(f"{code}:{count}" for code, count in sorted(errors.items())) or None
                cursor.execute(
                    """UPDATE ingestion_run SET status='SUCCEEDED',fetched_count=%s,inserted_count=%s,updated_count=%s,
                       failed_count=%s,error_summary=%s,completed_at=%s WHERE id=%s""",
                    (counts["fetched"], counts["inserted"], counts["updated"], counts["failed"], summary, now, run_id),
                )
        return run_id, counts, summary
    except Exception:
        connection.rollback()
        with connection.cursor() as cursor:
            cursor.execute("UPDATE ingestion_run SET status='FAILED',error_summary='PERSISTENCE_FAILURE',completed_at=%s WHERE id=%s", (now, run_id))
        connection.commit()
        raise


def read_snapshot(webhdfs: str, hdfs_user: str, hdfs_dir: str, snapshot_date: dt.date) -> list[dict[str, Any]]:
    path = f"{hdfs_dir}/dt={snapshot_date.isoformat()}/records.jsonl"
    # quote 기본값은 "=" 도 인코딩한다 — dt=2026-09-15 파티션 경로가 dt%3D... 가 되면 NameNode 가 다른 경로로 본다.
    url = f"{webhdfs.rstrip('/')}/webhdfs/v1{urllib.parse.quote(path, safe='/=')}?op=OPEN&user.name={urllib.parse.quote(hdfs_user)}"
    with urllib.request.urlopen(url, timeout=60) as response:
        body = response.read().decode("utf-8")
    return [json.loads(line) for line in body.splitlines() if line.strip()]


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dsn", default=os.environ.get("SHELTER_POSTGRES_DSN", ""))
    parser.add_argument("--region-catalog", default=os.environ.get("REGION_CODE_DATA_PATH", ""))
    parser.add_argument("--region-version", default=os.environ.get("REGION_CODE_DATA_VERSION", ""))
    parser.add_argument("--region-sha256", default=os.environ.get("REGION_CODE_DATA_SHA256", ""))
    parser.add_argument("--region-aliases", default=os.environ.get("REGION_ALIAS_DATA_PATH", ""))
    parser.add_argument("--region-aliases-sha256", default=os.environ.get("REGION_ALIAS_DATA_SHA256", ""))
    parser.add_argument("--webhdfs", default=os.environ.get("LOST_WEBHDFS", "http://bd-master:9870"))
    parser.add_argument("--hdfs-user", default=os.environ.get("LOST_HDFS_USER", "ubuntu"))
    parser.add_argument("--hdfs-dir", default="/data/lost/raw")
    parser.add_argument("--date", type=dt.date.fromisoformat, help="스냅샷 날짜(KST). 기본 오늘")
    parser.add_argument("--input", help="스냅샷 JSONL 파일 — HDFS 대신 (재적재·시험용)")
    args = parser.parse_args()
    if not all((args.dsn, args.region_catalog, args.region_version, args.region_sha256)):
        print("PostgreSQL DSN과 승인된 지역 기준 데이터 설정이 필요합니다", file=sys.stderr)
        return 2
    try:
        regions = RegionCatalog.from_csv(
            Path(args.region_catalog), args.region_version, args.region_sha256,
            aliases_path=Path(args.region_aliases) if args.region_aliases else None,
            aliases_checksum=args.region_aliases_sha256 or None,
        )
    except (OSError, ValueError) as error:
        print(f"적재기 설정 오류: {type(error).__name__}", file=sys.stderr)
        return 2
    snapshot_date = args.date or dt.datetime.now(ZoneInfo("Asia/Seoul")).date()
    try:
        if args.input:
            records = [json.loads(line) for line in Path(args.input).read_text(encoding="utf-8").splitlines() if line.strip()]
        else:
            records = read_snapshot(args.webhdfs, args.hdfs_user, args.hdfs_dir, snapshot_date)
    except OSError as error:
        print(f"스냅샷을 읽지 못했다({snapshot_date}): {type(error).__name__}", file=sys.stderr)
        return 3
    if not records:
        print(f"분실 적재: {snapshot_date} 스냅샷이 비어 있다 — 적재·종료 처리 생략")
        return 0
    try:
        import psycopg  # 스냅샷이 비어 있으면 DB 에 붙지도 않는다 — 여기서 늦게 올린다
    except ImportError:
        print("psycopg 가 필요하다 (shelter-loader venv)", file=sys.stderr)
        return 2
    with psycopg.connect(args.dsn) as connection:
        run_id, counts, summary = ingest_lost(connection, records, regions, snapshot_date)
    print(f"분실 적재 완료: run={run_id} · 스냅샷 {counts['fetched']} · 신규 {counts['inserted']} · 갱신 {counts['updated']}"
          f" · 종료 {counts['closed']} · 실패 {counts['failed']}" + (f" ({summary})" if summary else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
