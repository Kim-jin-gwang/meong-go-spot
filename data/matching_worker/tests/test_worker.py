from __future__ import annotations

import datetime as dt
import json
import socket
import urllib.error
from dataclasses import replace

import pytest

from data.matching_worker.worker import (
    EngineTimeout,
    MatchCandidate,
    MatchJob,
    MatchingWorker,
    HttpMatchEngine,
    QueryPhoto,
    WorkerError,
    parse_engine_response,
    validate_candidates,
    validate_endpoint,
)


def job() -> MatchJob:
    return MatchJob(
        run_id=7,
        query_case_id=11,
        query_case_version=3,
        model_id="dinov2_vitb14",
        model_version="v2",
        species="DOG",
        event_date=dt.date(2026, 9, 1),
        region_code="11680",
        photos=(QueryPhoto(31, "USER_UPLOAD", "/data/user/images/11/31.jpg"),),
    )


class FakeRepository:
    def __init__(self, claimed: MatchJob | None = None):
        self.claimed = claimed
        self.completed: tuple[MatchJob, tuple[MatchCandidate, ...]] | None = None
        self.failed: tuple[int, str] | None = None
        self.recovered = 0

    def recover_stale_runs(self) -> int:
        return self.recovered

    def claim_next(self) -> MatchJob | None:
        claimed, self.claimed = self.claimed, None
        return claimed

    def complete(self, claimed: MatchJob, candidates) -> bool:
        self.completed = (claimed, tuple(candidates))
        return True

    def fail(self, run_id: int, code: str) -> bool:
        self.failed = (run_id, code)
        return True


class FakeEngine:
    def __init__(self, result=(), failure: Exception | None = None):
        self.result = result
        self.failure = failure
        self.calls: list[MatchJob] = []

    def match(self, claimed: MatchJob):
        self.calls.append(claimed)
        if self.failure:
            raise self.failure
        return self.result


def test_zero_candidate_success_and_duplicate_poll_is_empty():
    repository = FakeRepository(job())
    engine = FakeEngine()
    worker = MatchingWorker(repository, engine)

    assert worker.run_once() is True
    assert repository.completed == (job(), ())
    assert repository.failed is None
    assert worker.run_once() is False
    assert engine.calls == [job()]


@pytest.mark.parametrize(
    ("failure", "code"),
    [(EngineTimeout("private-timeout"), "MATCH_TIMEOUT"), (WorkerError("private"), "MATCH_FAILED")],
)
def test_engine_failures_are_reduced_to_safe_codes(failure, code):
    repository = FakeRepository(job())

    assert MatchingWorker(repository, FakeEngine(failure=failure)).run_once() is True
    assert repository.failed == (job().run_id, code)


def test_response_preserves_engine_ranking_without_recomputing_scores():
    payload = {
        "matchRunId": 7,
        "modelId": "dinov2_vitb14",
        "modelVersion": "v2",
        "candidates": [
            {
                "targetCaseId": 90,
                "rank": 1,
                "totalScore": 0.8,
                "imageScore": 0.7,
                "distanceKm": 2.5,
                "timeGapDays": 3,
            },
            {"targetCaseId": 91, "rank": 2, "totalScore": 0.7},
        ],
    }

    candidates = parse_engine_response(job(), json.loads(json.dumps(payload)))

    assert [(candidate.target_case_id, candidate.rank) for candidate in candidates] == [
        (90, 1),
        (91, 2),
    ]
    assert candidates[0].total_score == 0.8


@pytest.mark.parametrize(
    "candidates",
    [
        [MatchCandidate(1, 2, 0.8, None, None, None)],
        [MatchCandidate(1, 1, 0.8, None, None, None), MatchCandidate(1, 2, 0.7, None, None, None)],
        [MatchCandidate(1, 1, 1.1, None, None, None)],
        [MatchCandidate(1, 1, 0.6, None, None, None), MatchCandidate(2, 2, 0.7, None, None, None)],
        [MatchCandidate(2, 1, 0.7, None, None, None), MatchCandidate(1, 2, 0.7, None, None, None)],
    ],
)
def test_rejects_invalid_engine_candidate_contract(candidates):
    with pytest.raises(WorkerError):
        validate_candidates(candidates)


def test_rejects_response_for_another_run_or_model():
    base = {
        "matchRunId": 7,
        "modelId": "dinov2_vitb14",
        "modelVersion": "v2",
        "candidates": [],
    }
    for changed in (
        {**base, "matchRunId": 8},
        {**base, "modelVersion": "v1"},
        {**base, "candidates": [{"targetCaseId": True, "rank": 1, "totalScore": 0.8}]},
        {**base, "candidates": [{"targetCaseId": 1, "rank": 1.5, "totalScore": 0.8}]},
    ):
        with pytest.raises(WorkerError):
            parse_engine_response(job(), changed)


def test_job_payload_has_only_engine_contract_fields():
    payload = job().payload()

    assert set(payload) == {
        "matchRunId",
        "queryCaseId",
        "queryCaseVersion",
        "modelId",
        "modelVersion",
        "species",
        "eventDate",
        "regionCode",
        "photos",
    }
    assert set(payload["photos"][0]) == {"photoId", "storageType", "storageUri"}


def test_endpoint_rejects_credentials_query_and_unsupported_schemes():
    assert validate_endpoint("http://matching-engine:8090/v1/matches")
    for endpoint in (
        "file:///private/engine",
        "http://user:secret@engine/v1/matches",
        "http://engine/v1/matches?token=private",
    ):
        with pytest.raises(ValueError):
            validate_endpoint(endpoint)


class FakeResponse:
    def __init__(self, payload: bytes):
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def read(self, limit: int):
        return self.payload[:limit]


def test_http_engine_uses_bounded_response_and_validates_identity(monkeypatch):
    payload = json.dumps(
        {
            "matchRunId": 7,
            "modelId": "dinov2_vitb14",
            "modelVersion": "v2",
            "candidates": [],
        }
    ).encode()
    monkeypatch.setattr("urllib.request.urlopen", lambda request, timeout: FakeResponse(payload))

    assert HttpMatchEngine("http://matching-engine:8090/v1/matches").match(job()) == ()


def test_http_engine_maps_timeout_and_rejects_oversized_response(monkeypatch):
    def timeout(_request, timeout):
        del timeout
        raise TimeoutError("private-network-detail")

    monkeypatch.setattr("urllib.request.urlopen", timeout)
    with pytest.raises(EngineTimeout, match="matching engine timeout"):
        HttpMatchEngine("http://matching-engine:8090/v1/matches").match(job())

    def url_error_timeout(_request, timeout):
        del timeout
        raise urllib.error.URLError(socket.timeout("private-network-detail"))

    monkeypatch.setattr("urllib.request.urlopen", url_error_timeout)
    with pytest.raises(EngineTimeout, match="matching engine timeout"):
        HttpMatchEngine("http://matching-engine:8090/v1/matches").match(job())

    monkeypatch.setattr(
        "urllib.request.urlopen",
        lambda request, timeout: FakeResponse(b"x" * (64 * 1024 + 1)),
    )
    with pytest.raises(WorkerError, match="response too large"):
        HttpMatchEngine("http://matching-engine:8090/v1/matches").match(job())


def test_dataclass_fixture_is_immutable():
    assert replace(job(), run_id=8).run_id == 8


def test_equal_scores_require_target_case_id_tie_breaker():
    candidates = validate_candidates(
        [
            MatchCandidate(1, 1, 0.7, None, None, None),
            MatchCandidate(2, 2, 0.7, None, None, None),
        ]
    )

    assert [candidate.target_case_id for candidate in candidates] == [1, 2]
