import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dags"))

import cluster_check as cluster_health  # noqa: E402 — 순수 모듈 (airflow 불필요)

REPORT = """Configured Capacity: 100
DFS Used%: 26.26%
Under replicated blocks: 490
Missing blocks: 0

-------------------------------------------------
Live datanodes (1):

Name: 172.26.9.186:9866 (bd-master)
DFS Used%: 46.46%

Dead datanodes (1):

Name: 172.26.3.162:9866 (bd-worker1)
"""

YARN = """Total Nodes:2
         Node-Id	     Node-State	Node-Http-Address	Number-of-Running-Containers
 bd-master:43637	        RUNNING	   bd-master:8042	                           0
bd-worker1:36055	       SHUTDOWN	  bd-worker1:8042	                           0
"""


def test_parse_report_and_yarn() -> None:
    dfs = cluster_health.parse_dfs_report(REPORT)
    assert dfs == {"live_datanodes": 1, "dfs_used_pct": 26.26, "under_replicated": 490}, "첫 DFS Used% 가 클러스터 전체"
    assert cluster_health.parse_yarn_nodes(YARN) == 1, "SHUTDOWN 은 세지 않는다"


def test_evaluate_flags_worker_down_and_disk() -> None:
    dfs = cluster_health.parse_dfs_report(REPORT)
    problems = cluster_health.evaluate(dfs, yarn_running=1, disk_used_pct=90.0)
    assert len(problems) == 3 and problems[0].startswith("HDFS 라이브 DataNode 1 < 2")
    assert cluster_health.evaluate({"live_datanodes": 2, "dfs_used_pct": 30.0, "under_replicated": 0}, 2, 12.0) == []


def test_status_message_shapes() -> None:
    dfs = {"live_datanodes": 2, "dfs_used_pct": 26.3, "under_replicated": 0}
    assert cluster_health.status_message(dfs, 2, 12.0, []).startswith(":green_heart:")
    warn = cluster_health.status_message(dfs, 1, 12.0, ["YARN RUNNING 노드 1 < 2"])
    assert warn.startswith(":warning:") and "YARN RUNNING 노드 1 < 2" in warn and "현재:" in warn


def test_parse_report_tolerates_leading_whitespace() -> None:
    report = "  DFS Used%: 41.5%" + chr(10) + "Live datanodes (2):" + chr(10)
    dfs = cluster_health.parse_dfs_report(report)
    assert dfs["dfs_used_pct"] == 41.5 and dfs["live_datanodes"] == 2


def test_run_logs_stderr_and_returns_stdout(monkeypatch, caplog) -> None:
    import subprocess

    class Proc:
        returncode = 1
        stdout = ""
        stderr = "hdfs: command failed"

    monkeypatch.setattr(subprocess, "run", lambda *a, **k: Proc())
    with caplog.at_level("WARNING"):
        out = cluster_health.run(["/opt/hadoop/bin/hdfs", "dfsadmin", "-report"])
    assert out == "" and "command failed" in caplog.text
    assert cluster_health.parse_dfs_report(out)["live_datanodes"] == 0, "빈 출력은 0 → 경보로 이어진다"

