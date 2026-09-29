"""embed.py 검증 — 입력 검증 오류 경로 + (가중치가 있을 때) 재현성·차원·정규화 계약.

모델이 필요한 테스트는 model.yaml 의 weights_path 가 존재하는 환경(서버 1 venv)에서만
실행되고, 없는 환경에서는 skip 된다.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from app.embed import (
    MIN_IMAGE_SIDE,
    DinoV2Embedder,
    ImageValidationError,
    ModelSpec,
    load_image,
)

SPEC = ModelSpec.load()
WEIGHTS_AVAILABLE = Path(SPEC.weights_path).is_file()
needs_model = pytest.mark.skipif(not WEIGHTS_AVAILABLE, reason="가중치 파일이 없는 환경 (서버 1 venv에서 실행)")


def make_image(path: Path, size: tuple[int, int] = (320, 240), color=(120, 80, 40)) -> Path:
    Image.new("RGB", size, color).save(path)
    return path


# ---------- 입력 검증 (모델 불필요) ----------

def test_missing_file(tmp_path: Path) -> None:
    with pytest.raises(ImageValidationError, match="파일이 없습니다"):
        load_image(tmp_path / "없는파일.jpg")


def test_non_image_file(tmp_path: Path) -> None:
    bogus = tmp_path / "bogus.jpg"
    bogus.write_text("이건 이미지가 아니다", encoding="utf-8")
    with pytest.raises(ImageValidationError, match="해석할 수 없는"):
        load_image(bogus)


def test_unsupported_format(tmp_path: Path) -> None:
    gif = tmp_path / "anim.gif"
    Image.new("RGB", (100, 100)).save(gif, format="GIF")
    with pytest.raises(ImageValidationError, match="지원하지 않는 포맷"):
        load_image(gif)


def test_too_small_image(tmp_path: Path) -> None:
    tiny = make_image(tmp_path / "tiny.png", size=(MIN_IMAGE_SIDE - 1, 100))
    with pytest.raises(ImageValidationError, match="너무 작습니다"):
        load_image(tiny)


def test_valid_image_loads(tmp_path: Path) -> None:
    image = load_image(make_image(tmp_path / "ok.png"))
    assert image.size == (320, 240)


# ---------- 모델 계약 (가중치 필요) ----------

@pytest.fixture(scope="module")
def embedder() -> DinoV2Embedder:
    return DinoV2Embedder()


@pytest.fixture()
def sample_images(tmp_path: Path) -> list[Image.Image]:
    colors = [(200, 30, 30), (30, 200, 30), (30, 30, 200)]
    return [Image.open(make_image(tmp_path / f"s{i}.png", color=c)) for i, c in enumerate(colors)]


@needs_model
def test_dim_dtype_and_norm(embedder: DinoV2Embedder, sample_images) -> None:
    vectors = embedder.embed(sample_images)
    assert vectors.shape == (3, SPEC.dim)
    assert vectors.dtype == np.float32
    norms = np.linalg.norm(vectors, axis=1)
    assert np.allclose(norms, 1.0, atol=1e-5)  # normalized: true 계약


@needs_model
def test_reproducibility(embedder: DinoV2Embedder, sample_images) -> None:
    first = embedder.embed(sample_images)
    second = embedder.embed(sample_images)
    assert np.array_equal(first, second)  # 동일 입력 → 동일 벡터 (CPU 결정적)


@needs_model
def test_batch_matches_single(embedder: DinoV2Embedder, sample_images) -> None:
    batched = embedder.embed(sample_images)
    singles = np.concatenate([embedder.embed([img]) for img in sample_images])
    assert np.allclose(batched, singles, atol=1e-5)


@needs_model
def test_cropped_pil_input_accepted(embedder: DinoV2Embedder, sample_images) -> None:
    # 크롭(#56) 결과를 흉내 낸 PIL 이미지를 경로 없이 직접 넘긴다
    crop = sample_images[0].crop((10, 10, 200, 180))
    payload = embedder.embed_photos([("photo-1", crop)])
    assert payload["model"]["model_id"] == SPEC.model_id
    assert payload["model"]["dim"] == SPEC.dim
    assert payload["model"]["normalized"] is True
    assert len(payload["embeddings"][0]["vector"]) == SPEC.dim


@needs_model
def test_contract_payload_shape(embedder: DinoV2Embedder, tmp_path: Path) -> None:
    photo = make_image(tmp_path / "p1.png")
    payload = embedder.embed_photos([("p1", photo)])
    assert set(payload) == {"model", "embeddings"}
    assert {"model_id", "model_version", "dim", "normalized"} <= set(payload["model"])
    assert payload["embeddings"][0]["photo_id"] == "p1"


@needs_model
def test_color_modes_converted(embedder: DinoV2Embedder) -> None:
    # RGBA·그레이스케일 입력도 convert("RGB") 를 거쳐 정상 추론돼야 한다 (MR !60 AI 리뷰 반영)
    rgba = Image.new("RGBA", (100, 100), (200, 30, 30, 128))
    gray = Image.new("L", (100, 100), 128)
    vectors = embedder.embed([rgba, gray])
    assert vectors.shape == (2, SPEC.dim)
    assert np.allclose(np.linalg.norm(vectors, axis=1), 1.0, atol=1e-5)


def test_truncated_image_rejected(tmp_path: Path) -> None:
    # verify() 를 통과할 수 있는 잘린 파일도 load() 단계에서 ImageValidationError 로 감싸져야 한다
    source = make_image(tmp_path / "full.png", size=(300, 300))
    data = source.read_bytes()
    truncated = tmp_path / "truncated.png"
    truncated.write_bytes(data[: len(data) // 2])
    with pytest.raises(ImageValidationError):
        load_image(truncated)


@needs_model
def test_generator_input_accepted(embedder: DinoV2Embedder, sample_images) -> None:
    # Sequence 대신 제너레이터를 넘겨도 결과가 비지 않아야 한다 (MR !60 AI 리뷰 반영)
    payload = embedder.embed_photos((f"id_{i}", img) for i, img in enumerate(sample_images))
    assert [e["photo_id"] for e in payload["embeddings"]] == ["id_0", "id_1", "id_2"]


@needs_model
def test_empty_input_rejected(embedder: DinoV2Embedder) -> None:
    with pytest.raises(ImageValidationError, match="비어 있습니다"):
        embedder.embed([])
