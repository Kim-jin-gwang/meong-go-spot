"""쎄타조인 결과 검증 — 로컬에서 전량 재계산한 정답 쌍과 MR 결과를 집합 비교한다.

사용법: python eval_theta.py --queries query-meta.tsv --shelters shelter-meta.tsv \
        --results theta-output.tsv --window 90
"""

import argparse
import datetime
from collections import defaultdict


def parse(path: str):
    rows = []
    with open(path, encoding="utf-8") as fp:
        for line in fp:
            cols = line.rstrip("\n").split("\t")
            if len(cols) >= 4:
                rows.append((cols[0], cols[1], cols[2], datetime.datetime.strptime(cols[3], "%Y%m%d")))
    return rows


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--queries", required=True)
    parser.add_argument("--shelters", required=True)
    parser.add_argument("--results", required=True)
    parser.add_argument("--window", type=int, default=90)
    args = parser.parse_args()

    shelters_by_bucket = defaultdict(list)
    for sid, kind, region, date in parse(args.shelters):
        shelters_by_bucket[(kind, region)].append((sid, date))

    expected = set()
    window = datetime.timedelta(days=args.window)
    for qid, kind, region, lost in parse(args.queries):
        for sid, happen in shelters_by_bucket.get((kind, region), []):
            if lost <= happen <= lost + window:
                expected.add((qid, sid))

    got = set()
    with open(args.results, encoding="utf-8") as fp:
        for line in fp:
            qid, sid, _date = line.rstrip("\n").split("\t")
            got.add((qid, sid))

    missing = len(expected - got)
    extra = len(got - expected)
    verdict = "PASS" if not missing and not extra else "FAIL"
    print(f"정답 쌍 {len(expected)} · MR 결과 {len(got)} · 누락 {missing} · 초과 {extra} → {verdict}")


if __name__ == "__main__":
    main()
