import json
import subprocess
import sys
import tarfile
from pathlib import Path

import pytest

import image_backfill


def test_chunked_is_deterministic_for_sorted_items() -> None:
    items = sorted({f"K{i:03d}": f"http://x/{i}" for i in range(5)}.items())
    chunks = list(image_backfill.chunked(items, 2))
    assert [idx for idx, _ in chunks] == [0, 1, 2]
    assert [len(c) for _, c in chunks] == [2, 2, 1]
    assert chunks[0][1][0][0] == "K000" and chunks[2][1][0][0] == "K004"
    assert list(image_backfill.chunked(items, 2)) == chunks, "같은 입력은 같은 청크 (재개의 전제)"


def _run_main(monkeypatch: pytest.MonkeyPatch, *args: str) -> int:
    monkeypatch.setattr(sys, "argv", ["image_backfill.py", *args])
    return image_backfill.main()


COMMON = ["--records-dir", "/r", "--hdfs-dir", "/h", "--metrics", "m.json"]


@pytest.mark.parametrize(
    "extra",
    [
        ["--from", "2024/01", "--to", "2026-09"],  # 형식 오류
        ["--from", "2026-09", "--to", "2024-01"],  # 역전
        ["--from", "2024-01", "--to", "2024-02", "--chunk", "0"],  # 0 이하
        ["--from", "2024-01", "--to", "2024-02", "--workers", "-1"],
    ],
)
def test_main_rejects_bad_arguments_with_exit_2(monkeypatch: pytest.MonkeyPatch, tmp_path: Path, extra: list[str]) -> None:
    assert _run_main(monkeypatch, *extra, "--state", str(tmp_path / "s.json"), *COMMON) == 2


def test_main_rejects_chunk_size_change_on_resume(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    state = tmp_path / "s.json"
    state.write_text(json.dumps({"done_chunks": {"202401": [0]}, "month_chunks": {}, "chunk_size": 1000}), encoding="utf-8")
    code = _run_main(monkeypatch, "--from", "2024-01", "--to", "2024-01", "--chunk", "2000", "--state", str(state), *COMMON)
    assert code == 2, "청크 크기가 다르면 기존 완료 표시가 다른 범위를 가리키므로 거부"


def test_upload_chunk_tars_saved_files_and_writes_missing(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    calls: list[list[str]] = []
    monkeypatch.setattr(subprocess, "run", lambda cmd, **kw: calls.append(cmd))
    (tmp_path / "A1_1.jpg").write_bytes(b"jpg")
    (tmp_path / "A2_1.png").write_bytes(b"png")
    missing = [{"entry": "A3_1", "url": "http://x/a3", "reason": "HTTP Error 404"}]

    size = image_backfill.upload_chunk(tmp_path, ["A2_1.png", "A1_1.jpg"], missing, "/h/yyyymm=202401", "202401-0000", 1)

    with tarfile.open(tmp_path / "images-202401-0000.tar") as tar:
        assert tar.getnames() == ["A1_1.jpg", "A2_1.png"], "엔트리는 정렬 순"
    assert size == (tmp_path / "images-202401-0000.tar").stat().st_size
    assert json.loads((tmp_path / "missing-202401-0000.jsonl").read_text(encoding="utf-8")) == missing[0]
    puts = [c for c in calls if "-put" in c]
    assert len(puts) == 2 and all("dfs.replication=1" in c for c in puts)
    assert puts[0][-1] == "/h/yyyymm=202401/images-202401-0000.tar"
    assert puts[1][-1] == "/h/yyyymm=202401/missing-202401-0000.jsonl"
