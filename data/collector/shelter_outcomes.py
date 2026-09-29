"""보호소 결과 통계 배치 — 홈 카드 "보호소에 들어온 동물은 어떻게 되나" (D3 `shelterOutcomes`).

HDFS 백필(`/data/shelter/backfill/yyyymm=YYYYMM/records.jsonl`, 2008~)을 WebHDFS 로 읽어 **최근 3년 종결 건**의
주인 반환·입양 비율과 평균 공고 기간을 계산하고 서비스 PostgreSQL `dashboard_stat(stat_key='shelter_outcomes',
region_code='00000')` 에 upsert 한다. 백엔드(D3)는 payload 를 그대로 카드에 전달한다.

정직성 규칙 (2026-09-14 결정, docs/api-spec.md D3):
- 최근 3년만 — 2012년 '방사 6%' 처럼 분류가 흘러왔다. 18년 평균은 쓰지 않는다.
- 종결(`종료(*)`) 건만, **최근 60일 제외** — 아직 `보호중`인 건이 섞이면 반환율이 깎여 보인다.
- 안락사율은 payload 에 남기되 카드 헤드라인으로 쓰지 않는다 (앱 몫).

실행 (서버 1, shelter-loader venv):
    python shelter_outcomes.py                    # 오늘(KST) 기준 창을 계산해 DB 에 적재
    python shelter_outcomes.py --print-only       # DB 없이 payload 만 출력 (점검용)

백필은 일회성이라 새 달이 자동으로 붙지 않는다. 창의 끝(오늘-60일)이 마지막 백필 달을 넘어가면 그 구간은 빠진 채
계산되고 payload.coverageEndMonth 에 드러난다 — 그때는 backfill.py 로 최근 달을 다시 받는다.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from collections import Counter
from dataclasses import dataclass, field
from typing import Any, Iterable, Iterator

KST = dt.timezone(dt.timedelta(hours=9))
STAT_KEY = "shelter_outcomes"
NATIONAL_REGION = "00000"
CLOSED_PREFIX = "종료"
RETURNED = "종료(반환)"
ADOPTED = "종료(입양)"
MONTH_DIR = re.compile(r"^yyyymm=(\d{6})$")


@dataclass
class Window:
    start: dt.date
    end: dt.date

    @classmethod
    def ending(cls, today: dt.date, years: int, exclude_days: int) -> "Window":
        end = today - dt.timedelta(days=exclude_days)
        start = dt.date(end.year - years, end.month, min(end.day, 28))
        return cls(start, end)

    def months(self) -> list[str]:
        """창에 걸치는 yyyymm 라벨 (백필 파티션 이름)."""
        labels = []
        year, month = self.start.year, self.start.month
        while (year, month) <= (self.end.year, self.end.month):
            labels.append(f"{year:04d}{month:02d}")
            year, month = (year + 1, 1) if month == 12 else (year, month + 1)
        return labels


@dataclass
class Outcomes:
    window: Window
    closed: int = 0
    states: Counter = field(default_factory=Counter)
    notice_days_total: int = 0
    notice_samples: int = 0
    by_year: dict[int, Counter] = field(default_factory=dict)
    skipped: Counter = field(default_factory=Counter)
    seen: set[str] = field(default_factory=set)
    coverage_months: list[str] = field(default_factory=list)

    def add(self, record: dict[str, Any]) -> None:
        happened = parse_date(record.get("happenDt"))
        if happened is None:
            self.skipped["NO_HAPPEN_DATE"] += 1
            return
        if not (self.window.start <= happened <= self.window.end):
            self.skipped["OUT_OF_WINDOW"] += 1
            return
        key = str(record.get("desertionNo") or "")
        if key:
            if key in self.seen:
                self.skipped["DUPLICATE"] += 1
                return
            self.seen.add(key)
        state = str(record.get("processState") or "").strip()
        if not state.startswith(CLOSED_PREFIX):
            self.skipped["NOT_CLOSED"] += 1
            return
        self.closed += 1
        self.states[state] += 1
        year_counter = self.by_year.setdefault(happened.year, Counter())
        year_counter["closed"] += 1
        if state == RETURNED:
            year_counter["returned"] += 1
        notice_start = parse_date(record.get("noticeSdt"))
        notice_end = parse_date(record.get("noticeEdt"))
        if notice_start and notice_end and notice_end >= notice_start:
            self.notice_days_total += (notice_end - notice_start).days
            self.notice_samples += 1

    def payload(self, computed_at: dt.datetime) -> dict[str, Any]:
        returned = self.states.get(RETURNED, 0)
        adopted = self.states.get(ADOPTED, 0)
        rate = lambda count: round(count / self.closed, 4) if self.closed else 0.0  # noqa: E731
        return {
            "windowStart": self.window.start.isoformat(),
            "windowEnd": self.window.end.isoformat(),
            "closedCount": self.closed,
            "returnCount": returned,
            "adoptionCount": adopted,
            "returnRate": rate(returned),
            "adoptionRate": rate(adopted),
            "averageNoticeDays": round(self.notice_days_total / self.notice_samples, 1) if self.notice_samples else None,
            "noticeSampleCount": self.notice_samples,
            "byState": dict(sorted(self.states.items())),
            "byYear": {
                str(year): {"closed": c["closed"], "returnRate": round(c["returned"] / c["closed"], 4) if c["closed"] else 0.0}
                for year, c in sorted(self.by_year.items())
            },
            "coverageStartMonth": self.coverage_months[0] if self.coverage_months else None,
            "coverageEndMonth": self.coverage_months[-1] if self.coverage_months else None,
            "skipped": dict(sorted(self.skipped.items())),
            "computedAt": computed_at.astimezone(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        }


def parse_date(value: Any) -> dt.date | None:
    """공공 API 날짜 — 'YYYYMMDD' 또는 'YYYY-MM-DD'. 그 밖은 None."""
    text = str(value or "").strip()
    for pattern in ("%Y%m%d", "%Y-%m-%d"):
        try:
            return dt.datetime.strptime(text[:10] if "-" in text else text[:8], pattern).date()
        except ValueError:
            continue
    return None


def aggregate(records: Iterable[dict[str, Any]], window: Window, months: list[str] | None = None) -> Outcomes:
    outcomes = Outcomes(window)
    outcomes.coverage_months = list(months or [])
    for record in records:
        outcomes.add(record)
    return outcomes


# ── WebHDFS ────────────────────────────────────────────────────────────────

def _webhdfs_url(base: str, path: str, op: str, user: str) -> str:
    # quote 기본값은 "=" 도 인코딩한다 — yyyymm=202609 파티션 경로가 yyyymm%3D... 가 되면 NameNode 가 다른 경로로 본다.
    return f"{base.rstrip('/')}/webhdfs/v1{urllib.parse.quote(path, safe='/=')}?op={op}&user.name={urllib.parse.quote(user)}"


def list_backfill_months(webhdfs: str, hdfs_user: str, backfill_dir: str) -> list[str]:
    with urllib.request.urlopen(_webhdfs_url(webhdfs, backfill_dir, "LISTSTATUS", hdfs_user), timeout=60) as response:
        listing = json.loads(response.read().decode("utf-8"))
    labels = []
    for status in listing.get("FileStatuses", {}).get("FileStatus", []):
        match = MONTH_DIR.match(status.get("pathSuffix", ""))
        if match and status.get("type") == "DIRECTORY":
            labels.append(match.group(1))
    return sorted(labels)


def iter_month_records(webhdfs: str, hdfs_user: str, backfill_dir: str, label: str) -> Iterator[dict[str, Any]]:
    path = f"{backfill_dir}/yyyymm={label}/records.jsonl"
    with urllib.request.urlopen(_webhdfs_url(webhdfs, path, "OPEN", hdfs_user), timeout=300) as response:
        for raw in response:
            line = raw.decode("utf-8").strip()
            if line:
                yield json.loads(line)


def read_window(webhdfs: str, hdfs_user: str, backfill_dir: str, window: Window) -> tuple[Iterator[dict[str, Any]], list[str]]:
    available = set(list_backfill_months(webhdfs, hdfs_user, backfill_dir))
    wanted = [label for label in window.months() if label in available]
    missing = [label for label in window.months() if label not in available]
    if missing:
        print(f"백필에 없는 달 {len(missing)}개는 빠진 채 계산한다: {missing[0]}~{missing[-1]}", file=sys.stderr)

    def records() -> Iterator[dict[str, Any]]:
        for label in wanted:
            count = 0
            for record in iter_month_records(webhdfs, hdfs_user, backfill_dir, label):
                count += 1
                yield record
            print(f"{label}: {count}건", file=sys.stderr)

    return records(), wanted


# ── PostgreSQL ─────────────────────────────────────────────────────────────

UPSERT = """
INSERT INTO dashboard_stat(stat_key, region_code, payload, computed_at)
VALUES (%s, %s, %s::jsonb, %s)
ON CONFLICT (stat_key, region_code) DO UPDATE SET payload = EXCLUDED.payload, computed_at = EXCLUDED.computed_at
"""


def upsert(connection: Any, payload: dict[str, Any], computed_at: dt.datetime) -> None:
    with connection.cursor() as cursor:
        cursor.execute(UPSERT, (STAT_KEY, NATIONAL_REGION, json.dumps(payload, ensure_ascii=False), computed_at))
    connection.commit()


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dsn", default=os.environ.get("SHELTER_POSTGRES_DSN", ""))
    parser.add_argument("--webhdfs", default=os.environ.get("LOST_WEBHDFS", "http://bd-master:9870"))
    parser.add_argument("--hdfs-user", default=os.environ.get("LOST_HDFS_USER", "ubuntu"))
    parser.add_argument("--backfill-dir", default="/data/shelter/backfill")
    parser.add_argument("--years", type=int, default=3, help="창 길이(년). 기본 3")
    parser.add_argument("--exclude-days", type=int, default=60, help="아직 보호 중일 수 있는 최근 일수 제외. 기본 60")
    parser.add_argument("--today", type=dt.date.fromisoformat, help="기준일(KST). 기본 오늘")
    parser.add_argument("--print-only", action="store_true", help="DB 에 쓰지 않고 payload 만 출력")
    args = parser.parse_args()

    now = dt.datetime.now(KST)
    today = args.today or now.date()
    window = Window.ending(today, args.years, args.exclude_days)
    print(f"창 {window.start} ~ {window.end} ({len(window.months())}개월)", file=sys.stderr)

    records, months = read_window(args.webhdfs, args.hdfs_user, args.backfill_dir, window)
    outcomes = aggregate(records, window, months)
    payload = outcomes.payload(now)
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    if outcomes.closed == 0:
        print("종결 건이 0 이다 — 백필 경로나 창을 확인할 것. DB 는 갱신하지 않는다.", file=sys.stderr)
        return 2
    if args.print_only:
        return 0
    if not args.dsn:
        print("PostgreSQL DSN 이 필요하다 (SHELTER_POSTGRES_DSN)", file=sys.stderr)
        return 2
    try:
        import psycopg
    except ImportError:
        print("psycopg 가 필요하다 (shelter-loader venv)", file=sys.stderr)
        return 2
    with psycopg.connect(args.dsn) as connection:
        upsert(connection, payload, now)
    print(f"dashboard_stat {STAT_KEY}/{NATIONAL_REGION} 갱신: 종결 {outcomes.closed:,}건, 반환율 {payload['returnRate']:.1%}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
