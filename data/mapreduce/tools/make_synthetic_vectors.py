"""합성 벡터 생성기 — 정답(클러스터)을 심어둔 벡터로 K-Means 정확성을 검증한다.

표준 라이브러리만 사용. 출력 형식은 data-ai-interface의 벡터 계약과 동일:
    vectors.tsv  : id<TAB>v1,v2,...   (K-Means 입력)
    truth.tsv    : id<TAB>clusterId   (심어둔 정답 — 채점용, 파이프라인엔 안 들어감)
    centers.tsv  : clusterId<TAB>v1,v2,...  (초기 중심 — 데이터에서 무작위 추출)

사용법: python make_synthetic_vectors.py --n 13000 --dim 64 --clusters 20 --out ./out
"""

import argparse
import random
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--n", type=int, default=13000)
    parser.add_argument("--dim", type=int, default=64)
    parser.add_argument("--clusters", type=int, default=20)
    parser.add_argument("--spread", type=float, default=0.08, help="군집 내 표준편차")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    rng = random.Random(args.seed)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    true_centers = [
        [rng.uniform(-1.0, 1.0) for _ in range(args.dim)] for _ in range(args.clusters)
    ]

    sample_rows: list[str] = []
    with (out / "vectors.tsv").open("w", encoding="utf-8") as vec_fp, \
            (out / "truth.tsv").open("w", encoding="utf-8") as truth_fp:
        for i in range(args.n):
            cluster = rng.randrange(args.clusters)
            vector = [rng.gauss(c, args.spread) for c in true_centers[cluster]]
            row = f"v{i:06d}\t{','.join(f'{x:.6f}' for x in vector)}"
            vec_fp.write(row + "\n")
            truth_fp.write(f"v{i:06d}\t{cluster}\n")
            sample_rows.append(row)

    # 초기 중심 = 데이터에서 무작위 K개 (정답 중심을 주지 않는다 — 찾아내는 게 시험)
    with (out / "centers.tsv").open("w", encoding="utf-8") as fp:
        for idx, row in enumerate(rng.sample(sample_rows, args.clusters)):
            fp.write(f"{idx}\t{row.split(chr(9))[1]}\n")

    print(f"생성 완료: n={args.n} dim={args.dim} k={args.clusters} → {out}")


if __name__ == "__main__":
    main()
