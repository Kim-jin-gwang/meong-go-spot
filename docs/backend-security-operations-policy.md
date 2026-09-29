# 백엔드 보안·운영 정책

> 상태: **개발 기준 확정** — 인증·개인정보·DB 마이그레이션·비동기 작업·운영 안전장치의
> 상세 기준이다. API 필드와 테이블 구조는 [API 명세](api-spec.md), [ERD](erd.md)를 함께 따른다.

## 1. 적용 원칙

1. 인증·개인정보 보호 기능은 의존성 장애나 설정 누락 시 허용으로 우회하지 않고 실패한다.
2. 원문이 필요 없는 값은 전용 키의 HMAC 또는 단방향 hash로만 저장한다.
3. 복호화가 필요한 개인정보는 목적별 키를 분리한 인증 암호화로 저장한다.
4. 클라이언트와 작업자의 재시도는 같은 논리 요청을 중복 생성하지 않아야 한다.
5. Flyway만 스키마를 변경하며 Hibernate는 스키마가 계약과 같은지 검증만 한다.

## 2. 휴대전화 인증

### 2.1 정규화와 OTP

- 입력은 `01012345678` 또는 `+821012345678`만 허용하고 E.164 `+821012345678`로 정규화한다.
- OTP는 CSPRNG로 균등 생성한 숫자 6자리이며 발송 접수 성공부터 3분 동안 유효하다.
- Redis에는 `HMAC-SHA-256(PHONE_OTP_HMAC_KEY_V1, verificationId || 0x00 || otp)`만
  저장한다. 전화번호 조회 키와 OTP HMAC 키를 재사용하지 않는다.
- OTP 비교는 상수 시간 비교를 사용한다. 한 코드의 5번째 실패에서 즉시 폐기한다.
- 운영 SMS 공급자는 SOLAPI다. 공급자가 접수 성공을 명확히 반환한 뒤에만 새 OTP를 활성화하고
  이전 OTP를 폐기한다. 타임아웃처럼 결과가 불확실하면 자동 재발송하지 않고 `PHONE-004`와
  재시도 가능 시각을 반환한다. 사용자의 명시적 재요청도 60초 제한을 따른다.
- Fake 공급자는 OTP 원문을 콘솔·파일·일반 애플리케이션 로그에 쓰지 않는다. 로컬은 설정한
  고정 코드, 자동 테스트는 테스트 프로필에서만 접근 가능한 in-memory inspector를 사용한다.
- `dev/test` 프로필과 Fake 공급자가 함께 활성화된 경우에만 `011-0000-0000` 가입 요청은 OTP와
  가입 증명을 생략할 수 있다. 내부 전용 정규값도 암호화·조회 HMAC과 중복 정책을 그대로 적용한다.
  `prod`에서는 Fake 공급자와 이 우회를 함께 기동할 수 없으며, 번호나 우회 여부를 로그에 남기지 않는다.

### 2.2 가입 증명

가입 증명은 서명 JWT가 아닌 불투명 1회용 토큰이다.

```text
pv1.{selector}.{secret}
```

- `selector`: CSPRNG 16바이트를 Base64 URL(no padding)로 인코딩한 22자 값
- `secret`: CSPRNG 32바이트를 Base64 URL(no padding)로 인코딩한 43자 값
- 전체 길이: 70자
- 유효 기간: 발급 후 10분

Redis에는 selector를 조회 키로 사용하고 다음 값만 TTL과 함께 저장한다.

| 필드 | 값 |
|---|---|
| `secretHash` | secret 바이트의 SHA-256 소문자 16진수 |
| `phoneLookupHash` | E.164 번호의 `PHONE_LOOKUP_HMAC_KEY_V1` HMAC |
| `status` | `ISSUED`, `CLAIMED`, `CONSUMED` |
| `claimRequestId` | `CLAIMED`를 만든 가입 요청 식별자, 아니면 없음 |
| `issuedAt`, `expiresAt` | UTC 시각 |

A1은 selector 조회, secret hash 상수 시간 비교, `phoneNumber` binding, 만료와 상태를 검사한다.
저비용 입력 검증과 Argon2 실행권 획득 뒤 Lua 또는 동등한 원자 연산으로
`ISSUED -> CLAIMED`를 선점한다. 회원 생성 커밋 뒤 `CONSUMED`로 바꾸며 같은
`claimRequestId`의 명확한 DB 전 실패만 `ISSUED`로 복구한다. Redis 후속 갱신 실패가 생겨도
DB의 활성 번호 부분 유일 인덱스가 중복 계정 생성을 최종 차단한다.

### 2.3 Redis 운영

- 외부 네트워크에 공개하지 않고 서비스 네트워크 안에서만 접근한다.
- 인증을 켜고 운영은 TLS 또는 호스트 간 암호화된 사설 경로를 사용한다.
- 인증 상태가 임의 축출되지 않도록 전용 인스턴스 또는 전용 logical DB에
  `maxmemory-policy noeviction`을 사용하고 메모리·연결 수를 경보한다.
- Redis 장애 시 OTP 발급·확인, 가입 증명 검증, 로그인 실패 제한은 `503`으로 fail-closed한다.
- 전화번호·로그인 ID·IP·OTP·가입 증명 원문을 key, value, metric label, trace attribute에 쓰지 않는다.

## 3. 회원 개인정보와 탈퇴

### 3.1 가입 동의

현재 개인정보 수집·이용 고지 버전은 `privacy-collection-v1`이다. 아래 표는 구현 누락을 막기
위한 공학적 데이터 목록이며, 실제 사용자 고지문은 운영 배포 전에 개인정보 책임자 검토와
승인을 받아 같은 버전의 고정 문서로 보관한다.

| 항목 | 내용 |
|---|---|
| 가입 필수 항목 | 로그인 ID, 비밀번호의 Argon2id hash, 닉네임, 휴대전화 번호 |
| 기능 사용 시 항목 | 게시물 내용·사진, 사건·현재 보호 위치의 행정구역·정확한 위치·선택 좌표, 채팅 내용, 개인 채팅 알림용 앱 설치 식별자·FCM 토큰 |
| 자동 생성 항목 | 전화 인증·개인정보 동의 시각, 인증 세션과 게시물·매칭·채팅 알림 처리 메타데이터 |
| 목적 | 회원·세션 관리, 번호 소유 확인과 중복 활성 계정 제한, 게시물·위치 기반 검색·매칭, 회원 간 채팅과 새 메시지 알림, 장애·보안 대응 |
| 보유 기간 | 회원 이용 중 보관하고 탈퇴 시 즉시 접근을 차단한다. 계정 보호값과 사용자 생성 데이터는 탈퇴일로부터 30일 이내 파기·익명화한다. 임시 인증 값은 각 TTL, 비민감 운영 로그는 30일을 넘기지 않는다. 별도 보존 의무가 실제 확인되면 대상·근거·기간·접근권한을 이 문서와 고지문에 먼저 추가한다. |
| 거부와 영향 | 동의를 거부할 수 있으나 필수 계정 기능에 필요한 항목이므로 가입 불가 |

A0-1·A0-2·A1은 `privacyCollectionAgreed=true`와
`privacyCollectionPolicyVersion=privacy-collection-v1`을 요구한다. 회원 행에는 동의 여부·버전과 서버의
동의 처리 시각을 함께 저장한다. 활성 회원에게는 세 값이 필수이며, 파기 완료 시에는 모두 `NULL`이다.
마케팅·제3자 제공 동의는 이 값에 섞지 않는다.

### 3.2 중복 가입과 탈퇴

- 인증된 같은 번호의 `ACTIVE` 회원은 하나만 존재할 수 있다. 서비스 검사와
  `phone_lookup_hash WHERE status='ACTIVE'` 부분 유일 인덱스를 함께 사용한다.
- 같은 해시가 탈퇴 회원에 남아 있는 30일 파기 대기 중에도 재가입을 거부한다. 기존 계정의
  ID·닉네임·상태·가입 시각은 응답하지 않고 문의 방법만 제공한다.
- `POST /api/v1/members/me/withdrawal`은 현재 비밀번호 재인증을 요구한다. 성공 트랜잭션은 회원을
  `WITHDRAWN`으로 만들고 모든 세션을 폐기하며 소유 게시물을 `DELETED`, `is_matchable=false`로
  바꾸고 개인 푸시 `auth_session.push_*` 등록을 비운다. 응답은 204다. 재인증(A5·A8)은 A2와 같은 Argon2 실행권과 계정·IP 실패 제한을 공유하며,
  Argon2 비교는 행 잠금 밖에서 끝내고 잠금 안에서는 상태와 검증한 hash가 그대로인지만 다시 확인한다.
- 30일 뒤 회원 개인정보 파기 트랜잭션은 전화번호 암호문·조회 해시·인증 시각·동의 여부·버전·시각을
  `NULL`로 만들고 로그인 ID·닉네임·비밀번호 자격 증명을 복구 불가능한 탈퇴 값으로 치환한다.
  이 커밋 뒤 같은 번호로 재가입할 수 있다. 실패하면 해시를 유지하고 재시도한다.
- 백엔드 전용 `member_data_erasure_job`은 매일 03:30 KST에 DB advisory lock으로 단일 실행하고
  탈퇴 후 30일이 지난 회원을 100건씩 처리한다. 계정 보호값 파기 트랜잭션을 먼저 커밋한 뒤
  사용자 게시물·위치·매칭·채팅 관계형 데이터와 HDFS 사진을 삭제·익명화한다. 관계형·외부
  저장소 실패가 번호 재사용을 지연시키지는 않지만 완료될 때까지 재시도하고 30일 목표 초과를
  경보한다. 탈퇴와 관련 없는 공공 원천 데이터·집계는 삭제하지 않는다.

번호 소유권 분쟁은 자동 계정 이전으로 처리하지 않는다. 서비스 책임자와 개인정보·보안
책임자의 이중 승인, 요청 근거와 인프라 접근 로그를 남기는 수동 절차만 허용한다.

## 4. 필드 암호화와 키 분리

### 4.1 암호화 형식

`member.phone_ciphertext`, `animal_case_location.exact_location_ciphertext`와
`auth_session.push_token_ciphertext`는 AES-256-GCM으로 암호화한다.

```text
enc:v1:{kid}:{nonce}:{ciphertextAndTag}
```

- plaintext는 NFC 정규화된 UTF-8이다.
- nonce는 매 암호화마다 CSPRNG로 새로 생성한 96비트 값이다.
- authentication tag는 128비트이며 ciphertext 뒤에 이어 붙인다.
- nonce와 ciphertext+tag는 Base64 URL(no padding)로 인코딩한다.
- AAD는 `mgbj:{purpose}:v1`이다. purpose는 `member-phone`, `exact-location` 또는 `fcm-token`이다.
- 복호화 인증 실패, 알 수 없는 버전·`kid`, 잘못된 envelope는 정상 데이터처럼 사용하지 않고
  안전한 내부 오류로 처리하며 원문이나 envelope를 로그에 남기지 않는다.

### 4.2 목적별 키

다음 Secret은 서로 다른 32바이트 이상의 무작위 값이어야 하며 JWT private key나 DB 비밀번호와
재사용하지 않는다.

| Secret | 목적 |
|---|---|
| `PHONE_DATA_ENCRYPTION_KEY_*` | 전화번호 AES-256-GCM |
| `LOCATION_DATA_ENCRYPTION_KEY_*` | 정확한 위치 AES-256-GCM |
| `FCM_TOKEN_ENCRYPTION_KEY_*` | 개인 FCM 등록 토큰 AES-256-GCM |
| `PHONE_LOOKUP_HMAC_KEY_V1` | 활성 번호 중복 조회 |
| `FCM_TOKEN_LOOKUP_HMAC_KEY_V1` | 개인 FCM 등록 토큰 중복 조회 |
| `PHONE_OTP_HMAC_KEY_V1` | OTP 검증 값 |
| `AUTH_LOGIN_ID_HMAC_KEY_V1` | 로그인 제한 계정 key |
| `AUTH_IP_HMAC_KEY_V1` | 로그인·발송 제한 IP key |

운영 키는 저장소·이미지·일반 env 파일에 넣지 않고 배포 Secret file 또는 동등한 secret manager로
주입한다. 애플리케이션은 현재 쓰기 `kid`와 현재 키가 없으면 기동하지 않는다. 교체 시 새 키로만
쓰고 현재·직전 키를 읽은 뒤, 배치 재암호화와 검증을 끝내고 직전 키를 제거한다.
`PHONE_LOOKUP_HMAC_KEY_V1` 교체는 가입을 잠시 중단하고 전화번호 암호문에서 조회값을 일괄
재계산한다. `FCM_TOKEN_LOOKUP_HMAC_KEY_V1` 교체는 N1과 개인 채팅 알림 worker를 잠시 중단하고
토큰 암호문에서 조회값을 일괄 재계산한다. 둘 다 조회 HMAC의 동시 다중 버전을 허용하지 않으며
유일성 검증 뒤 재개한다. 중단 중 채팅 메시지와 Outbox 저장은 계속하고 알림은 재개 뒤 처리한다.
같은 IP 키를 쓰는 제한 기능은 입력 앞에 각각 `login-ip\0`, `otp-send-ip\0` domain prefix를 붙여
서로의 카운터가 충돌하지 않게 한다.

### 4.3 개인 푸시 토큰

- 토큰 조회 정규 입력은 공급자가 발급한 UTF-8 문자열 그대로이며 공백 제거·대소문자 변환을 하지
  않는다. N1은 공백 문자열과 4096자 초과를 거부한다.
- `push_token_lookup_hash`는 `FCM_TOKEN_LOOKUP_HMAC_KEY_V1`의 HMAC-SHA-256 소문자 16진수
  64자로 `auth_session`에 저장한다. 토큰 원문·암호문·조회값을 요청·오류 로그, metric label,
  trace attribute에 남기지 않는다.
- `push_installation_id`와 토큰 조회값은 각각 전역 유일하다. 같은 설치 또는 토큰을 다른 세션에서
  등록하면 이전 세션의 `push_*` 값을 비우고 현재 인증 세션으로 한 트랜잭션에 재귀속해 이전 회원
  발송을 차단한다. worker는 회원이 `ACTIVE`이고 세션이 미폐기·미만료이며 `push_*` 등록이 완전한
  행만 사용한다.
- 로그아웃은 로컬 인증정보를 지우기 전에 N2를 best effort로 호출한다. N2 실패와 무관하게 A4는
  현재 인증 세션을 폐기해 즉시 발송 대상에서 제외한다. 서버는 N2와 FCM의 등록 해제·잘못된 토큰
  영구 오류에서 해당 세션의 `push_*` 값을 비우며, A4·A5·만료 세션은 값이 남아도 발송 대상에서
  제외한다. 일시 오류는 등록을 비우지 않는다.
- 일일 입소 요약 토픽 구독은 개인 토큰 등록과 분리한다. 개인 FCM payload에는 채팅 본문·닉네임·
  사진·위치를 넣지 않고 `type`, `chatRoomId`, `messageId`, `postId`만 넣는다.

### 4.4 좌표 예외

위도·경도는 MVP 시공간 매칭과 인덱싱을 위해 `numeric`으로 저장한다. 이는 편의를 위한 평문
노출 허용이 아니다. 좌표는 민감정보로 분류해 API·로그·오류·metric label·trace·분석 산출물에서
제외하고, 최소 권한 DB 역할과 암호화된 백업으로 보호한다. 직접 SQL 접근은 승인·접근 로그를
요구한다. 공개 기능에 좌표가 필요해지면 별도 정책 변경과 재동의를 선행한다.

## 5. 입력 정규화와 재시도 안전성

- 사용자 표시 문자열은 크기 상한을 원문에 먼저 적용하고 NFC 정규화한 뒤 앞뒤 공백을 제거한다.
  제어 문자와 Unicode format 문자는 거부한다. 내부 일반 공백은 보존한다.
- `exactLocation`은 1~200, `featureText`는 0~2,000 Unicode 코드 포인트다.
- P3 multipart의 JSON metadata part는 64 KiB 이하여야 한다.
- P3은 `clientRequestId`, C4는 `clientMessageId` UUID를 필수로 받는다. 각각 회원 범위에서
  유일하게 저장하고 같은 키·같은 payload 재요청에는 최초 성공 리소스를 반환한다. 같은 키에
  다른 canonical payload가 오면 `409 IDEMPOTENCY-001`이다.
- 비교용 `request_hash`는 서버가 canonical payload와 정규화된 사진 checksum 목록으로 계산한
  SHA-256이다. 민감 원문이나 바이너리를 저장하지 않는다.

### 5.1 행정구역 기준 데이터

- 서버는 승인된 공식 행정구역 데이터에서 만든 버전 고정 CSV를 읽고 `regionCode`·`emdCode`의
  형식, 사용 가능 상태와 상하위 소속을 검증한다. 파일 버전과 SHA-256 checksum을 배포 기록에
  남기며 파일·버전·checksum이 없거나 서로 다르면 위치 쓰기 기능을 준비 완료로 표시하지 않는다.
- 게시물에는 코드와 당시 표시 문자열을 함께 저장해 기준 데이터가 바뀌어도 과거 표시가 갑자기
  달라지지 않게 한다. 폐지 코드는 새 등록에서 거부하되 기존 게시물 조회는 저장된 표시값으로
  유지하고, 코드 통폐합은 명시적인 migration으로만 변경한다.
- 클라이언트가 보낸 자유 문자열을 코드 대신 신뢰하거나 임의 코드로 보정하지 않는다.

## 6. 비동기 매칭 작업

- M2 트랜잭션은 활성 실행이 없을 때 `match_run(PENDING)`을 생성한다. 같은 게시물에
  `PENDING/RUNNING`이 있으면 오류 대신 기존 실행을 202로 반환한다.
- DATA/AI 작업자는 PostgreSQL에서 `FOR UPDATE SKIP LOCKED`로 PENDING 행을 짧게 선점하고
  조건부 `PENDING -> RUNNING` 갱신 후 트랜잭션을 끝낸다. 긴 AI·MapReduce 처리를 DB 잠금 안에서
  수행하지 않는다.
- 작업 입력과 결과는 `matchRunId`, `queryCaseVersion`, `modelId`, `modelVersion`으로 식별한다.
  후보 적재와 `SUCCEEDED` 전이는 한 트랜잭션에서 수행하고 같은 실행의 재전달은 기존 결과와
  일치하면 성공으로 취급한다.
- RUNNING 5분 초과는 watchdog가 `FAILED`, `error_code=MATCH_TIMEOUT`으로 전환한다. 실제 처리
  hard timeout은 60초이며 이후 결과는 조건부 상태 갱신에 실패하므로 폐기한다.
- M2 접수 응답 p95 목표는 500ms다. 결과 완료 목표는 1~3장 p95 10초, 10장 p95 30초다.
  처리 시간은 SLA 보장이 아니라 초기 SLO이며 부하 시험으로 갱신한다.

MapReduce 한 잡의 기동 오버헤드가 약 15초이므로 M2마다 새 YARN 잡을 기동하지 않는다. MVP
작업자는 이미 준비된 임베딩·색인과 장기 실행 worker 또는 묶음 처리 경로를 사용하고,
MapReduce는 검색 대상의 사전 계산·대규모 갱신과 최종 Top-K 규칙의 소유자로 유지한다.

## 7. DB 스키마와 권한

- 첫 도메인 구현 전에 Flyway를 추가하고 `V1__initial_schema.sql`에서 승인된 ERD를 생성한다.
- 로컬·테스트·운영 모든 프로필은 `spring.jpa.hibernate.ddl-auto=validate`다.
- Flyway migration은 애플리케이션 기동 또는 단일 배포 단계 중 하나에서 한 번만 실행한다.
  DATA/AI 프로세스와 여러 API replica가 각각 DDL을 실행하지 않는다.
- 이미 적용된 migration을 수정하지 않고 새 버전 migration을 추가한다.
- 통합 테스트는 PostgreSQL Testcontainers에 실제 migration을 적용한다.
- `docs/erdcloud-import.sql`은 시각화 입력이며 Flyway 파일로 복사해 실행하지 않는다.

운영은 최소한 다음 역할을 분리한다.

| 역할 | 쓰기 범위 |
|---|---|
| `mgbj_migrator` | 배포 시 DDL, 평상시 애플리케이션 사용 금지 |
| `mgbj_api` | 회원·세션의 개인 푸시 등록·사용자 게시물·채팅·채팅 알림 Outbox·M2 생성·요약 발송 시각 |
| `mgbj_ingestor` | 공공 게시물·사진 메타데이터·수집 실행 |
| `mgbj_matcher` | match_run 상태와 match_candidate |

각 역할은 필요한 sequence와 대상 테이블만 허용한다. API 역할에는 다른 컴포넌트 소유 테이블의
임의 수정 권한을 주지 않는다.

## 8. 상태·관측·백업

- Airflow `collector_daily`는 매일 22:30 KST 실행한다. D1 판정은 `DAILY_INCREMENTAL`과 첫
  일일 실행 전의 `INITIAL_FULL`만 대상으로 하며 `BACKFILL`은 제외한다. 대상 최신 실행이
  `RUNNING`이면 `RUNNING`, 최신 종료 실행이 실패면 `FAILED`, 성공 이력이 없으면
  `NEVER_SYNCED`다. 그 외 마지막 성공한 `DAILY_INCREMENTAL`(아직 없으면 `INITIAL_FULL`)의
  `completed_at`이 현재보다 36시간 이전이면 `DELAYED`, 아니면 `SUCCEEDED`다.
- 모든 요청은 신뢰할 수 없는 외부 값과 구분되는 서버 생성 request ID를 응답 헤더와 구조화 로그에
  남긴다. 민감정보 redaction test를 둔다.
- liveness는 프로세스 생존만, readiness는 PostgreSQL·Redis와 필수 키·설정 준비 여부를 검사한다.
  HDFS·DATA/AI 장애는 게시물 쓰기/분석 접수 등 해당 기능 readiness와 상태 API로 분리한다.
- OTP·개인 FCM 발송 실패율, 채팅 알림 Outbox PENDING 적체·lease 만료, Redis 메모리·연결,
  36시간 수집 지연, match_run PENDING 적체, RUNNING 5분 초과,
  탈퇴 30일 파기 실패, HDFS staging·비참조 파일 정리 실패를 경보한다. 비민감 애플리케이션·
  보안 로그는 일 단위로 30일 초과분을 삭제하고 삭제 작업 실패도 경보한다.
- PostgreSQL은 매일 암호화 백업하고 30일 보관한다. 월 1회 별도 환경에서 복구를 검증한다.
- HDFS replication 2는 노드 장애 가용성 수단이지 백업이 아니다. 사용자 사진의 복구 목표가
  필요하면 별도 스냅샷·오프클러스터 백업을 구성하고 실제 복구 시험을 기록한다.

## 9. 개발 시작 전 차단 조건

- [ ] Flyway 의존성과 첫 migration, `ddl-auto=validate` 전환
- [ ] Redis 운영 설정과 모든 목적별 Secret 주입 경로
- [ ] SecLists 비밀번호 차단 파일의 고정 commit·checksum 파일
- [ ] SOLAPI 운영 자격증명과 Fake 공급자 분리
- [ ] FCM 운영 자격증명, 개인 토큰 암호화·조회 Secret과 로컬·테스트 Fake 공급자 분리
- [ ] 승인된 행정구역 CSV의 버전·checksum 고정과 readiness 검증
- [ ] HDFS·DB·Redis Testcontainers 또는 대체 통합 테스트 환경
- [ ] DATA/AI 작업자의 `match_run` 선점·완료 계약
- [ ] 활성 `v2` 모델의 점수 임계값 평가·설정
- [ ] 탈퇴 30일 파기 job, HDFS 정리, 30일 로그 보존·redaction과 경보 검증
- [ ] `privacy-collection-v1` 실제 고지문과 위치 공개 안내의 개인정보 책임자 검토·승인
