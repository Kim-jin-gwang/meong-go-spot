"""kNN 조인 결과 채점 — Top-1 적중률과 Top-K 포함률.

정답: 질의는 원본 벡터의 노이즈 사본이므로 rank 1 = 원본이어야 한다.
사용법: python eval_knn.py --truth query-truth.tsv --results knn-output.tsv
"""

import argparse
from collections import defaultdict


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--truth", required=True)
    parser.add_argument("--results", required=True)
    args = parser.parse_args()

    truth = {}
    with open(args.truth, encoding="utf-8") as fp:
        for line in fp:
            qid, original = line.split()
            truth[qid] = original

    ranked: dict[str, list[tuple[int, str]]] = defaultdict(list)
    with open(args.results, encoding="utf-8") as fp:
        for line in fp:
            qid, rank, sid, _dist = line.split("\t")
            ranked[qid].append((int(rank), sid))

    top1 = sum(1 for qid, rows in ranked.items() if min(rows)[1] == truth[qid])
    topk = sum(1 for qid, rows in ranked.items() if truth[qid] in {sid for _, sid in rows})
    n = len(ranked)
    print(f"질의 {n}개 · Top-1 적중 {top1/n:.4f} · Top-K 포함 {topk/n:.4f}")


if __name__ == "__main__":
    main()
