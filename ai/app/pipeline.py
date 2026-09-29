"""원본 사진 → 탐지·마스킹·크롭(YOLO26l-seg) → 임베딩(DINOv2) end-to-end 파이프라인.

배치·온디맨드가 공유하는 유일한 전처리+임베딩 경로다. 출력은 app.embed 의
AI→DATA 계약 페이로드에 탐지 전처리 정보를 동봉한 형태:

    {
      "model": { ...임베딩 메타데이터, "detector": {detector_id, detector_version, 규칙 값...} },
      "embeddings": [
        {"photo_id": ..., "vector": [...], "detection": {fallback, confidence, box, detection_count}},
      ]
    }

- 탐지·크롭은 장당 수행하지만 임베딩 forward 는 배치 텐서 1회다 (M2 1~10장, NFR 대비).
- 탐지 실패 폴백(원본 전체 사용) 시에도 벡터는 생성되며 detection.fallback 으로 구분한다.

CLI:
    python -m app.pipeline 사진1.jpg 사진2.png --out embeddings.json
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Iterable, Sequence

from PIL import Image

from app.detect import Yolo26SegDetector
from app.embed import DinoV2Embedder, load_image


class PetEmbeddingPipeline:
    """탐지기와 임베더를 묶은 end-to-end 파이프라인 — 둘 다 주입 가능(테스트·서빙 재사용)."""

    def __init__(
        self,
        detector: Yolo26SegDetector | None = None,
        embedder: DinoV2Embedder | None = None,
        detector_weights: str | None = None,
        embed_weights: str | None = None,
        device: str = "cpu",
    ) -> None:
        self.detector = detector or Yolo26SegDetector(weights_path=detector_weights, device=device)
        self.embedder = embedder or DinoV2Embedder(weights_path=embed_weights, device=device)

    def process_photos(self, photos: Iterable[tuple[str, Path | str | Image.Image]]) -> dict:
        """(사진ID, 이미지) 목록 → 탐지·마스킹·크롭 → 배치 임베딩 → 계약 페이로드."""
        photos = list(photos)
        crops = []
        for _, item in photos:
            image = item if isinstance(item, Image.Image) else load_image(item)
            crops.append(self.detector.detect_and_crop(image))

        payload = self.embedder.embed_photos(
            [(photo_id, crop.image) for (photo_id, _), crop in zip(photos, crops)]
        )
        payload["model"]["detector"] = self.detector.spec.metadata()
        for entry, crop in zip(payload["embeddings"], crops):
            entry["detection"] = crop.summary()
        return payload


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="탐지·마스킹·크롭 + 임베딩 end-to-end (CPU)")
    parser.add_argument("images", nargs="+", help="입력 이미지 경로 (사진ID는 파일명 stem)")
    parser.add_argument("--detector-weights", default=None, help="YOLO 가중치 경로 (기본: model.yaml)")
    parser.add_argument("--embed-weights", default=None, help="DINOv2 가중치 경로 (기본: model.yaml)")
    parser.add_argument("--out", default=None, help="출력 JSON 경로 (생략 시 stdout)")
    args = parser.parse_args(argv)

    pipeline = PetEmbeddingPipeline(detector_weights=args.detector_weights, embed_weights=args.embed_weights)
    payload = pipeline.process_photos([(Path(p).stem, p) for p in args.images])

    text = json.dumps(payload, ensure_ascii=False)
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
        fallbacks = sum(1 for e in payload["embeddings"] if e["detection"]["fallback"])
        print(f"{len(payload['embeddings'])}장 처리 완료 (폴백 {fallbacks}장) → {args.out}")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
