"""main.py — 기본 창 수집에 창 밖 재조회를 합쳐 증분·상태를 만드는 흐름."""

import datetime
import json
import sys
from pathlib import Path

import pytest

import api
import main


def test_merge_records_prefers_base_window_and_drops_duplicates() -> None:
    base = [{"desertionNo": "A", "updTm": "base"}, {"desertionNo": "B", "updTm": "base"}]
    extra = [{"desertionNo": "B", "updTm": "old"}, {"desertionNo": "C", "updTm": "old"}, {"updTm": "no-key"}]
    merged = main.merge_records(base, extra)
    assert [(r["desertionNo"], r["updTm"]) for r in merged] == [("A", "base"), ("B", "base"), ("C", "old")]


def test_resync_skips_only_the_slice_that_fails_or_drifts_too_far(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setattr(main.time, "sleep", lambda _seconds: None)
    today = datetime.date(2026, 9, 25)  # 재조회 90일: 06-27~06-30 · 07 · 08-01~08-24 세 슬라이스

    def fake_pages(_key, extra, interval_sec):
        if extra["bgnde"] == "20260627":
            raise RuntimeError("3회 재시도 실패")
        if extra["bgnde"] == "20260701":
            return [{"desertionNo": "J1"}], 100  # 허용치(20) 밖 — 부분 수집
        return [{"desertionNo": "A1"}, {"desertionNo": "A2"}], 3  # 허용치 안 — API 의 알려진 어긋남

    monkeypatch.setattr(api, "fetch_pages", fake_pages)
    records = main.resync_before_window("KEY", today, 90)
    assert [r["desertionNo"] for r in records] == ["A1", "A2"]
    err = capsys.readouterr().err
    assert "20260627~20260630 실패" in err and "20260701~20260731 건수 불일치" in err
    assert main.resync_before_window("KEY", today, 0) == []


def test_main_publishes_changes_found_outside_the_default_window(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("DATA_GO_KR_SERVICE_KEY", "KEY")
    monkeypatch.setattr(api, "fetch_all", lambda _key: ([{"desertionNo": "N1", "updTm": "t1"}], 1))
    seen: dict[str, int] = {}

    def fake_resync(_key, today, days):
        seen["days"] = days
        return [
            {"desertionNo": "OLD", "updTm": "t2"},  # 받았던 동물의 뒤늦은 변경 → 반영
            {"desertionNo": "N1", "updTm": "stale"},  # 기본 창에도 있음 → 기본 창 것 우선
            {"desertionNo": "ANCIENT", "updTm": "t0"},  # 일일 수집 전 접수 — 상태 파일에 없음 → 무시
        ]

    monkeypatch.setattr(main, "resync_before_window", fake_resync)
    state_path = tmp_path / "state.json"
    state_path.write_text(json.dumps({"OLD": "t1"}), encoding="utf-8")
    monkeypatch.setattr(sys, "argv", ["main.py", "--out", str(tmp_path / "out"), "--state", str(state_path)])

    assert main.main() == 0

    assert seen["days"] == main.DEFAULT_RESYNC_DAYS
    out_dir = next((tmp_path / "out").glob("dt=*"))
    delta = [json.loads(line) for line in (out_dir / "delta.jsonl").read_text(encoding="utf-8").splitlines()]
    assert [(r["desertionNo"], r["updTm"]) for r in delta] == [("N1", "t1"), ("OLD", "t2")]
    snapshot = (out_dir / "records.jsonl").read_text(encoding="utf-8").splitlines()
    assert len(snapshot) == 1, "records.jsonl 은 기본 창 스냅샷 그대로"
    assert not (out_dir / "resync.jsonl").exists(), "재조회 원문은 남기지 않는다 (하루 4만 건)"
    assert json.loads(state_path.read_text(encoding="utf-8")) == {"OLD": "t2", "N1": "t1"}


def test_main_resync_days_zero_skips_the_extra_fetch(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setenv("DATA_GO_KR_SERVICE_KEY", "KEY")
    monkeypatch.setattr(api, "fetch_all", lambda _key: ([], 0))
    monkeypatch.setattr(api, "fetch_pages", lambda *_a, **_k: pytest.fail("재조회를 호출하면 안 된다"))
    monkeypatch.setattr(
        sys, "argv", ["main.py", "--out", str(tmp_path / "out"), "--state", str(tmp_path / "s.json"), "--resync-days", "0"]
    )
    assert main.main() == 0


def test_known_only_keeps_records_the_state_file_has_seen() -> None:
    state = {"A": "t1"}
    records = [{"desertionNo": "A", "updTm": "t2"}, {"desertionNo": "B", "updTm": "t2"}, {"updTm": "t2"}]
    assert main.known_only(records, state) == [{"desertionNo": "A", "updTm": "t2"}]
