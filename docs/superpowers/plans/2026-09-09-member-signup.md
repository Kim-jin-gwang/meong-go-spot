# Member Signup Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development and test-driven-development to execute the tasks below.

**Goal:** A0-1, A0-2, A1을 연결해 전화 인증과 개인정보 동의를 검증한 회원을 생성한다.

**Architecture:** Controller–Service–Repository 계층을 유지한다. 전화번호와 비밀번호 보호는 독립 모듈로, OTP와 가입 증명의 상태 전이는 Redis 원자 연산으로 구현한다. 가입 서비스는 Argon2 실행권과 증명을 선점하고 PostgreSQL 커밋 결과에 따라 증명을 소비하거나 복구한다.

**Tech Stack:** Java 21, Spring Boot 4.1, PostgreSQL/Flyway, Spring Data Redis, Spring Security Argon2id, SOLAPI, JUnit/Testcontainers.

**Spec:** `docs/api-spec.md` A0-1/A0-2/A1, `docs/password-policy.md`, `docs/backend-security-operations-policy.md` 2–4, `docs/erd.md`.

## Global Constraints

- 기존 V1 migration을 수정하지 않는다. `ddl-auto=validate`, 실제 PostgreSQL·Redis 통합 테스트를 사용한다.
- API는 공통 ApiResponse와 문서의 오류 코드를 사용한다. 전화번호·IP·OTP·가입 증명·비밀번호는 로그와 일반 DTO toString에 노출하지 않는다.
- 전화번호는 E.164로 정규화하고 별도 키의 AES-256-GCM 암호문과 HMAC만 저장한다.
- 가입 증명은 10분, OTP는 공급자 접수 성공부터 3분이며 재전송 60초와 이동 구간 제한을 원자적으로 적용한다.
- 비밀번호 정책의 고정 목록, NFC 규칙, Argon2id 64 MiB/3회/병렬도 4와 프로세스 공용 실행권 4개를 준수한다.
- 설정 누락·Redis 장애는 fail-closed한다. Fake SMS는 dev/test만 허용하며 prod에서 거부한다.
- 회원가입에는 auth_session/JWT 발급을 포함하지 않는다. Jira #61의 회원 부분과 -69/-70/-71에 대응한다.
- 현재 기능 브랜치에서 작업하고 사용자 `.vscode/`를 보존한다. 커밋은 전체 검증 뒤 목적별로 수행한다.

### Task 1: 개인정보·비밀번호 보호

**Files:** `backend/src/main/java/com/meonggo/backend/auth/security/*`, 대응 단위 테스트, `backend/src/main/resources/security/password-blocklist/*`.

- [x] 정책 위반·정규화·키 누락/재사용·암호문 변조·실행권 포화 테스트를 먼저 작성하고 실패를 확인한다.
- [x] `SignupInputPolicy`는 `canonicalLoginId(String)`, `normalizePassword(String,String canonicalLoginId)`, `normalizeNickname(String)`를 제공한다. 유효하지 않은 입력은 안전한 `InputValidationException(field,message)`을 사용한다.
- [x] `PhoneProtection`은 `normalize(String)`, `lookupHash(String e164)`, `encrypt(String e164)`, `decrypt(String envelope)`, `otpHash(String verificationId,String otp)`, `ipHash(byte[] canonicalAddress)`를 제공한다. 생성자는 currentKid, Map<String,byte[]> encryptionKeys, lookupKey, otpKey, ipKey. 현재·직전 키 읽기, 현재 키 쓰기를 구현한다.
- [x] `PasswordWork`는 `acquire()`로 AutoCloseable 실행권을 반환한다. 실행권은 `encode(String normalized)`와 `matches(String normalized,String encoded)`를 제공하며 close는 멱등이다. 생성자는 concurrency(1..4). 포화는 `RetryableAuthException(AuthErrorCode.AUTH_UNAVAILABLE,1)`.
- [x] 고정 blocklist와 MIT 원문을 포함하고 checksum·바이트·줄수·UTF-8을 기동 시 검증한다. 설정 Bean은 Task 3에서 만든다.
- [x] 모듈 테스트를 실행하고 변경 범위에 대한 명세/품질 검토를 수행한다.

### Task 2: Redis 인증 상태

**Files:** `auth/repository/PhoneVerificationStore.java`, `auth/repository/RedisPhoneVerificationStore.java`, `resources/redis/*`, 대응 Redis Testcontainers 테스트.

- [x] OTP 시도 소진·교체·만료, 이동 구간 제한·동시 발송, 증명 선점 경쟁·소유 요청만 복구하는 테스트를 먼저 작성한다.
- [x] Store는 `reserveSend(phoneHash,ipHash,requestId)` → 재시도 초(0이면 예약), `activateOtp(phoneHash,requestId,verificationId,otpHash)` → boolean, `findOtp(phoneHash)` → Optional<OtpSnapshot>, `confirmOtp(phoneHash,verificationId,expectedHash,matched,ProofRecord)` → boolean을 제공한다. snapshot은 verificationId/hash, proof는 selector/secretHash/phoneLookupHash/issuedAt/expiresAt. 실패 비교도 동일 verificationId/hash에만 횟수를 원자 반영한다.
- [x] 발송 예약은 전화번호 최근 1시간 5회·24시간 10회, IP 1시간 20회와 60초 간격을 원자 검사한다. 불확실한 SMS 실패도 예약을 유지한다. 접수 성공 시 60초 기준을 성공 시각으로 갱신하고 새 OTP를 3분간 활성화한다. 발송 중 예약은 provider 최대 timeout보다 길게 유지한다.
- [x] `findProof(selector)` → Optional<ProofSnapshot>; snapshot 필드 secretHash/phoneLookupHash/status/issuedAt/expiresAt. `claimProof(selector,secretHash,phoneHash,requestId)` → boolean; `restoreProof(selector,requestId)`와 `consumeProof(selector,requestId)`는 자기 CLAIMED만 전이하며 원래 TTL을 유지한다.
- [x] Redis 서버 시간으로 TTL·rate 구간을 일관되게 계산한다. Redis 예외는 원문 없는 `RetryableAuthException(AuthErrorCode.AUTH_UNAVAILABLE,1)`로 변환한다. OTP와 proof는 동시 소비/발급을 Lua 하나로 수행한다.
- [x] Redis 실제 컨테이너에서 경합·TTL·장애 테스트와 명세/품질 검토를 수행한다.

### Task 3: SMS·회원가입 API 통합

**Files:** `auth/controller`, `auth/dto`, `auth/service`, `auth/config`, `auth/sms`, `member/entity`, `member/repository`, `member/exception`, `global/security`, `global/error`, Gradle·프로필 설정과 개발 안내.

- [x] 인증 없이 A0-1/A0-2/A1 유효성 오류를 받는 HTTP 계약 테스트로 현재 누락을 확인한다.
- [x] 오류/검증 공통 타입, 공유 Bean 설정, 8,192 byte streaming body 제한 및 compressed body 거부 필터, 신뢰 프록시 IP 처리를 구현한다.
- [x] SOLAPI 서명·명시적 접수 판정·유한 timeout·재시도 없음과 dev/test Fake를 구현하고 로컬 HTTP 서버로 검증한다.
- [x] 전화 인증 서비스에서 안전한 OTP 생성·상수 시간 비교·opaque proof 발급과 번호 중복 검사를 수행한다.
- [x] 회원 Entity는 현재 member 스키마에 매핑한다. 가입 트랜잭션은 전체 전화 hash 중복을 검사하며 DB 유일 제약 경합을 도메인 오류로 변환한다.
- [x] 가입 입력/proof 검증 → 실행권 → 원자 claim → encode → 독립 DB 트랜잭션 커밋 → consume 순서를 지킨다. DB 전 실패/명확한 rollback만 자기 claim을 복구하고 커밋 불명확 오류는 유지한다.
- [x] configtree 기반 운영 Secret과 테스트 전용 설정을 준비하고 dev 실행 방법·필요 변수·외부 검증 한계를 문서화한다.

### Task 4: 통합 검증과 MR

- [x] PostgreSQL·Redis API 성공, 중복·동시 가입, rollback·Redis 후속 실패, redaction·body 상한·보안 경로 테스트를 실행한다.
- [x] `npm run check`를 실행한다. 전체 변경의 독립 코드 리뷰를 수행하고 유효한 지적을 반영한다.
- [ ] `pre-mr-readiness`, Secret 검사·커밋 전략에 따라 목적별 커밋을 만들고 dev 대상 MR을 생성한다.
- [ ] CI와 AI 리뷰를 읽고 유효한 지적을 해결한 결과를 보고한다.
