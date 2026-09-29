"""부하 테스트용 임시 HTTP 래퍼 — 파이프라인을 FastAPI 로 감싼다 (#105).

측정 전용이다. 실서비스 서빙 구조를 정의하지 않으며, M2 온디맨드 요청
(사진 1~10장 → 탐지·크롭 → 배치 임베딩)의 연산 부하를 재현하는 것이 목적이다.
업로드 오버헤드를 빼고 순수 연산 지연을 재기 위해 요청은 장수만 지정하고
서버가 로컬 벤치 이미지에서 샘플링한다. kNN 검색(DATA 소유)은 범위 밖.

동시성 주의: FastAPI 동기 엔드포인트는 스레드풀에서 병렬 실행되고 모델 객체
(YOLO predictor·torch 모듈)를 스레드 간 공유한다 — CPU 경합을 그대로 재는 것이
목적이라 락을 걸지 않는다. ultralytics predictor 는 스레드 세이프가 보장되지
않으므로 이 래퍼를 실서비스 서빙에 그대로 쓰면 안 된다.

사용법 (서버 2, 이 파일이 있는 디렉터리에서, 벤치 venv 로):
    BENCH_IMAGE_DIR=~/bench/images uvicorn bench_serve:app --host 127.0.0.1 --port 8100
"""

from __future__ import annotations

import os
import random
import time
from pathlib import Path

from fastapi import FastAPI, HTTPException

from bench_common import list_bench_images
from bench_model_sweep import build_detector, build_embedder

from app.pipeline import PetEmbeddingPipeline

IMAGE_DIR = Path(os.environ.get("BENCH_IMAGE_DIR", "~/bench/images")).expanduser()
MAX_COUNT = 10

app = FastAPI(title="bench-serve (측정 전용)")
_images = list_bench_images(IMAGE_DIR)
_embed_dim = os.environ.get("BENCH_EMBED_DIM")
_pipeline = PetEmbeddingPipeline(
    detector=build_detector(os.environ.get("BENCH_DETECTOR_WEIGHTS") or None),
    embedder=build_embedder(
        os.environ.get("BENCH_EMBED_MODEL_ID") or None,
        int(_embed_dim) if _embed_dim else None,
        os.environ.get("BENCH_EMBED_WEIGHTS") or None,
    ),
)
_pipeline.process_photos([(_images[0].stem, _images[0])])  # 워밍업


@app.get("/healthz")
def healthz() -> dict:
    return {"ok": True, "images": len(_images)}


@app.post("/analyze")
def analyze(count: int = 1) -> dict:
    """M2 요청 재현 — count 장을 샘플링해 탐지+임베딩까지 수행하고 지연을 반환."""
    if not 1 <= count <= MAX_COUNT:
        raise HTTPException(status_code=400, detail=f"count 는 1~{MAX_COUNT}")
    chosen = random.sample(_images, k=min(count, len(_images)))
    start = time.perf_counter()
    payload = _pipeline.process_photos([(p.stem, p) for p in chosen])
    elapsed = time.perf_counter() - start
    return {
        "count": count,
        "elapsed_s": round(elapsed, 3),
        "fallbacks": sum(1 for e in payload["embeddings"] if e["detection"]["fallback"]),
    }
