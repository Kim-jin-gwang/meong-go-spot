import datetime
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dags"))

import alerts  # noqa: E402


def test_post_without_webhook_returns_false(monkeypatch, caplog) -> None:
    monkeypatch.delenv("MATTERMOST_WEBHOOK_URL", raising=False)
    assert alerts.post("hello") is False, "웹훅이 없으면 예외 없이 False"


def test_post_sends_json_text(monkeypatch) -> None:
    sent = {}

    class FakeResponse:
        status = 200

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

    def fake_urlopen(req, timeout):
        sent["url"] = req.full_url
        sent["body"] = json.loads(req.data.decode("utf-8"))
        sent["timeout"] = timeout
        return FakeResponse()

    monkeypatch.setattr(alerts.urllib.request, "urlopen", fake_urlopen)
    assert alerts.post("안녕", url="https://mm.example/hooks/x") is True
    assert sent == {"url": "https://mm.example/hooks/x", "body": {"text": "안녕"}, "timeout": alerts.TIMEOUT_SEC}


def test_post_swallows_network_errors(monkeypatch) -> None:
    def boom(req, timeout):
        raise OSError("connection refused")

    monkeypatch.setattr(alerts.urllib.request, "urlopen", boom)
    assert alerts.post("x", url="https://mm.example/hooks/x") is False


def test_failure_message_contains_essentials() -> None:
    when = datetime.datetime(2026, 9, 6, 13, 55, tzinfo=datetime.timezone.utc)
    text = alerts.failure_message("collector_daily", "collect_and_publish", "scheduled__2026-09-05T13:30:00+00:00",
                                  3, 3, when, "RuntimeError: 3회 재시도 실패: <urlopen error handshake timed out>")
    assert "collector_daily 실패" in text and "`collect_and_publish`" in text and "(시도 3/3)" in text
    assert "2026-09-06 22:55 KST" in text, "UTC → KST 변환"
    assert "handshake timed out" in text and "~/airflow/logs/dag_id=collector_daily" in text


def test_notify_failure_never_raises(monkeypatch) -> None:
    monkeypatch.delenv("MATTERMOST_WEBHOOK_URL", raising=False)
    alerts.notify_failure({})  # 빈 컨텍스트여도 예외 없음
    alerts.notify_success({"run_id": "manual__x"})


def test_failure_message_with_blank_error_does_not_raise() -> None:
    when = datetime.datetime(2026, 9, 6, 13, 55, tzinfo=datetime.timezone.utc)
    text = alerts.failure_message("d", "t", "r", 1, 3, when, " " + chr(10) + " ")  # 공백·개행만 있는 예외 문자열
    assert "오류:" not in text and "d 실패" in text


def test_success_message_accepts_naive_start() -> None:
    finished = datetime.datetime(2026, 9, 7, 13, 40, tzinfo=datetime.timezone.utc)
    text = alerts.success_message("collector_daily", "r", datetime.datetime(2026, 9, 7, 13, 30), finished)
    assert "소요 10분" in text and "22:40 KST" in text


def test_post_empty_text_without_webhook_returns_false(monkeypatch) -> None:
    monkeypatch.delenv("MATTERMOST_WEBHOOK_URL", raising=False)
    assert alerts.post("") is False  # 빈 메시지여도 IndexError 없이 계약(예외 없음) 유지


def test_notify_failure_skips_when_already_alerted(monkeypatch) -> None:
    calls = []
    monkeypatch.setattr(alerts, "post", lambda text, url=None: calls.append(text) or True)
    alerts.notify_failure({"exception": alerts.AlreadyAlertedError("임계치 위반"), "run_id": "r"})
    assert calls == [], "경보를 이미 보낸 실패는 중복 알림하지 않는다"
    alerts.notify_failure({"exception": RuntimeError("crash"), "run_id": "r"})
    assert len(calls) == 1 and "실패" in calls[0]



class TestCollectionSummary:
    """수집 건수 요약 — 스크립트가 찍는 실제 마지막 줄을 그대로 넣는다."""

    COLLECT = "수집 11626건 (totalCount 11626 일치) | 신규 255 · 변경 12 | Kafka 발행 267건 | 저장: /x/y.json"
    RECORDS = "적재 완료: 267건 → /data/shelter/raw/dt=2026-09-12/part-1.jsonl"
    IMAGES = "메시지 267건 → 이미지 대상 516장 | 성공 514 · 결손 2 | 적재: /data/shelter/images/dt=2026-09-12"

    ASSIGN = "합계 1,134개 배정 → /embeddings/dinov2_vitb14/v2/index/kmeans-k256/assignments/"
    SNAPSHOT = "스냅샷 456,335 × 768 (float32, 1402MB) → /embeddings/dinov2_vitb14/v2/snapshot"

    def test_다섯_태스크의_건수를_읽는다(self):
        lines = alerts.collection_summary({
            "collect_and_publish": self.COLLECT,
            "load_records_to_hdfs": self.RECORDS,
            "load_images_to_hdfs": self.IMAGES,
            "assign_new_vectors": self.ASSIGN,
            "build_vector_snapshot": self.SNAPSHOT,
        })
        assert lines == [
            "- 수집 11,626건 (신규 255 · 변경 12) · Kafka 발행 267건",
            "- HDFS 원문 267건",
            "- 이미지 514장 (결손 2)",
            "- 벡터 배정 1,134개",
            "- 스냅샷 456,335개 (1,402MB)",
        ]

    def test_쉼표가_있는_숫자를_읽는다(self):
        # index_tools 는 :, 포맷으로 찍는다 — 쉼표를 못 걷어내면 1,134 가 1 이 된다
        assert alerts.collection_summary({"assign_new_vectors": self.ASSIGN}) == ["- 벡터 배정 1,134개"]

    def test_스냅샷_크기에_소수가_있어도_맞게_읽는다(self):
        # ([0-9,]+)MB 만 쓰면 1402.5MB 에서 5 가 잡힌다 — 빠지는 것보다 틀린 숫자가 나쁘다
        assert alerts.collection_summary({
            "build_vector_snapshot": "스냅샷 456,335 × 768 (float32, 1402.5MB) → /x",
        }) == ["- 스냅샷 456,335개 (1,402MB)"]

    def test_크기를_못_읽어도_건수는_남는다(self):
        assert alerts.collection_summary({
            "build_vector_snapshot": "스냅샷 456,335 × 768 (float32, 1,402 MB) → /x",
        }) == ["- 스냅샷 456,335개"]

    def test_배정할_파티션이_없는_날(self):
        assert alerts.collection_summary({
            "assign_new_vectors": "shelter-daily: 배정할 새 파티션 없음",
        }) == ["- 벡터 배정: 새 파티션 없음"]

    def test_결손이_없으면_적지_않는다(self):
        line = alerts.collection_summary({
            "load_images_to_hdfs": "메시지 3건 → 이미지 대상 6장 | 성공 6 · 결손 0 | 적재: /x",
        })
        assert line == ["- 이미지 6장"]

    def test_새_메시지가_없는_날(self):
        assert alerts.collection_summary({
            "load_records_to_hdfs": "새 메시지 없음 — 적재 생략",
            "load_images_to_hdfs": "새 메시지 없음 — 이미지 수집 생략",
        }) == ["- HDFS 원문: 새 메시지 없음", "- 이미지: 새 메시지 없음"]

    def test_읽을_수_없는_출력은_건너뛴다(self):
        # XCom 이 비었거나(None) 형식이 바뀐 경우 — 숫자만 빠지고 알림 자체는 나가야 한다
        assert alerts.collection_summary({
            "collect_and_publish": None,
            "load_records_to_hdfs": "",
            "load_images_to_hdfs": "알 수 없는 출력",
        }) == []
        assert alerts.collection_summary({}) == []

    def test_요약이_완료_알림에_붙는다(self):
        now = datetime.datetime(2026, 9, 12, 13, 35, tzinfo=datetime.timezone.utc)
        text = alerts.success_message("collector_daily", "run-1", None, now, ["- 수집 10건"])
        assert text.splitlines()[0].startswith(":white_check_mark: [DATA] collector_daily 완료")
        assert text.splitlines()[1] == "- 수집 10건"
        # 요약이 없으면 기존 한 줄 그대로다
        assert "\n" not in alerts.success_message("collector_daily", "run-1", None, now)


class TestSummaryMatchesScriptOutput:
    """파서는 다른 모듈의 print 형식에 기대고 있다. 형식이 바뀌면 여기서 깨져야 한다."""

    COLLECTOR = Path(__file__).resolve().parents[2] / "collector"

    def test_스크립트가_여전히_같은_문구를_찍는다(self):
        main = (self.COLLECTOR / "main.py").read_text(encoding="utf-8")
        assert '수집 {len(records)}건' in main
        assert '신규 {len(new_records)} · 변경 {len(changed_records)}' in main
        assert 'Kafka 발행 {published}건' in main

        loader = (self.COLLECTOR / "loader.py").read_text(encoding="utf-8")
        assert '적재 완료: {len(messages)}건' in loader
        assert "새 메시지 없음" in loader

        imager = (self.COLLECTOR / "imager.py").read_text(encoding="utf-8")
        assert '성공 {len(saved)} · 결손 {len(missing)}' in imager
        assert "새 메시지 없음" in imager

    def test_index_tools_가_여전히_같은_문구를_찍는다(self):
        tools = (self.COLLECTOR.parent / "embedding" / "index_tools.py").read_text(encoding="utf-8")
        assert '합계 {assigned:,}개 배정' in tools
        assert "배정할 새 파티션 없음" in tools
        assert '스냅샷 {len(ids):,} × {dim}' in tools


def test_lost_snapshot_line_reads_counts_and_the_empty_case() -> None:
    line = alerts._lost_line("분실 스냅샷 완료: 163건 (개 110 · 고양이 26 · 미상 27) → /data/lost/raw/dt=2026-09-15/records.jsonl")
    assert line == "- 분실 스냅샷 163건 (개 110 · 고양이 26 · 미상 27)"
    assert alerts._lost_line("분실 스냅샷 완료: 5건 → /x") == "- 분실 스냅샷 5건"
    assert alerts._lost_line("분실 스냅샷: 0건 — API 가 빈 목록을 주었다 (저장 생략)") == "- 분실 스냅샷: 0건 (API 빈 목록)"
    assert alerts._lost_line("아무 말") is None
    assert "snapshot_lost_reports" in alerts.COUNT_TASKS


def test_lost_snapshot_script_still_prints_the_same_phrases() -> None:
    source = (Path(__file__).resolve().parents[2] / "collector" / "lost_snapshot.py").read_text(encoding="utf-8")
    assert "분실 스냅샷 완료: {len(records)}건" in source
    assert "분실 스냅샷: 0건" in source
