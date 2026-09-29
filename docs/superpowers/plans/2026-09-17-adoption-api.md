# Adoption API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the AD1 adoption candidate list for Jira #160, then AD2–AD4 adoption favorites for Jira #162.

**Architecture:** Add an `adoption` domain backed by explicit JDBC projections so card queries never select shelter contact details, exact locations, coordinates, or raw source states. Candidate eligibility is evaluated from current public shelter rows at a KST date; opaque keyset cursors bind that date and all filters. A composite-key `adoption_favorite` table supports member-scoped idempotent writes and a separate favorite-list projection that keeps ineligible rows visible as `UNAVAILABLE`.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Security, NamedParameterJdbcTemplate/JdbcTemplate, PostgreSQL 17, Flyway, MockMvc, Testcontainers.

**Spec:** `docs/product/adoption-discovery-mvp.md`, `docs/api-spec.md` AD1–AD4, `docs/erd.md` adoption_favorite.

## Global Constraints

- Work on `feat/adoption-api` created from `origin/dev` commit `57dc049`; target `dev`; do not rebase or force-push.
- Implement #160 before #162 and keep purpose-based commits with Jira references.
- AD1 is public with optional Bearer authentication. Missing credentials return anonymous cards with `favorited=false`; malformed, expired, or revoked credentials return `AUTH-002` through the existing JWT filter.
- Candidate eligibility is exactly `PUBLIC/SHELTERING/ACTIVE`, raw state `보호중`, non-null `notice_end_date < asOfDate` in `Asia/Seoul`, exact five-digit `EVENT.region_code`, requested `DOG`/`CAT` or both, and a safe representative `PUBLIC_URL` photo.
- AD1 order is `notice_end_date ASC, animal_case.id ASC`, fixed page size 10, with 11 safe cards used to determine `hasNext`. Cursor scope contains KST date, region, species, last notice end date, and post ID; malformed, cross-filter, or next-day reuse returns `CURSOR-001`.
- Card projections include only post ID, species, breed, sex, color, EVENT public location, safe representative URL, notice end date, calendar days since notice end, last sync time, and the contract-specific favorite fields. Never select shelter name/phone/address, CURRENT location, ciphertext, coordinates, `process_state_raw`, or feature text.
- `breedName` and `color` remain nullable. Unsafe or absent representative photos do not produce candidate cards.
- Favorite writes and reads require an active authenticated member. PUT and DELETE are idempotent. A new favorite requires current AD1 eligibility; missing or ineligible posts return `ADOPTION-001`. An existing favorite remains a successful PUT even after becoming unavailable.
- AD2 order is `favorited_at DESC, animal_case_id DESC`, fixed page size 10. Eligibility is recomputed on every read and exposed as `AVAILABLE` or `UNAVAILABLE`; ineligible favorites remain removable.
- `adoption_favorite` has composite primary key `(member_id, animal_case_id)`, cascading foreign keys, `created_at`, and a member/list-order index. The delayed relational-erasure job explicitly deletes favorites because retained anonymized member rows are not physically deleted.
- Follow RED → GREEN → REFACTOR for each behavior. Run targeted Testcontainers tests, `backend check`, and final `npm run check` with Docker.

---

### Task 1: AD1 request policy, cursor, and response contract

**Files:**
- Create: `backend/src/main/java/com/meonggo/backend/adoption/query/AdoptionListQuery.java`
- Create: `backend/src/main/java/com/meonggo/backend/adoption/query/AdoptionListInputPolicy.java`
- Create: `backend/src/main/java/com/meonggo/backend/adoption/query/AdoptionCursorCodec.java`
- Create: `backend/src/main/java/com/meonggo/backend/adoption/dto/AdoptionListResponse.java`
- Test: `backend/src/test/java/com/meonggo/backend/adoption/AdoptionCursorCodecTest.java`
- Test: `backend/src/test/java/com/meonggo/backend/adoption/AdoptionListApiTest.java`

**Interfaces:**
- `AdoptionListInputPolicy.parse(MultiValueMap<String,String>) -> AdoptionListQuery(regionCode, species, cursor)` accepts only `regionCode`, `species`, and `cursor` once each.
- `AdoptionCursorCodec.decode(String, AdoptionListQuery, LocalDate) -> Position` and `encode(Position, AdoptionListQuery, LocalDate) -> String`, where `Position` contains notice end date and post ID.
- `AdoptionListResponse(asOfDate, items, page)` uses immutable item lists and omits null `nextCursor`.

- [x] Write cursor tests for canonical round-trip, date/filter binding, positive ID/date bounds, malformed/noncanonical Base64URL, and null first-page cursor.
- [x] Run `node scripts/gradlew.mjs backend test --tests '*AdoptionCursorCodecTest'` and confirm RED because the adoption query types do not exist.
- [x] Implement the input policy, response records, and fixed-format SHA-256-scoped cursor using `PostErrorCode.INVALID_CURSOR`.
- [x] Re-run the targeted cursor test and confirm GREEN.

### Task 2: AD1 candidate list and optional authentication

**Files:**
- Create: `backend/src/main/java/com/meonggo/backend/adoption/controller/AdoptionController.java`
- Create: `backend/src/main/java/com/meonggo/backend/adoption/service/AdoptionListService.java`
- Create: `backend/src/main/java/com/meonggo/backend/adoption/repository/AdoptionListRepository.java`
- Create: `backend/src/main/resources/db/migration/V7__adoption_favorite.sql`
- Modify: `backend/src/main/java/com/meonggo/backend/global/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/meonggo/backend/adoption/AdoptionListApiTest.java`

**Interfaces:**
- `AdoptionListRepository.find(query, asOfDate, memberId, position, limit) -> List<Row>` applies all database eligibility and keyset predicates and may return rows whose photo URL still fails Java URI validation.
- `AdoptionListService.list(query, memberId) -> AdoptionListResponse` scans keyset chunks until it has 11 safe cards or exhausts rows, computes calendar days, and creates the next cursor from the tenth card.
- `GET /api/v1/adoptions` returns `ApiResponse.success("입양 후보 목록을 조회했습니다.", response)` with `Cache-Control: no-store`.

- [x] Write real PostgreSQL/JWT MockMvc tests for anonymous success, authenticated `favorited`, exact eligibility exclusions, nullable source fields, safe-photo filtering, deterministic ties, empty results, wrong query keys, invalid/cross-filter/next-day cursors, and invalid optional credentials.
- [x] Run `node scripts/gradlew.mjs backend test --tests '*AdoptionListApiTest'` and confirm RED because the route is absent or protected.
- [x] Add V7 favorite storage and candidate/list-order indexes required for the authenticated projection.
- [x] Implement the repository with explicit safe columns, service chunking and KST date calculation, controller, and exact GET security permit rule.
- [x] Re-run adoption cursor/list tests and confirm GREEN; run Spotless before reviewing the diff.
- [x] Stage only AD1/V7 changes and commit `feat(adoption): add candidate list API` with `Refs #160`.

### Task 3: AD2–AD4 favorite management

**Files:**
- Create: `backend/src/main/java/com/meonggo/backend/adoption/dto/AdoptionFavoriteListResponse.java`
- Create: `backend/src/main/java/com/meonggo/backend/adoption/exception/AdoptionErrorCode.java`
- Create: `backend/src/main/java/com/meonggo/backend/adoption/query/AdoptionFavoriteCursorCodec.java`
- Create: `backend/src/main/java/com/meonggo/backend/adoption/repository/AdoptionFavoriteRepository.java`
- Create: `backend/src/main/java/com/meonggo/backend/adoption/service/AdoptionFavoriteService.java`
- Modify: `backend/src/main/java/com/meonggo/backend/adoption/controller/AdoptionController.java`
- Modify: `backend/src/main/java/com/meonggo/backend/member/service/MemberRelationalDataErasureService.java`
- Test: `backend/src/test/java/com/meonggo/backend/adoption/AdoptionFavoriteApiTest.java`
- Modify test: `backend/src/test/java/com/meonggo/backend/member/service/MemberDataErasureJobTest.java`

**Interfaces:**
- `AdoptionFavoriteService.add(memberId, postId)` and `remove(memberId, postId)` return no body; `list(memberId, cursor)` returns the member-scoped page.
- `AdoptionFavoriteRepository.findEligiblePhoto(postId, asOfDate)` locks the current candidate rows before an idempotent composite-key insert; `remove` deletes only the caller's composite-key row.
- `AdoptionFavoriteCursorCodec` binds the viewer ID and `(favoritedAt, postId)` descending position.

- [x] Write authenticated API tests for PUT/DELETE idempotency, ineligible/missing `ADOPTION-001`, member isolation, newest-first tied pagination, unavailable retention and removal, withdrawn access rejection, concurrent duplicate PUT, response privacy, and cursor scope.
- [x] Extend the member-erasure integration test with one due member favorite and one active member favorite; require only the due member row to be deleted.
- [x] Run the new tests and confirm RED because AD2–AD4 are absent and erasure does not delete favorites.
- [x] Implement favorite DTO/error/cursor/repository/service/controller paths using member-scoped SQL and transactional duplicate handling.
- [x] Add explicit favorite deletion to relational erasure, retaining FK cascades for physical member or public case deletion.
- [x] Re-run favorite and erasure tests and confirm GREEN; run all adoption tests together.
- [x] Stage only AD2–AD4/erasure changes and commit `feat(adoption): add favorite APIs` with `Refs #162`.

### Task 4: Contract alignment and delivery verification

**Files:**
- Modify only if implementation exposed a real contract correction: `docs/api-spec.md`, `docs/erd.md`, `docs/product/adoption-discovery-mvp.md`, Jira 160/162 descriptions.
- Update: `docs/superpowers/plans/2026-09-17-adoption-api.md` checkboxes.

- [x] Verify implementation against every 157 acceptance condition and inspect JSON for forbidden fields.
- [x] Run `node scripts/gradlew.mjs backend check`, then `npm run check` with Docker/Testcontainers and capture exact results.
- [ ] Apply project convention, secret/config, commit-history, branch-policy, and pre-MR readiness checks.
- [ ] Merge current `origin/dev` only if it advanced, resolve without rebase, then repeat affected checks.
- [ ] Push `feat/adoption-api`, create an MR targeting `dev`, and reference both Jira issues without auto-completing them before merge.
- [ ] Read actual GitLab pipeline and AI review comments; fix valid findings or document evidence for false positives.
- [ ] After merge confirmation, transition Jira 160 and 162 to 완료 and remove the temporary worktree/local branch while preserving the original password-policy workspace.
