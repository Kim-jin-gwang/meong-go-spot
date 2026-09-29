"""pipeline.py 검증 — 원본 → 탐지·마스킹·크롭 → 임베딩 end-to-end.

배선(페이로드 병합·폴백 전파)은 페이크 탐지기·임베더로 모델 없이 검증하고,
실모델 end-to-end 는 두 가중치가 모두 있는 서버 2 venv 에서만 실행된다.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from app.detect import CropResult, Detection, DetectorSpec, crop_from_detections
from app.embed import ModelSpec
from app.pipeline import PetEmbeddingPipeline
from tests.test_detect import SAMPLE_DIR, ULTRALYTICS_AVAILABLE, make_detection

DETECTOR_SPEC = DetectorSpec.load()
EMBED_SPEC = ModelSpec.load()
BOTH_WEIGHTS_AVAILABLE = (
    Path(DETECTOR_SPEC.weights_path).is_file()
    and Path(EMBED_SPEC.weights_path).is_file()
    and ULTRALYTICS_AVAILABLE
)
needs_models = pytest.mark.skipif(
    not BOTH_WEIGHTS_AVAILABLE, reason="YOLO·DINOv2 가중치가 모두 있는 환경(서버 2 venv)에서 실행"
)


class FakeDetector:
    """이미지 크기별로 미리 정한 탐지 결과를 돌려주는 페이크 — 3경로 배선 검증용."""

    def __init__(self, detections_by_size: dict[tuple[int, int], list[Detection]]) -> None:
        self.spec = DETECTOR_SPEC
        self._by_size = detections_by_size

    def detect_and_crop(self, image: Image.Image) -> CropResult:
        return crop_from_detections(image, self._by_size.get(image.size, []), self.spec)


class FakeEmbedder:
    """결정적 벡터를 돌려주는 페이크 — 계약 페이로드 형태만 embed.py 와 동일하게 유지."""

    def __init__(self) -> None:
        self.spec = EMBED_SPEC
        self.received_sizes: list[tuple[int, int]] = []

    def embed_photos(self, photos) -> dict:
        photos = list(photos)
        self.received_sizes = [image.size for _, image in photos]
        return {
            "model": self.spec.metadata(),
            "embeddings": [
                {"photo_id": photo_id, "vector": [0.0] * self.spec.dim} for photo_id, _ in photos
            ],
        }


@pytest.fixture()
def fake_pipeline() -> tuple[PetEmbeddingPipeline, FakeEmbedder]:
    # (100,100): 정상 탐지 1개 / (200,100): 다중 개체 / (300,300): 탐지 없음 → 폴백
    detector = FakeDetector(
        {
            (100, 100): [make_detection(confidence=0.9, box=(10.0, 10.0, 90.0, 90.0), image_size=(100, 100))],
            (200, 100): [
                make_detection(confidence=0.7, box=(5.0, 5.0, 80.0, 80.0), image_size=(200, 100)),
                make_detection(confidence=0.95, box=(100.0, 10.0, 190.0, 95.0), image_size=(200, 100)),
            ],
        }
    )
    embedder = FakeEmbedder()
    return PetEmbeddingPipeline(detector=detector, embedder=embedder), embedder


def test_end_to_end_three_paths(fake_pipeline) -> None:
    pipeline, embedder = fake_pipeline
    photos = [
        ("normal", Image.new("RGB", (100, 100), (50, 50, 50))),
        ("multi", Image.new("RGB", (200, 100), (50, 50, 50))),
        ("none", Image.new("RGB", (300, 300), (50, 50, 50))),
    ]
    payload = pipeline.process_photos(photos)

    by_id = {entry["photo_id"]: entry for entry in payload["embeddings"]}
    assert set(by_id) == {"normal", "multi", "none"}

    assert by_id["normal"]["detection"]["fallback"] is False
    assert by_id["normal"]["detection"]["detection_count"] == 1

    assert by_id["multi"]["detection"]["fallback"] is False
    assert by_id["multi"]["detection"]["detection_count"] == 2
    assert by_id["multi"]["detection"]["confidence"] == 0.95  # 최고 신뢰도 1개 정책

    assert by_id["none"]["detection"]["fallback"] is True
    assert by_id["none"]["detection"]["detection_count"] == 0

    # 폴백은 원본 전체, 정상 탐지는 크롭된 이미지가 임베더에 들어가야 한다
    assert embedder.received_sizes[2] == (300, 300)
    assert embedder.received_sizes[0] < (100, 100)


def test_payload_contains_detector_metadata(fake_pipeline) -> None:
    pipeline, _ = fake_pipeline
    payload = pipeline.process_photos([("p", Image.new("RGB", (300, 300)))])
    detector_meta = payload["model"]["detector"]
    assert detector_meta["detector_id"] == "yolo26l-seg"
    assert detector_meta["detector_version"] == "v1"
    assert "weights_path" not in detector_meta
    # 임베딩 메타데이터(기존 계약)도 그대로 유지
    assert {"model_id", "model_version", "dim", "normalized"} <= set(payload["model"])


def test_generator_input_accepted(fake_pipeline) -> None:
    pipeline, _ = fake_pipeline
    payload = pipeline.process_photos((f"id_{i}", Image.new("RGB", (300, 300))) for i in range(2))
    assert [e["photo_id"] for e in payload["embeddings"]] == ["id_0", "id_1"]


# ---------- 실모델 end-to-end (서버 2) ----------

def sample(name: str) -> Path:
    path = SAMPLE_DIR / name
    if not path.is_file():
        pytest.skip(f"대표 이미지가 없습니다: {path}")
    return path


@pytest.fixture(scope="module")
def real_pipeline() -> PetEmbeddingPipeline:
    return PetEmbeddingPipeline()


@needs_models
def test_real_end_to_end_three_paths(real_pipeline: PetEmbeddingPipeline) -> None:
    payload = real_pipeline.process_photos(
        [("dog", sample("dog.jpg")), ("cats", sample("cats.jpg")), ("none", sample("none.jpg"))]
    )
    by_id = {entry["photo_id"]: entry for entry in payload["embeddings"]}

    assert by_id["dog"]["detection"]["fallback"] is False
    assert by_id["cats"]["detection"]["fallback"] is False
    assert by_id["cats"]["detection"]["detection_count"] >= 2
    assert by_id["none"]["detection"]["fallback"] is True

    for entry in payload["embeddings"]:
        vector = np.asarray(entry["vector"], dtype=np.float32)
        assert vector.shape == (EMBED_SPEC.dim,)
        assert np.isclose(np.linalg.norm(vector), 1.0, atol=1e-5)  # normalized 계약 유지


@needs_models
def test_real_end_to_end_reproducible(real_pipeline: PetEmbeddingPipeline) -> None:
    first = real_pipeline.process_photos([("dog", sample("dog.jpg"))])
    second = real_pipeline.process_photos([("dog", sample("dog.jpg"))])
    assert first["embeddings"][0]["vector"] == second["embeddings"][0]["vector"]
    assert first["embeddings"][0]["detection"] == second["embeddings"][0]["detection"]


@needs_models
def test_real_masking_changes_embedding(real_pipeline: PetEmbeddingPipeline) -> None:
    # 마스킹·크롭 유무가 임베딩에 실제로 반영되는지 — 원본 전체 임베딩과 달라야 한다
    image = Image.open(sample("dog.jpg"))
    cropped_vec = np.asarray(real_pipeline.process_photos([("d", image)])["embeddings"][0]["vector"])
    original_vec = real_pipeline.embedder.embed([image.convert("RGB")])[0]
    cosine = float(np.dot(cropped_vec, original_vec))
    assert cosine < 0.9999  # 동일 벡터가 아니어야 마스킹이 입력에 반영된 것
