"""이미지 결손 재시도 — `missing-*.jsonl` 중 일시 오류(타임아웃·연결 리셋)만 다시 내려받아 `images-<라벨>-retry.tar` 로 채운다.

404(원본 삭제)·포맷 불명은 재시도해도 같으므로 건너뛴다. 재시도 결과는 같은 파티션에
`images-<라벨>-retry.tar` + `missing-<라벨>-retry.jsonl`(여전히 실패한 건) 로 남기고, 원본 missing 파일은 손대지 않는다
(무엇이 원래 결손이었는지 기록 보존). 임베딩은 `TAR_GLOB='images-*-retry.tar' hdfs_embed_partition.sh` 로 그 tar 만.

    python retry_missing.py --hdfs-dir /data/shelter/images-backfill            # 전체 파티션
    python retry_missing.py --hdfs-dir /data/shelter/images-backfill --partitions yyyymm=202409 yyyymm=202410
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

import imager

PERMANENT_MARKERS = ("HTTP Error 404", "HTTP Error 410", "unknown-format")  # 다시 받아도 같은 결과


def is_retryable(reason: str) -> bool:
    # 부분 문자열 검사 — urllib 가 감싼 "<urlopen error HTTP Error 404: ...>" 형태도 영구 오류로 본다
    text = str(reason)
    return not any(marker in text for marker in PERMANENT_MARKERS)


def select_retryable(missing_lines: list[str]) -> dict[str, str]:
    """missing jsonl 줄들 → 재시도할 {엔트리: URL}.

    같은 엔트리가 여러 번 나오면 **마지막 기록의 상태**를 따른다 — 앞에서 timeout, 뒤에서 404 였다면 재시도하지 않는다.
    손상 줄·URL 없음·비 http 는 제외.
    """
    latest: dict[str, tuple[bool, str]] = {}
    for line in missing_lines:
        try:
            m = json.loads(line)
        except ValueError:
            continue
        if not isinstance(m, dict):
            continue
        entry, url = str(m.get("entry") or ""), m.get("url")
        if entry and isinstance(url, str) and url.startswith(("http://", "https://")):
            latest[entry] = (is_retryable(m.get("reason") or ""), url)  # reason: null 도 빈 문자열로
    return {entry: url for entry, (retryable, url) in latest.items() if retryable}


def hdfs(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["hdfs", "dfs", *args], capture_output=True, text=True, check=check)


def list_partitions(hdfs_dir: str) -> list[str]:
    out = hdfs("-ls", hdfs_dir, check=False).stdout
    return sorted(line.split()[-1].rsplit("/", 1)[-1] for line in out.splitlines() if "/yyyymm=" in line or "/dt=" in line)


def read_missing(hdfs_dir: str, partition: str) -> list[str]:
    """파티션의 missing-*.jsonl 전체(이전 retry 산출물 제외) 줄 목록."""
    listing = hdfs("-ls", f"{hdfs_dir}/{partition}", check=False).stdout
    files = [line.split()[-1] for line in listing.splitlines() if "/missing-" in line and "-retry.jsonl" not in line]
    if not files:
        return []
    # 파일마다 hdfs 를 띄우면 JVM 기동이 파일 수만큼 반복된다(249개 → 20분 실측) — cat 은 여러 경로를 한 번에 받는다
    return hdfs("-cat", *files).stdout.splitlines()


def retry_partition(hdfs_dir: str, partition: str) -> dict:
    label = partition.split("=", 1)[-1]
    jobs = select_retryable(read_missing(hdfs_dir, partition))
    stats = {"partition": partition, "retryable": len(jobs), "saved": 0, "still_missing": 0}
    if not jobs:
        return stats
    with tempfile.TemporaryDirectory(prefix=f"retry-{label}-") as tmp:
        spool = Path(tmp)
        saved, missing = imager.fetch_images(jobs, spool)
        stats["saved"], stats["still_missing"] = len(saved), len(missing)
        if saved:
            tar_path = spool / f"images-{label}-retry.tar"
            with tarfile.open(tar_path, "w") as tar:
                for name in sorted(saved):
                    tar.add(spool / name, arcname=name)
            hdfs("-put", "-f", str(tar_path), f"{hdfs_dir}/{partition}/images-{label}-retry.tar")
        if missing:
            miss_path = spool / f"missing-{label}-retry.jsonl"
            miss_path.write_text("".join(json.dumps(m, ensure_ascii=False) + "\n" for m in missing), encoding="utf-8")
            hdfs("-put", "-f", str(miss_path), f"{hdfs_dir}/{partition}/missing-{label}-retry.jsonl")
    return stats


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="이미지 결손(일시 오류) 재다운로드 → images-<라벨>-retry.tar")
    ap.add_argument("--hdfs-dir", required=True)
    ap.add_argument("--partitions", nargs="*", help="비우면 전체")
    ap.add_argument("--workers", type=int, default=imager.DOWNLOAD_WORKERS)
    args = ap.parse_args()
    imager.DOWNLOAD_WORKERS = args.workers
    partitions = args.partitions or list_partitions(args.hdfs_dir)
    total = {"retryable": 0, "saved": 0, "still_missing": 0}
    for part in partitions:
        st = retry_partition(args.hdfs_dir, part)
        for k in total:
            total[k] += st[k]
        if st["retryable"]:
            print(f"{part}: 재시도 {st['retryable']} → 성공 {st['saved']} · 여전히 실패 {st['still_missing']}")
    print(f"합계: 재시도 {total['retryable']} → 성공 {total['saved']} · 여전히 실패 {total['still_missing']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
