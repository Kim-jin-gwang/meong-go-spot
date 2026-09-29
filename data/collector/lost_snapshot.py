"""분실동물 공공 API 일일 스냅샷 → HDFS 원문 보관 (#145).

`lossInfoService` 는 **최근 1개월치만** 주고 과거 조회 파라미터가 없다. 오늘 안 받은 날은 영원히 없는 데이터라,
서비스 DB 적재(BE 스키마 대기)와 무관하게 매일 전량을 받아 `{hdfs_dir}/dt=YYYY-MM-DD/records.jsonl` 로 남긴다.
나중 대시보드(지역·축종별 신고 추이, 신고가 목록에서 사라지기까지의 기간)와 PostgreSQL 적재의 과거분 원천이다.

전량 스냅샷을 택한 이유(변경분 저장 대신): 신고가 창에 ~30일 머물기 때문에 하루 실패해도 다음 날 스냅샷이 다시
담는다(잃는 것은 사라진 날짜의 하루 오차). 상태 없이 멱등하고, API 필드가 바뀌어도 원문 JSON 은 그대로 남는다.
10년 누적 ≈ 600MB(복제 2 → 1.2GB) — 보관 비용은 문제가 아니다.

**저장 전에 지운다** — 신고자(비회원) 개인정보를 원장에 남길 이유가 없다:
    callName, callTel  → 삭제 (마스킹 문구 포함)
    happenAddrDtl      → 삭제 (건물명 — 자택일 수 있다)
    happenAddr         → 시·군·구까지만 ("대전광역시 유성구 유성대로654번길 130 (구암동)" → "대전광역시 유성구")
덧붙인다:
    lostKey    = sha256(happenDt + 원문 happenAddr + popfile) — 고유 ID 가 없어 만든 멱등 키 (2026-09-15 163건 중복 0).
                 자르기 **전** 주소로 만들어 안정적이고, 저장된 주소로는 되돌릴 수 없다
    species    = 품종 이름에서 유도한 축종 (DOG/CAT/OTHER/null, `breed_species.py`) — 사전이 없으면 생략
    snapshotDt = 스냅샷 날짜(KST)

마지막 줄 형식은 알림(`dags/alerts.py`)이 읽는다:
    분실 스냅샷 완료: 163건 (개 110 · 고양이 26 · 미상 27) → /data/lost/raw/dt=2026-09-15/records.jsonl
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from zoneinfo import ZoneInfo

import api
import breed_species

KST = ZoneInfo("Asia/Seoul")
DROP_FIELDS = ("callName", "callTel", "happenAddrDtl")


def lost_key(record: dict) -> str:
    raw = "|".join(str(record.get(k) or "").strip() for k in ("happenDt", "happenAddr", "popfile"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def coarse_address(address: str | None) -> str | None:
    """도로명 주소 → 앞 두 토큰(시·도 + 시·군·구). 세종처럼 한 토큰이면 그대로."""
    tokens = str(address or "").split()
    return " ".join(tokens[:2]) if tokens else None


def sanitize(record: dict, table: dict[str, str] | None, snapshot_date: dt.date) -> dict:
    out = {k: v for k, v in record.items() if k not in DROP_FIELDS}
    out["lostKey"] = lost_key(record)
    out["happenAddr"] = coarse_address(record.get("happenAddr"))
    out["orgNm"] = " ".join(str(record.get("orgNm") or "").split()) or None  # 뒤에 붙어 오는 공백 정리
    if table is not None:
        out["species"] = breed_species.species_of(record.get("kindCd"), table)
    out["snapshotDt"] = snapshot_date.isoformat()
    return out


def species_counts(records: list[dict]) -> dict[str, int]:
    counts = {"DOG": 0, "CAT": 0, "UNRESOLVED": 0}
    for r in records:
        species = r.get("species")
        counts["DOG" if species == "DOG" else "CAT" if species == "CAT" else "UNRESOLVED"] += 1
    return counts


def upload(records: list[dict], hdfs_dir: str, snapshot_date: dt.date) -> str:
    """같은 날짜 파티션에 `records.jsonl` 하나 — `-put -f` 라 재실행하면 덮어쓴다(멱등)."""
    dest_dir = f"{hdfs_dir}/dt={snapshot_date.isoformat()}"
    dest = f"{dest_dir}/records.jsonl"
    with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False, encoding="utf-8", newline="\n") as spool:
        for r in records:
            spool.write(json.dumps(r, ensure_ascii=False) + "\n")
        spool_path = Path(spool.name)
    try:
        subprocess.run(["hdfs", "dfs", "-mkdir", "-p", dest_dir], check=True)
        subprocess.run(["hdfs", "dfs", "-put", "-f", str(spool_path), dest], check=True)
    finally:
        spool_path.unlink(missing_ok=True)
    return dest


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--hdfs-dir", default="/data/lost/raw")
    ap.add_argument("--breed-species", default="reference/breed-species.csv", help="품종→축종 CSV (없으면 species 생략)")
    ap.add_argument("--dry-run", action="store_true", help="HDFS 에 쓰지 않고 건수만")
    args = ap.parse_args()

    key = (os.environ.get("DATA_GO_KR_SERVICE_KEY") or "").strip()
    if not key:
        print("DATA_GO_KR_SERVICE_KEY 환경변수가 없습니다", file=sys.stderr)
        return 2
    table_path = Path(args.breed_species)
    table = breed_species.load_table(table_path) if table_path.is_file() else None
    if table is None:
        print(f"품종 사전 없음({table_path}) — species 를 넣지 않는다", file=sys.stderr)

    snapshot_date = dt.datetime.now(KST).date()
    raw, total = api.fetch_lost_all(key)
    if not raw:
        print("분실 스냅샷: 0건 — API 가 빈 목록을 주었다 (저장 생략)")
        return 0
    if total and abs(total - len(raw)) > max(20, total * 0.5):
        print(f"totalCount {total} vs 실제 {len(raw)} — 차이가 크다 (참고, 실제 건수를 저장한다)", file=sys.stderr)
    records = [sanitize(r, table, snapshot_date) for r in raw]
    keys = {r["lostKey"] for r in records}
    if len(keys) != len(records):
        print(f"멱등 키 중복 {len(records) - len(keys)}건 — 같은 일시·주소·사진의 신고", file=sys.stderr)

    counts = species_counts(records) if table is not None else None
    detail = f" (개 {counts['DOG']} · 고양이 {counts['CAT']} · 미상 {counts['UNRESOLVED']})" if counts else ""
    if args.dry_run:
        print(f"분실 스냅샷 드라이런: {len(records)}건{detail}")
        return 0
    dest = upload(records, args.hdfs_dir, snapshot_date)
    print(f"분실 스냅샷 완료: {len(records)}건{detail} → {dest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
