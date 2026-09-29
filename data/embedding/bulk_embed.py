"""벌크 임베딩 러너 — 사진 tar를 읽어 AI 파이프라인(YOLO 크롭 → DINOv2)으로 벡터를 뽑아 파일로 쓴다.

입출력은 전부 로컬 파일이다. HDFS·scp 같은 이동은 이 스크립트 밖(래퍼)이 맡는다 — 그래야 같은 코드가
서버 2(hdfs get/put), GPU 서버(scp pull/push), CI(모의 파이프라인)에서 그대로 돈다.

    입력: <tars>/**/images-*.tar        (수집기 산출물 — 엔트리 {desertionNo}_{1|2}.{jpg|png})
    출력: <out>/vectors-<tar stem>.tsv     사진ID<TAB>v1,v2,...   ← MapReduce 5종 입력 형식 그대로
          <out>/detections-<tar stem>.jsonl 사진별 탐지 정보(fallback·confidence·box) 또는 error
          <out>/_meta.json                 모델 메타데이터(model_id·version·dim·normalized·detector) — 디렉터리당 1개,
                                           다른 버전의 벡터가 섞이면 거부한다 (docs/data-ai-interface.md §4 원칙 2)
    상태: <state>  — 완료한 tar 목록 (tar 단위 재개)
    지표: <metrics> — tar별 장수·성공·폴백·오류·소요

사진 ID는 tar 엔트리 이름의 stem(`{desertionNo}_{1|2}`) — 원본 이미지 HDFS·ID 계약과 같다.

사용법:
    # 서버 2 (CPU) — ai/.venv 로 실행
    ~/ai/.venv/bin/python -u bulk_embed.py --tars ./tars --out ./out --state st.json --metrics m.json
    # GPU 서버 — 팀 지정 장치
    python -u bulk_embed.py --tars ./tars --out ./out --state st.json --metrics m.json --device cuda:1
"""

from __future__ import annotations

import argparse
import io
import json
import os
import sys
import tarfile
import time
from pathlib import Path
from typing import Callable, Iterable, Protocol

VECTOR_DECIMALS = 6  # MapReduce 합성 벡터와 같은 정밀도 — TSV 크기 ≈ 768차원 × 9자 ≈ 7KB/장
DEFAULT_BATCH = 16   # pipeline.process_photos 한 번에 넘기는 장수 (임베딩 forward 1회 단위)
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}


class Pipeline(Protocol):
    """ai.app.pipeline.PetEmbeddingPipeline 의 필요한 면만 — 테스트는 모의 객체를 주입한다."""

    def process_photos(self, photos: Iterable[tuple[str, object]]) -> dict: ...


def load_json(path: Path, default):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default


def save_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    os.replace(tmp, path)


def resolve_weights(ai_dir: Path, configured: str | None) -> str | None:
    """model.yaml 의 가중치 경로가 다른 호스트 기준 절대 경로(예: 서버 2의 /home/ubuntu/ai/...)일 때,
    같은 상대 위치(models/ 이하)의 파일이 ai_dir 아래에 있으면 그것을 쓴다. 없으면 설정값 그대로(None 은 기본값)."""
    if not configured:
        return None
    parts = Path(configured).parts
    if "models" in parts:
        idx = len(parts) - 1 - parts[::-1].index("models")  # 마지막 models — 상위 경로에 같은 이름이 있어도 안전
        local = ai_dir.joinpath(*parts[idx:])
        if local.is_file():
            return str(local)
    return configured


def load_pipeline(ai_dir: Path, device: str) -> Pipeline:
    """ai/ 모듈을 지연 import 한다 — torch 없는 환경(CI)에서는 이 함수를 부르지 않는다."""
    if str(ai_dir) not in sys.path:
        sys.path.insert(0, str(ai_dir))
    import yaml  # noqa: PLC0415
    from app.pipeline import PetEmbeddingPipeline  # noqa: PLC0415 — 지연 import 가 목적

    spec = yaml.safe_load((ai_dir / "model.yaml").read_text(encoding="utf-8")) or {}
    embed_weights = resolve_weights(ai_dir, spec.get("weights_path"))
    detector_weights = resolve_weights(ai_dir, (spec.get("detector") or {}).get("weights_path"))
    return PetEmbeddingPipeline(device=device, detector_weights=detector_weights, embed_weights=embed_weights)


def list_tars(root: Path) -> list[Path]:
    return sorted(p for p in root.rglob("images-*.tar") if p.is_file())


def iter_images(tar_path: Path) -> Iterable[tuple[str, bytes]]:
    """tar 엔트리를 (사진ID, 바이트)로 순회한다. 디렉터리·비이미지·경로 이탈 엔트리는 건너뛴다."""
    with tarfile.open(tar_path) as tar:
        for member in tar:
            name = Path(member.name)
            if not member.isfile() or name.suffix.lower() not in IMAGE_SUFFIXES:
                continue
            if len(name.parts) > 1:  # 하위 디렉터리 엔트리 — 수집기 계약 위반, 무시 (./a.jpg 는 parts 1개라 통과)
                continue
            fp = tar.extractfile(member)
            if fp is None:
                continue
            yield name.stem, fp.read()


def batched(items: Iterable, size: int) -> Iterable[list]:
    batch: list = []
    for item in items:
        batch.append(item)
        if len(batch) >= size:
            yield batch
            batch = []
    if batch:
        yield batch


def format_vector(vector: list[float]) -> str:
    return ",".join(f"{x:.{VECTOR_DECIMALS}f}" for x in vector)


def check_or_write_meta(out_dir: Path, model_meta: dict) -> None:
    """출력 디렉터리의 모델 메타데이터를 고정한다 — 다른 모델·버전의 벡터를 같은 곳에 섞지 못하게."""
    meta_path = out_dir / "_meta.json"
    keys = ("model_id", "model_version", "dim", "normalized")
    incoming = {k: model_meta.get(k) for k in keys}
    incoming["detector"] = {k: (model_meta.get("detector") or {}).get(k) for k in ("detector_id", "detector_version")}
    if meta_path.exists():
        existing = json.loads(meta_path.read_text(encoding="utf-8"))
        compare = {k: existing.get(k) for k in keys}
        compare["detector"] = {k: (existing.get("detector") or {}).get(k) for k in ("detector_id", "detector_version")}
        if compare != incoming:
            raise SystemExit(
                f"모델 메타데이터 불일치: 출력 디렉터리 {existing} vs 현재 {incoming} — "
                "다른 모델/버전의 벡터는 같은 디렉터리에 섞을 수 없습니다 (새 출력 디렉터리를 쓰세요)"
            )
        return
    out_dir.mkdir(parents=True, exist_ok=True)
    save_json(meta_path, model_meta)


def process_tar(
    tar_path: Path,
    out_dir: Path,
    pipeline: Pipeline,
    batch_size: int,
    open_image: Callable[[bytes], object],
) -> dict:
    """tar 하나 → vectors/detections 파일. 반환: 지표. 출력은 임시 파일에 쓴 뒤 교체(tar 단위 원자성)."""
    stem = tar_path.stem  # images-202401-0000
    vec_tmp = out_dir / f"vectors-{stem}.tsv.tmp"
    det_tmp = out_dir / f"detections-{stem}.jsonl.tmp"
    stats = {"images": 0, "embedded": 0, "fallback": 0, "errors": 0, "seconds": 0.0}
    t0 = time.time()
    model_meta: dict | None = None
    try:
        with vec_tmp.open("w", encoding="utf-8", newline="\n") as vec_fp, det_tmp.open("w", encoding="utf-8", newline="\n") as det_fp:
            for batch in batched(iter_images(tar_path), batch_size):
                photos = []
                for photo_id, data in batch:
                    stats["images"] += 1
                    try:
                        photos.append((photo_id, open_image(data)))
                    except Exception as error:  # noqa: BLE001 — 깨진 파일 한 장이 tar 전체를 죽이면 안 된다
                        stats["errors"] += 1
                        det_fp.write(json.dumps({"photo_id": photo_id, "error": f"{type(error).__name__}: {error}"}, ensure_ascii=False) + "\n")
                if not photos:
                    continue
                payload = pipeline.process_photos(photos)
                for _, image in photos:  # 수십만 장 벌크 — GC에 맡기지 않고 디코딩 버퍼를 바로 놓는다
                    close = getattr(image, "close", None)
                    if callable(close):
                        close()
                if model_meta is None:
                    model_meta = payload["model"]
                    check_or_write_meta(out_dir, model_meta)  # 첫 배치 직후 검증 — 불일치면 남은 추론을 하지 않는다
                for entry in payload["embeddings"]:
                    vec_fp.write(f"{entry['photo_id']}\t{format_vector(entry['vector'])}\n")
                    detection = entry.get("detection") or {}
                    det_fp.write(json.dumps({"photo_id": entry["photo_id"], **detection}, ensure_ascii=False) + "\n")
                    stats["embedded"] += 1
                    stats["fallback"] += bool(detection.get("fallback"))
        os.replace(vec_tmp, out_dir / f"vectors-{stem}.tsv")
        os.replace(det_tmp, out_dir / f"detections-{stem}.jsonl")
    finally:
        for tmp in (vec_tmp, det_tmp):  # 정상 경로면 이미 교체돼 없고, 예외 경로면 남은 것을 지운다
            tmp.unlink(missing_ok=True)
    stats["seconds"] = time.time() - t0
    return stats


def default_open_image(data: bytes):
    from PIL import Image  # noqa: PLC0415 — 모의 파이프라인 테스트는 PIL 없이도 돌 수 있게 지연 import

    image = Image.open(io.BytesIO(data))
    image.load()
    return image


def run(args: argparse.Namespace, pipeline: Pipeline, open_image: Callable[[bytes], object]) -> int:
    tars_root, out_dir = Path(args.tars), Path(args.out)
    state_path, metrics_path = Path(args.state), Path(args.metrics)
    state = load_json(state_path, {"done_tars": []})
    metrics = load_json(metrics_path, {"tars": {}, "images_total": 0, "embedded_total": 0, "fallback_total": 0,
                                       "errors_total": 0, "seconds_total": 0.0})
    tars = list_tars(tars_root)
    if not tars:
        print(f"{tars_root} 아래에 images-*.tar 가 없습니다", file=sys.stderr)
        return 1
    out_dir.mkdir(parents=True, exist_ok=True)
    done = set(state["done_tars"])
    for tar_path in tars:
        key = tar_path.name
        if key in done:
            continue
        stats = process_tar(tar_path, out_dir, pipeline, args.batch, open_image)
        metrics["tars"][key] = stats
        for k in ("images", "embedded", "fallback", "errors"):
            metrics[f"{k}_total"] += stats[k]
        metrics["seconds_total"] += stats["seconds"]
        state["done_tars"].append(key)
        save_json(metrics_path, metrics)
        save_json(state_path, state)  # 출력 파일 교체가 끝난 뒤에만 완료 확정
        rate = stats["embedded"] / max(stats["seconds"], 1e-9)
        print(f"{key}: {stats['embedded']}/{stats['images']}장 (폴백 {stats['fallback']}, 오류 {stats['errors']}) "
              f"{stats['seconds']:.0f}초 ({rate:.1f}장/초) | 누계 {metrics['embedded_total']:,}장")
    print(f"완료: {metrics['embedded_total']:,}장 · 폴백 {metrics['fallback_total']:,} · 오류 {metrics['errors_total']:,} · "
          f"{metrics['seconds_total'] / 3600:.2f}시간")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="사진 tar → AI 파이프라인 → 벡터 TSV (tar 단위 재개)")
    p.add_argument("--tars", required=True, help="images-*.tar 가 들어 있는 로컬 디렉터리(하위 포함)")
    p.add_argument("--out", required=True, help="벡터·탐지·_meta.json 을 쓸 로컬 디렉터리 (모델 버전당 하나)")
    p.add_argument("--state", required=True)
    p.add_argument("--metrics", required=True)
    p.add_argument("--device", default="cpu", help="cpu | cuda:N (GPU 서버는 팀 지정 장치 번호)")
    p.add_argument("--batch", type=int, default=DEFAULT_BATCH)
    p.add_argument("--ai-dir", default=str(Path(__file__).resolve().parents[2] / "ai"),
                   help="ai/ 모듈 위치 (기본: 레포의 ai/). 서버는 ~/ai 처럼 지정")
    return p


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    args = build_parser().parse_args()
    if args.batch <= 0:
        print("--batch 는 1 이상이어야 합니다", file=sys.stderr)
        return 2
    ai_dir = Path(args.ai_dir)
    if not (ai_dir / "app" / "pipeline.py").is_file():
        print(f"--ai-dir {ai_dir} 에 app/pipeline.py 가 없습니다", file=sys.stderr)
        return 2
    pipeline = load_pipeline(ai_dir, args.device)
    return run(args, pipeline, default_open_image)


if __name__ == "__main__":
    sys.exit(main())
