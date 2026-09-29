# Authentication Sessions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement backend unit 2: #61 (remaining auth_session), 68, 72, 73, 74.

**Architecture:** RS256 access tokens identify database sessions; every authenticated request validates the current session and member. PostgreSQL transactions rotate refresh secrets and commit replay revocation; Redis atomically enforces login failure limits. Existing signup password work permits and client IP policy are shared.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Security, Nimbus JOSE, PostgreSQL 17, Redis 7.4.

**Spec:** `docs/auth-token-policy.md`, `docs/password-policy.md`, `docs/api-spec.md` A2–A4, `docs/backend-security-operations-policy.md`.

## Global Constraints

- RS256; RSA >= 2048 bits; typ `at+jwt`; issuer `meonggocuisine-auth`; audience `meonggocuisine-api`; client_id `meonggocuisine-android`.
- Access TTL PT15M, refresh TTL P30D fixed from login, JWT clock skew PT30S; all configured externally.
- Refresh format `v1.{selector22}.{secret43}`, canonical Base64 URL without padding; SHA-256 of UTF-8 secret only in database.
- Never log password, token, phone, login ID or IP plaintext; no committed private key; test RSA keys generated during execution.
- Replay revocation must commit despite AUTH-003; same-session rotation keeps selector, ID and expiry.
- Shared nonblocking Argon2 permits <= 4; dummy comparison once for unknown member or invalid password format.
- Account 5 failures/15min and IP 20/10min; fixed windows; block 15min without extending on rejected requests.
- No withdrawal endpoint or Android implementation. No user changes overwritten; preserve `.vscode/`.
- `npm run check` before commits; parent handles commits, MR and AI review. Do not commit from worker tasks.
- Redis outage uses AUTH-006/503 with Retry-After: 1, aligned with security operations 2.3; password policy wording is updated consistently.

### Task 1: Strict token primitives and key configuration

**Files:** Create `backend/src/main/java/com/meonggo/backend/auth/security/JwtTokenService.java`, `RefreshToken.java`, `AuthPrincipal.java`, `auth/config/SessionTokenConfiguration.java`; modify `backend/build.gradle`; create corresponding security/config unit tests and test-only ephemeral key bootstrap under `backend/src/test/`.

**Interfaces:**
- `AuthPrincipal(long memberId, long sessionId)` record contains no credentials.
- `JwtTokenService.issue(long memberId, long sessionId, Instant now)` returns `IssuedToken(String value, Instant expiresAt)`; `verify(String raw)` returns AuthPrincipal; verification failure throws BusinessException(AUTHENTICATION_REQUIRED).
- `RefreshToken.create()` and `RefreshToken.rotate(String selector)` produce opaque values; `parse(String raw)` rejects invalid input with BusinessException(INVALID_SESSION); instance `value()`, `selector()`, `secretHash()`, `matches(String storedHash)` constant-time. Override toString to redact sensitive data.
- Configuration exports JwtTokenService and named `authClock` Clock.systemUTC(); service exposes `refreshTokenTtl()` Duration for session lifecycle.
- Parent adds `AuthErrorCode.INVALID_SESSION` and other error enums. Do not edit member, session persistence, filters or login modules.

- [x] Write failing token tests with generated RSA keys: issue/verify, header/claims, previous public key, unknown kid, wrong signature/algorithm/type/issuer/audience/client ID, absent claims, invalid IDs, expiry/nbf skew boundaries, canonical refresh rejection and rotation.

```java
var token = RefreshToken.create();
assertThat(token.value()).hasSize(69);
assertThat(RefreshToken.parse(token.value()).matches(token.secretHash())).isTrue();
assertThat(RefreshToken.rotate(token.selector()).secretHash()).isNotEqualTo(token.secretHash());
```

- [x] Run targeted tests and record missing functionality failure before implementation.
- [x] Implement with Nimbus library (Spring managed dependency), strict PKCS8/JWKS loading and fail-fast required configuration. Verify active private/public pair, public-only JWKS, duplicate/unknown kid and RSA sizes. Read the full token policy for exact claims and setting names. Do not handwrite JWT cryptography.
- [x] Supply test-only ephemeral key configuration for all existing test-profile Spring contexts, without production fallback; verify key mismatch and missing settings fail.
- [x] Run token/config tests and report commands, outcomes, files and concerns to task report. Parent performs integration and commit.

### Task 2: Database session lifecycle

**Files:** Create `auth/entity/AuthSession.java`, `auth/repository/AuthSessionRepository.java`, `auth/service/SessionService.java`; modify Member and MemberRepository getters/lookups, AuthErrorCode; create `auth/service/SessionServiceTest.java`.

**Interfaces:** Consumes Task 1 tokens/principal/clock. Produces `create(long memberId)` token pair, `refresh(String raw)`, `logout(AuthPrincipal principal, String raw)`, `validate(AuthPrincipal principal)`; token pair contains tokens and expiry instants.

- [x] Write failing PostgreSQL tests for fixed expiry, rotation, replay persistence, concurrent refresh, logout idempotency, cross-session mismatch, expired/revoked/inactive sessions and revocation of all inactive member sessions.

```java
var initial = sessions.create(memberId);
var renewed = sessions.refresh(initial.refreshToken());
assertThat(renewed.refreshTokenExpiresAt()).isEqualTo(initial.refreshTokenExpiresAt());
assertThatThrownBy(() -> sessions.refresh(initial.refreshToken())).isInstanceOf(BusinessException.class);
assertThatThrownBy(() -> sessions.refresh(renewed.refreshToken())).isInstanceOf(BusinessException.class);
```

- [x] Run to verify failure, map the existing V1 schema without altering migration, then implement transactional results so errors are raised after commit. Lock member before session consistently to avoid multi-session revocation deadlocks.
- [x] Use constant-time hash matching; validate JWT sub/sid ownership and active status each request. The logout-only exception permits already revoked sessions with current matching refresh secret.
- [x] Run lifecycle tests with separate transactions for concurrency and assert database state after failed requests.

### Task 3: Login policy and atomic Redis limits

**Files:** Reuse `auth/security/SignupInputPolicy.java` normalization; create `LoginIdentityProtection.java`, `auth/repository/LoginAttemptStore.java`, `RedisLoginAttemptStore.java`, `auth/service/LoginService.java`, corresponding unit/Redis integration tests; modify shared password policy only to extract genuinely shared normalization.

**Interfaces:** `LoginAttemptStore.check(String accountHash, String ipHash)`, `failure(String accountHash, String ipHash)`, `success(String accountHash)` enforce limits via RetryableAuthException. LoginService consumes SessionService and shared PasswordWork/ClientIpResolver. Redis outage maps to AUTH-006/503 with Retry-After: 1.

- [x] Test account fifth/IP twentieth failure returns AUTH-004, fixed TTL and non-extending block, account-only success reset, concurrency and outages.
- [x] Test NFC canonical ID, max raw 256 codepoints, missing fields COMMON-001; empty or disallowed present passwords perform one dummy comparison then AUTH-001. Login does not apply signup blocklist again.

```java
for (int i = 0; i < 4; i++) store.failure(account, ip);
assertThatThrownBy(() -> store.failure(account, ip)).isInstanceOf(RetryableAuthException.class);
```

- [x] Run failing tests, then implement Lua atomic counters and HMAC keys with `login-ip` domain separation. Limits checked before and after acquiring the shared permit. Generate dummy hash once at startup.
- [x] Verify wrong password/unknown member indistinguishable, valid withdrawn credentials AUTH-005, semaphore saturation AUTH-006 Retry-After 1 and no failed-attempt increment.

### Task 4: HTTP security integration, developer setup and completion

**Files:** Create `auth/controller/SessionController.java`, login/refresh/token DTOs, `auth/web/JwtAuthenticationFilter.java`, `auth/config/LoginConfiguration.java`, `auth/SessionApiTest.java`; modify SecurityConfig, existing signup/security tests and auth body filter. Update auth setup script, backend guide and environment example.

**Interfaces:** A2 returns token pair plus member summary; A3 pair only; both no-store/no-cache. A4 returns empty 204. Only exact documented public method/path combinations bypass authentication; photos defer ownership checks to their later service.

- [x] Write HTTP failing test: valid login -> protected probe -> refresh -> replay -> protected probe AUTH-003; repeated logout 204; cross-device token pair rejected; missing/invalid JWT AUTH-002; no token leakage in errors or DTO toString.

```java
mvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON).content("{}"))
    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-001"));
```

- [x] Implement controller/filter and precise allowlist; verify disabled Basic/form/session behavior remains. Add safe local RSA/HMAC generation with private file permissions and no overwrite of existing keys.
- [x] Run focused tests, formatting, full `npm run check`; review entire diff and secret/config state with required repository skills.
- [ ] Commit purpose-based changes referencing Jira issues, push feature branch, create MR targeting dev, inspect pipeline and AI review. Report completed issue IDs and any actual validation limitation; do not change Jira status automatically.
