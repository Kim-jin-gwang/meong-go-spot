"""K-Means 배정 결과를 심어둔 정답과 대조해 purity를 계산한다.

purity = 각 예측 클러스터에서 최다 정답 라벨의 합 / 전체 — 1.0이면 완벽 복원.
사용법: python eval_purity.py --truth truth.tsv --assignments assignments.tsv
"""

import argparse
from collections import Counter, defaultdict


def load(path: str) -> dict[str, str]:
    result = {}
    with open(path, encoding="utf-8") as fp:
        for line in fp:
            if line.strip():
                key, value = line.rstrip("\n").split("\t")
                result[key] = value
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--truth", required=True)
    parser.add_argument("--assignments", required=True)
    args = parser.parse_args()

    truth = load(args.truth)
    assigned = load(args.assignments)

    by_cluster: dict[str, Counter] = defaultdict(Counter)
    for vector_id, cluster in assigned.items():
        by_cluster[cluster][truth[vector_id]] += 1

    dominant_total = sum(counter.most_common(1)[0][1] for counter in by_cluster.values())
    purity = dominant_total / len(assigned)
    print(f"벡터 {len(assigned)}개 · 예측 클러스터 {len(by_cluster)}개 · purity = {purity:.4f}")


if __name__ == "__main__":
    main()
