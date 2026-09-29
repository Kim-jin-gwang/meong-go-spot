# Post Queries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox syntax for tracking.

**Goal:** Jira #85·86·87·88: public LOST/SHELTERING lists, authenticated source-specific details, own posts.

**Architecture:** Explicit JDBC projections select only required columns. Lists share deterministic keyset pagination and strict filters; detail uses a consistent read transaction and source/owner-specific DTOs. Existing authentication, region catalog and location decryption are reused.

**Tech Stack:** Java 21, Spring Boot 4.1, PostgreSQL 17, Jackson 3.

**Spec:** docs/api-spec.md sections3.6,6.1,P1,P2,P7; docs/erd.md section6.1; docs/post-date-location-policy.md section4; docs/backend-security-operations-policy.md.

## Global Constraints

- Branch feat/post-queries from merged dev9f842b2. Preserve .vscode; no Jira transitions, ingestion/deployment/Gemini CI changes or schema migration.
- API prefix /api/v1, ApiResponse, exact documented error codes. Coordinates/member phone/hash/ciphertext/internal storage paths never leave projections or logs.
- Parent owns git operations/full npm run check and MR. One Gradle process at a time; workers request a test slot and never spawn agents.
- Current exact-location-v1 consent requires nonnull consent time and nonblank plaintext for nonowner ACTIVE user disclosure. Owner may manage private locations. DELETED/withdrawn owners are invisible.
- USER CLOSED retention expires at closedAt+90 days. After expiry suppress P2/P7 reads even if deletion job lags. Authenticated nonowners can read retained CLOSED metadata but receive no exact location and photos=[] because P8 is owner-only. Public CLOSED details remain accessible for historical matching; PUBLIC never discloses exact location.
- Public list uses stored animal_case.listed_at: ingestion owns notice start -> event date -> insertion timestamp priority per ERD7.4; do not recompute it in a read query or add ingestion logic. EVENT region_code exact match supersedes old Jira86 region-name wording.

### Task 1: Authenticated detail projections

**Files:** Create post/controller/PostDetailController.java, post/service/PostDetailService.java, post/repository/PostDetailRepository.java, post/dto/PostDetailResponse.java, backend/src/test/java/com/meonggo/backend/post/PostDetailApiTest.java. Java root backend/src/{main,test}/java/com/meonggo/backend/. Own no existing controller or error files. Parent supplies PostErrorCode.NOT_FOUND (POST-001404).

**Interfaces:** GET /api/v1/posts/{postId}, valid AuthPrincipal; inject LocationProtection for decryption. `PostDetailService.detail(long postId,long viewerId)` returns source-specific immutable response. Parent list code independent; do not import its DTOs.

- [x] First write API tests with actual PostgreSQL/JWT SessionService (MockitoBean LoginService like PhotoApiTest), read fixtures for ACTIVE USER LOST, USER SHELTERING, PUBLIC SHELTERING. Run missing-route RED before implementation; coordinate Gradle.
- [x] Use explicit safe projection with joined active author or shelter. Read locations/photos in same repeatable-read read-only transaction, no HDFS access. Catch SQL/decrypt errors into safe Common500 without sensitive causes. Invalid ID<=0/notfound/DELETED/withdrawnauthor/expiredclosedUSER => POST-001. Unauthenticated=>AUTH-002.
- [x] Return USER fields per P2: postId/type/source/status/version/name/species/breedName/sex/color/eventDate/eventTime/featureText/eventLocation/[currentLocation]/photos/author(memberId,nickname)/chat/owner/createdAt/updatedAt. LOST omits currentLocation. Owner receives exactLocation when present and exactLocationVisible boolean; nonowner never gets visibility/version/time, gets exactLocation only on ACTIVE+currentpolicy+consenttime+nonblankplaintext. Neither receives coordinates. Owner CLOSED sees locations and photos within90days; other CLOSED receives metadata and photos=[] with POST_NOT_ACTIVE chat. OWN_POST takes precedence only for ACTIVE ownpost; CLOSED always POST_NOT_ACTIVE.
- [x] Return separate PUBLIC DTO (no version/author/owner/chat), common descriptive fields and eventLocation/currentLocation public codes/display only; shelter includes name, phone (retain field even null), address, jurisdiction, noticeNo/start/end/processState. Photos ordered by sort_order; USER_UPLOAD mapped to /api/v1/photos/{id}, PUBLIC_URL validated http/https origin with no credentials, no unsafe URI/internal HDFS exposure. Public other storage types excluded; no external fetching. Missing required role/source relation fails safe (404), no guessed values.
- [x] Tests cover anonymous, owner/nonowner per-role disclosure and obsolete consent, private management, CLOSED/expired/DELETED/withdrawn, public current/closed/nullphone, exact source-field absence, no coordinates/ciphertexts/memberphone, unsafe URLs, stable photo order. Write report task-1-report.md; no commit. Focused GREEN and own-file formatting with granted slot.

### Task 2: Public and own cursor lists

**Files:** Create post/controller/PostListController.java, post/service/PostListService.java, post/repository/PostListRepository.java, post/dto/PostListResponse.java and query DTOs, post/query validation/cursor helpers and tests. Modify PostErrorCode and existing SessionApiTest route expectation; docs/query guide and API clarifications.

- [x] Write HTTP tests first for anonymous ACTIVE LOST, mixed region SHELTERING, privacy field absence, own ACTIVE/CLOSED separation, same timestamp over10rows with nextpage exclusion. Missing GET route must fail before implementation.
- [x] Strictly parse allowed unique query parameters. Public type required, enums strict, SHELTERING regionCode required, optional LOST region, source only SHELTERING else POST-006. Region code is five digits; no catalog check on reads so historical supported records remain queryable. Breed/color raw<=100 codepoints then NFC/strip, reject controls/formats/surrogates; substring match uses parameterized strpos so %, _ and backslash stay literal. Own accepts only type/status/cursor, status ACTIVE/CLOSED. Invalid safe COMMON-001.
- [x] Cursor versioned canonical Base64url binary encodes listedAt seconds/nanos + positivepostId and normalized filter/scope fingerprint. Decode bounded length before allocations, exact format/canonical encoding/time range/microsecond precision validated; malformed or different query scope => CURSOR-001. Cursor is pagination state, not authorization; SQL always reapplies owner/public predicates. No secret/auth claim or private values in token. Query max11, return10 and nextCursor iff11 exists; no countquery.
- [x] Public source membership via user_post+active member or shelter_animal, ACTIVE and not deleted, EVENT filter/summary only, source-specific safe thumbnail sort0 and no join of shelter contact/address. Own query locks scope to authenticatedmember, USERonly ACTIVE/CLOSED notexpired/deleted. Projection adds status/version/updatedAt only to own summaries. Public never emits author/shelter/featureText/exact/current/coordinates. No N+1: one bounded list query with lateral representative photo selection.
- [x] Validate public listedAt stored semantics with fixtures representing notice-date/event-date/fallback priorities and mixed descending order; do not introduce ingestion writes. Test filters, literal wildcard, emptypage, page boundaries/new row insertion, malformed/scope-mismatched cursors, other owners/source/state/retention exclusion and no sensitive fields.

### Task 3: Review and delivery

- [x] Independent task reviews and broad final review, resolve findings with meaningful regressions. Run full npm run check, inspect XML results and diff/secrets/config.
- [ ] Use pre-mr-readiness/commit-staged-changes/draft-mr; purpose-separated commits, push feature branch, create dev MR from feat.md. Capture CI and actual AI comments, report substantiated decisions without automatic retries.
- [ ] Final MR link, verification and completed Jira85/86/87/88 IDs. No Jira state mutation, preserve user changes.
