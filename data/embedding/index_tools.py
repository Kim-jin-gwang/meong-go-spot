"""서빙용 색인 산출물 관리 — worker 가 읽을 이진 스냅샷과 일일 증분 클러스터 배정.

배치(MapReduce)와 서빙(상주 worker)은 같은 벡터를 다르게 읽는다. 배치는 하둡이 줄바꿈에서 파일을 쪼갤 수 있어야
하므로 TSV 를 그대로 쓴다. 서빙은 프로세스 하나가 시작할 때 전량을 메모리에 올려야 해서 텍스트 파싱이 그대로 비용이 된다
(45만 장 × 768개 = 3억 4,700만 번 변환, 실측 65초). 이 스크립트가 그 간극을 메운다 — **TSV 계약은 건드리지 않고
같은 숫자를 이진으로 한 벌 더 만든다.**

    snapshot  전체 벡터(백필 + 일일) → `snapshot/vectors-<dtype>.npy` + `ids.txt` + `meta.json`
              로딩 65초 → 3초. worker 는 켤 때 한 번만 읽으므로 요청당 비용은 0 이 된다.
    assign    새 파티션의 벡터 → `index/kmeans-k{K}/assignments/<source>-<파티션>.tsv`
              색인은 백필만 보고 만들어졌다. 매일 들어오는 사진에 무리 딱지를 붙여야 검색 대상에서 빠지지 않는다
              (2026-09-10 발견: 3일치 4,175장이 색인 밖에 있었다).

사용법 (서버 2, numpy 있는 venv):
    python index_tools.py snapshot
    python index_tools.py assign --partition dt=2026-09-10 --allow-empty

메모리: snapshot 은 벡터 전량을 메모리에 만든다. float32 45만 × 768 = 1.4GB 이고 마지막 결합에서 한 벌 더 들어
**최대 3GB 를 쓴다** (서버 2 가용 11GB). float16 으로 저장하면 파일이 절반이 되고 순위에는 영향이 없다.
"""

from __future__ import annotations

import argparse
import datetime
import json
import hashlib
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path

import numpy as np

DEFAULT_SOURCES = ("shelter-backfill", "shelter-daily")
ACTIVE_POINTER = "/embeddings/ACTIVE"
FINGERPRINT_FILE = "_centers.sha256"  # 배정이 어느 중심 기준인지 — 색인 재구축을 감지한다
FALLBACK_FILE = "fallback.npy"  # ids 순서에 맞춘 탐지 폴백 여부 (bool) — 점수 집계에서 쓴다
BLOCK_ROWS = 20000  # 파싱 중간 결합 단위 — 블록당 float32 61MB


def hdfs(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["hdfs", "dfs", *args], capture_output=True, text=True, encoding="utf-8", check=check)


def stream_cat(path: str, allow_missing: bool = False):
    """`hdfs dfs -cat <글롭>` 출력을 줄 단위로 흘린다 — 3.1GB 를 한 번에 메모리에 담지 않는다.

    stderr 는 **임시 파일로** 받는다. 파이프로 받으면 하둡이 경고를 파이프 버퍼(64KB) 넘게 쓰는 순간
    자식은 stderr 쓰기에서 멈추고 우리는 stdout 만 읽으므로 서로 기다리는 교착이 된다.
    """
    with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as errors:
        proc = subprocess.Popen(["hdfs", "dfs", "-cat", path], stdout=subprocess.PIPE,
                                stderr=errors, text=True, encoding="utf-8", bufsize=1 << 20)
        try:
            yield from proc.stdout
        finally:
            proc.stdout.close()
            code = proc.wait()
            errors.seek(0)
            tail = (errors.read() or "").strip()[-300:]
        if code != 0 and not allow_missing:
            raise SystemExit(f"hdfs -cat 실패 ({path}): {tail}")


def counted(lines, counter: Counter, key: str):
    for line in lines:
        counter[key] += 1
        yield line


def read_meta(base: str) -> dict:
    """모델 메타(`_meta.json`) — dim 은 여기서만 읽는다 (계약 §4 ③: 차원 하드코딩 금지)."""
    text = hdfs("-cat", f"{base}/_meta.json").stdout
    meta = json.loads(text)
    if int(meta.get("dim", 0)) <= 0:
        raise SystemExit(f"{base}/_meta.json 에 dim 이 없습니다")
    return meta


def active_base() -> str:
    active = hdfs("-cat", ACTIVE_POINTER).stdout.strip()
    if not active:
        raise SystemExit(f"{ACTIVE_POINTER} 가 비어 있습니다")
    return f"/embeddings/{active}"


def parse_vector_lines(lines, dim: int, block: int = BLOCK_ROWS) -> tuple[list[str], np.ndarray]:
    """`사진ID<TAB>v1,...` 줄들 → (사진ID 목록, (N, dim) float32). 차원이 다르면 그 줄에서 실패한다."""
    ids: list[str] = []
    blocks: list[np.ndarray] = []
    buf: list[np.ndarray] = []
    for line in lines:
        pid, tab, csv = line.rstrip("\n").partition("\t")
        if not tab or not pid or not csv:
            continue
        row = np.array(csv.split(","), dtype=np.float32)
        if row.size != dim:
            raise SystemExit(f"벡터 차원 {row.size} != 기대 {dim} (사진 {pid}) — 다른 모델의 벡터가 섞였습니다")
        ids.append(pid)
        buf.append(row)
        if len(buf) >= block:
            blocks.append(np.vstack(buf))
            buf = []
    if buf:
        blocks.append(np.vstack(buf))
    matrix = np.vstack(blocks) if blocks else np.empty((0, dim), dtype=np.float32)
    return ids, matrix


def parse_center_lines(lines) -> tuple[np.ndarray, np.ndarray]:
    """`무리번호<TAB>v1,...` 줄들 → (무리번호 배열, (K, dim) float32)."""
    ids: list[int] = []
    rows: list[np.ndarray] = []
    for line in lines:
        cid, tab, csv = line.rstrip("\n").partition("\t")
        if not tab or not cid or not csv:
            continue
        row = np.array(csv.split(","), dtype=np.float32)
        if rows and row.size != rows[0].size:
            raise SystemExit(f"중심 {cid} 차원 {row.size} != {rows[0].size} — 중심 파일이 섞였습니다")
        ids.append(int(cid))
        rows.append(row)
    if not rows:
        raise SystemExit("중심 파일이 비어 있습니다")
    return np.array(ids, dtype=np.int64), np.vstack(rows)


def parse_detection_lines(lines) -> dict[str, bool]:
    """`detections-*.jsonl` → {사진ID: fallback}.

    임베딩 러너가 벡터와 짝을 맞춰 쓰는 파일이다 (`bulk_embed.py`). 탐지에 실패해 원본 전체를
    쓴 사진은 `fallback: true` 다 — 그 벡터는 개가 아니라 배경을 담고 있어 점수 집계에서
    빼야 한다 (계약 §후보 출력 정책 0-2: 한쪽 폴백 recall@1 0.179 vs 양쪽 폴백 0.603).

    벡터 없이 실패만 기록된 줄(`{"photo_id":…, "error":…}`)은 건너뛴다 — 그 사진은 ids 에 없다.
    """
    flags: dict[str, bool] = {}
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as error:
            raise SystemExit(f"detections 줄을 읽을 수 없습니다: {error}") from error
        pid = row.get("photo_id")
        if not isinstance(pid, str) or not pid:
            raise SystemExit(f"detections 줄에 photo_id 가 없습니다: {line[:80]}")
        if "error" in row:
            continue
        value = row.get("fallback")
        if not isinstance(value, bool):
            raise SystemExit(f"detections 의 fallback 이 bool 이 아닙니다 ({pid}): {value!r}")
        flags[pid] = value
    return flags


def align_flags(ids: list[str], flags: dict[str, bool]) -> np.ndarray:
    """ids 순서에 맞춘 (N,) bool 배열.

    없는 사진이 있으면 **실패한다.** 조용히 false 로 채우면 폴백 사진이 정상으로 취급돼
    점수 집계가 오염되고, 그 오염은 눈에 보이지 않는다.
    """
    missing = [pid for pid in ids if pid not in flags]
    if missing:
        raise SystemExit(
            f"detections 에 없는 사진 {len(missing):,}개 (예: {missing[:3]}). "
            "vectors-*.tsv 와 detections-*.jsonl 의 짝이 맞는지 확인하십시오.")
    return np.fromiter((flags[pid] for pid in ids), dtype=bool, count=len(ids))


def dedupe_last(ids: list[str], matrix: np.ndarray) -> tuple[list[str], np.ndarray, int]:
    """같은 사진 ID 가 여러 번 나오면 마지막 것만 남긴다 (일일 수집이 백필과 겹칠 수 있다)."""
    latest = {pid: i for i, pid in enumerate(ids)}
    if len(latest) == len(ids):
        return ids, matrix, 0
    keep = sorted(latest.values())
    return [ids[i] for i in keep], matrix[keep], len(ids) - len(keep)


def nearest_center(vectors: np.ndarray, centers: np.ndarray, chunk: int = BLOCK_ROWS) -> np.ndarray:
    """벡터마다 가장 가까운 중심의 **인덱스**. 기준은 K-Means 와 같은 유클리드 거리다.

    argmin ‖v−c‖² = argmax (v·c − ‖c‖²/2) — 중심은 무리 평균이라 길이가 1 이 아니므로
    내적만 비교하면 긴 중심이 이겨 틀린 무리에 붙는다. ‖c‖²/2 를 빼는 항이 그것을 막는다.
    """
    half_norms = 0.5 * (centers.astype(np.float32) ** 2).sum(axis=1)
    out = np.empty(len(vectors), dtype=np.int64)
    for start in range(0, len(vectors), chunk):
        block = vectors[start:start + chunk]
        out[start:start + chunk] = np.argmax(block @ centers.T - half_norms[None, :], axis=1)
    return out


# ── 서브커맨드 ────────────────────────────────────────────────────────────

def cmd_snapshot(args) -> int:
    base = args.base or active_base()
    meta = read_meta(base)
    dim = int(meta["dim"])
    print(f"모델 {meta.get('model_id')}/{meta.get('model_version')} · 차원 {dim} · {base}")

    counter: Counter = Counter()
    streams = (counted(stream_cat(f"{base}/{source}/*/vectors-*.tsv", allow_missing=True), counter, source)
               for source in args.sources)
    ids, matrix = parse_vector_lines(_chain(streams), dim)
    for source in args.sources:
        print(f"  {source}: {counter[source]:,}줄")
    ids, matrix, dropped = dedupe_last(ids, matrix)
    if dropped:
        print(f"  중복 사진 ID {dropped:,}개 — 마지막 것만 남김")
    if not ids:
        raise SystemExit("벡터가 없습니다")

    # 탐지 폴백 플래그 — 벡터와 같은 파티션의 detections-*.jsonl 에서 읽는다. 재임베딩이
    # 필요 없다 (임베딩 러너가 처음부터 기록해 왔다). 없으면 align_flags 가 실패한다.
    detection_counter: Counter = Counter()
    detection_streams = (counted(stream_cat(f"{base}/{source}/*/detections-*.jsonl", allow_missing=True),
                                 detection_counter, source)
                         for source in args.sources)
    flags = parse_detection_lines(_chain(detection_streams))
    for source in args.sources:
        print(f"  {source} 탐지 기록: {detection_counter[source]:,}줄")
    fallback = align_flags(ids, flags)
    fallback_count = int(fallback.sum())
    print(f"  폴백 사진 {fallback_count:,} / {len(ids):,} ({fallback_count / len(ids) * 100:.1f}%)")

    if args.dtype == "float16":
        matrix = matrix.astype(np.float16)
    work = Path(args.work)
    work.mkdir(parents=True, exist_ok=True)
    npy_name = f"vectors-{args.dtype}.npy"
    np.save(work / npy_name, matrix)
    (work / "ids.txt").write_text("".join(pid + "\n" for pid in ids), encoding="utf-8")
    # ids.txt 에 열을 붙이지 않고 별도 배열로 둔다 — 그 파일을 읽는 기존 도구를 깨지 않는다.
    np.save(work / FALLBACK_FILE, fallback)
    snapshot_meta = {**{k: meta.get(k) for k in ("model_id", "model_version", "dim", "normalized")},
                     "dtype": args.dtype, "count": len(ids), "sources": list(args.sources),
                     "duplicates_dropped": dropped, "vectors_file": npy_name, "ids_file": "ids.txt",
                     "fallback_file": FALLBACK_FILE, "fallback_count": fallback_count,
                     "built_at": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    (work / "meta.json").write_text(json.dumps(snapshot_meta, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    reloaded = np.load(work / npy_name, mmap_mode="r")  # 쓰기 직후 형태 확인 — 잘린 파일을 올리지 않는다
    if reloaded.shape != (len(ids), dim):
        raise SystemExit(f"스냅샷 형태 {reloaded.shape} != 기대 ({len(ids)}, {dim})")
    reloaded_flags = np.load(work / FALLBACK_FILE, mmap_mode="r")
    if reloaded_flags.shape != (len(ids),):
        raise SystemExit(f"폴백 배열 형태 {reloaded_flags.shape} != 기대 ({len(ids)},)")

    out_dir = f"{base}/snapshot"
    hdfs("-mkdir", "-p", out_dir)
    for name in (npy_name, "ids.txt", FALLBACK_FILE, "meta.json"):
        hdfs("-put", "-f", str(work / name), f"{out_dir}/{name}")
    size_mb = (work / npy_name).stat().st_size / 1e6
    print(f"스냅샷 {len(ids):,} × {dim} ({args.dtype}, {size_mb:.0f}MB) → {out_dir}")
    return 0


def list_partitions(base: str, source: str) -> list[str]:
    """소스 아래 파티션 이름 목록 (`dt=…` 또는 `yyyymm=…`), 오름차순."""
    out = hdfs("-ls", f"{base}/{source}", check=False).stdout
    return sorted(line.split()[-1].rsplit("/", 1)[-1] for line in out.splitlines()
                  if "/dt=" in line or "/yyyymm=" in line)


def assignment_name(source: str, partition: str) -> str:
    return f"{source}-{partition.replace(chr(61), chr(45))}.tsv"


def existing_assignments(index: str) -> set[str]:
    out = hdfs("-ls", f"{index}/assignments", check=False).stdout
    return {line.split()[-1].rsplit("/", 1)[-1] for line in out.splitlines() if line.rstrip().endswith(".tsv")}


def missing_partitions(base: str, source: str, index: str, limit: int) -> tuple[list[str], int]:
    """배정 파일이 없는 파티션 (최근 것 우선, limit 개까지) 과 전체 누락 수.

    하루 실패해도 다음 실행이 주워간다 — 그러지 않으면 그날 사진이 영구히 색인 밖에 남는다
    (2026-09-10 에 3일치 4,175장이 그렇게 빠져 있었다).
    """
    have = existing_assignments(index)
    missing = [p for p in list_partitions(base, source) if assignment_name(source, p) not in have]
    return (missing[-limit:] if limit and len(missing) > limit else missing), len(missing)


def centers_with_fingerprint(index: str) -> tuple[np.ndarray, np.ndarray, str]:
    """중심과 그 내용의 지문. 4MB 라 한 번에 읽어도 무리 없다."""
    text = hdfs("-cat", f"{index}/centers.tsv").stdout
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]
    center_ids, centers = parse_center_lines(text.splitlines())
    return center_ids, centers, digest


def check_fingerprint(index: str, digest: str, work: Path) -> None:
    """중심이 바뀌면 기존 배정의 무리 번호는 **다른 뜻**이 된다 — 섞이기 전에 막는다."""
    recorded = hdfs("-cat", f"{index}/{FINGERPRINT_FILE}", check=False).stdout.strip()
    if recorded and recorded != digest:
        raise SystemExit(
            f"중심이 바뀌었습니다 (기록 {recorded} != 현재 {digest}). 기존 배정의 무리 번호는 다른 중심 기준이라 "
            f"섞을 수 없습니다. 색인을 다시 만들었다면 {index}/assignments 와 {FINGERPRINT_FILE} 을 지우고 다시 배정하세요.")
    if not recorded:
        local = work / FINGERPRINT_FILE
        local.write_text(digest + chr(10), encoding="utf-8")
        hdfs("-put", "-f", str(local), f"{index}/{FINGERPRINT_FILE}")
        print(f"중심 지문 기록: {digest}")


def cmd_assign(args) -> int:
    base = args.base or active_base()
    dim = int(read_meta(base)["dim"])
    index = f"{base}/index/kmeans-k{args.k}"
    center_ids, centers, digest = centers_with_fingerprint(index)
    if centers.shape[1] != dim:
        raise SystemExit(f"중심 차원 {centers.shape[1]} != 메타 {dim} — 다른 모델의 색인입니다")
    work = Path(args.work)
    work.mkdir(parents=True, exist_ok=True)
    check_fingerprint(index, digest, work)
    if "base.tsv" not in existing_assignments(index):
        print("주의: assignments/base.tsv 가 없습니다 — 갤러리 대부분이 미배정 상태입니다 (색인 잡의 배정을 먼저 복사하세요)")

    if args.partition:
        partitions, total_missing = list(args.partition), len(args.partition)
    else:
        partitions, total_missing = missing_partitions(base, args.source, index, args.limit)
        if total_missing > len(partitions):
            print(f"주의: 배정 없는 파티션 {total_missing}개 중 최근 {len(partitions)}개만 처리합니다 (나머지는 다음 실행)")
    if not partitions:
        print(f"{args.source}: 배정할 새 파티션 없음")
        return 0
    shown = ", ".join(partitions[:5]) + ("…" if len(partitions) > 5 else "")
    print(f"대상 파티션 {len(partitions)}개: {shown}")

    assigned = 0
    for partition in partitions:
        lines = stream_cat(f"{base}/{args.source}/{partition}/vectors-*.tsv", allow_missing=True)
        ids, vectors = parse_vector_lines(lines, dim)
        if not ids:
            if args.allow_empty:
                print(f"  {partition}: 벡터 없음 — 건너뜀")
                continue
            raise SystemExit(f"{args.source}/{partition} 에 벡터가 없습니다")
        clusters = center_ids[nearest_center(vectors, centers)]
        name = assignment_name(args.source, partition)
        local = work / name
        local.write_text("".join(f"{pid}" + chr(9) + f"{c}" + chr(10) for pid, c in zip(ids, clusters)), encoding="utf-8")
        hdfs("-mkdir", "-p", f"{index}/assignments")
        hdfs("-put", "-f", str(local), f"{index}/assignments/{name}")  # 재실행은 덮어쓴다 (멱등)
        used = Counter(int(c) for c in clusters)
        print(f"  {partition}: {len(ids):,}개 · 무리 {len(used)}개 사용 → {name}")
        assigned += len(ids)
    print(f"합계 {assigned:,}개 배정 → {index}/assignments/")
    return 0


def _chain(iterables):
    for it in iterables:
        yield from it


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="서빙용 벡터 스냅샷·일일 클러스터 배정")
    ap.add_argument("--base", help="모델 버전 디렉터리 (기본: /embeddings/ACTIVE 가 가리키는 곳)")
    ap.add_argument("--work", default=str(Path.home() / "embedding-work" / "index"), help="로컬 작업 디렉터리")
    sub = ap.add_subparsers(dest="command", required=True)

    snap = sub.add_parser("snapshot", help="전체 벡터를 이진 npy 로 한 벌 더 만든다")
    snap.add_argument("--sources", nargs="+", default=list(DEFAULT_SOURCES))
    snap.add_argument("--dtype", choices=["float32", "float16"], default="float32")
    snap.set_defaults(func=cmd_snapshot)

    assign = sub.add_parser("assign", help="새 파티션 벡터에 무리 딱지를 붙인다")
    assign.add_argument("--partition", nargs="*", default=[],
                        help="예: dt=2026-09-10. 비우면 배정 파일이 없는 파티션을 찾아 처리한다 (하루 실패를 다음 실행이 주워간다)")
    assign.add_argument("--limit", type=int, default=30, help="자동 탐색 시 한 번에 처리할 최대 파티션 수 (최근 것 우선)")
    assign.add_argument("--source", default="shelter-daily")
    assign.add_argument("--k", type=int, default=256)
    assign.add_argument("--allow-empty", action="store_true", help="벡터가 없는 날은 정상 종료 (일일 DAG)")
    assign.set_defaults(func=cmd_assign)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
