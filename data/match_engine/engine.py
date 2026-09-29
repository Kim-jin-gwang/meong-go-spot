"""매칭 엔진 — worker 의 `MATCH_ENGINE_URL` 이 가리키는 상주 HTTP 서비스 (#144).

worker(`data/matching_worker`)가 `match_run` 을 선점해 JSON 을 POST 하면, 이 프로세스가

    1. 질의 사진(사용자 LOST 게시물, HDFS 사용자 저장소)을 WebHDFS 로 읽어 AI 파이프라인으로 임베딩한다
    2. 계약 §후보 출력 정책 0 대로 PostgreSQL 에서 후보를 먼저 좁힌다 — SHELTERING·is_matchable 에
       같은 축종, 실종일 이후 발견, 같은 시·도(지역 코드 앞 2자리). 공공(PUBLIC·ACTIVE)과
       사용자 보호 게시물(USER·ACTIVE·작성자 ACTIVE, URS UR-OWN-004) 두 갈래다
    3. 공공 후보 사진의 벡터는 스냅샷(ids·vectors·fallback)에서 꺼내고, 사용자 후보 사진은 질의 사진과
       같은 경로(WebHDFS→파이프라인)로 그 자리에서 임베딩해 photo id 로 캐시한다 — 야간 배치를 기다리지
       않는다. 동물 점수는 두 갈래 모두 **쌍 코사인 최댓값**이다 (0-2)
    4. 임계값(v2 = 0.60, 0-3) 미만을 버리고 `total_score DESC, target_case_id ASC` 로 상위 K=20 에 rank 를 붙여 돌려준다

모델·스냅샷은 기동 때 한 번 올리고, 스냅샷은 HDFS `meta.json` 의 `built_at` 이 바뀌면(매일 22:30 파이프라인 뒤)
백그라운드에서 내려받아 교체한다. 요청은 한 번에 하나만 처리한다(CPU 임베딩, worker 도 순차).

노출하지 않는 것: 요청 payload·사진 경로·오류 원문은 로그에 남기지 않는다(worker README 5). 로그는 실행 ID·건수·소요만.

설정은 전부 환경 변수다 (`MATCH_ENGINE_*`, README). 실행:
    /home/ubuntu/ai/.venv/bin/python -m data.match_engine.engine
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import io
import json
import logging
import os
import re
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable, Protocol

import numpy as np

LOG = logging.getLogger("match_engine")

SPECIES = ("DOG", "CAT")
REGION_CODE = re.compile(r"^[0-9]{5}$")
ISO_DATE = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
MAX_QUERY_PHOTOS = 10
MAX_REQUEST_BYTES = 64 * 1024
USER_PHOTO_PREFIX = "/data/user/"
SNAPSHOT_FILES = ("meta.json", "ids.txt", "vectors-float32.npy", "fallback.npy")


class EngineError(Exception):
    """클라이언트 오류 — HTTP 4xx 로 돌려준다."""

    def __init__(self, status: int, code: str) -> None:
        super().__init__(code)
        self.status = status
        self.code = code


# ── 설정 ──────────────────────────────────────────────────────────────


@dataclasses.dataclass(frozen=True)
class Config:
    bind: str
    port: int
    postgres_dsn: str
    snapshot_dir: Path
    webhdfs: str
    hdfs_user: str
    embeddings_root: str
    ai_dir: Path
    model_id: str
    model_version: str
    threshold: float
    top_k: int
    region_prefix_len: int
    refresh_seconds: int

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> "Config":
        e = os.environ if env is None else env

        def need(name: str) -> str:
            value = (e.get(name) or "").strip()
            if not value:
                raise SystemExit(f"{name} 가 비어 있다")
            return value

        threshold = float(e.get("MATCH_ENGINE_THRESHOLD", "0.60"))
        if not 0 < threshold < 1:
            raise SystemExit("MATCH_ENGINE_THRESHOLD 는 (0, 1) 사이여야 한다")
        top_k = int(e.get("MATCH_ENGINE_TOP_K", "20"))
        if not 1 <= top_k <= 20:
            raise SystemExit("MATCH_ENGINE_TOP_K 는 1..20 (계약 K=20 상한)")
        prefix = int(e.get("MATCH_ENGINE_REGION_PREFIX_LEN", "2"))
        if not 0 <= prefix <= 5:
            raise SystemExit("MATCH_ENGINE_REGION_PREFIX_LEN 은 0..5")
        return cls(
            bind=e.get("MATCH_ENGINE_BIND", "127.0.0.1"),
            port=int(e.get("MATCH_ENGINE_PORT", "8091")),
            postgres_dsn=need("MATCH_ENGINE_POSTGRES_DSN"),
            snapshot_dir=Path(need("MATCH_ENGINE_SNAPSHOT_DIR")),
            webhdfs=e.get("MATCH_ENGINE_WEBHDFS", "http://bd-master:9870").rstrip("/"),
            hdfs_user=e.get("MATCH_ENGINE_HDFS_USER", "ubuntu"),
            embeddings_root=e.get("MATCH_ENGINE_EMBEDDINGS_ROOT", "/embeddings"),
            ai_dir=Path(e.get("MATCH_ENGINE_AI_DIR", "/home/ubuntu/ai")),
            model_id=e.get("MATCH_ENGINE_MODEL_ID", "dinov2_vitb14"),
            model_version=e.get("MATCH_ENGINE_MODEL_VERSION", "v2"),
            threshold=threshold,
            top_k=top_k,
            region_prefix_len=prefix,
            refresh_seconds=int(e.get("MATCH_ENGINE_SNAPSHOT_REFRESH_SECONDS", "600")),
        )


# ── 스냅샷 ────────────────────────────────────────────────────────────


@dataclasses.dataclass
class Snapshot:
    ids: list[str]
    vectors: np.ndarray
    fallback: np.ndarray
    meta: dict[str, Any]
    index: dict[str, int]

    @property
    def built_at(self) -> str:
        return str(self.meta.get("built_at", ""))

    @classmethod
    def load(cls, directory: Path, expect_model: tuple[str, str] | None = None) -> "Snapshot":
        meta = json.loads((directory / "meta.json").read_text(encoding="utf-8"))
        if expect_model and (meta.get("model_id"), meta.get("model_version")) != expect_model:
            raise RuntimeError(f"스냅샷 모델 {meta.get('model_id')}/{meta.get('model_version')} != 설정 {expect_model}")
        ids = (directory / meta.get("ids_file", "ids.txt")).read_text(encoding="utf-8").split("\n")
        ids = [line for line in ids if line]
        vectors = np.load(directory / meta.get("vectors_file", "vectors-float32.npy"))
        if vectors.shape != (len(ids), int(meta["dim"])) or vectors.dtype != np.float32:
            raise RuntimeError(f"벡터 형태 {vectors.shape}/{vectors.dtype} 가 ids {len(ids)}·dim {meta['dim']} 와 다르다")
        if not meta.get("normalized"):
            raise RuntimeError("스냅샷이 L2 정규화돼 있지 않다 — 내적을 코사인으로 쓸 수 없다")
        fallback_name = meta.get("fallback_file")
        if not fallback_name or not (directory / fallback_name).is_file():
            raise RuntimeError("스냅샷에 fallback.npy 가 없다 — 옛 스냅샷이다")
        fallback = np.load(directory / fallback_name)
        if fallback.shape != (len(ids),) or fallback.dtype != bool:
            raise RuntimeError("fallback.npy 형태가 ids 와 다르다")
        return cls(ids, vectors, fallback, meta, {pid: i for i, pid in enumerate(ids)})


class WebHdfs:
    """NameNode WebHDFS 읽기 전용 — OPEN 은 DataNode 로 307 리다이렉트되고 urllib 이 따라간다.

    클러스터는 단순 인증(Kerberos 없음)이라 `user.name` 으로 말한 사용자로 권한을 검사한다. 사용자 사진은 backend 가
    `PHOTO_HDFS_USER`(ubuntu) 로 0600 으로 쓰므로 같은 사용자를 대야 읽힌다 — 안 대면 익명(dr.who)이라 403 이다(실제 발생).
    """

    def __init__(self, base: str, user: str, timeout: float = 30.0) -> None:
        self.base = base
        self.user = user
        self.timeout = timeout

    def url(self, path: str) -> str:
        return f"{self.base}/webhdfs/v1{urllib.parse.quote(path)}?op=OPEN&user.name={urllib.parse.quote(self.user)}"

    def open(self, path: str) -> bytes:
        with urllib.request.urlopen(self.url(path), timeout=self.timeout) as response:
            return response.read()

    def download(self, path: str, target: Path) -> None:
        with urllib.request.urlopen(self.url(path), timeout=self.timeout) as response, target.open("wb") as out:
            while chunk := response.read(8 << 20):
                out.write(chunk)


class SnapshotStore:
    """활성 스냅샷을 들고 있고, HDFS 의 새 빌드를 발견하면 통째로 내려받아 교체한다.

    HDFS 경로는 `{root}/ACTIVE` 포인터(예: `dinov2_vitb14/v2`)를 읽어 `{root}/{포인터}/snapshot/` 로 푼다.
    설정한 모델과 다른 포인터면 교체하지 않고 경고만 낸다 — 모델 교체는 사람이 임계값과 함께 정하는 일이다.
    """

    def __init__(self, config: Config, hdfs: WebHdfs) -> None:
        self.config = config
        self.hdfs = hdfs
        self._lock = threading.Lock()
        self.snapshot: Snapshot | None = None

    def load_local(self) -> Snapshot:
        snapshot = Snapshot.load(self.config.snapshot_dir, (self.config.model_id, self.config.model_version))
        with self._lock:
            self.snapshot = snapshot
        LOG.info("스냅샷 적재 %s장 built_at=%s", f"{len(snapshot.ids):,}", snapshot.built_at)
        return snapshot

    def current(self) -> Snapshot:
        with self._lock:
            if self.snapshot is None:
                raise RuntimeError("스냅샷이 아직 없다")
            return self.snapshot

    def hdfs_snapshot_dir(self) -> str:
        pointer = self.hdfs.open(f"{self.config.embeddings_root}/ACTIVE").decode("utf-8").strip()
        expected = f"{self.config.model_id}/{self.config.model_version}"
        if pointer != expected:
            raise RuntimeError(f"ACTIVE 포인터 {pointer} 가 설정 {expected} 와 다르다")
        return f"{self.config.embeddings_root}/{pointer}/snapshot"

    def refresh_if_newer(self) -> bool:
        """HDFS meta.json 의 built_at 이 현재보다 새로우면 내려받아 교체한다. 교체했으면 True."""
        remote_dir = self.hdfs_snapshot_dir()
        remote_meta = json.loads(self.hdfs.open(f"{remote_dir}/meta.json").decode("utf-8"))
        current = self.snapshot.built_at if self.snapshot else ""
        if str(remote_meta.get("built_at", "")) <= current:
            return False
        staging = Path(tempfile.mkdtemp(prefix="snapshot-", dir=self.config.snapshot_dir.parent))
        for name in SNAPSHOT_FILES:
            self.hdfs.download(f"{remote_dir}/{name}", staging / name)
        snapshot = Snapshot.load(staging, (self.config.model_id, self.config.model_version))  # 깨진 파일이면 여기서 실패
        previous = self.config.snapshot_dir.with_name(self.config.snapshot_dir.name + ".previous")
        if previous.exists():
            _rmtree(previous)
        if self.config.snapshot_dir.exists():
            self.config.snapshot_dir.rename(previous)
        staging.rename(self.config.snapshot_dir)
        with self._lock:
            self.snapshot = snapshot
        LOG.info("스냅샷 교체 %s장 built_at=%s", f"{len(snapshot.ids):,}", snapshot.built_at)
        return True

    def run_refresh_loop(self, stop: threading.Event) -> None:
        while not stop.wait(self.config.refresh_seconds):
            try:
                self.refresh_if_newer()
            except Exception as exception:  # noqa: BLE001 — 갱신 실패는 다음 주기에 다시 시도한다
                LOG.warning("스냅샷 갱신 실패: %s", type(exception).__name__)


def _rmtree(path: Path) -> None:
    for child in path.iterdir():
        if child.is_dir():
            _rmtree(child)
        else:
            child.unlink()
    path.rmdir()


# ── 후보 ──────────────────────────────────────────────────────────────


@dataclasses.dataclass(frozen=True)
class Candidate:
    case_id: int
    event_date: dt.date
    photo_ids: tuple[str, ...]


@dataclasses.dataclass(frozen=True)
class UserCandidate:
    """사용자 보호 게시물 후보 — 벡터가 스냅샷에 없으므로 사진 HDFS 경로를 들고 다닌다."""

    case_id: int
    event_date: dt.date
    photos: tuple[tuple[int, str], ...]  # (animal_photo.id, storage_uri) — 사진 교체는 새 id 라 id 로 캐시한다


def photo_vector_id(desertion_no: str, sort_order: int) -> str:
    """공공 사진의 스냅샷 벡터 ID — 수집기가 popfile{n} 을 `{desertionNo}_{n}` 으로 저장했고 적재기의 sort_order 는 0 부터다."""
    return f"{desertion_no}_{sort_order + 1}"


def group_candidates(rows: list[tuple[int, dt.date, str, int]]) -> list[Candidate]:
    """(case_id, event_date, desertion_no, sort_order) 행을 개체별로 묶는다."""
    by_case: dict[int, tuple[dt.date, list[str]]] = {}
    for case_id, event_date, desertion_no, sort_order in rows:
        entry = by_case.setdefault(case_id, (event_date, []))
        entry[1].append(photo_vector_id(desertion_no, sort_order))
    return [Candidate(case_id, event_date, tuple(sorted(set(photo_ids)))) for case_id, (event_date, photo_ids) in by_case.items()]


def group_user_candidates(rows: list[tuple[int, dt.date, int, str]]) -> list[UserCandidate]:
    """(case_id, event_date, photo_id, storage_uri) 행을 게시물별로 묶는다. 질의 사진과 같은 경로 규칙을
    어긴 storage_uri 는 여기서 버린다 — 후보 쪽 데이터 문제로 실행 전체를 실패시키지 않는다."""
    by_case: dict[int, tuple[dt.date, dict[int, str]]] = {}
    for case_id, event_date, photo_id, uri in rows:
        if not uri.startswith(USER_PHOTO_PREFIX) or ".." in uri or "?" in uri:
            continue
        entry = by_case.setdefault(case_id, (event_date, {}))
        entry[1][photo_id] = uri
    return [UserCandidate(case_id, event_date, tuple(sorted(photos.items())))
            for case_id, (event_date, photos) in by_case.items() if photos]


CANDIDATE_SQL = """
select ac.id, ac.event_date, sa.desertion_no, ap.sort_order
from animal_case ac
join shelter_animal sa on sa.animal_case_id = ac.id
join animal_photo ap on ap.animal_case_id = ac.id
join animal_case_location l on l.animal_case_id = ac.id and l.location_type = 'EVENT'
where ac.source_type = 'PUBLIC' and ac.case_type = 'SHELTERING' and ac.status = 'ACTIVE' and ac.is_matchable
  and ac.species = %(species)s
  and ac.event_date >= %(event_date)s
  and left(l.region_code, %(prefix_len)s) = %(region_prefix)s
"""

# 사용자 보호 게시물 후보 (URS UR-OWN-004). member·user_post 는 일부러 조인하지 않는다 — 엔진 계정은
# 그 테이블을 읽을 수 없다(setup-match-db.sh 의 보안 경계). 작성자 탈퇴(member WITHDRAWN) 게시물이
# match_candidate 에 저장될 수 있으나, 표시 계층(MatchResultRepository)이 작성자 ACTIVE 를 최종 검사해
# 사용자에게 노출되지 않는다.
USER_CANDIDATE_SQL = """
select ac.id, ac.event_date, ap.id, ap.storage_uri
from animal_case ac
join animal_photo ap on ap.animal_case_id = ac.id and ap.storage_type = 'USER_UPLOAD'
join animal_case_location l on l.animal_case_id = ac.id and l.location_type = 'EVENT'
where ac.source_type = 'USER' and ac.case_type = 'SHELTERING' and ac.status = 'ACTIVE' and ac.is_matchable
  and ac.deleted_at is null
  and ac.species = %(species)s
  and ac.event_date >= %(event_date)s
  and left(l.region_code, %(prefix_len)s) = %(region_prefix)s
"""


class CandidateRepository:
    """후보 조회. 연결 하나를 오래 쥐고 쓰되, 끊기면 새로 붙어 **한 번** 재시도한다.

    2026-09-18 배포로 PostgreSQL 컨테이너가 다시 만들어지자 엔진은 죽은 연결을 그대로 쥐고 있었고
    (worker 는 죽어서 systemd 가 살렸지만 엔진은 살아남았다), 23일까지 모든 분석이 `OperationalError` 로
    실패했다. [retry_on] 에 psycopg 의 연결 오류 타입을 넣으면 그 오류에서만 다시 붙는다 — SQL 오류처럼
    다시 붙어도 같은 결과가 나올 오류는 그대로 올린다.

    `ThreadingHTTPServer` 가 요청마다 스레드를 띄우므로 조회와 재접속은 잠금 하나로 직렬화한다 — 한
    스레드가 연결을 갈아 끼우는 사이 다른 스레드가 옛 연결로 조회하면 상태가 어긋난다. 후보 조회는
    수십 ms 라 직렬화 비용은 임베딩(초 단위)에 묻힌다.
    """

    def __init__(self, connect: Callable[[], Any], prefix_len: int,
                 retry_on: tuple[type[BaseException], ...] = ()) -> None:
        self._connect = connect
        self.connection = connect()
        self.prefix_len = prefix_len
        self.retry_on = retry_on
        self._lock = threading.Lock()

    def find(self, species: str, event_date: dt.date, region_code: str) -> list[Candidate]:
        rows = self._locked_rows(CANDIDATE_SQL, species, event_date, region_code)
        return group_candidates([(int(r[0]), r[1], str(r[2]), int(r[3])) for r in rows])

    def find_user(self, species: str, event_date: dt.date, region_code: str) -> list[UserCandidate]:
        rows = self._locked_rows(USER_CANDIDATE_SQL, species, event_date, region_code)
        return group_user_candidates([(int(r[0]), r[1], int(r[2]), str(r[3])) for r in rows])

    def _locked_rows(self, sql: str, species: str, event_date: dt.date, region_code: str) -> list[tuple]:
        with self._lock:
            try:
                return self._query(sql, species, event_date, region_code)
            except self.retry_on as exception:
                LOG.warning("후보 조회 연결 오류 %s — 다시 연결해 한 번 재시도한다", type(exception).__name__)
                self._reconnect()
                return self._query(sql, species, event_date, region_code)

    def _query(self, sql: str, species: str, event_date: dt.date, region_code: str) -> list[tuple]:
        try:
            with self.connection.cursor() as cursor:
                cursor.execute(sql, {"species": species, "event_date": event_date,
                                     "prefix_len": self.prefix_len, "region_prefix": region_code[: self.prefix_len]})
                rows = list(cursor.fetchall())
            self.connection.commit()  # 읽기 트랜잭션을 열어 두지 않는다
        except BaseException:
            # 실패한 트랜잭션을 열어 둔 채 다음 조회를 하면 "current transaction is aborted" 로 연쇄 실패한다.
            try:
                self.connection.rollback()
            except Exception:  # noqa: BLE001 — 연결이 이미 죽었으면 rollback 도 실패한다
                pass
            raise
        return rows

    def _reconnect(self) -> None:
        try:
            self.connection.close()
        except Exception:  # noqa: BLE001
            pass
        self.connection = self._connect()


# ── 질의 사진 → 벡터 ──────────────────────────────────────────────────


class Embedder(Protocol):
    def embed(self, images: list[bytes]) -> np.ndarray: ...


class PipelineEmbedder:
    """`ai/app/pipeline.PetEmbeddingPipeline` 을 감싼다 — 배치 임베딩과 같은 모델·전처리(model.yaml)."""

    def __init__(self, ai_dir: Path) -> None:
        if not (ai_dir / "app" / "pipeline.py").is_file():
            raise SystemExit(f"{ai_dir} 에 app/pipeline.py 가 없다")
        sys.path.insert(0, str(ai_dir))
        import yaml  # noqa: PLC0415 — ai venv 에만 있다
        from app.pipeline import PetEmbeddingPipeline  # noqa: PLC0415

        spec = yaml.safe_load((ai_dir / "model.yaml").read_text(encoding="utf-8"))
        self.model = (spec.get("model_id"), spec.get("model_version"))

        def resolve(path: str | None) -> str | None:
            if not path:
                return None
            candidate = Path(path)
            return str(candidate if candidate.is_file() else ai_dir / "models" / candidate.parent.name / candidate.name)

        self.pipeline = PetEmbeddingPipeline(
            device="cpu",
            detector_weights=resolve((spec.get("detector") or {}).get("weights_path")),
            embed_weights=resolve(spec.get("weights_path")),
        )

    def embed(self, images: list[bytes]) -> np.ndarray:
        from PIL import Image  # noqa: PLC0415

        photos = [(str(i), Image.open(io.BytesIO(data)).convert("RGB")) for i, data in enumerate(images)]
        payload = self.pipeline.process_photos(photos)
        rows = [np.asarray(entry["vector"], dtype=np.float32) for entry in payload["embeddings"]]
        matrix = np.vstack(rows)
        norms = np.linalg.norm(matrix, axis=1, keepdims=True)
        return matrix / np.maximum(norms, 1e-12)


# ── 점수 ──────────────────────────────────────────────────────────────


def _scored_entry(query: np.ndarray, vectors: np.ndarray, candidate_case_id: int, event_date: dt.date,
                  query_event_date: dt.date, threshold: float) -> tuple[float, int, int] | None:
    best = float((query @ vectors.T).max())
    best = min(max(best, 0.0), 1.0)  # 코사인은 음수일 수 있으나 계약은 0..1 이다
    if best < threshold:
        return None
    gap = (event_date - query_event_date).days
    return (best, candidate_case_id, max(gap, 0))


def score_public(query: np.ndarray, candidates: list[Candidate], snapshot: Snapshot, query_event_date: dt.date,
                 threshold: float) -> list[tuple[float, int, int]]:
    """계약 0-2·0-3: 공공 후보의 쌍 코사인 최댓값 → 임계값. 폴백 사진은 빼지도 깎지도 않는다(2026-09-14 실측)."""
    scored: list[tuple[float, int, int]] = []
    for candidate in candidates:
        rows = [snapshot.index[pid] for pid in candidate.photo_ids if pid in snapshot.index]
        if not rows:
            continue  # 어젯밤 이후 들어온 사진은 아직 벡터가 없다 — 오늘 22:30 뒤에 후보가 된다
        entry = _scored_entry(query, snapshot.vectors[rows], candidate.case_id, candidate.event_date,
                              query_event_date, threshold)
        if entry:
            scored.append(entry)
    return scored


def score_user(query: np.ndarray, candidates: list[UserCandidate], vectors_by_photo: dict[int, np.ndarray],
               query_event_date: dt.date, threshold: float) -> list[tuple[float, int, int]]:
    """사용자 보호 게시물 후보 — 온디맨드 임베딩 벡터로 같은 규칙(0-2·0-3)을 적용한다."""
    scored: list[tuple[float, int, int]] = []
    for candidate in candidates:
        rows = [vectors_by_photo[pid] for pid, _ in candidate.photos if pid in vectors_by_photo]
        if not rows:
            continue  # 사진을 읽거나 임베딩하지 못한 게시물은 이번 실행에서 건너뛴다
        entry = _scored_entry(query, np.vstack(rows), candidate.case_id, candidate.event_date,
                              query_event_date, threshold)
        if entry:
            scored.append(entry)
    return scored


def rank_rows(scored: list[tuple[float, int, int]], top_k: int) -> list[dict[str, Any]]:
    """계약 2·3: total_score DESC, target_case_id ASC → 상위 K, rank 1부터.

    total_score 는 MVP 에서 image_score 와 같다 — 거리·시간 가중은 정의된 것이 없고,
    공공 케이스에는 좌표가 없어 distance_km 은 null 이다.
    """
    scored = sorted(scored, key=lambda item: (-item[0], item[1]))
    return [{"targetCaseId": case_id, "rank": rank, "totalScore": round(score, 6), "imageScore": round(score, 6),
             "distanceKm": None, "timeGapDays": gap}
            for rank, (score, case_id, gap) in enumerate(scored[:top_k], start=1)]


def score_candidates(query: np.ndarray, candidates: list[Candidate], snapshot: Snapshot, query_event_date: dt.date,
                     threshold: float, top_k: int) -> list[dict[str, Any]]:
    """공공 후보만으로 점수→정렬까지 — score_public + rank_rows 의 합성(기존 호출·테스트 호환)."""
    return rank_rows(score_public(query, candidates, snapshot, query_event_date, threshold), top_k)


class UserPhotoVectors:
    """사용자 후보 사진의 온디맨드 임베딩 캐시.

    사진 교체(P5)는 새 animal_photo 행을 만들므로 photo id 는 불변 내용을 가리킨다 — id 로 캐시하면
    무효화가 필요 없다. 내려받기·임베딩에 실패한 사진은 캐시하지 않고 이번 실행에서만 빠진다(다음
    실행에서 다시 시도). 캐시는 엔진 프로세스 메모리에만 있고 상한을 넘으면 오래된 것부터 버린다.
    """

    def __init__(self, hdfs: WebHdfs, embedder: "Embedder", max_entries: int = 4096) -> None:
        self.hdfs = hdfs
        self.embedder = embedder
        self.max_entries = max_entries
        self._cache: dict[int, np.ndarray] = {}

    def vectors_for(self, candidates: list[UserCandidate]) -> dict[int, np.ndarray]:
        wanted: dict[int, str] = {pid: uri for candidate in candidates for pid, uri in candidate.photos}
        missing = [(pid, uri) for pid, uri in wanted.items() if pid not in self._cache]
        images: list[tuple[int, bytes]] = []
        for pid, uri in missing:
            try:
                images.append((pid, self.hdfs.open(uri)))
            except (urllib.error.URLError, OSError):
                continue  # 후보 사진 하나를 못 읽었다고 실행을 실패시키지 않는다
        if images:
            try:
                matrix = self.embedder.embed([data for _, data in images])
                for (pid, _), vector in zip(images, matrix):
                    if len(self._cache) >= self.max_entries:
                        self._cache.pop(next(iter(self._cache)))
                    self._cache[pid] = vector
            except Exception:  # noqa: BLE001 — 깨진 이미지 등: 이번 실행은 캐시된 사진만으로 진행한다
                LOG.warning("사용자 후보 사진 %d장 임베딩 실패", len(images))
        return {pid: self._cache[pid] for pid in wanted if pid in self._cache}


# ── 요청 검증 ─────────────────────────────────────────────────────────


@dataclasses.dataclass(frozen=True)
class MatchRequest:
    run_id: int
    species: str
    event_date: dt.date
    region_code: str
    photo_paths: tuple[str, ...]


def parse_request(payload: Any, config: Config) -> MatchRequest:
    if not isinstance(payload, dict):
        raise EngineError(400, "BODY_NOT_OBJECT")
    if payload.get("modelId") != config.model_id or payload.get("modelVersion") != config.model_version:
        raise EngineError(409, "MODEL_MISMATCH")
    run_id = payload.get("matchRunId")
    if not isinstance(run_id, int) or isinstance(run_id, bool) or run_id <= 0:
        raise EngineError(400, "INVALID_RUN_ID")
    species = payload.get("species")
    if species not in SPECIES:
        raise EngineError(400, "INVALID_SPECIES")
    event_text = str(payload.get("eventDate") or "")
    if not ISO_DATE.match(event_text):  # fromisoformat 은 3.11 부터 '20260815' 같은 기본형도 받는다 — 계약은 YYYY-MM-DD 다
        raise EngineError(400, "INVALID_EVENT_DATE")
    try:
        event_date = dt.date.fromisoformat(event_text)
    except ValueError as exception:
        raise EngineError(400, "INVALID_EVENT_DATE") from exception
    region_code = str(payload.get("regionCode") or "")
    if not REGION_CODE.match(region_code):
        raise EngineError(400, "INVALID_REGION_CODE")
    photos = payload.get("photos")
    if not isinstance(photos, list) or not 1 <= len(photos) <= MAX_QUERY_PHOTOS:
        raise EngineError(400, "INVALID_PHOTOS")
    paths: list[str] = []
    for photo in photos:
        if not isinstance(photo, dict) or photo.get("storageType") != "USER_UPLOAD":
            raise EngineError(400, "UNSUPPORTED_PHOTO_STORAGE")  # 질의는 사용자 LOST 게시물 사진만이다
        uri = str(photo.get("storageUri") or "")
        if not uri.startswith(USER_PHOTO_PREFIX) or ".." in uri or "?" in uri:
            raise EngineError(400, "INVALID_PHOTO_PATH")
        paths.append(uri)
    return MatchRequest(run_id, species, event_date, region_code, tuple(paths))


# ── 서비스 ────────────────────────────────────────────────────────────


class MatchService:
    def __init__(self, config: Config, store: SnapshotStore, repository: CandidateRepository, embedder: Embedder,
                 photo_reader: WebHdfs) -> None:
        self.config = config
        self.store = store
        self.repository = repository
        self.embedder = embedder
        self.photo_reader = photo_reader
        self.user_vectors = UserPhotoVectors(photo_reader, embedder)
        self._busy = threading.Lock()  # 임베딩은 CPU 를 다 쓰므로 한 번에 하나

    def match(self, request: MatchRequest) -> dict[str, Any]:
        t0 = time.time()
        with self._busy:
            images = [self.photo_reader.open(path) for path in request.photo_paths]
            t_read = time.time()
            query = self.embedder.embed(images)
            t_embed = time.time()
            candidates = self.repository.find(request.species, request.event_date, request.region_code)
            user_candidates = self.repository.find_user(request.species, request.event_date, request.region_code)
            snapshot = self.store.current()
            scored = score_public(query, candidates, snapshot, request.event_date, self.config.threshold)
            scored += score_user(query, user_candidates, self.user_vectors.vectors_for(user_candidates),
                                 request.event_date, self.config.threshold)
            rows = rank_rows(scored, self.config.top_k)
        LOG.info("run=%d 사진 %d장 후보 %d+%d마리 → %d건 (읽기 %.1fs 임베딩 %.1fs 전체 %.1fs)", request.run_id, len(images),
                 len(candidates), len(user_candidates), len(rows), t_read - t0, t_embed - t_read, time.time() - t0)
        return {"matchRunId": request.run_id, "modelId": self.config.model_id, "modelVersion": self.config.model_version,
                "candidates": rows}

    def health(self) -> dict[str, Any]:
        snapshot = self.store.snapshot
        return {"status": "ok" if snapshot else "loading", "modelId": self.config.model_id,
                "modelVersion": self.config.model_version, "threshold": self.config.threshold, "topK": self.config.top_k,
                "snapshot": {"count": len(snapshot.ids), "builtAt": snapshot.built_at} if snapshot else None}


def make_handler(service: MatchService) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        server_version = "meonggo-match-engine/1"

        def log_message(self, format: str, *args: Any) -> None:  # noqa: A002 — 기본 접근 로그(경로·클라이언트)를 끈다
            return

        def _send(self, status: int, body: dict[str, Any]) -> None:
            data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self) -> None:  # noqa: N802
            if self.path == "/health":
                body = service.health()
                self._send(200 if body["status"] == "ok" else 503, body)
            else:
                self._send(404, {"code": "NOT_FOUND"})

        def do_POST(self) -> None:  # noqa: N802
            if self.path not in ("/", "/match"):
                self._send(404, {"code": "NOT_FOUND"})
                return
            length = int(self.headers.get("Content-Length") or 0)
            if not 0 < length <= MAX_REQUEST_BYTES:
                self._send(413, {"code": "BODY_TOO_LARGE"})
                return
            try:
                request = parse_request(json.loads(self.rfile.read(length)), service.config)
            except EngineError as exception:
                self._send(exception.status, {"code": exception.code})
                return
            except (json.JSONDecodeError, UnicodeDecodeError):
                self._send(400, {"code": "INVALID_JSON"})
                return
            try:
                self._send(200, service.match(request))
            except urllib.error.URLError:
                LOG.warning("run=%d 질의 사진을 읽지 못했다", request.run_id)
                self._send(502, {"code": "PHOTO_UNAVAILABLE"})
            except Exception as exception:  # noqa: BLE001 — 원문은 남기지 않고 종류만
                LOG.error("run=%d 처리 실패 %s", request.run_id, type(exception).__name__)
                self._send(500, {"code": "ENGINE_FAILURE"})

    return Handler


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fetch-only", action="store_true", help="스냅샷만 HDFS 에서 내려받고 끝낸다 (첫 설치용)")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    config = Config.from_env()
    hdfs = WebHdfs(config.webhdfs, config.hdfs_user)
    store = SnapshotStore(config, hdfs)
    config.snapshot_dir.parent.mkdir(parents=True, exist_ok=True)
    if not (config.snapshot_dir / "meta.json").is_file():
        LOG.info("로컬 스냅샷이 없다 — HDFS 에서 내려받는다")
        store.refresh_if_newer()
    else:
        store.load_local()
    if args.fetch_only:
        return 0
    try:
        import psycopg  # noqa: PLC0415
    except ImportError as exception:
        raise SystemExit("psycopg 가 필요하다 (ai venv 에 psycopg[binary] 설치)") from exception
    embedder = PipelineEmbedder(config.ai_dir)
    if embedder.model != (config.model_id, config.model_version):
        raise SystemExit(f"model.yaml {embedder.model} 이 설정 {(config.model_id, config.model_version)} 과 다르다")
    repository = CandidateRepository(
        lambda: psycopg.connect(config.postgres_dsn, autocommit=False),
        config.region_prefix_len,
        # 서버 재기동·네트워크 단절로 끊긴 연결만 다시 붙는다. SQL·데이터 오류는 재시도 대상이 아니다.
        retry_on=(psycopg.OperationalError, psycopg.InterfaceError),
    )
    service = MatchService(config, store, repository, embedder, hdfs)
    stop = threading.Event()
    threading.Thread(target=store.run_refresh_loop, args=(stop,), daemon=True, name="snapshot-refresh").start()
    server = ThreadingHTTPServer((config.bind, config.port), make_handler(service))
    LOG.info("매칭 엔진 기동 %s:%d 임계값 %.2f K=%d 지역 접두 %d자리", config.bind, config.port, config.threshold,
             config.top_k, config.region_prefix_len)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
