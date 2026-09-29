"""행렬곱 결과 검증 — 표본 질의의 코사인을 로컬에서 재계산해 MR 결과와 대조한다.

사용법: python eval_matmul.py --queries queries.tsv --vectors vectors.tsv \
        --results matmul-output.tsv --threshold 0.8 --sample 20
"""

import argparse
import math
import random
from collections import defaultdict


def load_vectors(path: str) -> dict[str, list[float]]:
    out = {}
    with open(path, encoding="utf-8") as fp:
        for line in fp:
            if line.strip():
                vid, csv = line.rstrip("\n").split("\t")
                v = [float(x) for x in csv.split(",")]
                norm = math.sqrt(sum(x * x for x in v)) or 1.0
                out[vid] = [x / norm for x in v]
    return out


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--queries", required=True)
    parser.add_argument("--vectors", required=True)
    parser.add_argument("--results", required=True)
    parser.add_argument("--threshold", type=float, default=0.8)
    parser.add_argument("--sample", type=int, default=20)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    queries = load_vectors(args.queries)
    shelters = load_vectors(args.vectors)

    mr: dict[str, dict[str, float]] = defaultdict(dict)
    with open(args.results, encoding="utf-8") as fp:
        for line in fp:
            qid, sid, cos = line.split("\t")
            mr[qid][sid] = float(cos)

    rng = random.Random(args.seed)
    sample_qids = rng.sample(sorted(queries), min(args.sample, len(queries)))

    mismatches = 0
    checked_pairs = 0
    for qid in sample_qids:
        expected = {
            sid: sum(a * b for a, b in zip(queries[qid], vec))
            for sid, vec in shelters.items()
        }
        expected_over = {sid: c for sid, c in expected.items() if c >= args.threshold}
        got = mr.get(qid, {})
        if set(expected_over) != set(got):
            mismatches += 1
            continue
        for sid, cos in expected_over.items():
            checked_pairs += 1
            if abs(cos - got[sid]) > 1e-6:
                mismatches += 1
                break
    print(
        f"표본 질의 {len(sample_qids)}개 · 대조 쌍 {checked_pairs} · "
        f"불일치 {mismatches} → {'PASS' if mismatches == 0 else 'FAIL'}"
    )


if __name__ == "__main__":
    main()
