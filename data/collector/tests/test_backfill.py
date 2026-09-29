import json
from pathlib import Path

import backfill


def test_month_range_crosses_year_boundary_inclusive() -> None:
    assert list(backfill.month_range("2025-11", "2026-02")) == [(2025, 11), (2025, 12), (2026, 1), (2026, 2)]
    assert list(backfill.month_range("2024-05", "2024-05")) == [(2024, 5)]
    assert list(backfill.month_range("2026-09", "2008-01")) == [], "역전 범위는 빈 시퀀스 (main이 사전 검증)"


def test_month_bounds_handles_leap_year_and_december() -> None:
    assert backfill.month_bounds(2024, 2) == ("20240201", "20240229")
    assert backfill.month_bounds(2023, 2) == ("20230201", "20230228")
    assert backfill.month_bounds(2025, 12) == ("20251201", "20251231")


def test_save_json_atomic_and_load_default(tmp_path: Path) -> None:
    path = tmp_path / "m" / "metrics.json"
    assert backfill.load_json(path, {"x": 1}) == {"x": 1}
    backfill.save_json(path, {"months": {}})
    assert json.loads(path.read_text(encoding="utf-8")) == {"months": {}}
    assert not path.with_name(path.name + ".tmp").exists()


def _metrics() -> dict:
    return {"quality_by_year": {}, "state_by_year": {}, "return_days_hist": {}}


def test_profile_counts_missing_fields_and_states() -> None:
    metrics = _metrics()
    records = [
        {"popfile1": "", "updTm": None, "kindCd": "믹스견", "orgNm": "서울", "processState": "보호중"},
        {"popfile1": "u", "updTm": "2024-01-05 10:00:00", "kindCd": "", "orgNm": "", "processState": "종료(입양)"},
    ]
    backfill.profile(records, "2024", metrics)
    q = metrics["quality_by_year"]["2024"]
    assert (q["records"], q["no_image"], q["no_updtm"], q["no_kind"], q["no_region"]) == (2, 1, 1, 1, 1)
    assert metrics["state_by_year"]["2024"] == {"보호중": 1, "종료(입양)": 1}


def test_profile_golden_time_buckets_and_guards() -> None:
    metrics = _metrics()
    records = [
        {"processState": "종료(반환)", "happenDt": "20240101", "updTm": "2024-01-03 09:00:00"},  # 2일 → 0-3
        {"processState": "종료(반환)", "happenDt": "20240101", "updTm": "2024-01-20 09:00:00"},  # 19일 → 15-30
        {"processState": "종료(반환)", "happenDt": "20240101", "updTm": "2024-03-01 09:00:00"},  # 60일 → 31+
        {"processState": "종료(반환)", "happenDt": "20240110", "updTm": "2024-01-01 09:00:00"},  # 음수 → 제외
        {"processState": "종료(반환)", "happenDt": "bad", "updTm": "2024-01-01 09:00:00"},  # 파싱 실패 → 제외
        {"processState": "종료(입양)", "happenDt": "20240101", "updTm": "2024-01-02 09:00:00"},  # 반환 아님
    ]
    backfill.profile(records, "2024", metrics)
    assert metrics["return_days_hist"] == {"0-3": 1, "15-30": 1, "31+": 1}
