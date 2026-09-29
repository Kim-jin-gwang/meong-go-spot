import datetime as dt
import json
from pathlib import Path

import pytest

import lost_snapshot

RECORD = {
    "callName": "양복희", "callTel": "010-7645-3821",
    "happenDt": "2026-09-14 13:00:00.0",
    "happenAddr": "대전광역시 유성구 유성대로654번길 130 (구암동)", "happenAddrDtl": "유성고등학교",
    "happenPlace": "유성고등학교 골목 사이", "orgNm": "대전광역시 유성구 ",
    "popfile": "http://openapi.animal.go.kr/openapi/service/rest/fileDownloadSrvc/files/loss/2026/09/a.jpg",
    "kindCd": "믹스견", "colorCd": "흰색", "sexCd": "F", "age": "2살", "specialMark": "겁이 많다", "rfidCd": "410100129",
}
TABLE = {"믹스견": "DOG", "코리안 숏헤어": "CAT"}
DAY = dt.date(2026, 9, 15)


def test_sanitize_drops_reporter_pii_and_coarsens_the_address() -> None:
    out = lost_snapshot.sanitize(RECORD, TABLE, DAY)
    for field in ("callName", "callTel", "happenAddrDtl"):
        assert field not in out
    assert out["happenAddr"] == "대전광역시 유성구"
    assert out["orgNm"] == "대전광역시 유성구"
    assert out["species"] == "DOG" and out["snapshotDt"] == "2026-09-15"
    assert out["happenPlace"] == RECORD["happenPlace"] and out["rfidCd"] == RECORD["rfidCd"]
    dumped = json.dumps(out, ensure_ascii=False)
    assert RECORD["callTel"] not in dumped and RECORD["callName"] not in dumped  # 건물명은 happenPlace 에도 나올 수 있어 값으로는 안 본다


def test_lost_key_is_built_from_the_full_address_before_coarsening() -> None:
    key = lost_snapshot.lost_key(RECORD)
    assert len(key) == 64
    other = {**RECORD, "happenAddr": "대전광역시 유성구 다른로 1"}
    assert lost_snapshot.lost_key(other) != key, "같은 시·군·구의 다른 주소는 다른 신고다"
    assert lost_snapshot.lost_key({**RECORD, "callTel": "다른 번호"}) == key, "연락처는 키에 들어가지 않는다"
    assert lost_snapshot.sanitize(RECORD, TABLE, DAY)["lostKey"] == key


def test_coarse_address_keeps_single_token_cities() -> None:
    assert lost_snapshot.coarse_address("세종특별자치시 한누리대로 2130") == "세종특별자치시 한누리대로"
    assert lost_snapshot.coarse_address("") is None and lost_snapshot.coarse_address(None) is None


def test_species_counts_bucket_other_and_none_as_unresolved() -> None:
    rows = [{"species": "DOG"}, {"species": "CAT"}, {"species": "OTHER"}, {"species": None}, {}]
    assert lost_snapshot.species_counts(rows) == {"DOG": 1, "CAT": 1, "UNRESOLVED": 3}


def test_upload_writes_one_overwritable_file_per_day(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    calls: list[list[str]] = []
    spooled: dict[str, str] = {}

    def fake_run(cmd: list[str], check: bool) -> None:
        calls.append(cmd)
        if cmd[2] == "-put":
            spooled["content"] = Path(cmd[4]).read_text(encoding="utf-8")

    monkeypatch.setattr(lost_snapshot.subprocess, "run", fake_run)
    dest = lost_snapshot.upload([{"lostKey": "k", "happenAddr": "서울특별시 강남구"}], "/data/lost/raw", DAY)
    assert dest == "/data/lost/raw/dt=2026-09-15/records.jsonl"
    assert calls[0] == ["hdfs", "dfs", "-mkdir", "-p", "/data/lost/raw/dt=2026-09-15"]
    assert calls[1][:4] == ["hdfs", "dfs", "-put", "-f"] and calls[1][5] == dest
    assert json.loads(spooled["content"].strip()) == {"lostKey": "k", "happenAddr": "서울특별시 강남구"}


def test_main_prints_the_summary_line_alerts_parse(monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys) -> None:
    csv = tmp_path / "breed-species.csv"
    csv.write_text("version,breedName,species\nv,믹스견,DOG\nv,코리안 숏헤어,CAT\n", encoding="utf-8")
    records = [RECORD, {**RECORD, "kindCd": "코리안 숏헤어", "popfile": "http://x/b.jpg"}, {**RECORD, "kindCd": "기타", "popfile": "http://x/c.jpg"}]
    monkeypatch.setenv("DATA_GO_KR_SERVICE_KEY", "KEY")
    monkeypatch.setattr(lost_snapshot.api, "fetch_lost_all", lambda key: (records, 303))
    monkeypatch.setattr(lost_snapshot, "upload", lambda recs, hdfs_dir, day: f"{hdfs_dir}/dt={day.isoformat()}/records.jsonl")
    monkeypatch.setattr("sys.argv", ["lost_snapshot.py", "--breed-species", str(csv)])
    assert lost_snapshot.main() == 0
    last = capsys.readouterr().out.strip().splitlines()[-1]
    assert last.startswith("분실 스냅샷 완료: 3건 (개 1 · 고양이 1 · 미상 1) → /data/lost/raw/dt=")


def test_main_without_service_key_fails_loudly(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("DATA_GO_KR_SERVICE_KEY", raising=False)
    monkeypatch.setattr("sys.argv", ["lost_snapshot.py"])
    assert lost_snapshot.main() == 2
