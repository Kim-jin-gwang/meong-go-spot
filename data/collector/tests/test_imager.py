import json

import imager


def test_detect_extension_by_magic_bytes() -> None:
    assert imager.detect_extension(b"\xff\xd8\xff\xe0rest") == "jpg"
    assert imager.detect_extension(b"\x89PNG\r\n\x1a\nrest") == "png"
    assert imager.detect_extension(b"GIF89a") is None
    assert imager.detect_extension(b"") is None


def _msg(**record) -> bytes:
    return json.dumps(record).encode()


def test_collect_jobs_builds_entries_and_keeps_last_update() -> None:
    jobs, skipped = imager.collect_jobs(
        [
            _msg(desertionNo="A1", popfile1="http://x/a1.jpg", popfile2="https://x/a1b.jpg"),
            _msg(desertionNo="A1", popfile1="http://x/a1-new.jpg"),  # 같은 개체 갱신 → 마지막 것
        ]
    )
    assert skipped == 0
    assert jobs == {"A1_1": "http://x/a1-new.jpg", "A1_2": "https://x/a1b.jpg"}


def test_collect_jobs_skips_poison_and_unsafe_inputs() -> None:
    jobs, skipped = imager.collect_jobs(
        [
            b"{not json",  # 손상 메시지
            _msg(),  # desertionNo 없음
            json.dumps(["list", "not", "dict"]).encode(),  # dict 아님
            _msg(desertionNo="../../etc/passwd", popfile1="http://x/a.jpg"),  # 경로 이탈 시도
            _msg(desertionNo="B1", popfile1="file:///etc/passwd", popfile2=12345),  # SSRF·타입 오류
            _msg(desertionNo="C1", popfile1="https://x/c1.png"),  # 정상
        ]
    )
    assert jobs == {"C1_1": "https://x/c1.png"}
    assert skipped == 4, "손상·키 없음·비dict·경로 이탈 4건이 스킵 (B1은 URL만 걸러져 스킵 아님)"
