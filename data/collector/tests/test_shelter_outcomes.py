import datetime as dt
import json

import shelter_outcomes as outcomes


def record(happen: str, state: str, no: str = "", start: str | None = None, end: str | None = None) -> dict:
    item = {"happenDt": happen, "processState": state, "desertionNo": no or f"d-{happen}-{state}"}
    if start:
        item["noticeSdt"] = start
    if end:
        item["noticeEdt"] = end
    return item


def test_window_excludes_the_last_sixty_days_and_covers_three_years() -> None:
    window = outcomes.Window.ending(dt.date(2026, 9, 15), years=3, exclude_days=60)
    assert window.end == dt.date(2026, 7, 17)
    assert window.start == dt.date(2023, 7, 17)
    assert window.months()[0] == "202307"
    assert window.months()[-1] == "202607"
    assert len(window.months()) == 37


def test_aggregate_counts_only_closed_records_inside_the_window() -> None:
    window = outcomes.Window(dt.date(2024, 1, 1), dt.date(2024, 12, 31))
    result = outcomes.aggregate(
        [
            record("20240301", "종료(반환)", start="20240302", end="20240312"),
            record("20240302", "종료(입양)", start="20240303", end="20240313"),
            record("20240303", "종료(자연사)"),
            record("20240304", "종료(안락사)"),
            record("20240305", "보호중"),  # 아직 종결 아님 — 분모에 넣지 않는다
            record("20231231", "종료(반환)"),  # 창 밖
            record("2024-06-01", "종료(반환)", no="dash-date"),  # 하이픈 날짜도 읽는다
            {"processState": "종료(반환)"},  # 발견일 없음
        ],
        window,
        months=["202401", "202412"],
    )
    payload = result.payload(dt.datetime(2026, 9, 15, 23, 40, tzinfo=outcomes.KST))

    assert payload["closedCount"] == 5
    assert payload["returnCount"] == 2
    assert payload["adoptionCount"] == 1
    assert payload["returnRate"] == 0.4
    assert payload["adoptionRate"] == 0.2
    assert payload["averageNoticeDays"] == 10.0
    assert payload["noticeSampleCount"] == 2
    assert payload["byYear"] == {"2024": {"closed": 5, "returnRate": 0.4}}
    assert payload["skipped"] == {"NOT_CLOSED": 1, "NO_HAPPEN_DATE": 1, "OUT_OF_WINDOW": 1}
    assert payload["coverageStartMonth"] == "202401" and payload["coverageEndMonth"] == "202412"
    assert payload["computedAt"] == "2026-09-15T14:40:00Z"
    json.dumps(payload)  # jsonb 로 들어갈 수 있어야 한다


def test_duplicate_desertion_numbers_are_counted_once() -> None:
    window = outcomes.Window(dt.date(2024, 1, 1), dt.date(2024, 12, 31))
    result = outcomes.aggregate(
        [record("20240301", "종료(반환)", no="same"), record("20240301", "종료(반환)", no="same")], window
    )
    assert result.closed == 1
    assert result.skipped["DUPLICATE"] == 1


def test_empty_input_yields_zero_rates_not_division_errors() -> None:
    payload = outcomes.aggregate([], outcomes.Window(dt.date(2024, 1, 1), dt.date(2024, 1, 31))).payload(
        dt.datetime.now(dt.timezone.utc)
    )
    assert payload["closedCount"] == 0
    assert payload["returnRate"] == 0.0
    assert payload["averageNoticeDays"] is None


def test_webhdfs_urls_keep_the_partition_equals_sign() -> None:
    url = outcomes._webhdfs_url("http://bd-master:9870", "/data/shelter/backfill/yyyymm=202407/records.jsonl", "OPEN", "ubuntu")
    assert "yyyymm=202407" in url
    assert url.endswith("?op=OPEN&user.name=ubuntu")


def test_upsert_writes_the_national_row(monkeypatch) -> None:
    executed = []

    class Cursor:
        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def execute(self, sql, params):
            executed.append((sql, params))

    class Connection:
        committed = False

        def cursor(self):
            return Cursor()

        def commit(self):
            self.committed = True

    connection = Connection()
    outcomes.upsert(connection, {"closedCount": 1}, dt.datetime(2026, 9, 15, tzinfo=dt.timezone.utc))

    sql, params = executed[0]
    assert "ON CONFLICT (stat_key, region_code)" in sql
    assert params[0] == "shelter_outcomes" and params[1] == "00000"
    assert json.loads(params[2]) == {"closedCount": 1}
    assert connection.committed
