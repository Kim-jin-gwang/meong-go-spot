"""extract-list.tsv의 사진을 HDFS TAR에서 추출 — 서버 2에서 실행 (hdfs CLI 필요).

TAR을 통째로 받지 않고 `hdfs dfs -cat`으로 스트리밍하며 대상 entry만 꺼낸다.
entry 이름은 `{photo_id}.{jpg|png}` — 확장자는 TAR 안에서 판별되므로 목록에는 photo_id만 있으면 된다.

    python extract_images.py --list datasets/repr-v1/extract-list.tsv --out datasets/repr-v1/images
    python extract_images.py --list ... --out ... --hdfs-cmd "hdfs dfs -cat"   # 기본값
"""

from __future__ import annotations

import argparse
import shlex
import subprocess
import sys
import tarfile
from collections import defaultdict
from pathlib import Path


def read_list(path: Path) -> dict[str, set[str]]:
    """TAR 경로 → 추출할 photo_id 집합."""
    by_tar: dict[str, set[str]] = defaultdict(set)
    with path.open(encoding="utf-8") as fp:
        for line in fp:
            tar_path, _, pid = line.rstrip("\n").partition("\t")
            if tar_path and pid:
                by_tar[tar_path].add(pid)
    return by_tar


def extract_tar(hdfs_cmd: list[str], tar_path: str, wanted: set[str], out: Path) -> list[str]:
    """스트리밍 TAR에서 wanted photo_id의 entry를 out에 저장 — 저장된 photo_id 반환."""
    saved: list[str] = []
    proc = subprocess.Popen([*hdfs_cmd, tar_path], stdout=subprocess.PIPE)
    assert proc.stdout is not None
    try:
        with tarfile.open(fileobj=proc.stdout, mode="r|") as tar:
            for member in tar:
                pid = Path(member.name).stem
                if not member.isfile() or pid not in wanted:
                    continue
                dest = out / f"{pid}{Path(member.name).suffix.lower()}"
                src = tar.extractfile(member)
                if src is None:
                    continue
                dest.write_bytes(src.read())
                saved.append(pid)
                if len(saved) == len(wanted):
                    break
    finally:
        proc.stdout.close()
        proc.wait()
    return saved


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="HDFS TAR 스트리밍에서 대상 이미지만 추출")
    ap.add_argument("--list", required=True, help="extract-list.tsv (make_dataset.py 출력)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--hdfs-cmd", default="hdfs dfs -cat", help="TAR을 stdout으로 내보내는 명령")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    hdfs_cmd = shlex.split(args.hdfs_cmd)

    by_tar = read_list(Path(args.list))
    total_wanted = sum(len(v) for v in by_tar.values())
    total_saved = 0
    for i, (tar_path, wanted) in enumerate(sorted(by_tar.items()), 1):
        already = {p.stem for p in out.glob("*.*")}
        remaining = wanted - already  # 재실행 시 이미 추출한 entry는 건너뛴다
        if not remaining:
            total_saved += len(wanted)
            continue
        saved = extract_tar(hdfs_cmd, tar_path, remaining, out)
        total_saved += len(wanted & already) + len(saved)
        print(f"[{i}/{len(by_tar)}] {tar_path}: {len(saved)}/{len(remaining)}장")
        missing = remaining - set(saved)
        if missing:
            print(f"  누락: {sorted(missing)[:10]}")
    print(f"완료: {total_saved}/{total_wanted}장 → {out}")
    return 0 if total_saved == total_wanted else 1


if __name__ == "__main__":
    sys.exit(main())
