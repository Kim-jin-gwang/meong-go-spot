# Post Creation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox syntax for tracking.

**Goal:** Jira #83·84: authenticated LOST and SHELTERING creation through P3.

**Architecture:** Validate bounded multipart metadata, normalize photos, hash canonical input, and reuse PhotoWriteService's independent transaction boundary. Lock the member before checking the idempotency key and persisting the aggregate. Location reference data and purpose-specific encryption fail closed.

**Tech Stack:** Java 21, Spring Boot 4.1, PostgreSQL 17, existing WebHDFS adapter.

**Spec:** docs/api-spec.md P3; docs/post-date-location-policy.md; docs/backend-security-operations-policy.md sections 4–5; docs/photo-upload-policy.md.

## Global Constraints

- Work on feat/post-creation from merged dev 9c684bb. Preserve user .vscode/. No migration changes, automatic matching, Jira status changes, or Gemini CI changes.
- Payload 64 KiB; photos 1–10; existing photo validation applies. Never log raw input, coordinates, ciphertext or paths.
- Location encryption uses enc:v1:{kid}:{nonce}:{ciphertextAndTag}, AAD mgbj:exact-location:v1, AES-256-GCM, random 96-bit nonce, 128-bit tag, Base64url without padding.
- Current policy exact-location-v1. Private exact location is encrypted too; private locations store no disclosure version or time. Latest policy supersedes stale Jira83 storage wording.
- Parent owns full npm run check, purpose-based commits, push and dev MR. Workers do not commit or spawn agents. One Gradle test process at a time; obtain parent test slot.

### Task 1: Location reference and protection

**Files:** Create post/location/RegionCodeCatalog.java, LocationProtection.java, post/config/PostLocationConfiguration.java and tests under matching packages; modify test application settings and add test-only fixtures. Create backend/docs/post-creation-guide.md location setup section. Paths are under backend/src/{main,test}/java/com/meonggo/backend unless stated.

**Interfaces:** `RegionCodeCatalog.resolve(String regionCode, String emdCode): String` returns official public display, rejects unsupported/inactive/wrong-parent codes with safe InputValidationException. `LocationProtection.encrypt(String): String`, `decrypt(String): String`; null handling belongs to caller. Beans are injected by type. Parent owns DTOs, input validation, controller, orchestration, config env/compose documentation outside the guide.

- [x] Write tests for dictionary checksum/version validation, supported district/dong and abolished/wrong-parent rejection, encryption roundtrip/random nonce/tampering/wrong-key and purpose key separation. Example: `assertThat(protection.decrypt(protection.encrypt("역삼역"))).isEqualTo("역삼역");`.
- [x] Run tests first and record missing implementation RED. Read existing PhoneProtection and SignupConfiguration, code conventions and exact security specification before implementation.
- [x] Implement strict UTF-8 CSV with documented schema `version,regionCode,emdCode,publicLocation,active`; district has empty emdCode, dong has full 10 digits with matching district. Explicit rows for districts; reject duplicates, invalid statuses/format/version, and dangling child rows. Require REGION_CODE_DATA_PATH, REGION_CODE_DATA_VERSION, REGION_CODE_DATA_SHA256 with matching byte checksum. No production fixture/fabricated official data. Fail startup safely when unavailable so no unready location writer is exposed. Actual approved official dataset is a deployment prerequisite.
- [x] Implement bounded keyring loading from LOCATION_DATA_ENCRYPTION_KEYRING_PATH like phone format, current + previous keys only, 32-byte AES, distinct from all phone/HMAC keys including login identity key. Missing/malformed keys fail startup with cause-less safe error. Classpath files allowed only outside prod. Defensive copies, redacted representations and safe decrypt failure. `cipher.updateAAD("mgbj:exact-location:v1".getBytes(StandardCharsets.UTF_8));`.
- [x] Add test-only region and keyring fixtures/settings so existing Spring integration tests remain bootable; run focused tests when parent grants slot. Document deployment prerequisites and exact CSV/keyring formats. Report files, tests, design limits in task report; no commit.

### Task 2: P3 request and atomic creation

**Files:** Create post/dto, post/web payload reader, post/service input policy and creation service, post/repository/PostCreationRepository, post/controller/PostController, post/exception/PostErrorCode and tests. Modify PhotoMultipartLimitTest probe setup to coexist with real P3; .env.example, compose.prod.yml and docs as necessary.

**Interfaces:** Consumes Task1 beans and existing PhotoNormalizer, PhotoWriteService, PostAggregateService. Produces POST /api/v1/posts returning 201 ApiResponse with postId/type/source/status/version/createdAt.

- [x] Write HTTP integration tests against actual sessions/PostgreSQL for successful LOST/SHELTERING, unauthenticated, future Seoul date, forbidden/missing roles, disclosures, oversized payload, invalid photos, privacy and absence of automatic match_run. Run RED against missing endpoint.
- [x] Read multipart payload as bounded bytes before JSON parsing. Require one JSON payload and ordered photos; reject duplicate/unknown fields, wrong JSON types and invalid strict date/time/UUID. Normalize display text NFC/strip, reject controls/format/unpaired surrogate, raw and normalized limits. Coordinates require pair/range, normalize to DB precision deterministically for hashing.
- [x] Canonical hash uses explicit fixed-order normalized metadata plus ordered normalized photo checksums, excluding server-derived display/time/nonce/IDs. Example same decimal 37.5 and 37.500000 canonicalize identically. Optional empty display values canonicalize to null.
- [x] Bound upload work with a semaphore across normalization through storage, default one concurrent request; return documented safe 503 on saturation, release on every outcome. Preserve existing photo error meanings.
- [x] Preflight active member and key; inside PhotoWrite callback lock member first, recheck active and idempotency, then persist. A concurrent existing key signals a safe BusinessException subclass so PhotoWrite rolls back/compensates, and outer service returns matching original creation response or IDEMPOTENCY-001. DB unique constraint remains final guard. No outer transaction or storage under member lock. Existing success returns original postId/createdAt/type, initial status/version, even later lifecycle changes.
- [x] Add real concurrent retry tests (same payload one aggregate/same ID, different payload conflict, HDFS/DB failure cleanup, inactive member during storage), metadata canonicalization and capacity release tests. Run focused GREEN and privacy checks.

### Task 3: Review and delivery

- [x] Review Task1 and Task2 for spec and quality, fix substantiated findings, run full npm run check and inspect actual XML results.
- [ ] Run pre-mr-readiness, secret/config scan, commit-staged-changes and draft-mr skills. Commit only explicit relevant paths, separating location foundations and creation API where practical.
- [ ] Push feature branch and create dev MR with feat template, tests, deployment prerequisites and Jira83/84. Inspect CI and AI review; do not retry known daily Gemini quota. Report MR and completed Jira IDs without changing Jira statuses.
