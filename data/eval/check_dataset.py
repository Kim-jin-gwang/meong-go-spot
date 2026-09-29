"""데이터셋 정합성 검증 — manifest·pairs·이미지 파일이 서로 맞는지 확인하고 통계를 출력한다.

서버 2(이미지가 있는 곳)에서 실행한다. 이미지 없이 manifest·pairs만 검증하려면 --images 를 생략한다.

    python check_dataset.py --dataset datasets/repr-v1 --images datasets/repr-v1/images

검사 항목
- manifest의 모든 사진: 이미지 파일 존재 (photo_id.jpg|png|jpeg)
- pairs의 모든 photo_id가 manifest에 존재
- label 정합: 양성 쌍은 같은 group_id, 음성 쌍은 다른 group_id
- 태그 커버리지·축별 분포 통계 출력

하나라도 실패하면 종료 코드 1 — 데이터셋 갱신 후 반드시 통과시킨다.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter
from pathlib import Path

IMAGE_EXTS = (".jpg", ".jpeg", ".png")


def load_manifest(dataset_dir: Path) -> dict[str, dict]:
    path = dataset_dir / "manifest.jsonl"
    entries = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]
    return {e["photo_id"]: e for e in entries}


def load_pairs(dataset_dir: Path) -> list[dict]:
    with (dataset_dir / "pairs.tsv").open(encoding="utf-8") as fp:
        return list(csv.DictReader(fp, delimiter="\t"))


def check(dataset_dir: Path, images_dir: Path | None) -> tuple[list[str], dict]:
    """오류 목록과 통계를 반환 — 오류가 비어 있으면 통과."""
    errors: list[str] = []
    manifest = load_manifest(dataset_dir)
    pairs = load_pairs(dataset_dir)

    if images_dir is not None and not images_dir.is_dir():
        errors.append(f"이미지 디렉터리 없음: {images_dir}")
        images_dir = None
    if images_dir is not None:
        have = {p.stem for p in images_dir.iterdir() if p.suffix.lower() in IMAGE_EXTS}
        missing = sorted(pid for pid in manifest if pid not in have)
        if missing:
            errors.append(f"이미지 없음 {len(missing)}장: {missing[:10]}")
        extra = sorted(have - set(manifest))
        if extra:
            errors.append(f"manifest에 없는 이미지 {len(extra)}장: {extra[:10]}")

    for r in pairs:
        for k in ("query_id", "target_id"):
            if r[k] not in manifest:
                errors.append(f"{r['pair_id']}: {k}={r[k]} 가 manifest에 없음")
        q, t = manifest.get(r["query_id"]), manifest.get(r["target_id"])
        if q and t:
            same = q["group_id"] == t["group_id"]
            if r["label"] == "1" and not same:
                errors.append(f"{r['pair_id']}: 양성인데 다른 개체 ({q['group_id']} vs {t['group_id']})")
            if r["label"] == "0" and same:
                errors.append(f"{r['pair_id']}: 음성인데 같은 개체 ({q['group_id']})")

    tagged = [e for e in manifest.values() if e.get("tags") and any(e["tags"].values())]
    stats = {
        "photos": len(manifest),
        "animals": len({e["group_id"] for e in manifest.values()}),
        "pairs_by_type": dict(Counter(r["pair_type"] for r in pairs)),
        "pos": sum(1 for r in pairs if r["label"] == "1"),
        "neg": sum(1 for r in pairs if r["label"] == "0"),
        "by_species": dict(Counter(e["species"] or "샘플" for e in manifest.values())),
        "by_breed_top": dict(Counter(e["kind_nm"] for e in manifest.values() if e["kind_nm"]).most_common(10)),
        "by_fallback": dict(Counter("fallback" if e["fallback"] else "detected" for e in manifest.values())),
        "tagged": len(tagged),
        "by_angle": dict(Counter(e["tags"]["angle"] for e in tagged if e["tags"]["angle"])),
        "by_background": dict(Counter(e["tags"]["background"] for e in tagged if e["tags"]["background"])),
        "by_quality": dict(Counter(e["tags"]["quality"] for e in tagged if e["tags"]["quality"])),
    }
    return errors, stats


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="데이터셋 manifest·pairs·이미지 정합성 검증")
    ap.add_argument("--dataset", required=True, help="dataset.json·manifest.jsonl·pairs.tsv 가 있는 디렉터리")
    ap.add_argument("--images", default="", help="이미지 디렉터리 — 생략하면 파일 존재 검사 생략")
    args = ap.parse_args()

    errors, stats = check(Path(args.dataset), Path(args.images) if args.images else None)
    print(json.dumps(stats, ensure_ascii=False, indent=1))
    if errors:
        print(f"\nBLOCKER {len(errors)}건:")
        for e in errors[:30]:
            print(f"- {e}")
        return 1
    print("\nPASS — manifest·pairs" + ("·이미지" if args.images else "") + " 정합")
    return 0


if __name__ == "__main__":
    sys.exit(main())
