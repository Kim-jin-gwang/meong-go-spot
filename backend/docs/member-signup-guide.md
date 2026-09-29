# 회원가입 개발 안내

A0-3 로그인 ID 사용 가능 여부 확인과 A0-1 휴대전화 인증 요청 → A0-2 인증 확인 → A1 회원가입
순서로 처리한다. A0-3은 버튼을 누른 시점의 사용 가능 여부만 알려 주며 아이디를 예약하지 않는다.
A1은 같은 canonical 아이디를 다시 검사하고 DB 유일 인덱스로 동시 가입을 최종 차단한다.
회원가입은 세션이나 JWT를 발급하지 않는다. 로그인 방식은 계속 `loginId + password`다.
(2026-09-23: 이메일 인증 전환은 되돌렸다 — 운영은 SOLAPI 휴대전화 인증을 유지한다.)

**계정 찾기(A9, 2026-09-23 추가)** 는 같은 OTP 저장소·제한을 `recovery:` 키 공간에서 쓴다 — `AccountRecoveryService`.
A9-2 가 아이디와 복구 증명을 돌려주고 A9-3 이 증명·아이디·번호 결합을 확인해 비밀번호를 바꾸고 모든 세션을 끊는다.
미가입 번호에도 202 로 같게 답해 번호 열거를 막는다.

A0-1·A0-2·A1은 모두 `privacyCollectionAgreed=true`와 현재
`privacyCollectionPolicyVersion=privacy-collection-v1`을 요구한다. 불일치는 문자 발송·코드 소비·
가입 증명 선점 전에 거절한다. 회원 행이 생성되는 A1 성공 시 서버 시각을 동의 시각으로 기록하며,
클라이언트가 보낸 시각은 신뢰하지 않는다. Flyway V9는 기존 버전·시각이 모두 보관된 회원의
동의 여부만 `true`로 이관하고, 이미 파기된 기록은 되살리지 않는다. 활성 회원은 동의 여부가
`true`여야 하며 탈퇴 30일 후 동의 여부·버전·시각을 함께 지운다.

### 기존 회원 동의 필드 이관 (Flyway V9)

서버 기동 시 Flyway가 V8 다음에 V9를 트랜잭션으로 적용한다. 운영 DB에 아래 SQL을 별도로
수동 실행하지 않는다. 적용 순서는 (1) `privacy_collection_agreed boolean` 추가,
(2) 동의 버전·시각이 모두 남아 있고 개인정보 파기가 끝나지 않은 회원만 `true`로 이관,
(3) 이미 파기 완료된 행의 잔여 동의 버전·시각 제거, (4) 활성 회원의 동의 여부 `true` 및
파기 완료 회원의 동의 기록 `NULL` 제약 추가다. V1부터 활성 회원의 버전·시각은 필수이므로
가입 시각을 새로운 동의 시각으로 꾸며 채우지 않는다. 새 가입은 서버가 실제 가입 처리 시각을
기록한다.

적용 후 아래 조회 결과가 모두 `0`인지 확인한다. 제약 위반이 있으면 V9 트랜잭션 전체가
실패하므로 배포를 멈추고 원본 데이터와 기존 마이그레이션 상태를 확인한다.

```sql
SELECT count(*) FROM member
WHERE status = 'ACTIVE'
  AND (privacy_collection_agreed IS DISTINCT FROM true
       OR privacy_collection_policy_version IS NULL
       OR privacy_collection_consented_at IS NULL);

SELECT count(*) FROM member
WHERE personal_data_erased_at IS NOT NULL
  AND (privacy_collection_agreed IS NOT NULL
       OR privacy_collection_policy_version IS NOT NULL
       OR privacy_collection_consented_at IS NOT NULL);
```

## 로컬 실행

1. 저장소 루트에서 `node scripts/setup-auth-dev.mjs`를 한 번 실행한다. 서로 다른 무작위 키를
   `.secrets/auth-dev`에 생성하며 기존 디렉터리는 덮어쓰지 않는다.
2. 출력한 `SPRING_CONFIG_ADDITIONAL_LOCATION` 환경변수를 셸이나 IDE 실행 설정에 지정한다.
3. `docker compose -f compose.dev.yml up -d postgres redis`를 실행한다.
4. `npm run dev:be`를 실행한다. 기본 `dev` 프로필의 Fake SMS 고정 코드는 `000000`이다.
   실제 SMS는 보내지 않으며 OTP·전화번호를 로그에 출력하지 않는다.

`dev/test` 프로필에서 `SMS_PROVIDER=FAKE`를 사용할 때는 `011-0000-0000` 또는
`01100000000`으로 A0-1·A0-2를 거치지 않고 A1 회원가입을 호출할 수 있다. 이 경우
`phoneVerificationToken`은 생략한다. 이 번호도 기존 전화번호 중복 정책을 적용하므로 활성 또는
탈퇴 후 파기 대기 중인 계정 하나만 사용할 수 있다. `SOLAPI`와 `prod`에서는 우회가 비활성화되고
`011` 입력은 일반 번호 형식 검증으로 거부된다.

생성한 키를 분실하면 저장된 번호 암호문을 복호화할 수 없다. 기존 데이터가 있을 때 설정
디렉터리를 지우거나 새 키로 대체하지 않는다. 루트 `.env`는 이 실행 경로에서 자동으로 읽지 않는다.

## 설정 계약

환경변수 이름은 루트 `.env.example`을 따른다. 운영 Secret은 read-only 파일 또는 secret
manager로 주입한다. `/run/secrets/` configtree는 파일 이름을 속성 이름으로 읽는다.

| 설정 | 내용 |
|---|---|
| `PHONE_DATA_ENCRYPTION_KEYRING_PATH` | 전화번호 키링 JSON 파일 절대 경로 |
| `PHONE_LOOKUP_HMAC_KEY_V1` | 번호 조회용 Base64 키, 최소 32바이트 |
| `PHONE_OTP_HMAC_KEY_V1` | OTP 검증용 별도 Base64 키, 최소 32바이트 |
| `AUTH_IP_HMAC_KEY_V1` | IP 제한용 별도 Base64 키, 최소 32바이트 |
| `SMS_PROVIDER` | `SOLAPI`(기본) 또는 dev/test에서만 `FAKE`. `FAKE`는 테스트 가입 번호 우회도 활성화 |
| `SMS_FAKE_FIXED_OTP` | dev의 6자리 고정 코드. 자동 테스트는 무작위 코드와 테스트 전용 inspector 사용 |
| `SOLAPI_CREDENTIALS_PATH` | `apiKey`, `apiSecret`, `senderNumber`를 가진 properties 파일 |
| `SPRING_DATA_REDIS_URL` | 기본 `redis://localhost:6379`. 비밀번호는 URL에 넣지 않는다 |
| `REDIS_CREDENTIALS_PATH` | `spring.data.redis.username`(선택), `spring.data.redis.password` properties 파일 |
| `AUTH_TRUSTED_PROXY_CIDRS` | 쉼표로 구분한 신뢰 프록시 CIDR. 직접 연결은 비워 둔다 |
| `AUTH_ARGON2_MAX_CONCURRENCY` | 프로세스 실행권 1~4, 기본 4. API replica 확장 전 전역 합계 제한 필요 |

키링은 `{"currentKid":"V1","keys":{"V1":"<Base64 32 bytes>"}}` 형식이다. 교체 중에는
`keys`에 직전 키 하나를 추가하고 `currentKid`를 새 키로 지정한다. 현재 키와 모든 HMAC 키는
서로 달라야 한다. 키링 누락·무효 키·차단 목록 변조·prod의 Fake 선택은 기동 실패다.

비밀번호·OTP 길이와 TTL 등 정책 상수는 코드에서 고정한다. `.env.example`의 같은 이름은
계약 설명이며 정책을 런타임 환경변수로 낮추는 기능이 아니다. 로그인 실패 제한·JWT·위치
관련 항목은 후속 구현 범위다.

`server.forward-headers-strategy=none`을 유지한다. 신뢰 프록시는 외부 X-Forwarded-For를
덮어쓰고 실제 클라이언트 IP 하나만 전달해야 한다. 신뢰하지 않은 peer의 헤더는 무시한다.

## 실패와 동시 요청

- SMS는 [SOLAPI 서명](https://solapi.com/developers/api/authentication-api-key)과
  [발송 접수 결과](https://solapi.com/developers/api/messages)를 검증한다. 접수 성공 전에
  이전 OTP를 폐기하지 않으며 통신 오류에도 자동 재전송하지 않는다.
- 발송 제한은 예약 시도부터 보수적으로 집계한다. 성공 시 60초 간격을 접수 성공 시각으로
  갱신하며 불확실한 실패도 예약 제한을 유지한다.
- Redis는 단일 전용 인스턴스에서 Lua 원자 연산을 사용한다. Redis Cluster의 cross-slot
  실행은 지원하지 않는다. 운영은 사설 연결·인증·`noeviction`을 구성한다.
- A1은 입력과 증명 검증, 실행권 획득, 증명 선점, Argon2 인코딩, DB 커밋, 증명 소비 순서다.
  DB 전 실패와 확인된 rollback만 자기 증명을 복구한다. 커밋 불명확 오류는 claim을 유지한다.
  DB 커밋 뒤 Redis 소비 갱신만 실패하면 가입 성공을 반환하며 기존 claim과 DB 제약을 유지한다.
- SQL 오류 DETAIL에 로그인 ID나 전화 hash가 포함될 수 있어 Hibernate의 JDBC 오류·bind
  logger를 비활성화하고 서비스에서 안전한 코드만 기록한다.

## 검증·배포 경계

`npm run check`는 전체 lint와 테스트를 수행한다. 로컬은 PostgreSQL·Redis Testcontainers,
GitLab/Jenkins는 격리된 DB·Redis sidecar를 사용한다. 테스트 Redis는 `TEST_REDIS_URL`로
지정할 수 있으며 테스트가 데이터를 초기화하므로 개발·운영 Redis를 지정하지 않는다.

운영 SOLAPI 실발송과 자격증명 등록은 자동 테스트에서 실행하지 않는다. 운영 `main` 배포는
[배포 가이드](../../docs/deploy-guide.md)의 TLS·Secret mount·readiness 등 인증 출시 조건을
충족한 뒤 진행한다. 현재 운영 compose 스캐폴드에 이 코드를 그대로 배포하면 필수 Secret
누락으로 기동하지 않는다. `dev` MR은 운영 배포를 수행하지 않는다.
