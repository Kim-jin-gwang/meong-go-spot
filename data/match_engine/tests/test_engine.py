import datetime as dt
import json
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent.parent))

from data.match_engine import engine  # noqa: E402

CONFIG_ENV = {
    "MATCH_ENGINE_POSTGRES_DSN": "postgresql://x",
    "MATCH_ENGINE_SNAPSHOT_DIR": "/tmp/snapshot",
}


def unit(*v: float) -> list[float]:
    arr = np.array(v, dtype=np.float32)
    return (arr / np.linalg.norm(arr)).tolist()


def make_snapshot(tmp_path: Path, vectors: dict[str, list[float]], fallback: dict[str, bool] | None = None,
                  built_at: str = "2026-09-14T13:53:00+00:00") -> Path:
    ids = list(vectors)
    matrix = np.array([vectors[i] for i in ids], dtype=np.float32)
    fb = np.array([bool((fallback or {}).get(i, False)) for i in ids])
    (tmp_path / "ids.txt").write_text("\n".join(ids) + "\n", encoding="utf-8")
    np.save(tmp_path / "vectors-float32.npy", matrix)
    np.save(tmp_path / "fallback.npy", fb)
    (tmp_path / "meta.json").write_text(json.dumps({
        "model_id": "dinov2_vitb14", "model_version": "v2", "dim": matrix.shape[1], "normalized": True,
        "ids_file": "ids.txt", "vectors_file": "vectors-float32.npy", "fallback_file": "fallback.npy",
        "fallback_count": int(fb.sum()), "built_at": built_at}), encoding="utf-8")
    return tmp_path


D = dt.date(2026, 9, 1)


def test_config_defaults_follow_the_contract() -> None:
    config = engine.Config.from_env(CONFIG_ENV)
    assert (config.threshold, config.top_k, config.region_prefix_len) == (0.60, 20, 2)
    assert (config.model_id, config.model_version) == ("dinov2_vitb14", "v2")
    assert config.bind == "127.0.0.1"


def test_config_rejects_top_k_above_the_contract_cap() -> None:
    with pytest.raises(SystemExit):
        engine.Config.from_env({**CONFIG_ENV, "MATCH_ENGINE_TOP_K": "21"})


def test_photo_vector_id_matches_the_collector_naming() -> None:
    # 수집기는 popfile1 → {desertionNo}_1, 적재기 sort_order 는 0 부터
    assert engine.photo_vector_id("445478202600456", 0) == "445478202600456_1"
    assert engine.photo_vector_id("445478202600456", 1) == "445478202600456_2"


def test_group_candidates_merges_photos_per_case_and_drops_duplicate_rows() -> None:
    rows = [(7364, D, "445478202600456", 0), (7364, D, "445478202600456", 0), (7364, D, "445478202600456", 1),
            (7365, D, "445478202600457", 0)]
    got = engine.group_candidates(rows)
    assert got == [engine.Candidate(7364, D, ("445478202600456_1", "445478202600456_2")),
                   engine.Candidate(7365, D, ("445478202600457_1",))]


def test_score_candidates_uses_pairwise_max_threshold_and_stable_order(tmp_path: Path) -> None:
    snap = engine.Snapshot.load(make_snapshot(tmp_path, {
        "A_1": unit(1, 0, 0), "A_2": unit(0.6, 0.8, 0),   # A 의 두 번째 사진이 질의 2 와 아주 닮음
        "B_1": unit(0.9, 0.43, 0),                        # B 는 질의 1 과 0.9
        "C_1": unit(0, 0, 1),                             # C 는 멀다 → 임계값 아래
        "E_1": unit(0.9, 0.43, 0),                        # E 는 B 와 동점 → case_id 오름차순
    }))
    query = np.array([unit(1, 0, 0), unit(0.6, 0.8, 0)], dtype=np.float32)
    candidates = [engine.Candidate(10, dt.date(2026, 9, 5), ("A_1", "A_2")), engine.Candidate(30, dt.date(2026, 9, 3), ("B_1",)),
                  engine.Candidate(20, dt.date(2026, 9, 9), ("C_1",)), engine.Candidate(25, dt.date(2026, 9, 2), ("E_1",)),
                  engine.Candidate(40, dt.date(2026, 9, 2), ("Z_9",))]  # Z_9 는 벡터 없음(오늘 들어온 사진) → 건너뜀
    rows = engine.score_candidates(query, candidates, snap, D, threshold=0.60, top_k=20)
    assert [r["targetCaseId"] for r in rows] == [10, 25, 30]
    assert [r["rank"] for r in rows] == [1, 2, 3]
    assert rows[0]["totalScore"] == rows[0]["imageScore"] == pytest.approx(1.0, abs=1e-5)
    expected_b = float(np.dot(unit(1, 0, 0), unit(0.9, 0.43, 0)))
    assert rows[1]["totalScore"] == pytest.approx(expected_b, abs=1e-5) and rows[2]["totalScore"] == rows[1]["totalScore"]
    assert rows[0]["timeGapDays"] == 4 and rows[0]["distanceKm"] is None
    assert all(0 <= r["totalScore"] <= 1 for r in rows)


def test_score_candidates_caps_top_k(tmp_path: Path) -> None:
    vectors = {f"P{i}_1": unit(1, 0.01 * i, 0) for i in range(25)}
    snap = engine.Snapshot.load(make_snapshot(tmp_path, vectors))
    candidates = [engine.Candidate(i + 1, D, (f"P{i}_1",)) for i in range(25)]
    rows = engine.score_candidates(np.array([unit(1, 0, 0)], dtype=np.float32), candidates, snap, D, 0.5, 20)
    assert len(rows) == 20 and rows[-1]["rank"] == 20


def test_snapshot_load_refuses_a_model_mismatch_and_missing_fallback(tmp_path: Path) -> None:
    directory = make_snapshot(tmp_path, {"A_1": unit(1, 0, 0)})
    with pytest.raises(RuntimeError):
        engine.Snapshot.load(directory, ("dinov2_vitb14", "v3"))
    (directory / "fallback.npy").unlink()
    with pytest.raises(RuntimeError):
        engine.Snapshot.load(directory)


def _payload(**changes: object) -> dict:
    base = {"matchRunId": 123, "queryCaseId": 7116, "queryCaseVersion": 1, "modelId": "dinov2_vitb14", "modelVersion": "v2",
            "species": "CAT", "eventDate": "2026-08-15", "regionCode": "41170",
            "photos": [{"photoId": 15673, "storageType": "USER_UPLOAD", "storageUri": "/data/user/images/7116/15673.jpg"}]}
    base.update(changes)
    return base


def test_parse_request_accepts_the_worker_payload() -> None:
    config = engine.Config.from_env(CONFIG_ENV)
    got = engine.parse_request(_payload(), config)
    assert got == engine.MatchRequest(123, "CAT", dt.date(2026, 8, 15), "41170", ("/data/user/images/7116/15673.jpg",))


@pytest.mark.parametrize("changes, status, code", [
    ({"modelVersion": "v1"}, 409, "MODEL_MISMATCH"),
    ({"matchRunId": "123"}, 400, "INVALID_RUN_ID"),
    ({"species": "OTHER"}, 400, "INVALID_SPECIES"),
    ({"eventDate": "20260815"}, 400, "INVALID_EVENT_DATE"),
    ({"regionCode": "4117"}, 400, "INVALID_REGION_CODE"),
    ({"photos": []}, 400, "INVALID_PHOTOS"),
    ({"photos": [{"storageType": "PUBLIC_URL", "storageUri": "https://x/a.jpg"}]}, 400, "UNSUPPORTED_PHOTO_STORAGE"),
    ({"photos": [{"storageType": "USER_UPLOAD", "storageUri": "/data/user/../shelter/a.jpg"}]}, 400, "INVALID_PHOTO_PATH"),
    ({"photos": [{"storageType": "USER_UPLOAD", "storageUri": "/etc/passwd"}]}, 400, "INVALID_PHOTO_PATH"),
])
def test_parse_request_rejects_bad_payloads(changes: dict, status: int, code: str) -> None:
    config = engine.Config.from_env(CONFIG_ENV)
    with pytest.raises(engine.EngineError) as info:
        engine.parse_request(_payload(**changes), config)
    assert (info.value.status, info.value.code) == (status, code)


class FakeHdfs:
    def __init__(self, files: dict[str, bytes]) -> None:
        self.files = files
        self.opened: list[str] = []

    def open(self, path: str) -> bytes:
        self.opened.append(path)
        return self.files[path]

    def download(self, path: str, target: Path) -> None:
        target.write_bytes(self.files[path])


def test_snapshot_store_replaces_only_when_hdfs_build_is_newer(tmp_path: Path) -> None:
    (tmp_path / "snapshot").mkdir()
    (tmp_path / "remote").mkdir()
    local = make_snapshot(tmp_path / "snapshot", {"A_1": unit(1, 0, 0)}, built_at="2026-09-13T13:35:00+00:00")
    remote = make_snapshot(tmp_path / "remote", {"A_1": unit(1, 0, 0), "B_1": unit(0, 1, 0)}, built_at="2026-09-14T13:53:00+00:00")
    files = {"/embeddings/ACTIVE": b"dinov2_vitb14/v2\n"}
    for name in engine.SNAPSHOT_FILES:
        files[f"/embeddings/dinov2_vitb14/v2/snapshot/{name}"] = (remote / name).read_bytes()
    config = engine.Config.from_env({**CONFIG_ENV, "MATCH_ENGINE_SNAPSHOT_DIR": str(local)})
    store = engine.SnapshotStore(config, FakeHdfs(files))
    assert len(store.load_local().ids) == 1
    assert store.refresh_if_newer() is True
    assert len(store.current().ids) == 2 and store.current().built_at.startswith("2026-09-14")
    assert (tmp_path / "snapshot.previous" / "meta.json").is_file()  # 이전 스냅샷은 한 세대 보관
    assert store.refresh_if_newer() is False  # 같은 built_at 이면 그대로


def test_snapshot_store_refuses_a_pointer_to_another_model(tmp_path: Path) -> None:
    config = engine.Config.from_env({**CONFIG_ENV, "MATCH_ENGINE_SNAPSHOT_DIR": str(tmp_path / "snapshot")})
    store = engine.SnapshotStore(config, FakeHdfs({"/embeddings/ACTIVE": b"dinov2_vitb14/v3"}))
    with pytest.raises(RuntimeError):
        store.hdfs_snapshot_dir()


class FakeEmbedder:
    def __init__(self, vectors: np.ndarray) -> None:
        self.vectors = vectors

    def embed(self, images: list[bytes]) -> np.ndarray:
        return self.vectors[: len(images)]


class FakeRepository:
    def __init__(self, candidates: list[engine.Candidate],
                 user_candidates: list[engine.UserCandidate] | None = None) -> None:
        self.candidates = candidates
        self.user_candidates = user_candidates or []
        self.calls: list[tuple] = []
        self.user_calls: list[tuple] = []

    def find(self, species: str, event_date: dt.date, region_code: str) -> list[engine.Candidate]:
        self.calls.append((species, event_date, region_code))
        return self.candidates

    def find_user(self, species: str, event_date: dt.date, region_code: str) -> list[engine.UserCandidate]:
        self.user_calls.append((species, event_date, region_code))
        return self.user_candidates


def test_service_returns_the_worker_contract_response(tmp_path: Path) -> None:
    snap_dir = make_snapshot(tmp_path, {"A_1": unit(1, 0, 0), "B_1": unit(0, 1, 0)})
    config = engine.Config.from_env({**CONFIG_ENV, "MATCH_ENGINE_SNAPSHOT_DIR": str(snap_dir)})
    store = engine.SnapshotStore(config, FakeHdfs({}))
    store.load_local()
    repo = FakeRepository([engine.Candidate(10, dt.date(2026, 8, 20), ("A_1",)), engine.Candidate(11, dt.date(2026, 8, 21), ("B_1",))])
    photos = FakeHdfs({"/data/user/images/7116/15673.jpg": b"jpeg-bytes"})
    service = engine.MatchService(config, store, repo, FakeEmbedder(np.array([unit(1, 0, 0)], dtype=np.float32)), photos)
    response = service.match(engine.parse_request(_payload(), config))
    assert response == {"matchRunId": 123, "modelId": "dinov2_vitb14", "modelVersion": "v2",
                        "candidates": [{"targetCaseId": 10, "rank": 1, "totalScore": 1.0, "imageScore": 1.0,
                                        "distanceKm": None, "timeGapDays": 5}]}
    assert repo.calls == [("CAT", dt.date(2026, 8, 15), "41170")]
    assert photos.opened == ["/data/user/images/7116/15673.jpg"]
    health = service.health()
    assert health["status"] == "ok" and health["snapshot"]["count"] == 2 and health["threshold"] == 0.60


def test_group_user_candidates_groups_photos_and_drops_bad_uris() -> None:
    rows = [(50, D, 901, "/data/user/images/50/901.jpg"), (50, D, 902, "/data/user/images/50/902.jpg"),
            (50, D, 902, "/data/user/images/50/902.jpg"),          # 중복 행
            (51, D, 903, "/data/shelter/evil.jpg"),                # 사용자 저장소 밖 → 버림
            (51, D, 904, "/data/user/../shelter/a.jpg"),           # 경로 탈출 → 버림
            (52, D, 905, "/data/user/images/52/905.jpg")]
    got = engine.group_user_candidates(rows)
    assert got == [engine.UserCandidate(50, D, ((901, "/data/user/images/50/901.jpg"), (902, "/data/user/images/50/902.jpg"))),
                   engine.UserCandidate(52, D, ((905, "/data/user/images/52/905.jpg"),))]


class BytesEmbedder:
    """이미지 바이트를 준비된 벡터로 바꾸는 시험용 임베더 — 질의·사용자 후보 사진 호출을 구분한다."""

    def __init__(self, table: dict[bytes, list[float]]) -> None:
        self.table = table

    def embed(self, images: list[bytes]) -> np.ndarray:
        return np.array([self.table[data] for data in images], dtype=np.float32)


def test_service_ranks_user_sheltering_posts_with_public_candidates(tmp_path: Path) -> None:
    # URS UR-OWN-004 — 후보는 보호센터 입소 동물과 사용자 `보호하고 있어요` 게시물 둘 다다.
    snap_dir = make_snapshot(tmp_path, {"A_1": unit(0.9, 0.43, 0)})
    config = engine.Config.from_env({**CONFIG_ENV, "MATCH_ENGINE_SNAPSHOT_DIR": str(snap_dir)})
    store = engine.SnapshotStore(config, FakeHdfs({}))
    store.load_local()
    repo = FakeRepository(
        [engine.Candidate(10, dt.date(2026, 8, 20), ("A_1",))],
        [engine.UserCandidate(7, dt.date(2026, 8, 21), ((901, "/data/user/images/7/901.jpg"),)),
         engine.UserCandidate(9, dt.date(2026, 8, 22), ((902, "/data/user/images/9/902.jpg"),))])
    photos = FakeHdfs({"/data/user/images/7116/15673.jpg": b"query", "/data/user/images/7/901.jpg": b"same-dog",
                       "/data/user/images/9/902.jpg": b"far-dog"})
    embedder = BytesEmbedder({b"query": unit(1, 0, 0), b"same-dog": unit(1, 0, 0), b"far-dog": unit(0, 0, 1)})
    service = engine.MatchService(config, store, repo, embedder, photos)
    response = service.match(engine.parse_request(_payload(), config))
    rows = response["candidates"]
    assert [(r["targetCaseId"], r["rank"]) for r in rows] == [(7, 1), (10, 2)]  # 사용자 7 이 1.0, 공공 10 이 0.9
    assert rows[0]["totalScore"] == pytest.approx(1.0, abs=1e-5) and rows[0]["timeGapDays"] == 6
    assert repo.user_calls == repo.calls == [("CAT", dt.date(2026, 8, 15), "41170")]
    # 두 번째 실행 — 사용자 후보 사진은 photo id 캐시로 다시 읽지 않는다
    service.match(engine.parse_request(_payload(), config))
    assert photos.opened.count("/data/user/images/7/901.jpg") == 1
    assert photos.opened.count("/data/user/images/7116/15673.jpg") == 2  # 질의 사진은 매번 읽는다


def test_user_photo_vectors_skips_unreadable_photos_without_failing() -> None:
    import urllib.error

    class FlakyHdfs(FakeHdfs):
        def open(self, path: str) -> bytes:
            if path not in self.files:
                self.opened.append(path)
                raise urllib.error.URLError("gone")
            return super().open(path)

    hdfs = FlakyHdfs({"/data/user/images/7/901.jpg": b"ok"})
    vectors = engine.UserPhotoVectors(hdfs, BytesEmbedder({b"ok": unit(1, 0, 0)}))
    candidates = [engine.UserCandidate(7, D, ((901, "/data/user/images/7/901.jpg"), (902, "/data/user/images/7/902.jpg")))]
    got = vectors.vectors_for(candidates)
    assert set(got) == {901}  # 902 는 못 읽어서 이번 실행에서만 빠진다
    got_again = vectors.vectors_for(candidates)
    assert set(got_again) == {901} and hdfs.opened.count("/data/user/images/7/902.jpg") == 2  # 실패는 캐시하지 않는다


def test_webhdfs_open_url_names_the_hdfs_user_and_encodes_the_path() -> None:
    # 사용자 사진은 backend 가 ubuntu 로 0600 으로 쓴다 — user.name 없이 열면 익명(dr.who)이라 403 (2026-09-15 실제 발생)
    hdfs = engine.WebHdfs("http://bd-master:9870", "ubuntu")
    assert hdfs.url("/data/user/images/7116/15673.jpg") ==         "http://bd-master:9870/webhdfs/v1/data/user/images/7116/15673.jpg?op=OPEN&user.name=ubuntu"
    assert "%5B1%5D" in engine.WebHdfs("http://h", "u").url("/x/a[1].jpg")


# ── 후보 조회 연결 복구 ─────────────────────────────────────────────


class _ConnectionLost(Exception):
    """psycopg.OperationalError 자리의 시험용 예외."""


class _FakeCursor:
    def __init__(self, connection: "_FakeConnection") -> None:
        self.connection = connection

    def __enter__(self) -> "_FakeCursor":
        return self

    def __exit__(self, *exc: object) -> None:
        return None

    def execute(self, sql: str, params: dict) -> None:
        self.connection.executed.append(params)
        if self.connection.fail_with is not None:
            raise self.connection.fail_with

    def fetchall(self) -> list[tuple]:
        return [(1, dt.date(2026, 9, 1), "411300202600001", 0)]


class _FakeConnection:
    def __init__(self, fail_with: BaseException | None = None) -> None:
        self.fail_with = fail_with
        self.executed: list[dict] = []
        self.commits = 0
        self.rollbacks = 0
        self.closed = False

    def cursor(self) -> _FakeCursor:
        return _FakeCursor(self)

    def commit(self) -> None:
        self.commits += 1

    def rollback(self) -> None:
        self.rollbacks += 1

    def close(self) -> None:
        self.closed = True


def test_candidate_repository_reconnects_once_on_a_connection_error() -> None:
    connections = [_FakeConnection(fail_with=_ConnectionLost()), _FakeConnection()]
    repository = engine.CandidateRepository(lambda: connections.pop(0), 2, retry_on=(_ConnectionLost,))
    first = repository.connection

    result = repository.find("DOG", dt.date(2026, 9, 1), "11440")

    assert [c.case_id for c in result] == [1]
    assert first.closed and first.rollbacks == 1
    assert repository.connection is not first and repository.connection.commits == 1
    assert connections == []  # 새 연결은 한 번만 만든다


def test_candidate_repository_gives_up_when_the_reconnect_also_fails() -> None:
    connections = [_FakeConnection(fail_with=_ConnectionLost()), _FakeConnection(fail_with=_ConnectionLost()), _FakeConnection()]
    repository = engine.CandidateRepository(lambda: connections.pop(0), 2, retry_on=(_ConnectionLost,))

    with pytest.raises(_ConnectionLost):
        repository.find("DOG", dt.date(2026, 9, 1), "11440")
    assert len(connections) == 1  # 두 번째 연결까지만 썼다 — 무한 재시도하지 않는다


def test_candidate_repository_does_not_reconnect_on_other_errors_but_rolls_back() -> None:
    connection = _FakeConnection(fail_with=ValueError("bad sql"))
    made = 0

    def connect() -> _FakeConnection:
        nonlocal made
        made += 1
        return connection

    repository = engine.CandidateRepository(connect, 2, retry_on=(_ConnectionLost,))

    with pytest.raises(ValueError):
        repository.find("DOG", dt.date(2026, 9, 1), "11440")
    assert made == 1 and connection.rollbacks == 1 and not connection.closed


def test_candidate_repository_serializes_concurrent_lookups_and_reconnects_once() -> None:
    import threading

    connections = [_FakeConnection(fail_with=_ConnectionLost()), _FakeConnection()]
    repository = engine.CandidateRepository(lambda: connections.pop(0), 2, retry_on=(_ConnectionLost,))
    results: list[list[engine.Candidate]] = []
    errors: list[BaseException] = []

    def lookup() -> None:
        try:
            results.append(repository.find("DOG", dt.date(2026, 9, 1), "11440"))
        except BaseException as exception:  # noqa: BLE001
            errors.append(exception)

    threads = [threading.Thread(target=lookup) for _ in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert errors == []
    assert len(results) == 8 and all([c.case_id for c in r] == [1] for r in results)
    assert connections == []  # 첫 오류에서 한 번만 다시 붙었고, 나머지 7건은 새 연결을 그대로 썼다

