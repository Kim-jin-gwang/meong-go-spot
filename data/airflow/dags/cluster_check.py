"""클러스터 상태 점검 로직 — airflow 없이 import 되는 순수 모듈 (CI 테스트 대상).

    HDFS 라이브 DataNode  < 2      → 경보 (2노드 클러스터)
    YARN RUNNING 노드    < 2      → 경보
    HDFS 사용률           > 80%    → 경고
    서버 2 루트 디스크    > 85%    → 경고
    airflow·kafka 서비스  inactive → 경보 (감시 모드만 — Airflow 안에서는 자기 자신을 볼 수 없다)

두 소비자가 있다:
- cluster_health.py DAG — 월~금 09:00 상태 한 줄(정상/경보). "아침 확인 습관" 용.
- --watch 감시 모드 — systemd 타이머(data/airflow/systemd/, 5분 간격)가 Airflow 밖에서 실행. 위반이 **새로 생기면 즉시**
  경보 한 번, 풀리면 복구 한 줄. 지속 중 반복 알림은 없다(사용자 결정 2026-09-09). 정상이면 침묵. 상태 파일(JSON) 하나로 억제한다.
  Airflow 밖인 이유: SequentialExecutor 는 22:30 파이프라인(최대 2시간)이 도는 동안 다른 태스크를 못 돌리고,
  Airflow 자체가 죽으면 Airflow 기반 경보는 아무 말도 못 한다 (2026-09-09, 사용자 요청 "장애 알림은 즉시").
"""

from __future__ import annotations

import argparse
import datetime
import json
import logging
import os
import re
import shutil
import subprocess
from pathlib import Path

import alerts  # 같은 디렉터리 (dags/) — 표준 라이브러리만 쓰는 발송 모듈

LOG = logging.getLogger(__name__)
HADOOP_ENV = {"JAVA_HOME": "/usr/lib/jvm/java-17-openjdk-amd64", "HADOOP_HOME": "/opt/hadoop"}
HDFS = "/opt/hadoop/bin/hdfs"
YARN = "/opt/hadoop/bin/yarn"
THRESHOLDS = {"min_datanodes": 2, "min_yarn_nodes": 2, "max_dfs_used_pct": 80.0, "max_disk_used_pct": 85.0}
SERVICES = ("airflow", "kafka")  # 감시 모드에서 systemctl is-active 로 확인 (hadoop-cluster 는 oneshot 이라 상태가 데몬과 무관)
KST = datetime.timezone(datetime.timedelta(hours=9))


def parse_dfs_report(text: str) -> dict:
    """`hdfs dfsadmin -report` 출력에서 라이브 DataNode 수·전체 DFS 사용률·복제 부족 블록을 뽑는다."""
    live = re.search(r"Live datanodes \((\d+)\)", text)
    used = re.search(r"^\s*DFS Used%:\s*([\d.]+)%", text, re.M)  # 첫 번째가 클러스터 전체 (선행 공백 허용)
    under = re.search(r"Under replicated blocks:\s*(\d+)", text)
    return {"live_datanodes": int(live.group(1)) if live else 0,
            "dfs_used_pct": float(used.group(1)) if used else 0.0,
            "under_replicated": int(under.group(1)) if under else 0}


def parse_yarn_nodes(text: str) -> int:
    """`yarn node -list` 출력에서 RUNNING 노드 수."""
    return sum(1 for line in text.splitlines() if re.search(r"\bRUNNING\b", line))


def evaluate(dfs: dict, yarn_running: int, disk_used_pct: float, thresholds: dict = THRESHOLDS,
             inactive_services: list[str] | tuple[str, ...] = ()) -> list[str]:
    """임계치 위반 목록 (비어 있으면 정상). 서비스 비활성이 맨 앞 — 원인일 가능성이 가장 높다."""
    problems = [f"서비스 비활성: {name} (`sudo systemctl restart {name}` 서버 2)" for name in inactive_services]
    if dfs["live_datanodes"] < thresholds["min_datanodes"]:
        problems.append(f"HDFS 라이브 DataNode {dfs['live_datanodes']} < {thresholds['min_datanodes']} — 워커 다운? (`systemctl status hadoop-worker` 서버 1)")
    if yarn_running < thresholds["min_yarn_nodes"]:
        problems.append(f"YARN RUNNING 노드 {yarn_running} < {thresholds['min_yarn_nodes']}")
    if dfs["dfs_used_pct"] > thresholds["max_dfs_used_pct"]:
        problems.append(f"HDFS 사용률 {dfs['dfs_used_pct']:.1f}% > {thresholds['max_dfs_used_pct']:.0f}%")
    if disk_used_pct > thresholds["max_disk_used_pct"]:
        problems.append(f"서버 2 루트 디스크 {disk_used_pct:.0f}% > {thresholds['max_disk_used_pct']:.0f}%")
    return problems


def summary_line(dfs: dict, yarn_running: int, disk_used_pct: float) -> str:
    return (f"DataNode {dfs['live_datanodes']} · YARN {yarn_running} · HDFS {dfs['dfs_used_pct']:.1f}% "
            f"· 디스크 {disk_used_pct:.0f}% · 복제부족 {dfs['under_replicated']:,}")


def alert_message(problems: list[str], summary: str, extra: list[str] = ()) -> str:
    return ":warning: **[DATA] 클러스터 점검 경보**\n" + "\n".join(f"- {p}" for p in [*problems, f"현재: {summary}", *extra])


def status_message(dfs: dict, yarn_running: int, disk_used_pct: float, problems: list[str]) -> str:
    summary = summary_line(dfs, yarn_running, disk_used_pct)
    if problems:
        return alert_message(problems, summary)
    return f":green_heart: [DATA] 클러스터 정상 — {summary}"


def run(cmd: list[str]) -> str:
    """명령 stdout 을 돌려준다. 실패(비정상 종료·stderr)는 로그로 남긴다 — 파싱은 빈 출력을 0 으로 읽어 경보로 이어진다."""
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120, env={**os.environ, **HADOOP_ENV})
    # yarn 은 정상일 때도 "INFO client....: Connecting to ResourceManager" 를 stderr 로 찍는다 — 5분마다 경고로 남기지 않게 INFO 줄은 뺀다
    noise = [line for line in proc.stderr.splitlines() if line.strip() and " INFO " not in line]
    if proc.returncode != 0 or noise:
        LOG.warning("%s 종료코드 %s stderr: %s", cmd[0], proc.returncode, "\n".join(noise or proc.stderr.splitlines())[-300:])
    return proc.stdout


def collect() -> tuple[dict, int, float]:
    """서버에서 실제 명령을 실행해 (dfs, yarn_running, disk_used_pct) 를 얻는다."""
    dfs = parse_dfs_report(run([HDFS, "dfsadmin", "-report"]))
    yarn_running = parse_yarn_nodes(run([YARN, "node", "-list"]))
    usage = shutil.disk_usage("/")
    return dfs, yarn_running, usage.used / usage.total * 100


# ── 감시 모드 (systemd 타이머) ─────────────────────────────────────────────────

def check_services(names: tuple[str, ...] = SERVICES) -> list[str]:
    """`systemctl is-active` 로 active 가 아닌 서비스 이름 목록."""
    proc = subprocess.run(["systemctl", "is-active", *names], capture_output=True, text=True, timeout=30)
    states = proc.stdout.split()
    if len(states) != len(names):  # 출력 형식이 예상과 다르면 전부 미확인으로 — 조용히 정상 처리하지 않는다
        return [f"{name}(상태 확인 불가)" for name in names]
    return [name for name, state in zip(names, states) if state != "active"]


def problem_key(text: str) -> str:
    """문제 문장에서 수치를 지운 비교 키 — 디스크 86%→87% 같은 흔들림을 '새 문제'로 보지 않게."""
    return "".join(ch for ch in text if not (ch.isdigit() or ch in ".,%"))


def decide(prev: dict, problems: list[str], now: datetime.datetime) -> tuple[str | None, dict]:
    """상태 전이 → (보낼 종류, 새 상태).

    종류: "alert" 새 문제(또는 문제 구성 변경) · "recovered" 해소 · None 침묵(정상 지속, 같은 문제 지속).
    같은 문제가 이어져도 반복 알림은 없다 — 사용자 결정(2026-09-09). 구성이 바뀌면(문제 추가·일부 해소) 다시 경보.
    prev/state: {"problems": [...], "since": iso|None, "last_posted": iso|None, "checked": iso}
    """
    raw = prev.get("problems")
    prev_problems = [str(p) for p in raw] if isinstance(raw, list) else []  # 손상된 상태 파일(정수·문자열)은 빈 목록으로
    state = {"problems": problems, "since": prev.get("since"), "last_posted": prev.get("last_posted"), "checked": now.isoformat()}
    if problems:
        if not prev_problems or state["since"] is None:
            state["since"] = now.isoformat()
        if [problem_key(p) for p in problems] == [problem_key(p) for p in prev_problems]:
            return None, state
        state["last_posted"] = now.isoformat()
        return "alert", state
    state["since"] = None
    if prev_problems:
        state["last_posted"] = now.isoformat()
        return "recovered", state
    return None, state


def _parse_iso(value) -> datetime.datetime | None:
    try:
        parsed = datetime.datetime.fromisoformat(value) if value else None
    except (TypeError, ValueError):
        return None
    if parsed is not None and parsed.tzinfo is None:  # 손으로 고친 naive 문자열은 UTC 로 — aware 인 now 와의 뺄셈 TypeError 방지
        parsed = parsed.replace(tzinfo=datetime.timezone.utc)
    return parsed


def _minutes_since(since_iso, now: datetime.datetime) -> str:
    since = _parse_iso(since_iso)
    return f"{(now - since).total_seconds() / 60:.0f}분" if since else "?"


def watch_message(kind: str | None, problems: list[str], summary: str, prev: dict, state: dict, now: datetime.datetime) -> str | None:
    if kind == "alert":
        since = _parse_iso(state.get("since"))
        first = f" (첫 감지 {since.astimezone(KST):%m-%d %H:%M} KST)" if since and since < now else ""  # 구성 변경 재경보 때만 붙는다
        return alert_message(problems, summary, [f"감지 {now.astimezone(KST):%m-%d %H:%M} KST{first} · 해소되면 복구 알림, 그 전엔 반복 없음"])
    if kind == "recovered":
        return f":green_heart: **[DATA] 클러스터 복구** — 이상 {_minutes_since(prev.get('since'), now)} 만에 정상 · {summary}"
    return None


def load_state(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def save_state(path: Path, state: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(path)


def watch(state_path: Path, now: datetime.datetime | None = None) -> str | None:
    """한 번 점검 → 필요하면 발송 → 상태 저장. 반환은 보낸 종류(None 이면 침묵)."""
    now = now or datetime.datetime.now(datetime.timezone.utc)
    prev = load_state(state_path)
    try:
        dfs, yarn_running, disk_used_pct = collect()
        problems = evaluate(dfs, yarn_running, disk_used_pct, inactive_services=check_services())
        summary = summary_line(dfs, yarn_running, disk_used_pct)
    except Exception as error:  # noqa: BLE001 — 점검 자체가 죽는 것도 알려야 하는 이상
        problems, summary = [f"점검 실행 실패: {type(error).__name__}: {str(error)[:200]}"], "?"
    kind, state = decide(prev, problems, now)
    text = watch_message(kind, problems, summary, prev, state, now)
    if text and not alerts.post(text):
        # 발송 실패(웹훅 없음·네트워크) 면 새 상태를 굳히지 않는다 — 굳히면 경보도 복구 알림도 영영 억제된다. 다음 5분 실행이 같은 전이를 다시 시도
        LOG.warning("발송 실패 — 상태 유지, 다음 실행에서 재시도")
        state = {**prev, "checked": now.isoformat()}
    save_state(state_path, state)
    LOG.info("cluster watch: %s (%d problems)", kind or "quiet", len(problems))
    if text:
        print(text)
    elif problems:
        print(f"억제 — {len(problems)}개 문제 지속, 발송 없음 ({summary})")
    else:
        print(f"정상 — 발송 없음 ({summary})")
    return kind


def main() -> int:
    ap = argparse.ArgumentParser(description="클러스터 점검 — 기본은 상태 출력, --watch 는 상태 파일 기반 즉시 경보")
    ap.add_argument("--watch", action="store_true", help="감시 모드: 새 문제 즉시 경보 한 번 · 복구 알림 · 지속 중 반복 없음")
    ap.add_argument("--state", type=Path, default=Path(os.environ.get("AIRFLOW_HOME", os.path.expanduser("~/airflow"))) / "cluster-watch-state.json")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    if args.watch:
        watch(args.state)
        return 0
    d, y, disk = collect()
    print(status_message(d, y, disk, evaluate(d, y, disk)))
    return 0


if __name__ == "__main__":  # 서버에서 수동 점검: python3 cluster_check.py / 감시 1회: python3 cluster_check.py --watch
    raise SystemExit(main())
