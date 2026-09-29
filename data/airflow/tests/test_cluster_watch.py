"""감시 모드(--watch) — 상태 전이·억제·복구 알림. cluster_check 순수 함수만 쓰므로 airflow 불필요."""

import datetime
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dags"))

import alerts  # noqa: E402
import cluster_check  # noqa: E402

T0 = datetime.datetime(2026, 9, 9, 3, 0, tzinfo=datetime.timezone.utc)
HEALTHY = ({"live_datanodes": 2, "dfs_used_pct": 30.0, "under_replicated": 0}, 2, 40.0)
WORKER_DOWN = ({"live_datanodes": 1, "dfs_used_pct": 30.0, "under_replicated": 500}, 1, 40.0)


def minutes(n: int) -> datetime.datetime:
    return T0 + datetime.timedelta(minutes=n)


def test_inactive_services_come_first() -> None:
    problems = cluster_check.evaluate(*WORKER_DOWN, inactive_services=["kafka"])
    assert problems[0].startswith("서비스 비활성: kafka") and len(problems) == 3


def test_check_services_parses_systemctl(monkeypatch) -> None:
    class Proc:
        def __init__(self, out: str) -> None:
            self.stdout, self.returncode, self.stderr = out, 3, ""

    monkeypatch.setattr(subprocess, "run", lambda *a, **k: Proc("active" + chr(10) + "inactive" + chr(10)))
    assert cluster_check.check_services(("airflow", "kafka")) == ["kafka"]
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: Proc(""))
    assert cluster_check.check_services(("airflow", "kafka")) == ["airflow(상태 확인 불가)", "kafka(상태 확인 불가)"], "출력 이상은 정상으로 넘기지 않는다"


def test_problem_key_ignores_numbers() -> None:
    assert cluster_check.problem_key("서버 2 루트 디스크 86% > 85%") == cluster_check.problem_key("서버 2 루트 디스크 87% > 85%")
    assert cluster_check.problem_key("YARN RUNNING 노드 1 < 2") != cluster_check.problem_key("HDFS 사용률 81.0% > 80%")


def test_decide_alert_once_then_silent_until_recover() -> None:
    kind, state = cluster_check.decide({}, [], T0)
    assert kind is None and state["problems"] == [] and state["since"] is None, "정상→정상은 침묵"

    kind, state = cluster_check.decide(state, ["디스크 86% > 85%"], minutes(5))
    assert kind == "alert" and state["since"] == minutes(5).isoformat() and state["last_posted"] == minutes(5).isoformat()

    kind, state = cluster_check.decide(state, ["디스크 87% > 85%"], minutes(10))
    assert kind is None, "같은 문제(수치만 변함)는 다시 보내지 않는다"
    assert state["since"] == minutes(5).isoformat() and state["last_posted"] == minutes(5).isoformat()

    kind, state = cluster_check.decide(state, ["디스크 88% > 85%"], minutes(600))
    assert kind is None and state["last_posted"] == minutes(5).isoformat(), "10시간 지속돼도 반복 알림 없음 (사용자 결정)"

    kind, state = cluster_check.decide(state, ["디스크 88% > 85%", "YARN RUNNING 노드 1 < 2"], minutes(70))
    assert kind == "alert", "문제 구성이 바뀌면 즉시 다시 경보"

    kind, state = cluster_check.decide(state, [], minutes(80))
    assert kind == "recovered" and state["problems"] == [] and state["since"] is None

    kind, state = cluster_check.decide(state, [], minutes(85))
    assert kind is None


def test_decide_tolerates_corrupt_state() -> None:
    for bad in ("not-a-list", 123, True, {"a": 1}):
        kind, state = cluster_check.decide({"problems": bad, "last_posted": "garbage"}, ["x 1"], T0)
        assert kind == "alert" and state["since"] == T0.isoformat(), f"problems={bad!r}"


def test_decide_accepts_naive_timestamps() -> None:
    prev = {"problems": ["x 1"], "since": "2026-09-09T02:00:00", "last_posted": "2026-09-09T02:00:00"}  # tz 없는 손편집 값
    kind, state = cluster_check.decide(prev, ["x 2", "y 1"], T0)  # 구성 변경 → 재경보, since(02:00) 유지
    assert kind == "alert"
    assert "첫 감지 09-09 11:00 KST" in cluster_check.watch_message(kind, ["x 2", "y 1"], "s", prev, state, T0)
    kind, state = cluster_check.decide(prev, [], T0)
    assert kind == "recovered" and "60분 만에 정상" in cluster_check.watch_message(kind, [], "s", prev, state, T0)


def test_watch_keeps_previous_state_when_post_fails(monkeypatch, tmp_path) -> None:
    delivered: list[str] = []
    ok = {"value": False}
    monkeypatch.setattr(alerts, "post", lambda text, url=None: (delivered.append(text) or True) if ok["value"] else False)
    monkeypatch.setattr(cluster_check, "check_services", lambda names=None: [])
    state_file = tmp_path / "state.json"

    monkeypatch.setattr(cluster_check, "collect", lambda: WORKER_DOWN)
    cluster_check.watch(state_file, T0)  # 발송 실패
    assert cluster_check.load_state(state_file).get("problems") in (None, []), "실패한 경보는 상태에 굳히지 않는다"

    cluster_check.watch(state_file, minutes(5))  # 여전히 실패 → 다시 alert 시도, 여전히 굳히지 않음
    assert delivered == [] and cluster_check.load_state(state_file).get("problems") in (None, [])

    ok["value"] = True
    assert cluster_check.watch(state_file, minutes(10)) == "alert", "웹훅이 살아나면 억제 없이 바로 경보"
    assert len(delivered) == 1 and cluster_check.load_state(state_file)["problems"]

    monkeypatch.setattr(cluster_check, "collect", lambda: HEALTHY)
    ok["value"] = False
    cluster_check.watch(state_file, minutes(15))  # 복구 발송 실패 → 문제 상태 유지
    assert cluster_check.load_state(state_file)["problems"], "복구 알림이 실패하면 다음 실행에서 다시 보내야 한다"
    ok["value"] = True
    assert cluster_check.watch(state_file, minutes(20)) == "recovered" and delivered[-1].startswith(":green_heart:")


def test_watch_end_to_end(monkeypatch, tmp_path) -> None:
    posted: list[str] = []
    monkeypatch.setattr(alerts, "post", lambda text, url=None: posted.append(text) or True)
    monkeypatch.setattr(cluster_check, "check_services", lambda names=None: [])
    state_file = tmp_path / "state.json"

    monkeypatch.setattr(cluster_check, "collect", lambda: HEALTHY)
    assert cluster_check.watch(state_file, T0) is None and posted == [] and state_file.exists()

    monkeypatch.setattr(cluster_check, "collect", lambda: WORKER_DOWN)
    assert cluster_check.watch(state_file, minutes(5)) == "alert"
    assert posted[-1].startswith(":warning:") and "DataNode 1 < 2" in posted[-1] and "반복 없음" in posted[-1]

    assert cluster_check.watch(state_file, minutes(10)) is None and len(posted) == 1, "지속 중 억제"
    assert cluster_check.watch(state_file, minutes(70)) is None and len(posted) == 1, "1시간 넘어도 반복 알림 없음"

    monkeypatch.setattr(cluster_check, "collect", lambda: HEALTHY)
    assert cluster_check.watch(state_file, minutes(75)) == "recovered"
    assert posted[-1].startswith(":green_heart:") and "70분 만에 정상" in posted[-1]


def test_save_state_creates_missing_directory(tmp_path) -> None:
    path = tmp_path / "sub" / "dir" / "state.json"
    cluster_check.save_state(path, {"problems": []})
    assert cluster_check.load_state(path) == {"problems": []} and not path.with_suffix(".json.tmp").exists()


def test_watch_reports_check_failure_as_problem(monkeypatch, tmp_path) -> None:
    posted: list[str] = []
    monkeypatch.setattr(alerts, "post", lambda text, url=None: posted.append(text) or True)

    def boom() -> tuple:
        raise subprocess.TimeoutExpired("hdfs", 120)

    monkeypatch.setattr(cluster_check, "collect", boom)
    assert cluster_check.watch(tmp_path / "s.json", T0) == "alert"
    assert "점검 실행 실패: TimeoutExpired" in posted[-1]
