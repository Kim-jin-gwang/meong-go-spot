"""hadoop jar 실행 로그에서 잡별 YARN 카운터를 뽑아 metrics JSON으로 만든다.

실험 하네스가 캡처한 드라이버 stdout(잡 완료 시 카운터 전체가 출력됨)을 입력으로 받는다.
사용법: python parse_job_counters.py --log run.log --label 2nodes --out metrics.json
(같은 out 파일에 여러 라벨을 누적 — 실험 단계별로 호출)
"""

import argparse
import json
import re
from pathlib import Path

WANTED = {
    "Launched map tasks": "map_tasks",
    "Launched reduce tasks": "reduce_tasks",
    "Data-local map tasks": "data_local_maps",
    "Rack-local map tasks": "rack_local_maps",
    "Map input records": "map_input_records",
    "Map output records": "map_output_records",
    "Map output bytes": "map_output_bytes",
    "Combine input records": "combine_input_records",
    "Combine output records": "combine_output_records",
    "Reduce shuffle bytes": "shuffle_bytes",
    "Reduce input records": "reduce_input_records",
    "CPU time spent (ms)": "cpu_ms",
    "GC time elapsed (ms)": "gc_ms",
    "Total time spent by all map tasks (ms)": "all_maps_ms",
    "Total time spent by all reduce tasks (ms)": "all_reduces_ms",
}
HDFS_READ = re.compile(r"HDFS: Number of bytes read=(\d+)")
HDFS_WRITTEN = re.compile(r"HDFS: Number of bytes written=(\d+)")
JOB_START = re.compile(r"Running job: (job_\S+)")


def parse_jobs(text: str) -> list[dict]:
    jobs: list[dict] = []
    current: dict | None = None
    for line in text.splitlines():
        started = JOB_START.search(line)
        if started:
            current = {"job_id": started.group(1)}
            jobs.append(current)
            continue
        if current is None:
            continue
        for read in HDFS_READ.finditer(line):
            current["hdfs_read_bytes"] = int(read.group(1))
        for written in HDFS_WRITTEN.finditer(line):
            current["hdfs_written_bytes"] = int(written.group(1))
        stripped = line.strip()
        for counter_name, key in WANTED.items():
            prefix = counter_name + "="
            if stripped.startswith(prefix):
                current[key] = int(stripped[len(prefix):])
    return jobs


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--wall-seconds", type=float, default=None, help="하네스가 잰 전체 소요")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    jobs = parse_jobs(Path(args.log).read_text(encoding="utf-8", errors="replace"))

    out_path = Path(args.out)
    metrics = json.loads(out_path.read_text(encoding="utf-8")) if out_path.exists() else {}
    metrics[args.label] = {"wall_seconds": args.wall_seconds, "jobs": jobs}
    out_path.write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{args.label}: 잡 {len(jobs)}개 파싱 → {out_path}")


if __name__ == "__main__":
    main()
