"""이미지 역사 백필 — HDFS의 백필 레코드(월별 records.jsonl)에서 사진 URL을 읽어 내려받아 tar로 적재한다.

imager.py(Kafka 증분용)와 다운로드·판별·spool 로직을 공유하되, 입력은 Kafka가 아니라 HDFS 레코드다.
Kafka는 일일 증분의 통로(보존 7일)지 수십만 건 벌크의 길이 아니고, 이미지 파일은 2022-01 이후만
생존하므로(정찰 실측) 범위는 인자로 받는다.

    입력: <records-dir>/yyyymm=YYYYMM/records.jsonl        (backfill.py 산출물)
    출력: <hdfs-dir>/yyyymm=YYYYMM/images-YYYYMM-NNNN.tar   엔트리 {desertionNo}_{1|2}.{jpg|png}
          <hdfs-dir>/yyyymm=YYYYMM/missing-YYYYMM-NNNN.jsonl (실패 건 — 커버리지 근거)
    상태: <state>   — 월별 완료 청크 번호 (청크 단위 재개)
    지표: <metrics> — 월별 대상·성공·결손·바이트·소요, 결손 사유 분포

청크(기본 2,000장 ≈ 470MB)마다 다운로드 → tar → HDFS put → 상태 저장. 중단되면 같은 명령으로
재실행하면 완료한 청크는 건너뛴다. 청크는 결정적이다(엔트리 키 정렬 후 분할) — 재실행 시 같은 청크가
같은 번호를 받으므로 재개가 성립한다.

사용법 (서버 2):
    python image_backfill.py --from 2023-01 --to 2026-09 \
        --records-dir /data/shelter/backfill --hdfs-dir /data/shelter/images-backfill \
        --state ~/collector-data/image-backfill-state.json \
        --metrics ~/collector-data/image-backfill-metrics.json
"""

import argparse
import datetime
import json
import subprocess
import sys
import tarfile
import tempfile
import time
from pathlib import Path

import imager
from backfill import load_json, month_range, save_json

CHUNK_SIZE = 2000  # 장 — 청크 tar ≈ 470MB(평균 235KB), 실패 시 되돌릴 단위이자 재개 단위


def read_month_records(records_dir: str, label: str) -> list[bytes]:
    """HDFS의 월별 records.jsonl을 줄 단위 bytes로 읽는다 (imager.collect_jobs 입력 형식)."""
    path = f"{records_dir}/yyyymm={label}/records.jsonl"
    proc = subprocess.run(["hdfs", "dfs", "-cat", path], capture_output=True)
    if proc.returncode != 0:
        # 입력은 backfill.py 산출물이다 — 없는 월을 조용히 건너뛰면 이미지 커버리지에 구멍이 생기므로
        # 스택트레이스 대신 원인을 말하고 멈춘다 (레코드 백필을 먼저 완료하는 것이 해법)
        raise SystemExit(f"{label}: 레코드를 읽을 수 없음 ({path}) — backfill.py로 해당 월을 먼저 적재하세요"
                         "\n" +
                         f"{proc.stderr.decode(errors='replace').strip()[-300:]}")
    return [line for line in proc.stdout.splitlines() if line.strip()]


def chunked(items: list, size: int):
    for i in range(0, len(items), size):
        yield i // size, items[i : i + size]


def upload_chunk(spool: Path, saved: list[str], missing: list[dict], dest_dir: str,
                 stem: str, replication: int) -> int:
    """청크를 tar로 묶어 HDFS에 올린다. 반환: tar 바이트 수."""
    tar_path = spool / f"images-{stem}.tar"
    with tarfile.open(tar_path, "w") as tar:
        for name in sorted(saved):
            tar.add(spool / name, arcname=name)
    put = ["hdfs", "dfs", "-D", f"dfs.replication={replication}", "-put", "-f"]
    subprocess.run(["hdfs", "dfs", "-mkdir", "-p", dest_dir], check=True)
    subprocess.run([*put, str(tar_path), f"{dest_dir}/images-{stem}.tar"], check=True)
    if missing:
        missing_path = spool / f"missing-{stem}.jsonl"
        missing_path.write_text(
            "".join(json.dumps(m, ensure_ascii=False) + "\n" for m in missing), encoding="utf-8"
        )
        subprocess.run([*put, str(missing_path), f"{dest_dir}/missing-{stem}.jsonl"], check=True)
    return tar_path.stat().st_size


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    p = argparse.ArgumentParser(description="HDFS 백필 레코드 → 이미지 다운로드 → HDFS tar (청크 재개)")
    p.add_argument("--from", dest="start", required=True, help="YYYY-MM")
    p.add_argument("--to", dest="end", required=True, help="YYYY-MM")
    p.add_argument("--records-dir", required=True, help="backfill.py 산출 루트 (예: /data/shelter/backfill)")
    p.add_argument("--hdfs-dir", required=True, help="이미지 적재 루트 (예: /data/shelter/images-backfill)")
    p.add_argument("--state", required=True)
    p.add_argument("--metrics", required=True)
    p.add_argument("--chunk", type=int, default=CHUNK_SIZE)
    p.add_argument("--workers", type=int, default=imager.DOWNLOAD_WORKERS)
    # 백필 이미지는 원본 서버에서 다시 받을 수 있는 파생물이라 복제 1로 HDFS 용량을 아낀다.
    # (증분 이미지·레코드는 클러스터 기본 복제를 따른다)
    p.add_argument("--replication", type=int, default=1)
    args = p.parse_args()
    try:
        start = datetime.datetime.strptime(args.start, "%Y-%m")
        end = datetime.datetime.strptime(args.end, "%Y-%m")
    except ValueError:
        print(f"--from/--to 는 YYYY-MM 형식이어야 합니다 (받은 값: {args.start}, {args.end})", file=sys.stderr)
        return 2
    if start > end:
        print(f"--from {args.start} 이 --to {args.end} 보다 뒤입니다", file=sys.stderr)
        return 2
    if args.chunk <= 0 or args.workers <= 0 or args.replication <= 0:
        print("--chunk, --workers, --replication 은 1 이상이어야 합니다", file=sys.stderr)
        return 2
    imager.DOWNLOAD_WORKERS = args.workers

    state_path, metrics_path = Path(args.state), Path(args.metrics)
    state = load_json(state_path, {"done_chunks": {}, "month_chunks": {}})
    state.setdefault("month_chunks", {})  # 월별 총 청크 수 — 완료 월은 레코드를 다시 읽지 않고 건너뛴다
    # 청크 번호는 청크 크기에 종속된다 — 크기를 바꿔 재실행하면 완료 표시가 다른 범위를 가리켜
    # 중복·누락이 생기므로, 상태에 기록된 크기와 다르면 거부한다 (새 크기로 하려면 새 상태 파일)
    saved_chunk = state.setdefault("chunk_size", args.chunk)
    if saved_chunk != args.chunk:
        print(f"--chunk {args.chunk} 이 상태 파일의 청크 크기 {saved_chunk} 와 다릅니다 — "
              f"기존 상태로 재개하려면 --chunk {saved_chunk} 로 실행하세요", file=sys.stderr)
        return 2
    metrics = load_json(metrics_path, {"months": {}, "missing_reasons": {}, "bytes_total": 0,
                                       "saved_total": 0, "missing_total": 0, "seconds_total": 0.0})

    for y, m in month_range(args.start, args.end):
        label = f"{y}{m:02d}"
        done = set(state["done_chunks"].get(label, []))
        if label in state["month_chunks"] and len(done) >= state["month_chunks"][label]:
            continue
        records = read_month_records(args.records_dir, label)
        jobs, skipped = imager.collect_jobs(records)
        if skipped:
            print(f"{label}: 손상 레코드 {skipped}건 스킵", file=sys.stderr)
        items = sorted(jobs.items())  # 결정적 분할 — 재개의 전제
        n_chunks = (len(items) + args.chunk - 1) // args.chunk
        state["month_chunks"][label] = n_chunks
        if done >= set(range(n_chunks)):  # 이미지 대상이 0건인 월도 여기서 완료로 확정된다
            save_json(state_path, state)
            continue
        mm = metrics["months"].setdefault(label, {"records": len(records), "jobs": len(items),
                                                  "saved": 0, "missing": 0, "bytes": 0, "seconds": 0.0})
        dest_dir = f"{args.hdfs_dir}/yyyymm={label}"
        for idx, chunk in chunked(items, args.chunk):
            if idx in done:
                continue
            t0 = time.time()
            with tempfile.TemporaryDirectory(prefix=f"imgbf-{label}-") as tmp:
                spool = Path(tmp)
                saved, missing = imager.fetch_images(dict(chunk), spool)
                size = upload_chunk(spool, saved, missing, dest_dir, f"{label}-{idx:04d}", args.replication)
            sec = time.time() - t0
            for mi in missing:
                reason = mi["reason"].split(":")[0][:60]
                metrics["missing_reasons"][reason] = metrics["missing_reasons"].get(reason, 0) + 1
            mm["saved"] += len(saved); mm["missing"] += len(missing); mm["bytes"] += size; mm["seconds"] += sec
            metrics["saved_total"] += len(saved); metrics["missing_total"] += len(missing)
            metrics["bytes_total"] += size; metrics["seconds_total"] += sec
            state["done_chunks"].setdefault(label, []).append(idx)
            save_json(metrics_path, metrics)
            save_json(state_path, state)  # 업로드 성공 후에만 확정 — 실패 청크는 재실행이 다시 받는다
            print(f"{label} 청크 {idx + 1}/{n_chunks}: 성공 {len(saved)} 결손 {len(missing)} "
                  f"{size / 2**20:.0f}MB {sec:.0f}초 ({len(saved) / max(sec, 1):.1f}장/초) | "
                  f"누계 {metrics['saved_total']:,}장 {metrics['bytes_total'] / 2**30:.1f}GB")

    print(f"완료: 성공 {metrics['saved_total']:,}장 · 결손 {metrics['missing_total']:,} · "
          f"{metrics['bytes_total'] / 2**30:.1f}GB · {metrics['seconds_total'] / 3600:.1f}시간")
    return 0


if __name__ == "__main__":
    sys.exit(main())
