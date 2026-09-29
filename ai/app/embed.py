"""이미지 특징 벡터 추출 프로세스 — DINOv2 ViT-B/14 (frozen, CPU 추론).

사진(원본 또는 크롭된 PIL 이미지)을 받아 768차원 L2 정규화 float32 벡터를 만든다.
출력은 docs/data-ai-interface.md 의 AI→DATA 계약을 따른다:
(사진ID, 벡터) 목록 + model.yaml 메타데이터(model_id·model_version·dim·normalized) 동봉.

- 가중치는 torch.hub(facebookresearch/dinov2) 아키텍처에 공식 사전학습 .pth 를
  주입해 로딩한다. 경로는 model.yaml 기본값이며 설정으로 덮어쓸 수 있다.
- 여러 장 입력은 하나의 배치 텐서로 만들어 forward 를 1회만 수행한다
  (M2 요청 1~10장, NFR 평균 5초 대비 — 장당 루프 금지).
- 크롭(YOLO26l-seg 마스킹)은 app.detect 가 수행하고 app.pipeline 이 둘을 잇는다 —
  이 모듈은 파일 경로와 크롭된 PIL 이미지를 동일하게 받는다.

CLI:
    python -m app.embed 사진1.jpg 사진2.png --out embeddings.json
    (사진ID 기본값은 파일명 stem, --out 생략 시 stdout)
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Sequence

import numpy as np
import torch
import yaml
from PIL import Image, UnidentifiedImageError

DEFAULT_SPEC_PATH = Path(__file__).resolve().parent.parent / "model.yaml"

# 이보다 작은 이미지는 임베딩 품질을 보장할 수 없어 명시적으로 거부한다.
MIN_IMAGE_SIDE = 32
SUPPORTED_FORMATS = {"JPEG", "PNG", "WEBP", "BMP"}


class ImageValidationError(ValueError):
    """입력 이미지가 계약(존재·포맷·최소 크기)을 만족하지 않을 때."""


class ModelContractError(RuntimeError):
    """로딩된 모델 출력이 model.yaml 스펙(차원 등)과 어긋날 때."""


@dataclass(frozen=True)
class ModelSpec:
    model_id: str
    model_version: str
    dim: int
    input_size: int
    pooling: str
    normalized: bool
    interpolation: str
    mean: tuple[float, float, float]
    std: tuple[float, float, float]
    hub_repo: str
    weights_path: str

    @classmethod
    def load(cls, path: Path | str = DEFAULT_SPEC_PATH, weights_path: str | None = None) -> "ModelSpec":
        """model.yaml 을 읽는다. weights_path 인자로 서버 절대 경로 기본값을 덮어쓸 수 있다."""
        raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
        raw.pop("detector", None)  # 탐지 전처리 스펙은 app.detect 의 DetectorSpec 이 읽는다
        if weights_path is not None:
            raw["weights_path"] = str(weights_path)
        raw["mean"] = tuple(raw["mean"])
        raw["std"] = tuple(raw["std"])
        return cls(**raw)

    def metadata(self) -> dict:
        """AI→DATA 계약에 동봉하는 메타데이터 — 임베딩 파일이 강제해야 하는 필드 포함."""
        meta = asdict(self)
        meta.pop("weights_path")  # 서버 로컬 경로는 계약 대상이 아니다
        return meta


def load_image(path: Path | str) -> Image.Image:
    """이미지 파일을 검증하고 연다. 실패 원인을 명확한 메시지로 구분한다."""
    path = Path(path)
    if not path.is_file():
        raise ImageValidationError(f"이미지 파일이 없습니다: {path}")
    try:
        with Image.open(path) as probe:
            image_format = probe.format
            probe.verify()  # 손상 여부 검사 (verify 후에는 다시 열어야 한다)
    except UnidentifiedImageError as error:
        raise ImageValidationError(f"이미지로 해석할 수 없는 파일입니다: {path}") from error
    except OSError as error:
        raise ImageValidationError(f"손상된 이미지입니다: {path} ({error})") from error

    if image_format not in SUPPORTED_FORMATS:
        raise ImageValidationError(
            f"지원하지 않는 포맷입니다: {path} (감지: {image_format}, 지원: {sorted(SUPPORTED_FORMATS)})"
        )

    try:
        image = Image.open(path)
        image.load()  # verify() 는 헤더만 본다 — 픽셀 로딩 실패(잘린 파일 등)도 여기서 잡는다
    except (UnidentifiedImageError, OSError) as error:
        raise ImageValidationError(f"손상된 이미지입니다: {path} ({error})") from error
    validate_image(image, source=str(path))
    return image


def validate_image(image: Image.Image, source: str = "<메모리 이미지>") -> None:
    """크기 계약 검증 — 크롭된 PIL 이미지를 직접 받는 경로도 동일하게 통과해야 한다."""
    width, height = image.size
    if min(width, height) < MIN_IMAGE_SIDE:
        raise ImageValidationError(
            f"이미지가 너무 작습니다: {source} ({width}x{height}, 최소 변 {MIN_IMAGE_SIDE}px)"
        )


class DinoV2Embedder:
    """DINOv2 ViT-B/14 임베더 — 사전학습 가중치 그대로, 추론 전용(frozen)."""

    def __init__(self, spec: ModelSpec | None = None, weights_path: str | None = None, device: str = "cpu") -> None:
        self.spec = spec or ModelSpec.load(weights_path=weights_path)
        self.device = device

        weights = Path(self.spec.weights_path)
        if not weights.is_file():
            raise FileNotFoundError(
                f"가중치 파일이 없습니다: {weights}\n"
                f"  model.yaml 의 weights_path 를 확인하거나 weights_path 인자로 주입하세요."
            )

        # 아키텍처는 torch.hub 로 만들고(pretrained=False → hub 의 자체 다운로드 차단),
        # 서버에 적재된 공식 .pth 를 strict 모드로 주입한다 — 키 불일치는 즉시 실패.
        # hub 저장소가 캐시(~/.cache/torch/hub)에 있으면 네트워크 없이 로딩된다.
        try:
            self.model = torch.hub.load(self.spec.hub_repo, self.spec.model_id, pretrained=False, trust_repo=True)
        except Exception as error:  # noqa: BLE001 — 원인을 그대로 보여주는 편이 낫다
            raise RuntimeError(
                f"torch.hub 아키텍처 로딩 실패: {self.spec.hub_repo}/{self.spec.model_id}\n"
                f"  원인: {error}\n"
                f"  hub 캐시(기본 ~/.cache/torch/hub)에 저장소가 없으면 최초 1회 GitHub 접근이 필요합니다.\n"
                f"  네트워크가 막힌 환경이면 캐시 디렉터리를 미리 복사해 두세요."
            ) from error
        state_dict = torch.load(weights, map_location="cpu")
        self.model.load_state_dict(state_dict, strict=True)

        self.model.eval().to(self.device)
        for parameter in self.model.parameters():
            parameter.requires_grad_(False)

        self._mean = torch.tensor(self.spec.mean).view(3, 1, 1)
        self._std = torch.tensor(self.spec.std).view(3, 1, 1)

    def _preprocess(self, images: Sequence[Image.Image]) -> torch.Tensor:
        """전처리 스펙(model.yaml)을 그대로 구현 — 배치와 온디맨드가 공유하는 유일한 구현."""
        size = self.spec.input_size
        tensors = []
        for image in images:
            validate_image(image)
            resized = image.convert("RGB").resize((size, size), Image.BICUBIC)
            array = np.asarray(resized, dtype=np.float32) / 255.0
            tensor = torch.from_numpy(array).permute(2, 0, 1)
            tensors.append((tensor - self._mean) / self._std)
        return torch.stack(tensors)

    @torch.inference_mode()
    def embed(self, images: Sequence[Image.Image]) -> np.ndarray:
        """이미지 N장 → (N, 768) float32. 배치 텐서 하나로 forward 1회."""
        if not images:
            raise ImageValidationError("입력 이미지가 비어 있습니다")

        batch = self._preprocess(images).to(self.device)
        # DINOv2 hub 모델의 forward 는 최종 LayerNorm 을 거친 CLS 토큰을 반환한다 (pooling: cls)
        cls = self.model(batch)

        if cls.shape != (len(images), self.spec.dim):
            raise ModelContractError(
                f"모델 출력 차원이 스펙과 다릅니다: {tuple(cls.shape)} (기대: ({len(images)}, {self.spec.dim}))"
            )

        if self.spec.normalized:
            cls = torch.nn.functional.normalize(cls, dim=-1)
        return cls.cpu().float().numpy()

    def embed_photos(self, photos: Iterable[tuple[str, Path | str | Image.Image]]) -> dict:
        """(사진ID, 이미지) 목록 → AI→DATA 계약 페이로드.

        이미지 자리는 파일 경로 또는 이미 크롭된 PIL 이미지 둘 다 허용한다.
        """
        photos = list(photos)  # 제너레이터가 들어와도 아래 zip에서 소진되지 않게 고정
        images = [item if isinstance(item, Image.Image) else load_image(item) for _, item in photos]
        vectors = self.embed(images)
        return {
            "model": self.spec.metadata(),
            "embeddings": [
                {"photo_id": photo_id, "vector": vector.tolist()}
                for (photo_id, _), vector in zip(photos, vectors)
            ],
        }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="DINOv2 ViT-B/14 이미지 임베딩 (CPU)")
    parser.add_argument("images", nargs="+", help="입력 이미지 경로 (사진ID는 파일명 stem)")
    parser.add_argument("--weights", default=None, help="가중치 경로 (기본: model.yaml 의 weights_path)")
    parser.add_argument("--out", default=None, help="출력 JSON 경로 (생략 시 stdout)")
    args = parser.parse_args(argv)

    embedder = DinoV2Embedder(weights_path=args.weights)
    payload = embedder.embed_photos([(Path(p).stem, p) for p in args.images])

    text = json.dumps(payload, ensure_ascii=False)
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
        print(f"{len(payload['embeddings'])}장 임베딩 완료 → {args.out}")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
