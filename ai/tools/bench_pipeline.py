"""구간별 성능 프로파일 — 로드 → 탐지 → 마스킹·크롭 → 전처리 → 임베딩 forward (#105).

기존 파이프라인(app.detect / app.embed)을 수정 없이 import 해서 동일 연산을 구간별로
계측한다. 콜드 스타트(모델 로딩)는 별도 항목으로 기록한다.

사용법 (서버 2, ai/ 디렉터리에서):
    python tools/bench_pipeline.py --image-dir ~/bench/images --counts 1 3 5 10 --repeats 5 \
        --out ~/bench/results/profile.json
"""

from __future__ import annotations

import argparse
import time
from pathlib import Path

from bench_common import (
    MemHeadroomSampler,
    StageTimer,
    list_bench_images,
    mem_available_mb,
    peak_rss_mb,
    summarize,
    write_json,
)

from bench_model_sweep import build_detector, build_embedder

from app.detect import Yolo26SegDetector, crop_from_detections
from app.embed import DinoV2Embedder, load_image


def profile_once(detector: Yolo26SegDetector, embedder: DinoV2Embedder, paths: list[Path]) -> dict:
    """이미지 N장 한 세트를 구간별로 계측 — process_photos 와 동일한 연산 순서."""
    timer = StageTimer()
    total_start = time.perf_counter()

    images = []
    with timer.measure("load_decode"):
        for path in paths:
            images.append(load_image(path))

    detections_per_image = []
    with timer.measure("detect"):
        for image in images:
            detections_per_image.append(detector.detect(image))

    crops = []
    with timer.measure("mask_crop"):
        for image, detections in zip(images, detections_per_image):
            crops.append(crop_from_detections(image, detections, detector.spec))

    with timer.measure("preprocess"):
        batch = embedder._preprocess([crop.image for crop in crops])

    with timer.measure("embed_forward"):
        import torch

        with torch.inference_mode():
            output = embedder.model(batch.to(embedder.device))
            if embedder.spec.normalized:
                output = torch.nn.functional.normalize(output, dim=-1)
            output.cpu().float().numpy()

    total = time.perf_counter() - total_start
    fallbacks = sum(1 for crop in crops if crop.fallback)
    return {"stages": {k: v[0] for k, v in timer.records.items()}, "total": total, "fallbacks": fallbacks}


def main() -> int:
    parser = argparse.ArgumentParser(description="파이프라인 구간별 프로파일 (CPU)")
    parser.add_argument("--image-dir", required=True)
    parser.add_argument("--counts", type=int, nargs="+", default=[1, 3, 5, 10])
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--detector-weights", default=None)
    parser.add_argument("--embed-model-id", default=None, help="예: dinov2_vits14 (기본: model.yaml)")
    parser.add_argument("--embed-dim", type=int, default=None, help="예: 384")
    parser.add_argument("--embed-weights", default=None)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    images = list_bench_images(Path(args.image_dir).expanduser(), limit=max(args.counts))
    if len(images) < max(args.counts):
        raise SystemExit(f"이미지 부족: {len(images)}장 (필요: {max(args.counts)}장)")

    # 콜드 스타트 — 프로세스당 1회 발생하는 비용을 웜 측정과 분리
    cold = {}
    start = time.perf_counter()
    detector = build_detector(args.detector_weights)
    cold["detector_load"] = round(time.perf_counter() - start, 3)
    start = time.perf_counter()
    embedder = build_embedder(args.embed_model_id, args.embed_dim, args.embed_weights)
    cold["embedder_load"] = round(time.perf_counter() - start, 3)

    # 워밍업 1회 — 첫 추론의 lazy 초기화(스레드 풀 등)를 웜 측정에서 제외
    profile_once(detector, embedder, images[:1])

    results = {}
    for count in args.counts:
        with MemHeadroomSampler() as sampler:
            runs = [profile_once(detector, embedder, images[:count]) for _ in range(args.repeats)]
        stage_names = runs[0]["stages"].keys()
        results[str(count)] = {
            "total": summarize([r["total"] for r in runs]),
            "stages": {name: summarize([r["stages"][name] for r in runs]) for name in stage_names},
            "fallbacks": runs[0]["fallbacks"],
            "min_mem_available_mb": round(sampler.min_available_mb, 1),
        }
        print(
            f"{count}장 × {args.repeats}회: total p50 {results[str(count)]['total']['p50']}초, "
            f"RAM 여유 최소 {results[str(count)]['min_mem_available_mb']}MB"
        )

    write_json(Path(args.out).expanduser(), {
        "bench": "pipeline_profile",
        "detector_weights": str(detector.spec.weights_path),
        "embed_weights": str(embedder.spec.weights_path),
        "cold_start_s": cold,
        "peak_rss_mb": round(peak_rss_mb(), 1),
        "idle_mem_available_mb": round(mem_available_mb(), 1),
        "results": results,
    })
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
