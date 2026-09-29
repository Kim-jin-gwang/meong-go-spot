"""동물 탐지·마스킹·크롭 전처리 프로세스 — YOLO26l-seg (CPU 추론).

입력 이미지에서 개·고양이 개체를 탐지하고, 세그멘테이션 마스크로 배경을 제거한 뒤
개체 bbox 기준으로 크롭해 DINOv2 임베딩(app.embed) 입력을 만든다.
마스킹·크롭 규칙(신뢰도 임계값·bbox 패딩·배경 채움·다중 개체 처리)은 코드 상수가 아니라
model.yaml 의 detector 블록에서 읽는다 — 이 값을 바꾸는 것도 model_version 상향 대상이다
(docs/data-ai-interface.md §4).

- 탐지 실패(개체 없음·저신뢰)나 크롭이 임베딩 최소 크기 미만이면 원본 전체를 사용하고
  결과에 fallback: true 를 남긴다 — 파이프라인이 끊기지 않되 품질 저하를 추적할 수 있다.
- 마스크는 retina_masks=True 로 원본 해상도에서 받는다. 크롭·마스킹 산술은 순수 함수로
  분리해 가중치 없는 환경에서도 테스트한다.

CLI:
    python -m app.detect 사진1.jpg 사진2.png --out-dir crops/
    (크롭 결과를 PNG로 저장하고 탐지 요약 JSON을 stdout에 출력)
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Sequence

import numpy as np
import yaml
from PIL import Image

from app.embed import MIN_IMAGE_SIDE, load_image

DEFAULT_SPEC_PATH = Path(__file__).resolve().parent.parent / "model.yaml"

BACKGROUND_FILLS = {"zero", "mean"}
MULTI_OBJECT_POLICIES = {"highest_confidence"}


class DetectorContractError(RuntimeError):
    """detector 스펙(model.yaml)이나 탐지 결과가 계약과 어긋날 때."""


@dataclass(frozen=True)
class DetectorSpec:
    detector_id: str
    detector_version: str
    weights_path: str
    target_classes: tuple[int, ...]
    conf_threshold: float
    bbox_padding_ratio: float
    background_fill: str
    multi_object_policy: str

    @classmethod
    def load(cls, path: Path | str = DEFAULT_SPEC_PATH, weights_path: str | None = None) -> "DetectorSpec":
        """model.yaml 의 detector 블록을 읽는다. weights_path 인자로 기본 경로를 덮어쓸 수 있다."""
        raw = yaml.safe_load(Path(path).read_text(encoding="utf-8")).get("detector")
        if raw is None:
            raise DetectorContractError(f"model.yaml 에 detector 블록이 없습니다: {path}")
        if weights_path is not None:
            raw["weights_path"] = str(weights_path)
        raw["target_classes"] = tuple(raw["target_classes"])
        spec = cls(**raw)
        if spec.background_fill not in BACKGROUND_FILLS:
            raise DetectorContractError(
                f"지원하지 않는 background_fill: {spec.background_fill} (지원: {sorted(BACKGROUND_FILLS)})"
            )
        if spec.multi_object_policy not in MULTI_OBJECT_POLICIES:
            raise DetectorContractError(
                f"지원하지 않는 multi_object_policy: {spec.multi_object_policy} (지원: {sorted(MULTI_OBJECT_POLICIES)})"
            )
        if not 0.0 < spec.conf_threshold < 1.0:
            raise DetectorContractError(f"conf_threshold 는 (0, 1) 범위여야 합니다: {spec.conf_threshold}")
        if spec.bbox_padding_ratio < 0.0:
            raise DetectorContractError(f"bbox_padding_ratio 는 음수일 수 없습니다: {spec.bbox_padding_ratio}")
        return spec

    def metadata(self) -> dict:
        """AI→DATA 계약에 동봉하는 전처리 메타데이터 — 규칙 값 전체를 포함한다."""
        meta = asdict(self)
        meta.pop("weights_path")  # 서버 로컬 경로는 계약 대상이 아니다
        meta["target_classes"] = list(meta["target_classes"])
        return meta


@dataclass(frozen=True)
class Detection:
    """탐지 개체 하나 — 좌표·마스크는 모두 원본 이미지 해상도 기준."""

    confidence: float
    class_id: int
    box: tuple[float, float, float, float]  # xyxy
    mask: np.ndarray  # (H, W) bool


@dataclass(frozen=True)
class CropResult:
    """탐지·마스킹·크롭 산출물 — fallback 이면 image 는 원본 전체(RGB)다."""

    image: Image.Image
    fallback: bool
    confidence: float | None = None
    box: tuple[int, int, int, int] | None = None  # 패딩 적용 후 실제 크롭 영역 (xyxy)
    detection_count: int = 0

    def summary(self) -> dict:
        """계약 페이로드·CLI 요약에 들어가는 직렬화 가능한 부분."""
        return {
            "fallback": self.fallback,
            "confidence": self.confidence,
            "box": list(self.box) if self.box else None,
            "detection_count": self.detection_count,
        }


def select_detection(detections: Sequence[Detection], policy: str) -> Detection:
    """다중 개체 처리 정책 적용 — highest_confidence: 최고 신뢰도 1개."""
    if policy not in MULTI_OBJECT_POLICIES:
        raise DetectorContractError(f"지원하지 않는 multi_object_policy: {policy}")
    return max(detections, key=lambda detection: detection.confidence)


def expand_box(
    box: tuple[float, float, float, float], padding_ratio: float, image_size: tuple[int, int]
) -> tuple[int, int, int, int]:
    """bbox를 각 변 길이의 padding_ratio 만큼 사방으로 확장하고 이미지 경계로 클램프한다."""
    x1, y1, x2, y2 = box
    pad_x = (x2 - x1) * padding_ratio
    pad_y = (y2 - y1) * padding_ratio
    width, height = image_size
    return (
        max(0, int(round(x1 - pad_x))),
        max(0, int(round(y1 - pad_y))),
        min(width, int(round(x2 + pad_x))),
        min(height, int(round(y2 + pad_y))),
    )


def apply_mask_and_crop(image: Image.Image, detection: Detection, spec: DetectorSpec) -> tuple[Image.Image, tuple[int, int, int, int]]:
    """마스크 밖을 배경 채움 값으로 지우고, 패딩 확장된 bbox로 크롭한다."""
    rgb = image.convert("RGB")
    array = np.asarray(rgb, dtype=np.uint8)
    if detection.mask.shape != array.shape[:2]:
        raise DetectorContractError(
            f"마스크 해상도가 원본과 다릅니다: {detection.mask.shape} (원본: {array.shape[:2]})"
        )
    if spec.background_fill == "mean":
        fill = array.reshape(-1, 3).mean(axis=0).round().astype(np.uint8)
    else:  # zero
        fill = np.zeros(3, dtype=np.uint8)
    masked = np.where(detection.mask[..., None], array, fill)
    x1, y1, x2, y2 = expand_box(detection.box, spec.bbox_padding_ratio, rgb.size)
    return Image.fromarray(masked[y1:y2, x1:x2]), (x1, y1, x2, y2)


def crop_from_detections(image: Image.Image, detections: Sequence[Detection], spec: DetectorSpec) -> CropResult:
    """탐지 목록 → CropResult. 탐지 없음·크롭이 임베딩 최소 크기 미만이면 원본 폴백."""
    rgb = image.convert("RGB")
    if not detections:
        return CropResult(image=rgb, fallback=True, detection_count=0)

    best = select_detection(detections, spec.multi_object_policy)
    cropped, box = apply_mask_and_crop(rgb, best, spec)
    if min(cropped.size) < MIN_IMAGE_SIDE:
        # 임베딩 입력 계약(최소 변 32px)을 못 채우는 크롭은 품질 보장이 안 된다 — 원본 폴백
        return CropResult(image=rgb, fallback=True, confidence=best.confidence, detection_count=len(detections))
    return CropResult(
        image=cropped,
        fallback=False,
        confidence=best.confidence,
        box=box,
        detection_count=len(detections),
    )


class Yolo26SegDetector:
    """YOLO26l-seg 탐지기 — 사전학습 가중치 그대로, 추론 전용."""

    def __init__(self, spec: DetectorSpec | None = None, weights_path: str | None = None, device: str = "cpu") -> None:
        self.spec = spec or DetectorSpec.load(weights_path=weights_path)
        self.device = device

        weights = Path(self.spec.weights_path)
        if not weights.is_file():
            raise FileNotFoundError(
                f"YOLO 가중치 파일이 없습니다: {weights}\n"
                f"  model.yaml 의 detector.weights_path 를 확인하거나 weights_path 인자로 주입하세요."
            )

        try:
            from ultralytics import YOLO
        except ImportError as error:
            raise RuntimeError(
                "ultralytics 가 설치되어 있지 않습니다 — ai/requirements.txt 로 설치하세요."
            ) from error
        self.model = YOLO(str(weights), task="segment")

    def detect(self, image: Image.Image) -> list[Detection]:
        """개·고양이 개체 탐지 + 원본 해상도 세그멘테이션 마스크 획득."""
        rgb = image.convert("RGB")
        results = self.model.predict(
            source=rgb,
            conf=self.spec.conf_threshold,
            classes=list(self.spec.target_classes),
            device=self.device,
            retina_masks=True,  # 마스크를 원본 해상도로 받는다 — 크롭 좌표계와 일치
            verbose=False,
        )
        result = results[0]
        if result.masks is None or len(result.boxes) == 0:
            return []

        boxes = result.boxes.xyxy.cpu().numpy()
        confidences = result.boxes.conf.cpu().numpy()
        class_ids = result.boxes.cls.cpu().numpy().astype(int)
        masks = result.masks.data.cpu().numpy() > 0.5
        return [
            Detection(confidence=float(conf), class_id=int(cls), box=tuple(box.tolist()), mask=mask)
            for box, conf, cls, mask in zip(boxes, confidences, class_ids, masks)
        ]

    def detect_and_crop(self, image: Image.Image) -> CropResult:
        """탐지 → 마스킹 → 크롭 한 번에. 임베딩 앞단이 호출하는 유일한 진입점."""
        return crop_from_detections(image, self.detect(image), self.spec)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="YOLO26l-seg 동물 탐지·마스킹·크롭 (CPU)")
    parser.add_argument("images", nargs="+", help="입력 이미지 경로")
    parser.add_argument("--weights", default=None, help="YOLO 가중치 경로 (기본: model.yaml)")
    parser.add_argument("--out-dir", default=None, help="크롭 결과 PNG 저장 디렉터리 (생략 시 저장 안 함)")
    args = parser.parse_args(argv)

    detector = Yolo26SegDetector(weights_path=args.weights)
    out_dir = Path(args.out_dir) if args.out_dir else None
    if out_dir:
        out_dir.mkdir(parents=True, exist_ok=True)

    summaries = []
    for path in args.images:
        result = detector.detect_and_crop(load_image(path))
        summary = {"image": path, **result.summary()}
        if out_dir:
            crop_path = out_dir / f"{Path(path).stem}_crop.png"
            result.image.save(crop_path)
            summary["crop_path"] = str(crop_path)
        summaries.append(summary)

    print(json.dumps({"detector": detector.spec.metadata(), "results": summaries}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
