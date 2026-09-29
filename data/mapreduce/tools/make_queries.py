"""kNN 조인 검증용 질의 생성 — 보호 벡터 일부에 소량 노이즈를 얹어 질의로 만든다.

각 질의의 정답(가장 가까워야 할 보호 벡터)은 원본 자신이므로,
kNN 결과의 rank 1이 원본과 일치하는 비율(Top-1 적중률)로 채점할 수 있다.

사용법: python make_queries.py --vectors vectors.tsv --n 200 --out ./out
"""

import argparse
import random
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vectors", required=True)
    parser.add_argument("--n", type=int, default=200)
    parser.add_argument("--noise", type=float, default=0.02, help="원본 대비 노이즈 표준편차")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    rng = random.Random(args.seed)
    lines = [l for l in Path(args.vectors).read_text(encoding="utf-8").splitlines() if l.strip()]
    chosen = rng.sample(lines, args.n)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    with (out / "queries.tsv").open("w", encoding="utf-8") as q_fp, \
            (out / "query-truth.tsv").open("w", encoding="utf-8") as t_fp:
        for i, line in enumerate(chosen):
            original_id, csv = line.split("\t", 1)
            noisy = [float(x) + rng.gauss(0, args.noise) for x in csv.split(",")]
            q_fp.write(f"q{i:04d}\t{','.join(f'{x:.6f}' for x in noisy)}\n")
            t_fp.write(f"q{i:04d}\t{original_id}\n")
    print(f"질의 {args.n}개 생성 → {out}")


if __name__ == "__main__":
    main()
