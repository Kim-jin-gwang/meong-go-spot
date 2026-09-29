"""`shelter.raw`를 별도 컨슈머 그룹으로 읽어 사진을 내려받아 HDFS에 tar로 적재한다.

loader와 같은 토픽을 다른 그룹(image-collector)으로 소비한다 — Kafka 팬아웃.
배치형·at-least-once·업로드 후 동기 커밋 등 운영 계약은 loader와 동일하다.

산출물 (HDFS):
    /data/shelter/images/dt=YYYY-MM-DD/images-<ms>.tar   엔트리명 {desertionNo}_{1|2}.{jpg|png}
    /data/shelter/images/dt=YYYY-MM-DD/missing-<ms>.jsonl 실패 건 기록 (커버리지 KPI 근거)

사용법:
    python imager.py --bootstrap bd-master:9092 --hdfs-dir /data/shelter/images
"""

import argparse
import concurrent.futures
import datetime
import json
import re
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
from pathlib import Path

from confluent_kafka import Consumer

from loader import consume_all

GROUP_ID = "image-collector"
DOWNLOAD_WORKERS = 8  # 공공 서버 부하를 고려한 상한
DOWNLOAD_RETRIES = 2
TIMEOUT_SEC = 30


def detect_extension(data: bytes) -> str | None:
    """매직 바이트로 실제 포맷 판별 — URL 확장자는 신뢰하지 않는다 (실측 ~2.5% 불일치)."""
    if data[:2] == b"\xff\xd8":
        return "jpg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    return None


def download(url: str) -> bytes:
    last_error: Exception | None = None
    for attempt in range(1, DOWNLOAD_RETRIES + 1):
        try:
            with urllib.request.urlopen(url, timeout=TIMEOUT_SEC) as res:
                return res.read()
        except Exception as error:  # noqa: BLE001 — 실패 사유는 결손 기록으로 남긴다
            last_error = error
            time.sleep(attempt)
    raise RuntimeError(str(last_error))


def collect_jobs(messages: list[bytes]) -> tuple[dict[str, str], int]:
    """메시지에서 (엔트리 키 → URL) 목록을 만든다. 같은 개체의 갱신은 마지막 것만 남긴다.

    손상 메시지는 스킵하고 개수만 센다 — 하나 때문에 크래시하면 오프셋이 안 나가
    재실행마다 같은 자리에서 죽는 poison-pill 루프가 되기 때문.
    """
    jobs: dict[str, str] = {}
    skipped = 0
    for value in messages:
        try:
            record = json.loads(value)
        except ValueError:
            skipped += 1
            continue
        if not isinstance(record, dict):
            skipped += 1
            continue
        no = str(record.get("desertionNo") or "")
        # 파일명·tar 엔트리명에 쓰이므로 화이트리스트 검증 — ../ 등 경로 이탈 차단
        if not re.fullmatch(r"[A-Za-z0-9_-]+", no):
            skipped += 1
            continue
        for idx in (1, 2):
            url = record.get(f"popfile{idx}")
            # http(s)만 허용 — file:// 등이 섞인 조작 메시지로 서버 내부 파일을
            # tar에 담아 내보내는 SSRF 경로를 차단한다 (타입 검증 겸용)
            if isinstance(url, str) and url.startswith(("http://", "https://")):
                jobs[f"{no}_{idx}"] = url
    return jobs, skipped


def fetch_images(jobs: dict[str, str], spool_dir: Path) -> tuple[list[str], list[dict]]:
    """병렬 다운로드 — 결과를 메모리에 들지 않고 spool_dir에 파일로 쓴다 (벌크 수 GB 대비).

    반환: (저장된 파일명 목록, 결손 목록).
    """
    saved: list[str] = []
    missing: list[dict] = []

    def work(item: tuple[str, str]) -> None:
        key, url = item
        try:
            data = download(url)
        except RuntimeError as error:
            missing.append({"entry": key, "url": url, "reason": str(error)})
            return
        extension = detect_extension(data)
        if extension is None:
            missing.append({"entry": key, "url": url, "reason": "unknown-format"})
            return
        name = f"{key}.{extension}"
        try:
            (spool_dir / name).write_bytes(data)
        except OSError as error:  # 디스크 부족 등 — 한 장 때문에 배치 전체를 죽이지 않는다
            missing.append({"entry": key, "url": url, "reason": f"write-error: {error}"})
            return
        saved.append(name)

    with concurrent.futures.ThreadPoolExecutor(max_workers=DOWNLOAD_WORKERS) as pool:
        list(pool.map(work, jobs.items()))
    return saved, missing


def upload_to_hdfs(spool_dir: Path, saved: list[str], missing: list[dict], hdfs_dir: str) -> str:
    date_partition = datetime.date.today().isoformat()
    dest_dir = f"{hdfs_dir}/dt={date_partition}"
    stamp = int(time.time() * 1000)

    tar_path = spool_dir / f"images-{stamp}.tar"
    with tarfile.open(tar_path, "w") as tar:
        for name in sorted(saved):
            tar.add(spool_dir / name, arcname=name)
    missing_path = spool_dir / f"missing-{stamp}.jsonl"
    missing_path.write_text(
        "".join(json.dumps(m, ensure_ascii=False) + "\n" for m in missing),
        encoding="utf-8",
    )
    subprocess.run(["hdfs", "dfs", "-mkdir", "-p", dest_dir], check=True)
    subprocess.run(["hdfs", "dfs", "-put", str(tar_path), f"{dest_dir}/"], check=True)
    if missing:
        subprocess.run(["hdfs", "dfs", "-put", str(missing_path), f"{dest_dir}/"], check=True)
    return f"{dest_dir}/images-{stamp}.tar"


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Kafka → 이미지 다운로드 → HDFS tar 적재")
    parser.add_argument("--bootstrap", required=True, help="Kafka bootstrap servers")
    parser.add_argument("--hdfs-dir", required=True, help="HDFS 이미지 루트 (예: /data/shelter/images)")
    args = parser.parse_args()

    consumer = Consumer(
        {
            "bootstrap.servers": args.bootstrap,
            "group.id": GROUP_ID,
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,
            # 다운로드~적재가 수십 분 걸리는 배치 소비자 — 기본 5분이면 그룹에서
            # 축출돼 커밋이 실패한다 (2026-09-01 벌크 실측: _ASSIGNMENT_LOST)
            "max.poll.interval.ms": 7_200_000,  # 2시간
        }
    )
    try:
        messages = consume_all(consumer)
        if not messages:
            print("새 메시지 없음 — 이미지 수집 생략")
            return 0
        jobs, skipped = collect_jobs(messages)
        if skipped:
            print(f"경고: 손상 메시지 {skipped}건 스킵", file=sys.stderr)
        with tempfile.TemporaryDirectory() as tmp:
            spool_dir = Path(tmp)
            saved, missing = fetch_images(jobs, spool_dir)
            dest = upload_to_hdfs(spool_dir, saved, missing, args.hdfs_dir)
        consumer.commit(asynchronous=False)
        print(
            f"메시지 {len(messages)}건 → 이미지 대상 {len(jobs)}장 | "
            f"성공 {len(saved)} · 결손 {len(missing)} | 적재: {dest}"
        )
        return 0
    finally:
        consumer.close()


if __name__ == "__main__":
    sys.exit(main())
