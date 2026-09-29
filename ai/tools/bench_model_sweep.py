"""모델 사이즈 스윕 — 탐지(YOLO26 n/s/l-seg) × 임베딩(DINOv2 vits14/vitb14) (#105).

한 프로세스가 한 조합만 측정한다(피크 RSS 를 조합별로 정확히 재기 위해).
조합 전체는 셸 루프로 돌린다. model.yaml 은 수정하지 않고 스펙 필드만
dataclasses.replace 로 덮어써서 측정한다 — model_version v2 스펙은 그대로다.

품질 대리 지표:
- 탐지: 성공률(비폴백)·confidence 분포
- 임베딩: 벤치 세트 임베딩을 .npy 로 저장 → `--compare A.npy B.npy` 모드가
  이미지별 Top-5 이웃(코사인) 집합의 Jaccard 겹침으로 순위 보존율을 계산

사용법 (서버 2, ai/ 디렉터리에서):
    python tools/bench_model_sweep.py --image-dir ~/bench/images \
        --detector-weights ~/bench/weights/yolo26n-seg.pt \
        --embed-model-id dinov2_vits14 --embed-dim 384 \
        --embed-weights ~/bench/weights/dinov2_vits14_pretrain.pth \
        --tag n_s --out-dir ~/bench/results

    python tools/bench_model_sweep.py --compare ~/bench/results/vecs_l_b.npy ~/bench/results/vecs_n_s.npy
"""

from __future__ import annotations

import argparse
import dataclasses
import time
from pathlib import Path

import numpy as np

from bench_common import list_bench_images, peak_rss_mb, summarize, write_json

from app.detect import DetectorSpec, Yolo26SegDetector
from app.embed import DinoV2Embedder, ModelSpec, load_image
from app.pipeline import PetEmbeddingPipeline


def build_detector(weights: str | None) -> Yolo26SegDetector:
    spec = DetectorSpec.load()
    if weights:
        stem = Path(weights).stem
        spec = dataclasses.replace(spec, weights_path=str(Path(weights).expanduser()), detector_id=stem)
    return Yolo26SegDetector(spec=spec)


def build_embedder(model_id: str | None, dim: int | None, weights: str | None) -> DinoV2Embedder:
    spec = ModelSpec.load()
    overrides = {}
    if model_id:
        overrides["model_id"] = model_id
    if dim:
        overrides["dim"] = dim
    if weights:
        overrides["weights_path"] = str(Path(weights).expanduser())
    if overrides:
        spec = dataclasses.replace(spec, **overrides)
    return DinoV2Embedder(spec=spec)


def run_sweep(args: argparse.Namespace) -> int:
    images = list_bench_images(Path(args.image_dir).expanduser())
    out_dir = Path(args.out_dir).expanduser()
    out_dir.mkdir(parents=True, exist_ok=True)

    cold = {}
    start = time.perf_counter()
    detector = build_detector(args.detector_weights)
    cold["detector_load"] = round(time.perf_counter() - start, 3)
    start = time.perf_counter()
    embedder = build_embedder(args.embed_model_id, args.embed_dim, args.embed_weights)
    cold["embedder_load"] = round(time.perf_counter() - start, 3)

    pipeline = PetEmbeddingPipeline(detector=detector, embedder=embedder)
    pipeline.process_photos([(images[0].stem, images[0])])  # 워밍업

    timings = {}
    for count in args.counts:
        subset = [(p.stem, p) for p in images[:count]]
        runs = []
        for _ in range(args.repeats):
            start = time.perf_counter()
            pipeline.process_photos(subset)
            runs.append(time.perf_counter() - start)
        timings[str(count)] = summarize(runs)
        print(f"[{args.tag}] {count}장: p50 {timings[str(count)]['p50']}초")

    # 품질 대리 지표 — 전체 벤치 세트 1회 처리로 탐지 통계 + 임베딩 저장
    payload = pipeline.process_photos([(p.stem, p) for p in images])
    detections = [e["detection"] for e in payload["embeddings"]]
    confidences = [d["confidence"] for d in detections if d["confidence"] is not None]
    vectors = np.array([e["vector"] for e in payload["embeddings"]], dtype=np.float32)
    vec_path = out_dir / f"vecs_{args.tag}.npy"
    np.save(vec_path, vectors)

    write_json(out_dir / f"sweep_{args.tag}.json", {
        "bench": "model_sweep",
        "tag": args.tag,
        "detector": detector.spec.detector_id,
        "embedder": embedder.spec.model_id,
        "dim": embedder.spec.dim,
        "cold_start_s": cold,
        "peak_rss_mb": round(peak_rss_mb(), 1),
        "timings_e2e_s": timings,
        "detection": {
            "images": len(detections),
            "fallback_count": sum(1 for d in detections if d["fallback"]),
            "confidence": summarize(confidences) if confidences else None,
        },
        "vectors_npy": str(vec_path),
    })
    return 0


def run_compare(base_path: str, other_path: str, top_k: int = 5) -> int:
    """두 임베딩 세트의 이미지별 Top-K 이웃 집합 Jaccard 겹침 — 순위 보존율."""
    base = np.load(Path(base_path).expanduser())
    other = np.load(Path(other_path).expanduser())
    if base.shape[0] != other.shape[0]:
        raise SystemExit(f"이미지 수 불일치: {base.shape[0]} vs {other.shape[0]}")
    if base.shape[0] <= top_k:
        raise SystemExit(f"이미지 수({base.shape[0]})가 top_k({top_k}) 이하라 이웃 비교가 무의미합니다")

    def top_neighbors(vectors: np.ndarray) -> list[set[int]]:
        sims = vectors @ vectors.T
        np.fill_diagonal(sims, -np.inf)
        return [set(np.argsort(-row)[:top_k].tolist()) for row in sims]

    overlaps = [
        len(a & b) / len(a | b)
        for a, b in zip(top_neighbors(base), top_neighbors(other))
    ]
    print(f"이미지 {base.shape[0]}장, Top-{top_k} 이웃 Jaccard 겹침:")
    print(f"  평균 {np.mean(overlaps):.3f} / 중앙값 {np.median(overlaps):.3f} / 최소 {np.min(overlaps):.3f}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="모델 사이즈 스윕 (CPU)")
    parser.add_argument("--compare", nargs=2, metavar=("BASE_NPY", "OTHER_NPY"), default=None)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--image-dir")
    parser.add_argument("--detector-weights", default=None)
    parser.add_argument("--embed-model-id", default=None, help="예: dinov2_vits14")
    parser.add_argument("--embed-dim", type=int, default=None, help="예: 384")
    parser.add_argument("--embed-weights", default=None)
    parser.add_argument("--counts", type=int, nargs="+", default=[1, 10])
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--tag", default="run")
    parser.add_argument("--out-dir", default=".")
    args = parser.parse_args()

    if args.compare:
        return run_compare(args.compare[0], args.compare[1], args.top_k)
    if not args.image_dir:
        parser.error("--image-dir 는 스윕 모드에서 필수입니다")
    return run_sweep(args)


if __name__ == "__main__":
    raise SystemExit(main())
