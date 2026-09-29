from __future__ import annotations

import datetime as dt
import os
import time
from pathlib import Path

import pytest

psycopg = pytest.importorskip("psycopg")

from data.matching_worker.worker import (  # noqa: E402
    EngineTimeout,
    MatchCandidate,
    MatchingWorker,
    MatchRunRepository,
    WorkerError,
)

DSN = os.environ.get("MATCH_WORKER_TEST_DSN")
pytestmark = pytest.mark.skipif(not DSN, reason="MATCH_WORKER_TEST_DSN is not configured")
UTC = dt.timezone.utc


class Clock:
    def __init__(self):
        self.value = dt.datetime(2026, 9, 11, tzinfo=UTC)

    def __call__(self):
        return self.value


@pytest.fixture(scope="session", autouse=True)
def prepare_schema():
    if not DSN or os.environ.get("MATCH_WORKER_TEST_APPLY_SCHEMA") != "1":
        yield
        return
    connection = None
    for _attempt in range(30):
        try:
            connection = psycopg.connect(DSN, autocommit=True)
            break
        except psycopg.OperationalError:
            time.sleep(1)
    if connection is None:
        raise RuntimeError("matching worker test database did not become ready")
    try:
        schema = Path("backend/src/main/resources/db/migration/V1__initial_schema.sql")
        connection.execute(schema.read_text(encoding="utf-8"))
    finally:
        connection.close()
    yield


@pytest.fixture
def database():
    connection = psycopg.connect(DSN, autocommit=True)
    with connection.cursor() as cursor:
        cursor.execute("truncate match_candidate, match_run, animal_photo, animal_case cascade")
    try:
        yield connection
    finally:
        connection.close()


def insert_case(connection, case_type="LOST", source_type="USER", species="DOG") -> int:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            insert into animal_case(
                case_type,source_type,status,is_matchable,version,listed_at,species,
                sex,event_date,created_at,updated_at)
            values (%s,%s,'ACTIVE',true,3,clock_timestamp(),%s,'UNKNOWN',
                    date '2026-09-01',clock_timestamp(),clock_timestamp())
            returning id
            """,
            (case_type, source_type, species),
        )
        case_id = cursor.fetchone()[0]
        cursor.execute(
            """
            insert into animal_case_location(
                animal_case_id,location_type,region_code,public_location)
            values (%s,'EVENT','11680','서울특별시 강남구')
            """,
            (case_id,),
        )
        if case_type == "LOST":
            cursor.execute(
                """
                insert into animal_photo(
                    animal_case_id,storage_type,storage_uri,content_type,byte_size,
                    width_px,height_px,sort_order,checksum_sha256,created_at)
                values (%s,'USER_UPLOAD',%s,'image/jpeg',1024,640,480,0,%s,clock_timestamp())
                """,
                (case_id, f"/data/user/images/{case_id}/1.jpg", "a" * 64),
            )
        return case_id


def insert_run(connection, query_case_id: int) -> int:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            insert into match_run(
                query_case_id,query_case_version,status,model_id,model_version,created_at)
            values (%s,3,'PENDING','dinov2_vitb14','v2',clock_timestamp())
            returning id
            """,
            (query_case_id,),
        )
        return cursor.fetchone()[0]


def state(connection, run_id: int):
    with connection.cursor() as cursor:
        cursor.execute(
            """
            select status,candidate_count,error_code,started_at,completed_at
            from match_run where id=%s
            """,
            (run_id,),
        )
        return cursor.fetchone()


def test_claim_is_single_winner_and_zero_candidates_complete_successfully(database):
    clock = Clock()
    query = insert_case(database)
    run_id = insert_run(database, query)
    first = MatchRunRepository(database, clock)
    second_connection = psycopg.connect(DSN, autocommit=True)
    try:
        second = MatchRunRepository(second_connection, clock)
        job = first.claim_next()

        assert job is not None and job.run_id == run_id
        assert second.claim_next() is None
        assert first.complete(job, ()) is True
        assert state(database, run_id) == (
            "SUCCEEDED",
            0,
            None,
            clock.value,
            clock.value,
        )
    finally:
        second_connection.close()


def test_candidates_and_success_transition_commit_atomically(database):
    clock = Clock()
    query = insert_case(database)
    target = insert_case(database, "SHELTERING", "PUBLIC")
    run_id = insert_run(database, query)
    repository = MatchRunRepository(database, clock)
    job = repository.claim_next()

    assert repository.complete(
        job, (MatchCandidate(target, 1, 0.87, 0.9, 1.25, 3),)
    )
    with database.cursor() as cursor:
        cursor.execute(
            "select target_case_id,rank,total_score from match_candidate where match_run_id=%s",
            (run_id,),
        )
        assert cursor.fetchone() == (target, 1, 0.87)
    assert state(database, run_id)[:3] == ("SUCCEEDED", 1, None)


def test_watchdog_fails_stale_run_and_rejects_late_completion(database):
    clock = Clock()
    query = insert_case(database)
    run_id = insert_run(database, query)
    repository = MatchRunRepository(database, clock)
    job = repository.claim_next()
    clock.value += dt.timedelta(minutes=5, seconds=1)

    assert repository.recover_stale_runs() == 1
    assert repository.complete(job, ()) is False
    assert state(database, run_id)[:3] == ("FAILED", 0, "MATCH_TIMEOUT")


def test_worker_persists_only_safe_failure_code(database):
    clock = Clock()
    query = insert_case(database)
    run_id = insert_run(database, query)

    class FailingEngine:
        def match(self, _job):
            raise WorkerError("private-photo-and-provider-details")

    assert MatchingWorker(MatchRunRepository(database, clock), FailingEngine()).run_once()
    assert state(database, run_id)[:3] == ("FAILED", 0, "MATCH_FAILED")
    with database.cursor() as cursor:
        cursor.execute("select count(*) from match_candidate where match_run_id=%s", (run_id,))
        assert cursor.fetchone()[0] == 0


def test_worker_persists_timeout_after_engine_timeout(database):
    clock = Clock()
    query = insert_case(database)
    run_id = insert_run(database, query)

    class TimeoutEngine:
        def match(self, _job):
            raise EngineTimeout("private-engine-timeout")

    assert MatchingWorker(MatchRunRepository(database, clock), TimeoutEngine()).run_once()
    assert state(database, run_id)[:3] == ("FAILED", 0, "MATCH_TIMEOUT")
