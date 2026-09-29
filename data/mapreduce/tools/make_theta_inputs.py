"""쎄타조인 입력 준비 — 실수집 레코드(records.jsonl)에서 메타 tsv를 뽑고 합성 신고를 만든다.

    shelter-meta.tsv : sid \t 품종코드 \t 지역(시도) \t 발견일(YYYYMMDD)
    query-meta.tsv   : qid \t 품종코드 \t 지역(시도) \t 실종일(YYYYMMDD)

합성 신고 = 실제 레코드 표본의 (품종·지역)을 그대로 두고 실종일 = 발견일 - 0~30일.
따라서 각 신고는 최소한 자기 원본과는 조인돼야 한다 (채점은 로컬 전량 재계산으로).

사용법: python make_theta_inputs.py --records records.jsonl --queries 300 --out ./out
"""

import argparse
import datetime
import json
import random
from pathlib import Path


def region_of(record: dict) -> str:
    return str(record.get("orgNm") or "").split()[0] if record.get("orgNm") else ""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--records", required=True)
    parser.add_argument("--queries", type=int, default=300)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    rng = random.Random(args.seed)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    rows = []
    seen = set()
    with open(args.records, encoding="utf-8") as fp:
        for line in fp:
            r = json.loads(line)
            sid = str(r.get("desertionNo") or "")
            kind = str(r.get("kindCd") or "")
            region = region_of(r)
            happen = str(r.get("happenDt") or "")
            if sid and kind and region and len(happen) == 8 and sid not in seen:
                seen.add(sid)
                rows.append((sid, kind, region, happen))

    with (out / "shelter-meta.tsv").open("w", encoding="utf-8") as fp:
        for row in rows:
            fp.write("\t".join(row) + "\n")

    with (out / "query-meta.tsv").open("w", encoding="utf-8") as fp:
        for i, (sid, kind, region, happen) in enumerate(rng.sample(rows, args.queries)):
            d = datetime.datetime.strptime(happen, "%Y%m%d") - datetime.timedelta(days=rng.randint(0, 30))
            fp.write(f"q{i:04d}\t{kind}\t{region}\t{d.strftime('%Y%m%d')}\n")

    print(f"보호 {len(rows)}건 · 신고 {args.queries}건 → {out}")


if __name__ == "__main__":
    main()
