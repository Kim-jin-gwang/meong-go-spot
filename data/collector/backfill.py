"""역사 백필 — 공공 API 과거 공고를 월 단위로 전량 수집해 HDFS에 적재한다.

정찰(2026-09-03) 결과: 2008~2026 레코드 1,634,915건 = 1,643호출(일 쿼터 10,000의 16%).
Kafka를 거치지 않고 HDFS 직행 — Kafka는 일일 증분의 통로(보존 7일)지 벌크의 길이 아니다.

    HDFS: <hdfs-dir>/yyyymm=YYYYMM/records.jsonl   (월당 파일 1개 — small files 회피)
    상태: <state>  — 완료한 월 목록 + 누적 호출 수 (재개 가능)
    지표: <metrics> — 월별 totalCount vs 수집 수, 호출·실패, 연도별 품질 프로파일·상태 분포,
                      반환 케이스 골든타임 히스토그램 재료

사용법 (서버 2):
    python backfill.py --from 2008-01 --to 2026-09 --hdfs-dir /data/shelter/backfill \
        --state ~/collector-data/backfill-state.json --metrics ~/collector-data/backfill-metrics.json
"""

import argparse
import datetime
import json
import os
import subprocess
import sys
import tempfile
import time
from collections import Counter, defaultdict
from pathlib import Path

import api

DAILY_CALL_CAP = 9000  # 일 쿼터 10,000 — 일일 수집기 몫을 남겨둔다
MIN_INTERVAL_SEC = 0.2  # 초당 5호출 상한 (포털 tps 배려)


def month_range(start: str, end: str):
    y, m = map(int, start.split("-"))
    ey, em = map(int, end.split("-"))
    while (y, m) <= (ey, em):
        yield y, m
        m += 1
        if m > 12:
            y, m = y + 1, 1


def month_bounds(y: int, m: int) -> tuple[str, str]:
    first = datetime.date(y, m, 1)
    last = (datetime.date(y + (m == 12), (m % 12) + 1, 1) - datetime.timedelta(days=1))
    return first.strftime("%Y%m%d"), last.strftime("%Y%m%d")


def load_json(path: Path, default):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default


def save_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    os.replace(tmp, path)


def profile(records: list[dict], year: str, metrics: dict) -> None:
    """연도별 품질·도메인 지표 누적 — 결측률, 상태 분포, 반환 골든타임(발견→종료 갱신 일수)."""
    q = metrics["quality_by_year"].setdefault(
        year, {"records": 0, "no_image": 0, "no_updtm": 0, "no_kind": 0, "no_region": 0}
    )
    states = metrics["state_by_year"].setdefault(year, {})
    golden = metrics["return_days_hist"]
    for r in records:
        q["records"] += 1
        q["no_image"] += not r.get("popfile1")
        q["no_updtm"] += not r.get("updTm")
        q["no_kind"] += not r.get("kindCd")
        q["no_region"] += not r.get("orgNm")
        state = str(r.get("processState") or "?")
        states[state] = states.get(state, 0) + 1
        if "반환" in state and r.get("happenDt") and r.get("updTm"):
            try:
                found = datetime.datetime.strptime(r["happenDt"], "%Y%m%d")
                closed = datetime.datetime.strptime(r["updTm"][:10], "%Y-%m-%d")
                days = (closed - found).days
                bucket = "0-3" if days <= 3 else "4-7" if days <= 7 else "8-14" if days <= 14 else "15-30" if days <= 30 else "31+"
                if days >= 0:
                    golden[bucket] = golden.get(bucket, 0) + 1
            except ValueError:
                pass


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    p = argparse.ArgumentParser(description="역사 백필 (월 슬라이스, 재개 가능)")
    p.add_argument("--from", dest="start", required=True, help="YYYY-MM")
    p.add_argument("--to", dest="end", required=True, help="YYYY-MM")
    p.add_argument("--hdfs-dir", required=True)
    p.add_argument("--state", required=True)
    p.add_argument("--metrics", required=True)
    args = p.parse_args()
    if tuple(map(int, args.start.split("-"))) > tuple(map(int, args.end.split("-"))):
        print(f"--from {args.start} 이 --to {args.end} 보다 뒤입니다 — 처리할 월이 없습니다", file=sys.stderr)
        return 2

    key = os.environ.get("DATA_GO_KR_SERVICE_KEY", "")
    if not key:
        print("DATA_GO_KR_SERVICE_KEY 환경변수가 없습니다", file=sys.stderr)
        return 1

    state_path, metrics_path = Path(args.state), Path(args.metrics)
    state = load_json(state_path, {"done_months": [], "calls_by_day": {}})
    metrics = load_json(metrics_path, {
        "months": {}, "quality_by_year": {}, "state_by_year": {}, "return_days_hist": {},
        "calls_total": 0, "retries": 0,
    })
    today = datetime.date.today().isoformat()

    for y, m in month_range(args.start, args.end):
        label = f"{y}{m:02d}"
        if label in state["done_months"]:
            continue
        if state["calls_by_day"].get(today, 0) >= DAILY_CALL_CAP:
            print(f"일 호출 상한({DAILY_CALL_CAP}) 도달 — 내일 재실행하면 {label}부터 이어간다")
            break

        bgnde, endde = month_bounds(y, m)
        records: list[dict] = []
        total = 0
        page = 1
        while True:
            time.sleep(MIN_INTERVAL_SEC)
            try:
                page_records, total = api.fetch_page(key, page, {"bgnde": bgnde, "endde": endde})
            except RuntimeError as e:  # 재시도 소진 — 이 월은 미완으로 두고 다음 실행에 재시도
                metrics["retries"] += 1
                print(f"{label} p{page} 실패: {e} — 월 미완료로 남김")
                records = None
                break
            state["calls_by_day"][today] = state["calls_by_day"].get(today, 0) + 1
            metrics["calls_total"] += 1
            if not page_records:
                break
            records.extend(page_records)
            if len(records) >= total:
                break
            page += 1
        if records is None:
            save_json(state_path, state)
            save_json(metrics_path, metrics)
            continue

        # 적재 (건수 대조 후) — 월당 파일 1개.
        # 과거 월은 API의 totalCount와 실제 항목 수가 몇 건씩 어긋난다(페이지 사이 갱신·삭제된 레코드,
        # 2026-09-03 실측: 94개월에서 1~11건). 유령 레코드는 받아올 방법이 없으므로 소량은 허용하고
        # 차이를 지표에 그대로 남긴다. 큰 차이는 부분 수집으로 보고 미완료 처리한다.
        diff = total - len(records)
        tolerance = max(20, int(total * 0.005))
        if abs(diff) > tolerance:
            print(f"{label}: 건수 불일치 수집 {len(records)} != totalCount {total} (허용 {tolerance}) — 미완료로 남김")
            metrics["months"][label] = {"total": total, "fetched": len(records), "ok": False}
            save_json(state_path, state)  # 이 월의 호출 수(calls_by_day)도 확정 — 캡 계산 누락 방지
            save_json(metrics_path, metrics)
            continue
        with tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False, encoding="utf-8") as fp:
            for r in records:
                fp.write(json.dumps(r, ensure_ascii=False) + "\n")
            spool = fp.name
        dest = f"{args.hdfs_dir}/yyyymm={label}"
        try:
            subprocess.run(["hdfs", "dfs", "-mkdir", "-p", dest], check=True)
            subprocess.run(["hdfs", "dfs", "-put", "-f", spool, f"{dest}/records.jsonl"], check=True)
        finally:
            os.unlink(spool)  # hdfs 실패로 예외가 나가도 스풀은 남기지 않는다

        profile(records, str(y), metrics)
        metrics["months"][label] = {"total": total, "fetched": len(records), "ok": True, "diff": diff}
        state["done_months"].append(label)
        save_json(state_path, state)
        save_json(metrics_path, metrics)
        print(f"{label}: {len(records):,}건 적재 (호출 누계 {metrics['calls_total']})")

    done = len(state["done_months"])
    print(f"완료 월 {done}개 · 총 호출 {metrics['calls_total']} · 재시도 소진 {metrics['retries']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
