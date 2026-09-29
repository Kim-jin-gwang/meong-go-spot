import datetime
import json
import urllib.parse

import pytest

import api


def test_normalize_key_unquotes_encoding_key_only() -> None:
    assert api._normalize_key("abc%2Bdef%3D") == "abc+def="
    assert api._normalize_key("abc+def=") == "abc+def=", "Decoding 키는 그대로 통과"


def test_gateway_error_raises_api_error() -> None:
    body = json.dumps(
        {"OpenAPI_ServiceResponse": {"cmmMsgHeader": {"returnAuthMsg": "SERVICE_KEY_IS_NOT_REGISTERED_ERROR"}}}
    )
    with pytest.raises(api.ApiError, match="SERVICE_KEY_IS_NOT_REGISTERED_ERROR"):
        api._raise_if_gateway_error(body)


@pytest.mark.parametrize("body", ["not json", json.dumps({"response": {"header": {"resultCode": "00"}}})])
def test_gateway_error_ignores_non_gateway_bodies(body: str) -> None:
    api._raise_if_gateway_error(body)  # 예외 없음


def test_fetch_page_builds_query_with_extra_and_normalizes_items(monkeypatch: pytest.MonkeyPatch) -> None:
    seen: dict[str, str] = {}

    def fake_request(url: str) -> dict:
        seen.update(urllib.parse.parse_qsl(urllib.parse.urlsplit(url).query))
        return {"totalCount": "1", "items": {"item": {"desertionNo": "X1"}}}  # 결과 1건은 dict로 온다

    monkeypatch.setattr(api, "_request", fake_request)
    records, total = api.fetch_page("KEY", 3, {"bgnde": "20240101", "endde": "20240131"})

    assert records == [{"desertionNo": "X1"}] and total == 1
    assert seen["pageNo"] == "3" and seen["numOfRows"] == str(api.PAGE_SIZE) and seen["_type"] == "json"
    assert seen["bgnde"] == "20240101" and seen["endde"] == "20240131"


def test_fetch_page_without_extra_omits_period_params(monkeypatch: pytest.MonkeyPatch) -> None:
    seen: dict[str, str] = {}
    monkeypatch.setattr(
        api, "_request", lambda url: seen.update(urllib.parse.parse_qsl(urllib.parse.urlsplit(url).query)) or {}
    )
    records, total = api.fetch_page("KEY", 1)
    assert (records, total) == ([], 0)
    assert "bgnde" not in seen and "endde" not in seen


def test_fetch_lost_all_stops_at_the_first_empty_page_not_at_total_count(monkeypatch: pytest.MonkeyPatch) -> None:
    # totalCount 는 303 이라 하지만 실제는 100 + 63 건 — 빈 페이지가 종료 조건이어야 한다 (2026-09-15 실측)
    pages = {1: [{"popfile": f"p{i}"} for i in range(100)], 2: [{"popfile": f"q{i}"} for i in range(63)], 3: []}
    calls: list[int] = []

    def fake_request(url: str) -> dict:
        page = int(dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(url).query))["pageNo"])
        calls.append(page)
        return {"totalCount": "303", "items": {"item": pages[page]} if pages[page] else ""}

    monkeypatch.setattr(api, "_request", fake_request)
    records, total = api.fetch_lost_all("KEY")
    assert len(records) == 163 and total == 303 and calls == [1, 2, 3]


def test_fetch_lost_all_gives_up_after_max_pages(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(api, "_request", lambda url: {"totalCount": "1", "items": {"item": {"popfile": "x"}}})
    with pytest.raises(RuntimeError, match="빈 페이지"):
        api.fetch_lost_all("KEY", max_pages=3)


def test_fetch_lost_page_uses_the_loss_endpoint_and_its_page_size(monkeypatch: pytest.MonkeyPatch) -> None:
    seen: dict[str, str] = {}

    def fake_request(url: str) -> dict:
        assert url.startswith(api.LOST_BASE_URL)
        seen.update(urllib.parse.parse_qsl(urllib.parse.urlsplit(url).query))
        return {"totalCount": "1", "items": {"item": {"popfile": "x"}}}

    monkeypatch.setattr(api, "_request", fake_request)
    assert api.fetch_lost_page("KEY", 2) == ([{"popfile": "x"}], 1)
    assert seen["numOfRows"] == str(api.LOST_PAGE_SIZE) and seen["pageNo"] == "2"


def test_month_slices_cut_on_calendar_months_and_clip_both_ends() -> None:
    slices = api.month_slices(datetime.date(2026, 3, 29), datetime.date(2026, 5, 2))
    assert slices == [("20260329", "20260331"), ("20260401", "20260430"), ("20260501", "20260502")]
    assert api.month_slices(datetime.date(2026, 12, 31), datetime.date(2026, 12, 31)) == [("20261231", "20261231")]
    assert api.month_slices(datetime.date(2026, 5, 2), datetime.date(2026, 5, 1)) == []


def test_resync_slices_end_the_day_before_the_default_window() -> None:
    today = datetime.date(2026, 9, 25)
    slices = api.resync_slices(today, 60)
    # 기본 창은 08-25~09-25 → 재조회는 07-27~08-24
    assert slices == [("20260727", "20260731"), ("20260801", "20260824")]
    assert api.resync_slices(today, api.DEFAULT_WINDOW_DAYS) == []
    assert api.resync_slices(today, 0) == []


def test_count_tolerance_matches_the_backfill_rule() -> None:
    assert api.count_tolerance(0) == 20
    assert api.count_tolerance(7318) == 36  # 0.5%
    assert api.count_tolerance(3000) == 20  # 바닥 20


def test_fetch_pages_passes_extra_to_every_page_and_sleeps_between_pages(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(api, "PAGE_SIZE", 2)
    sleeps: list[float] = []
    monkeypatch.setattr(api.time, "sleep", sleeps.append)
    calls: list[tuple[str, str]] = []

    def fake_request(url: str) -> dict:
        query = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(url).query))
        calls.append((query["bgnde"], query["pageNo"]))
        items = {"1": [{"desertionNo": "A1"}, {"desertionNo": "A2"}], "2": [{"desertionNo": "A3"}]}[query["pageNo"]]
        return {"totalCount": "3", "items": {"item": items}}

    monkeypatch.setattr(api, "_request", fake_request)
    records, total = api.fetch_pages("KEY", {"bgnde": "20260801", "endde": "20260824"}, interval_sec=0.2)
    assert [r["desertionNo"] for r in records] == ["A1", "A2", "A3"] and total == 3
    assert calls == [("20260801", "1"), ("20260801", "2")]
    assert sleeps == [0.2], "첫 쪽 앞에는 쉬지 않고 쪽 사이에만 쉰다"
