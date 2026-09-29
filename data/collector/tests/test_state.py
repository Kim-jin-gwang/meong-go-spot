import json
from pathlib import Path

import state


def test_load_missing_file_returns_empty(tmp_path: Path) -> None:
    assert state.load(tmp_path / "none.json") == {}


def test_save_is_atomic_and_roundtrips(tmp_path: Path) -> None:
    path = tmp_path / "nested" / "state.json"
    state.save(path, {"A1": "2026-09-01 10:00:00"})
    assert json.loads(path.read_text(encoding="utf-8")) == {"A1": "2026-09-01 10:00:00"}
    assert not path.with_name(path.name + ".tmp").exists(), "임시 파일은 교체 후 남지 않아야 한다"


def test_diff_separates_new_changed_unchanged() -> None:
    current = {"A1": "t1", "A2": "t1"}
    records = [
        {"desertionNo": "A1", "updTm": "t1"},  # 변화 없음
        {"desertionNo": "A2", "updTm": "t2"},  # 갱신
        {"desertionNo": "A3", "updTm": "t1"},  # 신규
        {"updTm": "t9"},  # 키 없음 — 무시
    ]
    new, changed = state.diff(records, current)
    assert [r["desertionNo"] for r in new] == ["A3"]
    assert [r["desertionNo"] for r in changed] == ["A2"]


def test_apply_does_not_mutate_input() -> None:
    before = {"A1": "t1"}
    after = state.apply([{"desertionNo": "A2", "updTm": None}], before)
    assert before == {"A1": "t1"}
    assert after == {"A1": "t1", "A2": ""}, "updTm 결측은 빈 문자열로 정규화된다"
