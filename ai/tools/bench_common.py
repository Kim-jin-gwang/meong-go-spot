"""성능 측정 공용 유틸 — 타이머·백분위·결과 직렬화 (#105).

측정 스크립트(bench_pipeline / bench_model_sweep / bench_serve)가 공유한다.
app 모듈을 수정하지 않고 import 만 하기 위해 ai/ 루트를 sys.path 에 추가한다.
"""

from __future__ import annotations

import json
import os
import statistics
import sys
import time
from pathlib import Path

# 스크립트를 ai/tools 밖(예: 서버의 ~/bench/tools)에 복사해 돌릴 때는
# BENCH_AI_ROOT 로 app 패키지가 있는 ai/ 루트를 지정한다.
AI_ROOT = Path(os.environ.get("BENCH_AI_ROOT") or Path(__file__).resolve().parent.parent).expanduser()
if str(AI_ROOT) not in sys.path:
    sys.path.insert(0, str(AI_ROOT))


def percentile(values: list[float], p: float) -> float:
    """선형 보간 백분위 — 표본이 적어도(반복 5회) 동작한다."""
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    rank = (len(ordered) - 1) * p / 100.0
    low = int(rank)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (rank - low)


def summarize(values: list[float]) -> dict:
    return {
        "n": len(values),
        "mean": round(statistics.mean(values), 4),
        "p50": round(percentile(values, 50), 4),
        "p95": round(percentile(values, 95), 4),
        "min": round(min(values), 4),
        "max": round(max(values), 4),
    }


class StageTimer:
    """구간별 소요 시간 누적 기록기."""

    def __init__(self) -> None:
        self.records: dict[str, list[float]] = {}

    def measure(self, stage: str):
        return _Span(self, stage)

    def add(self, stage: str, seconds: float) -> None:
        self.records.setdefault(stage, []).append(seconds)

    def summary(self) -> dict:
        return {stage: summarize(values) for stage, values in self.records.items()}


class _Span:
    def __init__(self, timer: StageTimer, stage: str) -> None:
        self.timer = timer
        self.stage = stage

    def __enter__(self):
        self.start = time.perf_counter()
        return self

    def __exit__(self, *_exc) -> None:
        self.timer.add(self.stage, time.perf_counter() - self.start)


def mem_available_mb() -> float:
    """시스템 전체 가용 메모리(MB) — /proc/meminfo MemAvailable 기준.

    측정 대상은 Linux 서버(서버 2)다. /proc/meminfo 가 없는 환경(macOS·Windows)에서는
    측정을 막지 않도록 NaN 을 반환한다 — 결과의 RAM 항목만 무효가 된다.
    """
    meminfo = Path("/proc/meminfo")
    if not meminfo.exists():
        return float("nan")
    for line in meminfo.read_text().splitlines():
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) / 1024.0
    return float("nan")


class MemHeadroomSampler:
    """백그라운드 스레드로 MemAvailable 을 주기 샘플링 — 작업 중 RAM 여유분 최소값을 기록."""

    def __init__(self, interval_s: float = 0.2) -> None:
        import threading

        self.interval_s = interval_s
        self.min_available_mb = float("inf")
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._loop, daemon=True)

    def _sample(self) -> None:
        value = mem_available_mb()
        if value == value:  # NaN(비 Linux) 샘플은 버린다
            self.min_available_mb = min(self.min_available_mb, value)

    def _loop(self) -> None:
        while not self._stop.is_set():
            self._sample()
            self._stop.wait(self.interval_s)

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *_exc) -> None:
        self._stop.set()
        self._thread.join()
        self._sample()
        if self.min_available_mb == float("inf"):  # 샘플이 하나도 없던 환경
            self.min_available_mb = float("nan")


def peak_rss_mb() -> float:
    """프로세스 시작 이후 최대 RSS(MB) — 리눅스 getrusage 기준."""
    import resource

    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0


def list_bench_images(image_dir: Path, limit: int | None = None) -> list[Path]:
    paths = sorted(
        p for p in Path(image_dir).iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
    )
    if not paths:
        raise SystemExit(f"벤치 이미지가 없습니다: {image_dir}")
    return paths[:limit] if limit else paths


def write_json(path: Path | str, payload: dict) -> None:
    Path(path).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"결과 저장 → {path}")
