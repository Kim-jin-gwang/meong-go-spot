# Post Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox syntax for tracking.

**Goal:** Jira #89·90·91: P4 partial metadata, P5 complete photo replacement, P6 closure.

**Architecture:** Existing registration normalization, location encryption, photo publication and authenticated read APIs remain the foundations. Mutations lock active member then case, recheck owner/state/version and update JDBC projections atomically. Photo publication stays outside the mutation transaction until all files exist.

**Tech Stack:** Java21, Spring Boot4.1, PostgreSQL17, Jackson3, existing PhotoWriteService.

**Spec:** docs/api-spec.md P4/P5/P6 and3.7; docs/post-date-location-policy.md6.3/7; docs/photo-upload-policy.md; docs/erd.md7.6.

## Global Constraints

- Branch feat/post-management starts at merged dev b35cfbc. Preserve .vscode. No Jira status writes, automatic matching, schema/config/Gemini changes or production deployment.
- All three endpoints require AuthPrincipal and version: integer JSON number in 0..Long.MAX_VALUE (no string/float/null). Unknown/duplicate keys and oversized JSON fail safely. Read JSON at most64KiB+1 before parsing; reject Content-Encoding.
- Shared root-owned interfaces: `PostMutationGuard.requireEditable(long postId,long memberId,long version,boolean lock)` returns `PostMutationRepository.Target` with `type():CaseType`, `version():long`, `postId():long`. It validates member, absence/deletion/withdrawn author POST-001, PUBLIC POST-005, nonowner POST-002, nonACTIVE POST-003, version mismatch POST-004 in that order. Lock=true only in caller transaction; lock active member before case. Root also supplies `PostJsonReader.read(HttpServletRequest):JsonNode` and `PostMutationInput.version(JsonNode):long` (version extraction only; endpoint owns allowed fields).
- Parent supplies PostErrorCode.NOT_OWNER/NOT_ACTIVE/VERSION_CONFLICT/PUBLIC_IMMUTABLE with documented POST-002/003/004/005 messages. Never change user-private fields in responses/logs. Sanitize SQL/transaction/crypto exceptions without raw causes.
- Content changes advance case version exactly1; photo replacement and closure always advance1. P4 consent-only changes update location proofs/updatedAt without advancing the matching version (avoids false STALE); normalized no-op does not advance. All P4 content fields count as content, including name/eventTime/featureText and role location fields. No match_run/candidate writes; preserve old runs for future M1 query_case_version comparison. Reject overflow safely before mutation.
- Gradle is serialized across agents. Request a slot before any test/formatter; parent owns full npm run check, staging/commits/push/MR. No worker subagents or commits. Each task writes its report in this plan's ignored SDD workspace.

### Task 1: Partial metadata and per-role consent

**Files:** Worker owns new post/controller/PostMetadataController.java, post/service/PostMetadataService.java, post/service/PostMetadataInput.java, post/repository/PostMetadataRepository.java, post/dto/UpdatePostResponse.java, test post/PostMetadataApiTest.java. May modify existing PostInputPolicy only to reuse validation helpers without changing P3 semantics. Root owns shared guard/JSON/version helpers, error enum and all other mutation APIs.

**Interfaces:** PATCH /api/v1/posts/{postId}; controller reads bounded JsonNode, validates allowed top-level fields (version,name,species,breedName,sex,color,eventDate,eventTime,featureText,eventLocation,currentLocation), then service executes transaction and guard lock=true. Response ApiResponse message `게시물을 수정했습니다.` + UpdatePostResponse(postId,version,updatedAt).

- [x] Write real PostgreSQL/JWT API tests first; run missing-route RED before production. Fixture use valid current region11680/emd1168010100 and encrypted private exact location; metadata version0.
- [x] Select complete safe internal content and role locations only after guard; use redacted toString. Merge present JSON fields, preserve omitted, null clears optional fields; null required scalar/whole location invalid. Role set LOST EVENT only, SHELTERING EVENT+CURRENT. Reject extra location keys and flat locations. Validate raw types/NFC/limits/date/coordinates with existing P3 policy; date only newly requested is checked for future. Resolve catalog when region/emd fields requested, preserve stored display/code for untouched historical location; region change with stale emd must fail unless emd changed/null.
- [x] Location exact decrypt only as needed under authorized mutation, compare normalized plaintext and numeric coordinates. Public false->true or public exact text change requires explicit current policy in same patch; provided policy always must be exact-location-v1, including false requests. true->false preserves previous proof. Explicit current policy on visible unchanged location may refresh obsolete consent. Persist current policy+server timestamp on public confirmation, never copy arbitrary proof from request. Validate final visible requires nonblank exact, coordinate pair/range and complete roles. Unchanged location data should not be needlessly reencrypted or resolve to a new label.
- [x] Atomically persist content/location changes and version policy from globals, leave listedAt/type/source/owner/createdAt/isMatchable unchanged. Use `update ... where id=? and version=?` even with lock and check rowcount. Return stored microsecond updatedAt. No new runs or candidate deletion. Roll back all role updates if any invalid.
- [x] Tests: omitted/null/required/unknown and duplicateJSON, ownership/PUBLIC/CLOSED/deleted/staleversion, LOST/CURRENT rejection, role merge and coordinatepair, region/emd, futuredate, consent transition/proofpreservation and oldconsent, plaintext nonleak, contentversion versus consent-only/noop, preserved runs, two concurrent sameversion content updates only one success. Report task-1-report.md after focusedGREEN and scoped formatting.

### Task 2: Guard, photo replacement and closure

**Files:** Root creates shared PostMutationGuard/Repository, PostJsonReader, PostMutationInput; PostPhotoReplacementController/Service/Response; PostClosureController/Service/Response; CloseReason enum; test PostManagementApiTest. Modify PostErrorCode and docs guides.

- [x] Write closure/replacement HTTP tests first and verify missing-route failures. Shared root guard enforces preflight and transaction-time access, member->case lock order, staleversion and safe errors.
- [x] P5 uses existing multipart reader, normalize photos only after preflight, PhotoWriteService.write outside transaction. Its callback rechecks guarded target with locks, increments version, deletes old photo rows and persists all prepared entities with EntityManager.flush in same transaction. Read DB timestamps for response. Existing publication service verifies IDs/order and handles rollback/unknown commit/old-file cleanup. No schema/new photo storage adapter.
- [x] P6 JSON fields version/reason only. CloseReason RETURNED/TRANSFERRED/OTHER strict. Atomic status=CLOSED,is_matchable=false,closed_at,close_reason,updated_at,version+1; preserve photographs/history/match rows. Response exact P6 shape. Existing P1/P2/P7/P8 and future candidate/chat state rules observe closure immediately.
- [x] Test successful both types, source/ownership/state/version/validation, immediate public-list and anonymous-photo exclusion with owner retention, match run/candidate preservation and eligibility predicate false, metadata unchanged on first/later storage failure and DB conflict after file prepare, ordered replacement/old cleanup, concurrent replacement/closure race, withdrawal during storage, and no match creation. Existing multipart/capacity filters already cover P5; add actual HTTP boundary coverage if current tests omit PUT path.

### Task 3: Review and delivery

- [x] Independent task reviews then fresh whole-branch review, resolve findings with regression tests. Run npm run check and inspect XML failures/errors/skips; scan secrets/config and diff.
- [ ] pre-mr-readiness, commit-staged-changes, draft-mr; show purpose-separated commit plan, push once and create dev MR. Confirm mandatory CI and actual Gemini comments/logs; no quota retry.
- [ ] Report MR, implementation-complete Jira89/90/91, checks and material limits. Keep Jira status untouched.
