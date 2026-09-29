"""색인 산출물 도구 — 파싱 계약과 배정 기준(유클리드)만 검증한다. HDFS 는 건드리지 않는다."""

import io
import json
import subprocess
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import index_tools  # noqa: E402


def vector_line(pid: str, values) -> str:
    return pid + "\t" + ",".join(f"{v:.6f}" for v in values) + "\n"


def test_parse_vector_lines_keeps_order_and_shape() -> None:
    lines = [vector_line("A_1", [0.1, 0.2, 0.3]), vector_line("A_2", [0.4, 0.5, 0.6])]
    ids, matrix = index_tools.parse_vector_lines(lines, dim=3)
    assert ids == ["A_1", "A_2"] and matrix.shape == (2, 3) and matrix.dtype == np.float32
    assert matrix[1][2] == pytest.approx(0.6)


def test_parse_vector_lines_skips_noise_but_fails_on_wrong_dim() -> None:
    lines = ["", "탭없는줄\n", "\t0.1,0.2\n", "B_1\t\n", vector_line("B_2", [0.1, 0.2, 0.3])]
    ids, matrix = index_tools.parse_vector_lines(lines, dim=3)
    assert ids == ["B_2"] and matrix.shape == (1, 3), "빈 줄·탭 없는 줄·ID 없는 줄은 건너뛴다"

    with pytest.raises(SystemExit) as error:
        index_tools.parse_vector_lines([vector_line("C_1", [0.1, 0.2])], dim=768)
    assert "768" in str(error.value) and "C_1" in str(error.value), "차원이 다르면 그 줄에서 시끄럽게 실패"


def test_parse_vector_lines_crosses_block_boundary() -> None:
    lines = [vector_line(f"P{i}", [i, i, i]) for i in range(7)]
    ids, matrix = index_tools.parse_vector_lines(lines, dim=3, block=3)  # 3+3+1 로 나뉜다
    assert ids == [f"P{i}" for i in range(7)] and matrix.shape == (7, 3)
    assert matrix[6][0] == pytest.approx(6.0)


def test_parse_vector_lines_empty_input_gives_empty_matrix() -> None:
    ids, matrix = index_tools.parse_vector_lines([], dim=5)
    assert ids == [] and matrix.shape == (0, 5)


def test_parse_center_lines_and_mixed_dim_failure() -> None:
    ids, centers = index_tools.parse_center_lines(["0\t1.0,2.0\n", "7\t3.0,4.0\n"])
    assert list(ids) == [0, 7] and centers.shape == (2, 2)

    with pytest.raises(SystemExit) as error:
        index_tools.parse_center_lines(["0\t1.0,2.0\n", "1\t3.0,4.0,5.0\n"])
    assert "섞" in str(error.value)

    with pytest.raises(SystemExit):
        index_tools.parse_center_lines(["", "쓰레기\n"])


def test_nearest_center_uses_euclidean_not_plain_dot() -> None:
    # 중심은 무리 평균이라 길이가 1 이 아니다. 내적만 비교하면 긴 중심(c1)이 이겨 틀린 무리에 붙는다.
    vectors = np.array([[1.0, 0.0]], dtype=np.float32)
    centers = np.array([[0.9, 0.0], [2.0, 0.0]], dtype=np.float32)
    assert int(np.argmax(vectors @ centers.T)) == 1, "내적만 보면 c1 — 이게 함정"
    assert index_tools.nearest_center(vectors, centers).tolist() == [0], "거리로는 c0 가 가깝다"


def test_nearest_center_matches_brute_force() -> None:
    rng = np.random.default_rng(0)
    vectors = rng.standard_normal((50, 8), dtype=np.float32)
    centers = rng.standard_normal((6, 8), dtype=np.float32) * 1.7
    expected = [int(np.argmin(((centers - v) ** 2).sum(axis=1))) for v in vectors]
    assert index_tools.nearest_center(vectors, centers, chunk=7).tolist() == expected


def test_dedupe_last_keeps_latest_row() -> None:
    ids = ["A_1", "B_1", "A_1"]
    matrix = np.array([[1.0], [2.0], [3.0]], dtype=np.float32)
    kept_ids, kept, dropped = index_tools.dedupe_last(ids, matrix)
    assert kept_ids == ["B_1", "A_1"] and dropped == 1
    assert kept.flatten().tolist() == [2.0, 3.0], "A_1 은 마지막 값(3.0)이 남는다"

    unique = matrix[:2]  # 슬라이스는 매번 새 뷰 객체라 변수로 잡아두고 동일성을 본다
    same_ids, same, none = index_tools.dedupe_last(["X_1", "Y_1"], unique)
    assert same_ids == ["X_1", "Y_1"] and none == 0 and same is unique, "중복이 없으면 복사하지 않는다"


class FakeProc:
    """Popen 대역 — stdout 만 읽는 stream_cat 의 계약을 확인한다."""

    def __init__(self, lines: list[str], code: int) -> None:
        self.stdout = io.StringIO("".join(lines))
        self._code = code

    def wait(self) -> int:
        return self._code


def test_stream_cat_takes_stderr_as_file_not_pipe(monkeypatch) -> None:
    # 파이프로 받으면 하둡이 64KB 넘게 쓰는 순간 자식이 멈추고 교착된다 — 파일이어야 한다
    def fake_popen(cmd, stdout=None, stderr=None, **kwargs):
        assert stderr is not subprocess.PIPE, "stderr 를 파이프로 받으면 교착 위험"
        stderr.write("WARN NativeCodeLoader " * 5000)  # 100KB 넘게 — 파이프였다면 막힐 양
        return FakeProc(["a\n", "b\n"], code=0)

    monkeypatch.setattr(index_tools.subprocess, "Popen", fake_popen)
    assert list(index_tools.stream_cat("/data/*.tsv")) == ["a\n", "b\n"]


def test_stream_cat_reports_failure_unless_allowed(monkeypatch) -> None:
    def fake_popen(cmd, stdout=None, stderr=None, **kwargs):
        stderr.write("cat: `/missing': No such file or directory")
        return FakeProc([], code=1)

    monkeypatch.setattr(index_tools.subprocess, "Popen", fake_popen)
    with pytest.raises(SystemExit) as error:
        list(index_tools.stream_cat("/missing/*"))
    assert "No such file" in str(error.value) and "/missing/*" in str(error.value)

    assert list(index_tools.stream_cat("/missing/*", allow_missing=True)) == [], "일일 DAG: 없는 파티션은 빈 결과"


def test_counted_reports_lines_per_source() -> None:
    from collections import Counter

    counter = Counter()
    assert list(index_tools.counted(["a\n", "b\n"], counter, "daily")) == ["a\n", "b\n"]
    assert counter["daily"] == 2


class TestDetectionFlags:
    """탐지 폴백 플래그 — 조용히 false 로 채우면 점수 집계가 오염된다."""

    def test_fallback_을_읽는다(self):
        lines = [
            json.dumps({"photo_id": "a_1", "fallback": False, "confidence": 0.9}),
            json.dumps({"photo_id": "a_2", "fallback": True, "detection_count": 0}),
        ]
        assert index_tools.parse_detection_lines(lines) == {"a_1": False, "a_2": True}

    def test_빈_줄과_error_줄을_건너뛴다(self):
        lines = [
            "",
            "   ",
            json.dumps({"photo_id": "bad_1", "error": "OSError: broken"}),
            json.dumps({"photo_id": "a_1", "fallback": True}),
        ]
        assert index_tools.parse_detection_lines(lines) == {"a_1": True}

    def test_잘못된_줄은_실패(self):
        with pytest.raises(SystemExit):
            index_tools.parse_detection_lines(["{not json"])
        with pytest.raises(SystemExit):
            index_tools.parse_detection_lines([json.dumps({"fallback": True})])
        with pytest.raises(SystemExit):
            index_tools.parse_detection_lines([json.dumps({"photo_id": "a_1", "fallback": "yes"})])
        with pytest.raises(SystemExit):
            index_tools.parse_detection_lines([json.dumps({"photo_id": "a_1"})])

    def test_ids_순서에_맞춘다(self):
        flags = {"a_1": True, "a_2": False, "a_3": True}
        aligned = index_tools.align_flags(["a_2", "a_3", "a_1"], flags)
        assert aligned.dtype == bool
        assert aligned.tolist() == [False, True, True]

    def test_누락은_조용히_넘기지_않는다(self):
        with pytest.raises(SystemExit) as error:
            index_tools.align_flags(["a_1", "a_2"], {"a_1": False})
        assert "a_2" in str(error.value)

    def test_벡터에_없는_사진이_detections_에_있어도_된다(self):
        # 파티션 전체를 흘려 읽으므로 중복 제거로 빠진 사진이 남을 수 있다.
        aligned = index_tools.align_flags(["a_1"], {"a_1": True, "a_9": False})
        assert aligned.tolist() == [True]
