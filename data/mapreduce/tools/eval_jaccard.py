"""자카드 결과 검증 — 표본 id의 자카드를 로컬 전량 재계산해 MR 결과와 집합 대조한다.

사용법: python eval_jaccard.py --tags tags.tsv --results pairs.tsv --threshold 0.6 --sample 100
"""

import argparse
import random
from collections import defaultdict


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tags", required=True)
    parser.add_argument("--results", required=True)
    parser.add_argument("--threshold", type=float, default=0.6)
    parser.add_argument("--sample", type=int, default=100)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    tag_sets: dict[str, frozenset] = {}
    with open(args.tags, encoding="utf-8") as fp:
        for line in fp:
            animal_id, csv = line.rstrip("\n").split("\t")
            tag_sets[animal_id] = frozenset(csv.split(","))

    mr_pairs: dict[str, set[tuple[str, float]]] = defaultdict(set)
    with open(args.results, encoding="utf-8") as fp:
        for line in fp:
            a, b, j = line.rstrip("\n").split("\t")
            mr_pairs[a].add((b, round(float(j), 6)))
            mr_pairs[b].add((a, round(float(j), 6)))

    rng = random.Random(args.seed)
    sample = rng.sample(sorted(tag_sets), min(args.sample, len(tag_sets)))

    mismatches = 0
    for a in sample:
        expected = set()
        for b, tags_b in tag_sets.items():
            if b == a:
                continue
            common = len(tag_sets[a] & tags_b)
            if common == 0:
                continue
            jaccard = common / len(tag_sets[a] | tags_b)
            if jaccard >= args.threshold:
                expected.add((b, round(jaccard, 6)))
        if expected != mr_pairs.get(a, set()):
            mismatches += 1
    print(
        f"표본 {len(sample)}개 id 전량 대조 · 불일치 {mismatches} → "
        f"{'PASS' if mismatches == 0 else 'FAIL'}"
    )


if __name__ == "__main__":
    main()
