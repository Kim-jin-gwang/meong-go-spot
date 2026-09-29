"""색인 도구의 HDFS 경로 — hdfs 호출을 대역으로 바꿔 CLI 흐름을 검증한다.

순수 함수는 test_index_tools.py 가 덮는다. 여기서는 "어떤 명령을 어떤 순서로 부르고 무엇을 쓰는가"를 본다.
2026-09-10 결함 셋(배정 경로 누락·하루 실패 영구 누락·색인 재구축 미감지)의 회귀를 막는 것이 목적이다.
"""

import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import index_tools  # noqa: E402

TAB = chr(9)
NL = chr(10)


class FakeHdfs:
    """`hdfs(*args)` 대역 — 명령에 포함된 문자열로 응답을 고르고, 모든 호출을 기록한다."""

    def __init__(self, responses: dict[str, str] | None = None) -> None:
        self.responses = responses or {}
        self.calls: list[str] = []

    def __call__(self, *args: str, check: bool = True):
        joined = " ".join(args)
        self.calls.append(joined)
        for pattern, out in self.responses.items():
            if pattern in joined:
                return SimpleNamespace(stdout=out, returncode=0, stderr="")
        return SimpleNamespace(stdout="", returncode=0, stderr="")


def listing(paths: list[str]) -> str:
    """`hdfs dfs -ls` 출력 흉내 — 마지막 칸이 경로다."""
    return NL.join(f"drwxr-xr-x   - ubuntu supergroup          0 2026-09-10 04:00 {p}" for p in paths)


def test_missing_partitions_takes_newest_first_and_reports_total(monkeypatch) -> None:
    fake = FakeHdfs({
        "-ls /b/shelter-daily": listing([f"/b/shelter-daily/dt=2026-09-0{d}" for d in (7, 8, 9)]),
        "-ls /idx/assignments": listing(["/idx/assignments/base.tsv", "/idx/assignments/shelter-daily-dt-2026-09-07.tsv"]),
    })
    monkeypatch.setattr(index_tools, "hdfs", fake)
    picked, total = index_tools.missing_partitions("/b", "shelter-daily", "/idx", limit=30)
    assert picked == ["dt=2026-09-08", "dt=2026-09-09"] and total == 2, "배정 파일이 없는 파티션만"
    picked, total = index_tools.missing_partitions("/b", "shelter-daily", "/idx", limit=1)
    assert picked == ["dt=2026-09-09"] and total == 2, "limit 이면 최근 것부터 (최근 입소가 중요하다)"


def test_assignment_name_replaces_equals() -> None:
    assert index_tools.assignment_name("shelter-daily", "dt=2026-09-10") == "shelter-daily-dt-2026-09-10.tsv"
    assert index_tools.assignment_name("shelter-backfill", "yyyymm=202609") == "shelter-backfill-yyyymm-202609.tsv"


def test_check_fingerprint_refuses_when_centers_changed(monkeypatch, tmp_path) -> None:
    fake = FakeHdfs({"-cat /idx/" + index_tools.FINGERPRINT_FILE: "aaaaaaaaaaaaaaaa" + NL})
    monkeypatch.setattr(index_tools, "hdfs", fake)
    with pytest.raises(SystemExit) as error:
        index_tools.check_fingerprint("/idx", "bbbbbbbbbbbbbbbb", tmp_path)
    assert "중심이 바뀌었습니다" in str(error.value), "낡은 배정과 섞이기 전에 막아야 한다"


def test_check_fingerprint_records_when_absent(monkeypatch, tmp_path) -> None:
    fake = FakeHdfs()
    monkeypatch.setattr(index_tools, "hdfs", fake)
    index_tools.check_fingerprint("/idx", "abc1234567890def", tmp_path)
    assert (tmp_path / index_tools.FINGERPRINT_FILE).read_text(encoding="utf-8").strip() == "abc1234567890def"
    assert any("-put" in call for call in fake.calls), "지문을 HDFS 에 올려야 다음 실행이 비교할 수 있다"


def assign_args(tmp_path, **overrides):
    defaults = {"base": "/b", "work": str(tmp_path), "k": 4, "source": "shelter-daily",
                "partition": ["dt=2026-09-10"], "allow_empty": True, "limit": 30}
    return SimpleNamespace(**{**defaults, **overrides})


def assign_fake(extra: dict[str, str] | None = None) -> FakeHdfs:
    responses = {
        "-cat /b/_meta.json": '{"dim": 2, "model_id": "m", "model_version": "v", "normalized": true}',
        "-cat /b/index/kmeans-k4/centers.tsv": "0" + TAB + "1.0,0.0" + NL + "1" + TAB + "0.0,1.0" + NL,
        "-ls /b/index/kmeans-k4/assignments": listing(["/b/index/kmeans-k4/assignments/base.tsv"]),
    }
    responses.update(extra or {})
    return FakeHdfs(responses)


def test_cmd_assign_writes_nearest_cluster_rows(monkeypatch, tmp_path, capsys) -> None:
    fake = assign_fake()
    monkeypatch.setattr(index_tools, "hdfs", fake)
    monkeypatch.setattr(index_tools, "stream_cat",
                        lambda path, allow_missing=False: iter(["A_1" + TAB + "0.99,0.01" + NL,
                                                                "A_2" + TAB + "0.02,0.98" + NL]))
    assert index_tools.cmd_assign(assign_args(tmp_path)) == 0
    written = (tmp_path / "shelter-daily-dt-2026-09-10.tsv").read_text(encoding="utf-8")
    assert written == "A_1" + TAB + "0" + NL + "A_2" + TAB + "1" + NL
    uploads = [c for c in fake.calls if "-put" in c]
    assert any("assignments/shelter-daily-dt-2026-09-10.tsv" in c for c in uploads)
    assert "주의" not in capsys.readouterr().out, "base.tsv 가 있으면 경고할 것이 없다"


def test_cmd_assign_warns_when_base_missing(monkeypatch, tmp_path, capsys) -> None:
    fake = assign_fake({"-ls /b/index/kmeans-k4/assignments": ""})
    monkeypatch.setattr(index_tools, "hdfs", fake)
    monkeypatch.setattr(index_tools, "stream_cat", lambda path, allow_missing=False: iter(["A_1" + TAB + "1.0,0.0" + NL]))
    index_tools.cmd_assign(assign_args(tmp_path))
    assert "base.tsv 가 없습니다" in capsys.readouterr().out, "갤러리 대부분 미배정을 조용히 넘기지 않는다"


def test_cmd_assign_skips_empty_partition(monkeypatch, tmp_path, capsys) -> None:
    fake = assign_fake()
    monkeypatch.setattr(index_tools, "hdfs", fake)
    monkeypatch.setattr(index_tools, "stream_cat", lambda path, allow_missing=False: iter([]))
    assert index_tools.cmd_assign(assign_args(tmp_path)) == 0
    assert "벡터 없음" in capsys.readouterr().out
    assert not any("-put" in c and ".tsv" in c for c in fake.calls), "빈 날은 파일을 올리지 않는다"


def test_cmd_assign_discovers_partitions_when_none_given(monkeypatch, tmp_path, capsys) -> None:
    # 하루 실패를 다음 실행이 주워가는 경로 — --partition 없이 부르면 배정 없는 파티션을 찾는다
    fake = assign_fake({
        "-ls /b/shelter-daily": listing([f"/b/shelter-daily/dt=2026-09-0{d}" for d in (8, 9)]),
        "-ls /b/index/kmeans-k4/assignments": listing(["/b/index/kmeans-k4/assignments/base.tsv"]),
    })
    monkeypatch.setattr(index_tools, "hdfs", fake)
    monkeypatch.setattr(index_tools, "stream_cat", lambda path, allow_missing=False: iter(["A_1" + TAB + "1.0,0.0" + NL]))
    assert index_tools.cmd_assign(assign_args(tmp_path, partition=[])) == 0
    out = capsys.readouterr().out
    assert "대상 파티션 2개" in out and "dt=2026-09-08" in out and "dt=2026-09-09" in out
    assert (tmp_path / "shelter-daily-dt-2026-09-08.tsv").is_file()
    assert (tmp_path / "shelter-daily-dt-2026-09-09.tsv").is_file()


def test_cmd_assign_reports_nothing_to_do(monkeypatch, tmp_path, capsys) -> None:
    fake = assign_fake({
        "-ls /b/shelter-daily": listing(["/b/shelter-daily/dt=2026-09-09"]),
        "-ls /b/index/kmeans-k4/assignments": listing(["/b/index/kmeans-k4/assignments/base.tsv",
                                                       "/b/index/kmeans-k4/assignments/shelter-daily-dt-2026-09-09.tsv"]),
    })
    monkeypatch.setattr(index_tools, "hdfs", fake)
    assert index_tools.cmd_assign(assign_args(tmp_path, partition=[])) == 0
    assert "배정할 새 파티션 없음" in capsys.readouterr().out
