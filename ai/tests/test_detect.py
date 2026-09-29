"""detect.py 검증 — 스펙 파싱·마스킹·크롭·다중 개체·폴백 규칙(모델 불필요)
+ (가중치가 있을 때) YOLO26l-seg 실탐지 경로.

모델이 필요한 테스트는 detector.weights_path 가 존재하는 환경(서버 2 venv)에서만 실행된다.
실이미지 테스트는 AI_SAMPLE_DIR(기본 /home/ubuntu/ai/samples)의 대표 이미지를 쓰고,
없으면 skip 된다.
"""

from __future__ import annotations

import os
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from app.detect import (
    CropResult,
    Detection,
    DetectorContractError,
    DetectorSpec,
    Yolo26SegDetector,
    apply_mask_and_crop,
    crop_from_detections,
    expand_box,
    select_detection,
)
from app.embed import MIN_IMAGE_SIDE, ModelSpec

SPEC = DetectorSpec.load()
WEIGHTS_AVAILABLE = Path(SPEC.weights_path).is_file()
try:
    import ultralytics  # noqa: F401

    ULTRALYTICS_AVAILABLE = True
except ImportError:
    ULTRALYTICS_AVAILABLE = False

needs_yolo = pytest.mark.skipif(
    not (WEIGHTS_AVAILABLE and ULTRALYTICS_AVAILABLE),
    reason="YOLO 가중치·ultralytics 가 없는 환경 (서버 2 venv에서 실행)",
)

SAMPLE_DIR = Path(os.environ.get("AI_SAMPLE_DIR", "/home/ubuntu/ai/samples"))


def make_detection(
    confidence: float = 0.9,
    box: tuple[float, float, float, float] = (40.0, 30.0, 200.0, 180.0),
    image_size: tuple[int, int] = (320, 240),
    class_id: int = 16,
) -> Detection:
    """box 내부만 True 인 사각 마스크를 가진 탐지 개체를 만든다."""
    width, height = image_size
    mask = np.zeros((height, width), dtype=bool)
    x1, y1, x2, y2 = (int(v) for v in box)
    mask[y1:y2, x1:x2] = True
    return Detection(confidence=confidence, class_id=class_id, box=box, mask=mask)


# ---------- 스펙 파싱 (모델 불필요) ----------

def test_spec_loads_rules_from_yaml() -> None:
    assert SPEC.detector_id == "yolo26l-seg"
    assert SPEC.detector_version == "v1"
    assert set(SPEC.target_classes) == {15, 16}  # COCO cat·dog
    assert 0.0 < SPEC.conf_threshold < 1.0
    assert SPEC.background_fill in {"zero", "mean"}
    assert SPEC.multi_object_policy == "highest_confidence"


def test_spec_metadata_excludes_weights_path() -> None:
    meta = SPEC.metadata()
    assert "weights_path" not in meta
    assert {"detector_id", "detector_version", "conf_threshold", "bbox_padding_ratio"} <= set(meta)


def test_spec_rejects_unknown_background_fill(tmp_path: Path) -> None:
    bad = tmp_path / "model.yaml"
    bad.write_text(
        "detector:\n"
        "  detector_id: x\n  detector_version: v1\n  weights_path: /x.pt\n"
        "  target_classes: [15]\n  conf_threshold: 0.5\n  bbox_padding_ratio: 0.1\n"
        "  background_fill: blur\n  multi_object_policy: highest_confidence\n",
        encoding="utf-8",
    )
    with pytest.raises(DetectorContractError, match="background_fill"):
        DetectorSpec.load(bad)


def test_embed_spec_still_loads_with_detector_block() -> None:
    # model.yaml 에 detector 블록이 추가돼도 임베딩 ModelSpec 파싱은 깨지지 않아야 한다
    spec = ModelSpec.load()
    assert spec.model_id == "dinov2_vitb14"
    assert spec.model_version == "v2"  # 마스킹·크롭 전처리 추가로 상향 (data-ai-interface §4)


# ---------- 마스킹·크롭 산술 (모델 불필요) ----------

def test_expand_box_pads_and_clamps() -> None:
    box = (10.0, 10.0, 110.0, 60.0)  # w=100, h=50
    x1, y1, x2, y2 = expand_box(box, 0.1, (120, 65))
    assert (x1, y1) == (0, 5)  # 10 - 10 = 0, 10 - 5 = 5
    assert (x2, y2) == (120, 65)  # 클램프


def test_expand_box_zero_padding_is_identity() -> None:
    assert expand_box((10.0, 20.0, 30.0, 40.0), 0.0, (100, 100)) == (10, 20, 30, 40)


def test_apply_mask_zero_fill_blacks_out_background() -> None:
    image = Image.new("RGB", (320, 240), (200, 100, 50))
    detection = make_detection()
    cropped, box = apply_mask_and_crop(image, detection, SPEC)
    array = np.asarray(cropped)
    x1, y1, x2, y2 = box
    assert cropped.size == (x2 - x1, y2 - y1)
    # 마스크 내부는 원색 유지, 패딩으로 들어온 마스크 밖 픽셀은 검정
    assert (array == (200, 100, 50)).all(axis=-1).any()
    assert (array == 0).all(axis=-1).any()


def test_apply_mask_mean_fill_uses_image_mean() -> None:
    from dataclasses import replace

    image = Image.new("RGB", (320, 240), (100, 100, 100))
    spec = replace(SPEC, background_fill="mean")
    cropped, _ = apply_mask_and_crop(image, make_detection(), spec)
    array = np.asarray(cropped)
    # 단색 이미지의 평균색 = 원색이므로 배경도 (100,100,100)
    assert (array == 100).all()


def test_mask_resolution_mismatch_rejected() -> None:
    image = Image.new("RGB", (320, 240))
    wrong = Detection(confidence=0.9, class_id=16, box=(0, 0, 50, 50), mask=np.ones((10, 10), dtype=bool))
    with pytest.raises(DetectorContractError, match="마스크 해상도"):
        apply_mask_and_crop(image, wrong, SPEC)


# ---------- 3경로: 정상 탐지 / 다중 개체 / 폴백 (모델 불필요) ----------

def test_single_detection_crops_without_fallback() -> None:
    image = Image.new("RGB", (320, 240), (200, 100, 50))
    result = crop_from_detections(image, [make_detection(confidence=0.87)], SPEC)
    assert result.fallback is False
    assert result.confidence == 0.87
    assert result.detection_count == 1
    assert result.image.size < image.size


def test_multiple_detections_pick_highest_confidence() -> None:
    low = make_detection(confidence=0.6, box=(10.0, 10.0, 100.0, 100.0))
    high = make_detection(confidence=0.95, box=(150.0, 100.0, 300.0, 220.0))
    result = crop_from_detections(Image.new("RGB", (320, 240)), [low, high], SPEC)
    assert result.fallback is False
    assert result.confidence == 0.95
    assert result.detection_count == 2
    # 크롭 영역이 최고 신뢰도 개체의 bbox(패딩 포함)에서 나왔는지 확인
    assert result.box == expand_box(high.box, SPEC.bbox_padding_ratio, (320, 240))


def test_no_detection_falls_back_to_original() -> None:
    image = Image.new("RGB", (320, 240), (10, 20, 30))
    result = crop_from_detections(image, [], SPEC)
    assert result.fallback is True
    assert result.confidence is None
    assert result.detection_count == 0
    assert result.image.size == (320, 240)


def test_tiny_crop_falls_back_to_original() -> None:
    # 임베딩 최소 변(32px) 미만 크롭은 원본 폴백해야 한다
    tiny = make_detection(confidence=0.9, box=(50.0, 50.0, 50.0 + MIN_IMAGE_SIDE - 2, 60.0))
    from dataclasses import replace

    spec = replace(SPEC, bbox_padding_ratio=0.0)
    result = crop_from_detections(Image.new("RGB", (320, 240)), [tiny], spec)
    assert result.fallback is True
    assert result.confidence == 0.9
    assert result.detection_count == 1
    assert result.image.size == (320, 240)


def test_select_detection_rejects_unknown_policy() -> None:
    with pytest.raises(DetectorContractError, match="multi_object_policy"):
        select_detection([make_detection()], "largest_area")


def test_crop_result_summary_serializable() -> None:
    import json

    result = crop_from_detections(Image.new("RGB", (320, 240)), [make_detection()], SPEC)
    assert json.dumps(result.summary())  # 계약 페이로드에 그대로 들어간다


# ---------- 실모델 경로 (가중치 필요, 서버 2) ----------

@pytest.fixture(scope="module")
def detector() -> Yolo26SegDetector:
    return Yolo26SegDetector()


def sample(name: str) -> Path:
    path = SAMPLE_DIR / name
    if not path.is_file():
        pytest.skip(f"대표 이미지가 없습니다: {path}")
    return path


@needs_yolo
def test_real_dog_image_detected(detector: Yolo26SegDetector) -> None:
    result = detector.detect_and_crop(Image.open(sample("dog.jpg")))
    assert result.fallback is False
    assert result.confidence >= SPEC.conf_threshold
    assert min(result.image.size) >= MIN_IMAGE_SIDE


@needs_yolo
def test_real_multi_cat_image_picks_one(detector: Yolo26SegDetector) -> None:
    result = detector.detect_and_crop(Image.open(sample("cats.jpg")))
    assert result.fallback is False
    assert result.detection_count >= 2  # 다중 개체 이미지
    assert result.confidence >= SPEC.conf_threshold


@needs_yolo
def test_real_no_animal_image_falls_back(detector: Yolo26SegDetector) -> None:
    result = detector.detect_and_crop(Image.open(sample("none.jpg")))
    assert result.fallback is True
    assert result.detection_count == 0


@needs_yolo
def test_real_detection_reproducible(detector: Yolo26SegDetector) -> None:
    image = Image.open(sample("dog.jpg"))
    first = detector.detect_and_crop(image)
    second = detector.detect_and_crop(image)
    assert first.box == second.box
    assert first.confidence == second.confidence
    assert np.array_equal(np.asarray(first.image), np.asarray(second.image))
