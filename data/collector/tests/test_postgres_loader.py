"""postgres_loader 의 파일 입력 모드 — Kafka 를 거치지 않고 원문 파일을 적재하는 경로.

Kafka 에는 신규·변경분만 흐르므로, 이미 지나간 원문(2026-09-14 지역 해석 결함으로 버려진 11,313건)을
다시 넣는 유일한 길이다. DB 없이 돌리는 부분(파일 읽기·인자 검증·Kafka 미접촉)만 여기서 본다.
"""

import json
from pathlib import Path

import pytest

import postgres_loader as loader


class TestReadInput:
    def test_json_배열을_메시지_목록으로_읽는다(self, tmp_path: Path) -> None:
        path = tmp_path / "records.json"
        path.write_text(json.dumps([{"desertionNo": "A"}, {"desertionNo": "B"}], ensure_ascii=False), encoding="utf-8")
        messages = loader.read_input(path)
        assert [json.loads(m)["desertionNo"] for m in messages] == ["A", "B"]

    def test_jsonl_은_빈_줄을_건너뛴다(self, tmp_path: Path) -> None:
        path = tmp_path / "records.jsonl"
        path.write_text('{"desertionNo":"A"}\n\n   \n{"desertionNo":"B"}\n', encoding="utf-8")
        assert len(loader.read_input(path)) == 2

    def test_한글이_깨지지_않는다(self, tmp_path: Path) -> None:
        path = tmp_path / "records.json"
        path.write_text(json.dumps([{"orgNm": "전남광주통합특별시 북구"}], ensure_ascii=False), encoding="utf-8")
        assert json.loads(loader.read_input(path)[0])["orgNm"] == "전남광주통합특별시 북구"

    def test_배열이_아닌_json_은_거부(self, tmp_path: Path) -> None:
        path = tmp_path / "bad.json"
        path.write_text('{"not": "a list"}', encoding="utf-8")
        # 객체 하나는 JSONL 로 읽혀 메시지 1건이 된다 — 배열 판정은 '[' 로 시작할 때만이다
        assert len(loader.read_input(path)) == 1
        path.write_text("[1, 2", encoding="utf-8")
        with pytest.raises(ValueError):
            loader.read_input(path)


class TestInputMode:
    def _run(self, monkeypatch, argv: list[str]) -> int:
        monkeypatch.setattr(loader.sys, "argv", ["postgres_loader.py", *argv])
        return loader.main()

    def test_input_모드는_Kafka_를_만들지_않는다(self, tmp_path: Path, monkeypatch) -> None:
        records = tmp_path / "r.json"
        records.write_text("[]", encoding="utf-8")
        regions = tmp_path / "regions.csv"
        regions.write_text("version,regionCode,emdCode,publicLocation,active\nv1,11710,,서울특별시 송파구,true\n", encoding="utf-8")
        import hashlib
        sha = hashlib.sha256(regions.read_bytes()).hexdigest()

        def boom(*a, **k):
            raise AssertionError("--input 모드에서 Kafka Consumer 가 생성됐다")

        seen = {}

        def fake_ingest(messages, args, regions_, source):
            seen.update(messages=messages, source=source, run_type=args.run_type)
            return 0

        monkeypatch.setattr(loader, "Consumer", boom)
        monkeypatch.setattr(loader, "ingest_messages", fake_ingest)
        # main() 은 psycopg 존재만 확인한다(import). CI 러너와 이 PC 에는 psycopg 가 없으므로 빈 모듈을 꽂는다.
        import sys, types
        monkeypatch.setitem(sys.modules, "psycopg", types.ModuleType("psycopg"))
        code = self._run(monkeypatch, [
            "--input", str(records), "--dsn", "postgresql://x", "--run-type", "INITIAL_FULL",
            "--region-catalog", str(regions), "--region-version", "v1", "--region-sha256", sha,
        ])
        assert code == 0
        assert seen == {"messages": [], "source": str(records), "run_type": "INITIAL_FULL"}

    def test_bootstrap_도_input_도_없으면_실패(self, tmp_path: Path, monkeypatch) -> None:
        with pytest.raises(SystemExit):
            self._run(monkeypatch, ["--dsn", "postgresql://x"])
