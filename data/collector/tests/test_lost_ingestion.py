import datetime as dt
import hashlib
import json
from contextlib import contextmanager
from pathlib import Path

import pytest

import lost_ingestion
from public_ingestion import Location, RecordError, RegionCatalog

SNAPSHOT_DAY = dt.date(2026, 9, 15)
NOW = dt.datetime(2026, 9, 15, 14, 20, tzinfo=dt.timezone.utc)
KEY = hashlib.sha256(b"x").hexdigest()


def catalog() -> RegionCatalog:
    return RegionCatalog([Location("30200", None, "대전광역시 유성구"), Location("11680", None, "서울특별시 강남구")])


def record(**changes: object) -> dict:
    base = {"lostKey": KEY, "species": "DOG", "kindCd": "믹스견", "sexCd": "F", "colorCd": "흰색", "age": "2살",
            "happenDt": "2026-09-14 13:00:00.0", "happenAddr": "대전광역시 유성구", "happenPlace": "유성고등학교 골목 사이",
            "orgNm": "대전광역시 유성구", "rfidCd": "410100129", "specialMark": "겁이 많다",
            "popfile": "http://openapi.animal.go.kr/openapi/service/rest/fileDownloadSrvc/files/loss/2026/09/a.jpg",
            "snapshotDt": "2026-09-15"}
    base.update(changes)
    return base


def test_normalize_lost_maps_the_snapshot_record() -> None:
    item = lost_ingestion.normalize_lost(record(), catalog())
    assert item.lost_key == KEY and item.species == "DOG" and item.sex == "FEMALE" and item.breed_name == "믹스견"
    assert item.event_date == dt.date(2026, 9, 14) and item.event_time == dt.time(13, 0)
    assert item.listed_at == dt.datetime(2026, 9, 14, 4, 0, tzinfo=dt.timezone.utc)  # 13:00 KST
    assert item.location.region_code == "30200" and item.location.public_location == "대전광역시 유성구"
    assert item.photo_url.startswith("https://") and item.rfid_code == "410100129" and item.org_name == "대전광역시 유성구"


@pytest.mark.parametrize("changes, error", [
    ({"lostKey": "short"}, "MISSING_LOST_KEY"),
    ({"species": "OTHER"}, "SPECIES_NOT_SUPPORTED"),
    ({"species": None}, "SPECIES_NOT_SUPPORTED"),
    ({"popfile": None}, "MISSING_PUBLIC_PHOTO"),
    ({"happenDt": "어제"}, "INVALID_DATE"),
    ({"happenAddr": "화성 올림푸스"}, "LOCATION_NOT_MAPPED"),
])
def test_normalize_lost_rejects(changes: dict, error: str) -> None:
    with pytest.raises(RecordError, match=error):
        lost_ingestion.normalize_lost(record(**changes), catalog())


def test_happen_accepts_date_only_forms() -> None:
    assert lost_ingestion._happen("20260914") == (dt.date(2026, 9, 14), None)
    assert lost_ingestion._happen("2026-09-14") == (dt.date(2026, 9, 14), None)


class FakeCursor:
    def __init__(self, db: "FakeDb") -> None:
        self.db = db
        self._next: tuple | None = None
        self.rowcount = -1

    def execute(self, query: str, params: tuple = ()) -> None:
        q = " ".join(query.split())
        self.db.log.append((q, params))
        if q.startswith("INSERT INTO ingestion_run"):
            self.db.run_id += 1
            self._next = (self.db.run_id,)
        elif q.startswith("SELECT animal_case_id FROM lost_report"):
            case = self.db.by_key.get(params[0])
            self._next = (case,) if case else None
        elif q.startswith("INSERT INTO animal_case("):
            self.db.case_id += 1
            self._next = (self.db.case_id,)
            self.db.status[self.db.case_id] = "ACTIVE"
        elif q.startswith("INSERT INTO lost_report"):
            self.db.by_key[params[1]] = params[0]
        elif q.startswith("UPDATE animal_case SET status='ACTIVE'"):
            self.db.status[params[-1]] = "ACTIVE"
        elif q.startswith("UPDATE animal_case c SET status='CLOSED'"):
            seen = set(params[2])
            closed = [cid for key, cid in self.db.by_key.items() if key not in seen and self.db.status.get(cid) == "ACTIVE"]
            for cid in closed:
                self.db.status[cid] = "CLOSED"
            self.rowcount = len(closed)
        elif q.startswith("UPDATE ingestion_run SET"):
            self.db.run_updates.append(params)

    def fetchone(self) -> tuple | None:
        value, self._next = self._next, None
        return value

    def __enter__(self) -> "FakeCursor":
        return self

    def __exit__(self, *args: object) -> None:
        return None


class FakeDb:
    def __init__(self) -> None:
        self.log: list[tuple[str, tuple]] = []
        self.by_key: dict[str, int] = {}
        self.status: dict[int, str] = {}
        self.run_id = 100
        self.case_id = 7000
        self.run_updates: list[tuple] = []
        self.commits = 0
        self.rollbacks = 0

    def cursor(self) -> FakeCursor:
        return FakeCursor(self)

    def commit(self) -> None:
        self.commits += 1

    def rollback(self) -> None:
        self.rollbacks += 1

    @contextmanager
    def transaction(self):
        yield


def test_ingest_inserts_updates_and_closes_missing_reports() -> None:
    db = FakeDb()
    other_key = hashlib.sha256(b"y").hexdigest()
    run_id, counts, summary = lost_ingestion.ingest_lost(db, [record(), record(lostKey=other_key, popfile="http://x/b.jpg")], catalog(), SNAPSHOT_DAY, NOW)
    assert run_id == 101 and counts == {"fetched": 2, "inserted": 2, "updated": 0, "closed": 0, "failed": 0} and summary is None
    # 다음 날: 첫 건만 남고, 축종 미상 한 건이 섞였다
    gone_key = hashlib.sha256(b"z").hexdigest()
    run_id, counts, summary = lost_ingestion.ingest_lost(
        db, [record(), record(lostKey=gone_key, species="OTHER", popfile="http://x/c.jpg")], catalog(), SNAPSHOT_DAY + dt.timedelta(days=1), NOW)
    assert run_id == 102
    assert counts == {"fetched": 2, "inserted": 0, "updated": 1, "closed": 1, "failed": 1} and summary == "SPECIES_NOT_SUPPORTED:1"
    assert db.status[db.by_key[other_key]] == "CLOSED" and db.status[db.by_key[KEY]] == "ACTIVE"
    final = db.run_updates[-1]
    assert final[:4] == (2, 0, 1, 1) and final[4] == "SPECIES_NOT_SUPPORTED:1"
    photo_inserts = [p for q, p in db.log if q.startswith("INSERT INTO animal_photo")]
    assert all(p[1].startswith("https://") for p in photo_inserts)
    case_inserts = [q for q, _ in db.log if q.startswith("INSERT INTO animal_case(")]
    assert all("'LOST','PUBLIC','ACTIVE',false" in q for q in case_inserts), "공공 LOST 는 매칭 기준·후보가 아니다"


def test_ingest_marks_the_run_failed_on_persistence_errors() -> None:
    class BrokenDb(FakeDb):
        def cursor(self) -> FakeCursor:
            cursor = super().cursor()
            original = cursor.execute

            def execute(query: str, params: tuple = ()) -> None:
                if query.lstrip().startswith("INSERT INTO animal_case("):
                    raise RuntimeError("connection lost")
                original(query, params)

            cursor.execute = execute  # type: ignore[method-assign]
            return cursor

    db = BrokenDb()
    with pytest.raises(RuntimeError):
        lost_ingestion.ingest_lost(db, [record()], catalog(), SNAPSHOT_DAY, NOW)
    assert db.rollbacks == 1
    assert any("status='FAILED'" in q for q, _ in db.log)


def test_read_snapshot_builds_a_webhdfs_open_url_with_user_name(monkeypatch: pytest.MonkeyPatch) -> None:
    seen: dict[str, str] = {}

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return None

        def read(self) -> bytes:
            return (json.dumps({"lostKey": KEY}) + "\n\n").encode("utf-8")

    def fake_urlopen(url: str, timeout: float) -> Response:
        seen["url"] = url
        return Response()

    monkeypatch.setattr(lost_ingestion.urllib.request, "urlopen", fake_urlopen)
    rows = lost_ingestion.read_snapshot("http://bd-master:9870/", "ubuntu", "/data/lost/raw", SNAPSHOT_DAY)
    assert rows == [{"lostKey": KEY}]
    assert seen["url"] == "http://bd-master:9870/webhdfs/v1/data/lost/raw/dt=2026-09-15/records.jsonl?op=OPEN&user.name=ubuntu"


def test_main_skips_everything_when_the_snapshot_is_empty(monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys) -> None:
    csv = tmp_path / "region.csv"
    csv.write_text("version,regionCode,emdCode,publicLocation,active\nv,30200,,대전광역시 유성구,true\n", encoding="utf-8", newline="\n")
    digest = hashlib.sha256(csv.read_bytes()).hexdigest()
    empty = tmp_path / "empty.jsonl"
    empty.write_text("", encoding="utf-8")
    monkeypatch.setattr("sys.argv", ["lost_ingestion.py", "--dsn", "postgresql://x", "--region-catalog", str(csv), "--region-version", "v",
                                     "--region-sha256", digest, "--input", str(empty)])
    assert lost_ingestion.main() == 0
    assert "비어 있다" in capsys.readouterr().out


def test_ingest_does_not_close_anything_when_every_record_fails_normalization() -> None:
    # 스냅샷 파일은 있지만 전량이 축종 미상 → seen 이 비어 "다 사라졌다" 로 오판하면 기존 ACTIVE 전부가 닫힌다 (AI 리뷰 !176)
    db = FakeDb()
    lost_ingestion.ingest_lost(db, [record()], catalog(), SNAPSHOT_DAY, NOW)
    logged_before = len(db.log)  # 첫 실행은 정상 키가 있어 종료 검사를 돈다 — 그 뒤만 본다
    run_id, counts, summary = lost_ingestion.ingest_lost(db, [record(species="OTHER")], catalog(), SNAPSHOT_DAY + dt.timedelta(days=1), NOW)
    assert counts == {"fetched": 1, "inserted": 0, "updated": 0, "closed": 0, "failed": 1} and summary == "SPECIES_NOT_SUPPORTED:1"
    assert db.status[db.by_key[KEY]] == "ACTIVE"
    assert not any(q.startswith("UPDATE animal_case c SET status='CLOSED'") for q, _ in db.log[logged_before:])


def test_normalize_lost_lowercases_the_key_to_match_the_db_constraint() -> None:
    item = lost_ingestion.normalize_lost(record(lostKey=KEY.upper()), catalog())
    assert item.lost_key == KEY
