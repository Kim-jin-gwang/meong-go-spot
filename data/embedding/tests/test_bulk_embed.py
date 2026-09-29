import argparse
import io
import json
import sys
import tarfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import bulk_embed  # noqa: E402

MODEL_META = {"model_id": "dinov2_vitb14", "model_version": "v2", "dim": 3, "normalized": True,
              "detector": {"detector_id": "yolo26l-seg", "detector_version": "v1"}}


class FakePipeline:
    """탐지·임베딩 없이 계약 페이로드 형태만 흉내 낸다 — 사진ID를 벡터에 새겨 순서 대응을 검증할 수 있게."""

    def __init__(self, meta: dict = MODEL_META) -> None:
        self.meta = meta
        self.calls: list[list[str]] = []

    def process_photos(self, photos):
        photos = list(photos)
        self.calls.append([pid for pid, _ in photos])
        return {
            "model": self.meta,
            "embeddings": [
                {"photo_id": pid, "vector": [float(len(pid)), 0.5, -0.25],
                 "detection": {"fallback": pid.endswith("_2"), "confidence": 0.9, "box": [0, 0, 1, 1], "detection_count": 1}}
                for pid, _ in photos
            ],
        }


def make_tar(path: Path, entries: dict[str, bytes]) -> Path:
    with tarfile.open(path, "w") as tar:
        for name, data in entries.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    return path


def open_ok_or_raise(data: bytes):
    if data == b"broken":
        raise ValueError("cannot identify image file")
    return data  # 모의 파이프라인은 이미지 객체를 쓰지 않는다


def _args(tmp_path: Path, **overrides) -> argparse.Namespace:
    base = dict(tars=str(tmp_path / "tars"), out=str(tmp_path / "out"), state=str(tmp_path / "s.json"),
                metrics=str(tmp_path / "m.json"), batch=2)
    base.update(overrides)
    return argparse.Namespace(**base)


def test_run_writes_tsv_detections_meta_and_resumes(tmp_path: Path, capsys) -> None:
    tars = tmp_path / "tars" / "yyyymm=202401"
    tars.mkdir(parents=True)
    make_tar(tars / "images-202401-0000.tar", {"A1_1.jpg": b"img", "A1_2.png": b"img", "B7_1.jpg": b"broken",
                                               "README.txt": b"skip", "sub/C1_1.jpg": b"img", "./D1_1.jpg": b"img"})
    pipeline = FakePipeline()
    assert bulk_embed.run(_args(tmp_path), pipeline, open_ok_or_raise) == 0

    out = tmp_path / "out"
    rows = (out / "vectors-images-202401-0000.tsv").read_text(encoding="utf-8").splitlines()
    assert rows == ["A1_1\t4.000000,0.500000,-0.250000", "A1_2\t4.000000,0.500000,-0.250000",
                    "D1_1\t4.000000,0.500000,-0.250000"], "탭 구분 id + 콤마 벡터 (MapReduce 입력 형식); ./ 접두 엔트리는 정상 사진"
    dets = [json.loads(l) for l in (out / "detections-images-202401-0000.jsonl").read_text(encoding="utf-8").splitlines()]
    assert {d["photo_id"] for d in dets} == {"A1_1", "A1_2", "B7_1", "D1_1"}
    assert next(d for d in dets if d["photo_id"] == "B7_1")["error"].startswith("ValueError")
    assert next(d for d in dets if d["photo_id"] == "A1_2")["fallback"] is True
    assert json.loads((out / "_meta.json").read_text(encoding="utf-8"))["model_version"] == "v2"
    assert pipeline.calls == [["A1_1", "A1_2"], ["D1_1"]], "배치 2 — 깨진 파일은 파이프라인에 넘기지 않는다; txt·하위경로 엔트리는 무시"

    metrics = json.loads((tmp_path / "m.json").read_text(encoding="utf-8"))
    assert (metrics["images_total"], metrics["embedded_total"], metrics["fallback_total"], metrics["errors_total"]) == (4, 3, 1, 1)
    assert not list(out.glob("*.tmp"))

    # 재실행: 완료 tar는 건너뛴다
    pipeline2 = FakePipeline()
    assert bulk_embed.run(_args(tmp_path), pipeline2, open_ok_or_raise) == 0
    assert pipeline2.calls == []
    assert "완료" in capsys.readouterr().out


def test_run_refuses_mixing_model_versions(tmp_path: Path) -> None:
    tars = tmp_path / "tars"
    tars.mkdir()
    make_tar(tars / "images-a.tar", {"A1_1.jpg": b"img"})
    assert bulk_embed.run(_args(tmp_path), FakePipeline(), open_ok_or_raise) == 0
    make_tar(tars / "images-b.tar", {"A2_1.jpg": b"img"})
    v3 = dict(MODEL_META, model_version="v3")
    with pytest.raises(SystemExit, match="모델 메타데이터 불일치"):
        bulk_embed.run(_args(tmp_path), FakePipeline(v3), open_ok_or_raise)
    assert not (tmp_path / "out" / "vectors-images-b.tsv").exists(), "거부된 tar 의 출력은 남지 않는다"
    assert not list((tmp_path / "out").glob("*.tmp")), "거부 시 임시 파일도 남지 않는다"


def test_run_fails_when_no_tars(tmp_path: Path) -> None:
    (tmp_path / "tars").mkdir()
    assert bulk_embed.run(_args(tmp_path), FakePipeline(), open_ok_or_raise) == 1


def test_format_vector_precision() -> None:
    assert bulk_embed.format_vector([1, 0.1234567, -2]) == "1.000000,0.123457,-2.000000"


def test_batched_groups_and_flushes_remainder() -> None:
    assert list(bulk_embed.batched(iter(range(5)), 2)) == [[0, 1], [2, 3], [4]]


def test_all_broken_tar_yields_empty_outputs_and_continues(tmp_path: Path) -> None:
    tars = tmp_path / "tars"
    tars.mkdir()
    make_tar(tars / "images-a.tar", {"X1_1.jpg": b"broken", "X2_1.jpg": b"broken"})
    make_tar(tars / "images-b.tar", {"Y1_1.jpg": b"img"})
    pipeline = FakePipeline()
    assert bulk_embed.run(_args(tmp_path), pipeline, open_ok_or_raise) == 0
    out = tmp_path / "out"
    assert (out / "vectors-images-a.tsv").read_text(encoding="utf-8") == "", "유효 벡터 0개여도 빈 파일로 완료 처리"
    assert len((out / "detections-images-a.jsonl").read_text(encoding="utf-8").splitlines()) == 2
    assert pipeline.calls == [["Y1_1"]], "깨진 tar 는 파이프라인 호출 없이 넘어가고 다음 tar 를 처리한다"
    assert (out / "_meta.json").exists() and not list(out.glob("*.tmp"))
    state = json.loads((tmp_path / "s.json").read_text(encoding="utf-8"))
    assert state["done_tars"] == ["images-a.tar", "images-b.tar"]


def test_resolve_weights_prefers_local_models_dir(tmp_path: Path) -> None:
    local = tmp_path / "ai" / "models" / "yolo26l-seg" / "yolo26l-seg.pt"
    local.parent.mkdir(parents=True)
    local.write_bytes(b"w")
    assert bulk_embed.resolve_weights(tmp_path / "ai", "/home/ubuntu/ai/models/yolo26l-seg/yolo26l-seg.pt") == str(local)
    assert bulk_embed.resolve_weights(tmp_path / "ai", "/home/ubuntu/ai/models/none/none.pt") == "/home/ubuntu/ai/models/none/none.pt", "로컬에 없으면 설정값 그대로"
    assert bulk_embed.resolve_weights(tmp_path / "ai", None) is None
    assert bulk_embed.resolve_weights(tmp_path / "ai", "models/yolo26l-seg/yolo26l-seg.pt") == str(local), "상대 경로도 ai_dir 기준으로 해석"
    assert bulk_embed.resolve_weights(tmp_path / "ai", "/var/models/app/ai/models/yolo26l-seg/yolo26l-seg.pt") == str(local), "상위에 models 가 또 있어도 마지막 기준"

