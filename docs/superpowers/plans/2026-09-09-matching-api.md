# Matching API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement M2 analysis acceptance and M1 private candidate polling for Jira #93, 95, 96, 97.

**Architecture:** PostgreSQL `match_run(PENDING)` is the durable DATA queue. Spring accepts requests and reads stored results; DATA owns claiming, computation, atomic candidate completion and watchdog (94 remains separate). Explicit JDBC projections and response records prevent internal values from reaching the API.

**Tech Stack:** Java 21, Spring Boot, JdbcTemplate, PostgreSQL Flyway, MockMvc/Testcontainers.

**Spec:** `docs/api-spec.md` §7, `docs/backend-security-operations-policy.md` §6, `docs/data-ai-interface.md` §3, `docs/erd.md` match_run/match_candidate.

## Global Constraints

- Source `dev` 5c1097b; branch `feat/matching-api` targets `dev`. Preserve `.vscode/`. No rebase/force push. Existing authorization covers implementation, commits, push and MR; user merges.
- Keep V1 unchanged. Existing schema already supplies rank/count bounds and unique active run. No DATA computation, worker, candidate writes or auto-analysis in Spring.
- Guard order: absent/deleted/withdrawn author 404 POST-001, PUBLIC/SHELTERING 409 MATCH-001, other owner 403 POST-002, own CLOSED 409 POST-003. Authentication 401 remains Security responsibility.
- M2 locks active viewer/member then case row, snapshots case version without modifying it, returns existing PENDING/RUNNING or inserts one PENDING transactionally. Model tuple is configuration pinned to approved ai/model.yaml (`dinov2_vitb14/v2` default); worker must verify same model/version, never silently substitute ACTIVE.
- M1 uses one read-only repeatable-read transaction for guard, latest request, latest success, candidates. Latest request ordered created_at DESC,id DESC; latest success completed_at DESC,id DESC (existing index). If success version < case version, STALE overrides analysisStatus even with newer pending/failed; latestRun still reports actual state and polling interval is based on latestRun. Previous-result flag compares run IDs.
- Candidate list preserves stored rank (no score re-sort/re-rank), max20. USER ACTIVE/matchable/active author; PUBLIC ACTIVE or CLOSED/matchable. Exclude deleted or missing source/EVENT. Only EVENT.publicLocation. USER author nickname only; PUBLIC shelter name/phone (null retained). No exact location, coordinates, CURRENT, member ID/phone, internal scores/distances/time gaps or storage paths.
- latestRun.candidateCount is stored successful run count (0..20); live filters can make visible candidates shorter. Failed output allowlist: MATCH_TIMEOUT; all other worker errors map to MATCH_FAILED. No raw error values in response/logs.
- One Gradle process at a time. Test-first expected API404 failures, then green targeted tests; full `npm run check` before commits; independent review and pre-mr-readiness, inspect actual Gemini review without quota retries.

### Task 1: Durable request acceptance

**Files:** matching/controller/MatchRunController.java, matching/service/MatchRequestService.java, matching/service/MatchAccessGuard.java, matching/repository/MatchRequestRepository.java, matching/dto/MatchRequestResponse.java, matching/exception/MatchErrorCode.java, matching/config/MatchingConfiguration.java under backend/src/main/java/com/meonggo/backend; test matching/MatchRequestApiTest.java; application.yml and .env.example model configuration.

**Interfaces:** `MatchAccessGuard.requireEligible(long postId,long memberId,boolean lock)` returns existing `PostMutationRepository.Target`; caller owns transaction. `MatchRequestService.request(long postId,long memberId)` returns MatchRequestResponse(postId,matchRunId,status,createdAt).

- [x] Add authenticated PostgreSQL API tests: acceptance202, snapshot version/model, no animal_case mutation, duplicate and parallel requests same ID, RUNNING reuse, new run after success/failure, guards and no history leak. Execute `node scripts/gradlew.mjs backend test --tests '*MatchRequestApiTest' --console=plain`; first expect 404 instead of202.
- [x] Implement guarded TransactionTemplate request path and explicit INSERT RETURNING. Hold case lock until active lookup/insert completes; DB partial unique index backs this up. Sanitize DataAccessException/TransactionException to cause-less COMMON-500. Use server model config with bounded identifier validation. No body DTO/version requirement.
- [x] Run tests and report command/log/count. Worker does not commit or spawn agents.

### Task 2: Candidate polling and retained result policy

**Files:** matching/controller/MatchCandidateController.java, matching/service/MatchCandidateService.java, matching/repository/MatchResultRepository.java, matching/dto/MatchCandidatesResponse.java; test matching/MatchCandidateApiTest.java.

**Migration:** V2__index_latest_match_request.sql adds (query_case_id,created_at DESC,id DESC) for polling latest requests of any status; existing partial indexes only serve active/success subsets. V1 remains unchanged, previous app remains compatible.

**Interfaces:** consumes Task1 MatchAccessGuard; `MatchCandidateService.candidates(long postId,long memberId)` returns MatchCandidatesResponse with postId,analysisStatus,latestRun,resultRunId,usingPreviousResult,recommendedPollAfterMs,candidates. Private repository run record contains ID,version,status,count,safe failure classification,timestamps; candidate response never contains score columns.

- [x] Write MockMvc/real DB cases covering NOT_REQUESTED, pending/running polling1000ms, success0/N, failure safe mapping, previous results, STALE precedence (also newer pending/failed), guard order, filters and serialized field privacy. Assert `jsonPath("$.data.analysisStatus").value("NOT_REQUESTED")`; first run expects404 instead of200.
- [x] Implement read-only repeatable-read projections, rank ASC limit20, source-specific nested response records, safe public http(s) thumbnail URLs and generated USER P8 route. NON_NULL optional run fields; immutable candidate lists. Successful latest run includes persisted candidateCount.
- [x] Add real P4 disclosure/content changes to prove consent preserves fresh results while content makes STALE with run/candidate rows unchanged. Verify no extra runs from reads.
- [x] Run targeted tests and independent whole-change review including Task1 concurrency and Task2 privacy.

### Task 3: Reviewable delivery

**Files:** backend/docs/matching-api-guide.md, backend/docs/README.md, docs/api-spec.md state/worker boundary clarifications and this plan.

- [ ] Document model pinning, DATA pending handoff, safe errors, STALE/polling precedence, filtered count semantics, test evidence, and remaining94 integration prerequisite. No end-to-end inference claim.
- [ ] Format, run full npm run check, code/branch/commit/secrets review; organize purpose commits with explicit staging paths after announcing messages.
- [ ] Push feature branch, create dev MR using feat template, inspect required pipeline jobs and actual Gemini comments. Fix valid findings or report quota failure. Final lists completed93/95/96/97 and remaining94 explicitly.
