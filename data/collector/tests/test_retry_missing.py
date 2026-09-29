import json

import retry_missing


def test_select_retryable_filters_permanent_and_invalid() -> None:
    lines = [
        json.dumps({"entry": "A_1", "url": "http://x/a", "reason": "HTTP Error 404: Not Found"}),   # 영구 — 제외
        json.dumps({"entry": "B_1", "url": "http://x/b", "reason": "unknown-format"}),              # 영구 — 제외
        json.dumps({"entry": "C_1", "url": "http://x/c", "reason": "<urlopen error timed out>"}),   # 일시 — 포함
        json.dumps({"entry": "D_1", "url": "http://x/d", "reason": "[Errno 104] Connection reset by peer"}),
        json.dumps({"entry": "D_1", "url": "http://x/d2", "reason": "timed out"}),                  # 같은 엔트리 → 마지막 URL
        json.dumps({"entry": "E_1", "url": "file:///etc/passwd", "reason": "timed out"}),           # 비 http — 제외
        json.dumps({"entry": "", "url": "http://x/f", "reason": "timed out"}),                      # 엔트리 없음 — 제외
        "{broken json",
        json.dumps(["not", "dict"]),
    ]
    assert retry_missing.select_retryable(lines) == {"C_1": "http://x/c", "D_1": "http://x/d2"}


def test_is_retryable() -> None:
    assert retry_missing.is_retryable("<urlopen error timed out>")
    assert not retry_missing.is_retryable("HTTP Error 404: Not Found")
    assert not retry_missing.is_retryable("HTTP Error 410: Gone")


def test_latest_record_wins_when_entry_becomes_permanent() -> None:
    lines = [
        json.dumps({"entry": "A_1", "url": "http://x/a", "reason": "timed out"}),
        json.dumps({"entry": "A_1", "url": "http://x/a", "reason": "HTTP Error 404: Not Found"}),  # 나중 기록이 404 → 제외
        json.dumps({"entry": "B_1", "url": "http://x/b", "reason": "HTTP Error 404: Not Found"}),
        json.dumps({"entry": "B_1", "url": "http://x/b", "reason": "timed out"}),                  # 나중 기록이 일시 → 포함
    ]
    assert retry_missing.select_retryable(lines) == {"B_1": "http://x/b"}


def test_is_retryable_matches_wrapped_permanent_errors() -> None:
    assert not retry_missing.is_retryable("<urlopen error HTTP Error 404: Not Found>")
    assert not retry_missing.is_retryable("write-error: unknown-format")
    assert retry_missing.is_retryable("<urlopen error [Errno 104] Connection reset by peer>")


def test_null_or_missing_reason_is_treated_as_retryable() -> None:
    lines = [json.dumps({"entry": "N_1", "url": "http://x/n", "reason": None}), json.dumps({"entry": "M_1", "url": "http://x/m"})]
    assert retry_missing.select_retryable(lines) == {"N_1": "http://x/n", "M_1": "http://x/m"}

