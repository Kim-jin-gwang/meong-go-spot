"""Claim match runs, delegate scoring, and persist final candidates atomically."""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import json
import logging
import math
import os
import signal
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Sequence
from typing import Any, Protocol

LOG = logging.getLogger("matching-worker")
UTC = dt.timezone.utc
MAX_CANDIDATES = 20
MAX_RESPONSE_BYTES = 64 * 1024
WATCHDOG_AGE = dt.timedelta(minutes=5)
HARD_TIMEOUT_SECONDS = 60.0
SAFE_FAILURE_CODES = frozenset({"MATCH_FAILED", "MATCH_TIMEOUT"})


class WorkerError(RuntimeError):
    """A failure whose external details must not be persisted or logged."""


class EngineTimeout(WorkerError):
    """The matching engine exceeded the hard deadline."""


@dataclasses.dataclass(frozen=True)
class QueryPhoto:
    photo_id: int
    storage_type: str
    storage_uri: str


@dataclasses.dataclass(frozen=True)
class MatchJob:
    run_id: int
    query_case_id: int
    query_case_version: int
    model_id: str
    model_version: str
    species: str
    event_date: dt.date
    region_code: str
    photos: tuple[QueryPhoto, ...]

    def payload(self) -> dict[str, Any]:
        return {
            "matchRunId": self.run_id,
            "queryCaseId": self.query_case_id,
            "queryCaseVersion": self.query_case_version,
            "modelId": self.model_id,
            "modelVersion": self.model_version,
            "species": self.species,
            "eventDate": self.event_date.isoformat(),
            "regionCode": self.region_code,
            "photos": [
                {
                    "photoId": photo.photo_id,
                    "storageType": photo.storage_type,
                    "storageUri": photo.storage_uri,
                }
                for photo in self.photos
            ],
        }


@dataclasses.dataclass(frozen=True)
class MatchCandidate:
    target_case_id: int
    rank: int
    total_score: float
    image_score: float | None
    distance_km: float | None
    time_gap_days: int | None


class MatchEngine(Protocol):
    def match(self, job: MatchJob) -> tuple[MatchCandidate, ...]: ...


class MatchRunRepository:
    """Owns the match_run state machine; every public method is one transaction."""

    def __init__(self, connection: Any, clock: Callable[[], dt.datetime] | None = None):
        self.connection = connection
        self.clock = clock or (lambda: dt.datetime.now(UTC))

    def recover_stale_runs(self) -> int:
        cutoff = self.clock() - WATCHDOG_AGE
        with self.connection.transaction():
            with self.connection.cursor() as cursor:
                cursor.execute(
                    """
                    update match_run
                    set status='FAILED', candidate_count=0, error_code='MATCH_TIMEOUT',
                        completed_at=%s
                    where status='RUNNING' and started_at < %s
                    """,
                    (self.clock(), cutoff),
                )
                return cursor.rowcount

    def claim_next(self) -> MatchJob | None:
        now = self.clock()
        with self.connection.transaction():
            with self.connection.cursor() as cursor:
                cursor.execute(
                    """
                    with next_run as (
                        select r.id, r.query_case_id, r.query_case_version,
                               r.model_id, r.model_version, q.version as current_version,
                               q.case_type, q.source_type, q.status as case_status,
                               q.is_matchable, q.species, q.event_date, l.region_code
                        from match_run r
                        join animal_case q on q.id=r.query_case_id
                        left join animal_case_location l
                          on l.animal_case_id=q.id and l.location_type='EVENT'
                        where r.status='PENDING'
                        order by r.created_at, r.id
                        for update of r skip locked
                        limit 1
                    )
                    update match_run r
                    set status='RUNNING', started_at=%s
                    from next_run n
                    where r.id=n.id and r.status='PENDING'
                    returning r.id, r.query_case_id, r.query_case_version,
                              r.model_id, r.model_version, n.current_version, n.case_type,
                              n.source_type, n.case_status, n.is_matchable, n.species,
                              n.event_date, n.region_code
                    """,
                    (now,),
                )
                row = cursor.fetchone()
                if row is None:
                    return None
                (
                    run_id,
                    query_case_id,
                    requested_version,
                    model_id,
                    model_version,
                    current_version,
                    case_type,
                    source_type,
                    status,
                    is_matchable,
                    species,
                    event_date,
                    region_code,
                ) = row
                if (
                    requested_version != current_version
                    or case_type != "LOST"
                    or source_type != "USER"
                    or status != "ACTIVE"
                    or not is_matchable
                    or event_date is None
                    or region_code is None
                ):
                    self._fail_claimed(cursor, run_id, now)
                    return None
                cursor.execute(
                    """
                    select id, storage_type, storage_uri
                    from animal_photo
                    where animal_case_id=%s
                    order by sort_order, id
                    """,
                    (query_case_id,),
                )
                photos = tuple(QueryPhoto(*photo) for photo in cursor.fetchall())
                if not photos:
                    self._fail_claimed(cursor, run_id, now)
                    return None
                return MatchJob(
                    run_id,
                    query_case_id,
                    requested_version,
                    model_id,
                    model_version,
                    species,
                    event_date,
                    region_code,
                    photos,
                )

    @staticmethod
    def _fail_claimed(cursor: Any, run_id: int, now: dt.datetime) -> None:
        cursor.execute(
            """
            update match_run
            set status='FAILED', candidate_count=0, error_code='MATCH_FAILED',
                completed_at=%s
            where id=%s and status='RUNNING'
            """,
            (now, run_id),
        )

    def complete(self, job: MatchJob, candidates: Sequence[MatchCandidate]) -> bool:
        validated = validate_candidates(candidates)
        with self.connection.transaction():
            with self.connection.cursor() as cursor:
                cursor.execute(
                    """
                    select r.status, r.query_case_id, r.query_case_version,
                           r.model_id, r.model_version, q.version
                    from match_run r join animal_case q on q.id=r.query_case_id
                    where r.id=%s
                    for update of r
                    """,
                    (job.run_id,),
                )
                state = cursor.fetchone()
                if state is None or state[0] != "RUNNING":
                    return False
                expected = (
                    job.query_case_id,
                    job.query_case_version,
                    job.model_id,
                    job.model_version,
                    job.query_case_version,
                )
                if state[1:] != expected:
                    raise WorkerError("query changed before completion")
                self._validate_targets(cursor, job, validated)
                cursor.executemany(
                    """
                    insert into match_candidate(
                        match_run_id,target_case_id,rank,total_score,image_score,
                        distance_km,time_gap_days,created_at)
                    values (%s,%s,%s,%s,%s,%s,%s,%s)
                    """,
                    [
                        (
                            job.run_id,
                            candidate.target_case_id,
                            candidate.rank,
                            candidate.total_score,
                            candidate.image_score,
                            candidate.distance_km,
                            candidate.time_gap_days,
                            self.clock(),
                        )
                        for candidate in validated
                    ],
                )
                cursor.execute(
                    """
                    update match_run
                    set status='SUCCEEDED', candidate_count=%s, error_code=null,
                        completed_at=%s
                    where id=%s and status='RUNNING'
                    """,
                    (len(validated), self.clock(), job.run_id),
                )
                if cursor.rowcount != 1:
                    raise WorkerError("run state changed before completion")
                return True

    def fail(self, run_id: int, error_code: str) -> bool:
        if error_code not in SAFE_FAILURE_CODES:
            raise ValueError("unsafe match failure code")
        now = self.clock()
        with self.connection.transaction():
            with self.connection.cursor() as cursor:
                cursor.execute(
                    """
                    update match_run
                    set status='FAILED', candidate_count=0, error_code=%s,
                        started_at=coalesce(started_at,%s), completed_at=%s
                    where id=%s and status in ('PENDING','RUNNING')
                    """,
                    (error_code, now, now, run_id),
                )
                return cursor.rowcount == 1

    @staticmethod
    def _validate_targets(
        cursor: Any, job: MatchJob, candidates: Sequence[MatchCandidate]
    ) -> None:
        if not candidates:
            return
        target_ids = [candidate.target_case_id for candidate in candidates]
        # 계약 0-0: 후보 출처는 공공 입소 동물(PUBLIC)과 사용자 보호 게시물(USER) 둘 다다.
        cursor.execute(
            """
            select count(*)
            from animal_case target
            where target.id = any(%s)
              and target.case_type='SHELTERING'
              and target.source_type in ('PUBLIC','USER')
              and target.status='ACTIVE'
              and target.is_matchable=true
              and target.deleted_at is null
              and target.species=%s
              and target.event_date >= %s
            """,
            (target_ids, job.species, job.event_date),
        )
        if cursor.fetchone()[0] != len(target_ids):
            raise WorkerError("engine returned ineligible target")


class HttpMatchEngine:
    """Calls a long-running DATA/AI engine through a bounded JSON contract."""

    def __init__(self, endpoint: str, timeout_seconds: float = HARD_TIMEOUT_SECONDS):
        self.endpoint = validate_endpoint(endpoint)
        if not 0 < timeout_seconds <= HARD_TIMEOUT_SECONDS:
            raise ValueError("engine timeout must be within 60 seconds")
        self.timeout_seconds = timeout_seconds

    def match(self, job: MatchJob) -> tuple[MatchCandidate, ...]:
        body = json.dumps(job.payload(), separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            self.endpoint,
            data=body,
            method="POST",
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                raw = response.read(MAX_RESPONSE_BYTES + 1)
        except TimeoutError as exception:
            raise EngineTimeout("matching engine timeout") from exception
        except urllib.error.URLError as exception:
            if isinstance(exception.reason, (TimeoutError, socket.timeout)):
                raise EngineTimeout("matching engine timeout") from exception
            raise WorkerError("matching engine unavailable") from exception
        except OSError as exception:
            raise WorkerError("matching engine unavailable") from exception
        if len(raw) > MAX_RESPONSE_BYTES:
            raise WorkerError("matching engine response too large")
        try:
            payload = json.loads(raw)
            return parse_engine_response(job, payload)
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exception:
            raise WorkerError("invalid matching engine response") from exception


class MatchingWorker:
    def __init__(self, repository: MatchRunRepository, engine: MatchEngine):
        self.repository = repository
        self.engine = engine

    def run_once(self) -> bool:
        recovered = self.repository.recover_stale_runs()
        if recovered:
            LOG.warning("Recovered stale matching runs: count=%d code=MATCH_TIMEOUT", recovered)
        job: MatchJob | None = None
        try:
            job = self.repository.claim_next()
            if job is None:
                return False
            candidates = self.engine.match(job)
            if self.repository.complete(job, candidates):
                LOG.info("Matching run completed: matchRunId=%d count=%d", job.run_id, len(candidates))
            return True
        except EngineTimeout:
            if job is not None:
                self.repository.fail(job.run_id, "MATCH_TIMEOUT")
                LOG.warning("Matching run failed: matchRunId=%d code=MATCH_TIMEOUT", job.run_id)
            return True
        except (WorkerError, RuntimeError):
            if job is not None:
                self.repository.fail(job.run_id, "MATCH_FAILED")
                LOG.warning("Matching run failed: matchRunId=%d code=MATCH_FAILED", job.run_id)
            else:
                LOG.warning("Matching claim deferred: code=MATCH_FAILED")
            return job is not None


def validate_candidates(candidates: Sequence[MatchCandidate]) -> tuple[MatchCandidate, ...]:
    result = tuple(candidates)
    if len(result) > MAX_CANDIDATES:
        raise WorkerError("too many candidates")
    if [candidate.rank for candidate in result] != list(range(1, len(result) + 1)):
        raise WorkerError("candidate ranks are not contiguous")
    if len({candidate.target_case_id for candidate in result}) != len(result):
        raise WorkerError("duplicate candidate target")
    for candidate in result:
        if candidate.target_case_id <= 0:
            raise WorkerError("invalid target case")
        if not math.isfinite(candidate.total_score) or not 0 <= candidate.total_score <= 1:
            raise WorkerError("invalid total score")
        if candidate.image_score is not None and (
            not math.isfinite(candidate.image_score) or not 0 <= candidate.image_score <= 1
        ):
            raise WorkerError("invalid image score")
        if candidate.distance_km is not None and (
            not math.isfinite(candidate.distance_km) or candidate.distance_km < 0
        ):
            raise WorkerError("invalid distance")
        if candidate.time_gap_days is not None and candidate.time_gap_days < 0:
            raise WorkerError("invalid time gap")
    if list(result) != sorted(
        result, key=lambda candidate: (-candidate.total_score, candidate.target_case_id)
    ):
        raise WorkerError("candidates are not stably score ordered")
    return result


def parse_engine_response(job: MatchJob, payload: Any) -> tuple[MatchCandidate, ...]:
    if not isinstance(payload, dict):
        raise WorkerError("engine response must be an object")
    if payload.get("matchRunId") != job.run_id:
        raise WorkerError("engine response run mismatch")
    if payload.get("modelId") != job.model_id or payload.get("modelVersion") != job.model_version:
        raise WorkerError("engine response model mismatch")
    rows = payload.get("candidates")
    if not isinstance(rows, list):
        raise WorkerError("engine candidates must be a list")
    candidates = tuple(
        MatchCandidate(
            target_case_id=required_int(row, "targetCaseId"),
            rank=required_int(row, "rank"),
            total_score=required_float(row, "totalScore"),
            image_score=optional_float(row.get("imageScore")),
            distance_km=optional_float(row.get("distanceKm")),
            time_gap_days=optional_int(row.get("timeGapDays")),
        )
        for row in rows
        if isinstance(row, dict)
    )
    if len(candidates) != len(rows):
        raise WorkerError("engine candidate must be an object")
    return validate_candidates(candidates)


def optional_float(value: Any) -> float | None:
    if value is None:
        return None
    return numeric_float(value)


def optional_int(value: Any) -> int | None:
    if value is None:
        return None
    if type(value) is not int:
        raise WorkerError("engine integer field has invalid type")
    return value


def required_int(row: dict[str, Any], key: str) -> int:
    value = row[key]
    if type(value) is not int:
        raise WorkerError("engine integer field has invalid type")
    return value


def required_float(row: dict[str, Any], key: str) -> float:
    return numeric_float(row[key])


def numeric_float(value: Any) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise WorkerError("engine numeric field has invalid type")
    return float(value)


def validate_endpoint(endpoint: str) -> str:
    parsed = urllib.parse.urlsplit(endpoint)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("matching engine URL must use http or https")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("matching engine URL must not contain credentials, query, or fragment")
    return endpoint


def required_environment(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise SystemExit(f"Missing required setting: {name}")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description="PostgreSQL-backed matching worker")
    parser.add_argument("--once", action="store_true", help="process at most one run")
    parser.add_argument("--poll-seconds", type=float, default=1.0)
    args = parser.parse_args()
    if not 0.1 <= args.poll_seconds <= 60:
        raise SystemExit("--poll-seconds must be between 0.1 and 60")
    dsn = required_environment("MATCH_WORKER_POSTGRES_DSN")
    endpoint = required_environment("MATCH_ENGINE_URL")
    try:
        import psycopg
    except ImportError as exception:
        raise SystemExit("psycopg is required") from exception
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    stopping = False

    def stop(_signum: int, _frame: Any) -> None:
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    with psycopg.connect(dsn) as connection:
        worker = MatchingWorker(MatchRunRepository(connection), HttpMatchEngine(endpoint))
        while not stopping:
            processed = worker.run_once()
            if args.once:
                break
            if not processed:
                time.sleep(args.poll_seconds)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
