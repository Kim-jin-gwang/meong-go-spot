# 비밀번호 정책

## 1. 목적과 범위

이 문서는 MVP의 회원가입 비밀번호 생성, 서버 검증, 저장, 로그인 실패 제한과 Android 입력
책임을 정의하는 단일 상세 기준이다. 공개 API의 요약 계약은 [api-spec.md](api-spec.md)의
OD-02와 A1·A2를 따르며, 서로 다르면 이 문서의 상세 기준에 맞춰 함께 수정한다.

아이디·비밀번호 찾기와 변경, 다중 인증, 영구 계정 잠금, CAPTCHA는 MVP 범위가 아니다.

## 2. 확정 요약

| 항목 | 정책 |
| --- | --- |
| 길이 | NFC 정규화 후 Unicode 코드 포인트 기준 8자 이상 64자 이하 |
| 허용 문자 | 공백·제어·형식 문자를 제외한 한글·영문·숫자·일반 문장 부호와 기호 |
| 조합 규칙 | 영문 대·소문자, 숫자, 특수문자 종류별 포함을 강제하지 않음 |
| 공백 처리 | 앞·뒤·중간 위치와 종류에 관계없이 금지하며 자동 제거·치환하지 않음 |
| 입력 상한 | A1·A2 JSON body 8,192바이트, 정규화 전 loginId·password 각각 256 Unicode 코드 포인트 |
| 취약 비밀번호 | 버전이 고정된 서버 로컬 차단 목록과 대소문자를 무시해 전체 값 비교 후 거부 |
| 확인 입력 | Android 화면에서만 검증하고 A1 요청에는 포함하지 않음 |
| 저장 | `{argon2id-v1}` 식별자를 붙인 Argon2id 단방향 인코딩 문자열만 저장 |
| 계정 실패 제한 | 동일 로그인 ID로 15분 내 연속 5회 실패하면 15분 제한 |
| IP 실패 제한 | 동일 IP로 10분 내 20회 실패하면 15분 제한 |
| 제한 해제 | 기간 만료 또는 제한 전 정상 로그인 시 계정 실패 횟수 초기화. 영구 잠금 없음 |

## 3. 회원가입 검증

### 3.1 서버가 최종 기준이다

Android는 같은 규칙을 먼저 안내하지만 A1 서버가 모든 규칙을 다시 검사한다. Android나 다른
클라이언트의 사전 검증을 신뢰하지 않는다. 서버는 다음 순서로 처리한다.

1. A1·A2는 `Content-Length` 유무나 chunked 전송과 관계없이 JSON body를 최대 8,192바이트까지만
   읽고, 초과하면 JSON 역직렬화·NFC 정규화 전에 `COMMON-001`로 거부한다. 압축된 요청 body는
   MVP에서 받지 않는다.
2. JSON 역직렬화 뒤 NFC 정규화 전에 `loginId`와 `password`를 각각 Unicode 코드 포인트
   256자 이하인지 검사한다. 초과하면 해당 필드의 `COMMON-001`로 거부하며 입력값은 응답이나
   로그에 포함하지 않는다.
3. `password`의 누락·`null`·빈 문자열과 올바르지 않은 Unicode 입력을 거부하고 NFC로
   정규화한다.
4. 정규화한 문자열을 Unicode 코드 포인트 단위로 세어 8~64자인지 확인한다.
5. 공백·제어·보이지 않는 형식 문자가 하나라도 있는지 확인한다.
6. 정규화한 전체 값이 취약 비밀번호 차단 목록에 있는지 확인한다.
7. 모든 입력과 가입 증명 검증을 통과한 요청만 Argon2id로 인코딩해 저장한다.

검증과 저장에는 같은 NFC 정규화 결과를 사용한다. 원문이나 정규화 결과를 `trim`, 대·소문자
변환 또는 다른 문자열로 바꾸어 저장하지 않는다.

### 3.2 문자 규칙

- 스페이스, 탭, 줄바꿈을 포함해 Java의 `Character.isWhitespace` 또는
  `Character.isSpaceChar`에 해당하는 모든 문자를 거부한다.
- Unicode 일반 범주 `Cc`(제어), `Cf`(형식), `Cs`(대리), `Co`(사용자 정의),
  `Cn`(미할당)에 해당하는 코드 포인트를 거부한다. 따라서 zero-width space 같은 보이지 않는
  문자도 허용하지 않는다.
- 그 외의 한글·영문·숫자·문장 부호·기호는 허용한다.
- 서버는 입력을 자동으로 잘라내지 않는다. 사용자가 공백을 실수로 붙인 요청도 명시적인
  `password` 필드 오류로 거부한다.
- 문자 종류 조합이나 주기적 비밀번호 변경을 강제하지 않는다.

예를 들어 `멍고반점 안전한비밀번호!2026`은 중간 공백 때문에 거부한다.
`멍고반점-안전한비밀번호!2026`은 길이·문자 규칙을 통과하며, 최종 허용 여부는 취약
비밀번호 차단 목록 검사까지 수행한 뒤 결정한다.

### 3.3 로그인 ID canonical 값

대소문자나 Unicode 표현 차이로 계정별 로그인 제한을 우회하지 못하도록 회원가입, 중복
검사, 로그인 조회, 비밀번호의 로그인 ID 전체 일치 차단과 Redis 제한 키가 모두 다음 값을
사용한다.

~~~text
loginIdCanonical = NFC(NFC(loginId).toLowerCase(Locale.ROOT))
~~~

공백을 자동 제거하지 않으며 canonical 변환 후 공백이 없고 1~50자인지 검증한다. A1은
`member.login_id`에 canonical 값만 저장하고 응답도 같은 값을 반환한다. 데이터베이스는
`member.login_id` 자체에 유일 제약을 적용하며 A2도 canonical 값의 정확한 일치로 조회한다.

### 3.4 취약 비밀번호 차단 목록

- 서버 배포물은 아래에 고정한 정확히 100,000줄의 흔한·유출 비밀번호 목록을 변경 없이
  `backend/src/main/resources/security/password-blocklist/pwdb-top-100000.txt`에 포함한다.
- 외부 유출 비밀번호 조회 API는 회원가입 처리 중 호출하지 않는다.
- 원본 파일은 UTF-8로 엄격하게 해석한다. 각 줄과 입력 비밀번호에
  `NFC(NFC(value).toLowerCase(Locale.ROOT))`를 적용한 전체 값끼리 비교하며 부분 문자열만으로
  거부하지 않는다.
- 서비스명 `멍고반점`과 `loginIdCanonical` 자체도 같은 방식의 전체 일치 차단 항목으로
  취급한다.
- 차단 목록에 있으면 `COMMON-001`의 `data.fieldErrors.password`로 강한 다른 비밀번호를
  사용하라고 안내한다. 어떤 목록 항목과 일치했는지는 응답·로그에 남기지 않는다.
- 파일 누락, SHA-256 불일치, UTF-8 해석 실패나 줄 수 불일치가 있으면 애플리케이션 시작을
  실패시킨다. 회원가입만 차단 목록 없이 우회 실행하지 않는다.

| 항목 | 고정 값 |
| --- | --- |
| 배포 원본 | [SecLists `Pwdb_top-100000.txt`](https://github.com/danielmiessler/SecLists/blob/38d4d047c091c7db3724711ede4ba1dd7eec0a5f/Passwords/Common-Credentials/Pwdb_top-100000.txt) |
| SecLists commit | `38d4d047c091c7db3724711ede4ba1dd7eec0a5f` |
| 원본 SHA-256 | `07f876a616f08fb2cc5c3e0ce04e4a6d1123380580472b0997baebc4e8226977` |
| 원본 크기 | 100,000줄, 828,498바이트 |
| 라이선스 | [SecLists MIT License](https://github.com/danielmiessler/SecLists/blob/38d4d047c091c7db3724711ede4ba1dd7eec0a5f/LICENSE) |

## 4. 비밀번호 확인과 Android 책임

- 회원가입 화면은 `password`와 `passwordConfirm`을 입력받는다.
- Android는 두 값을 각각 NFC로 정규화한 뒤 같을 때만 A1을 호출한다.
- `passwordConfirm`은 입력 실수 방지용 UI 값이며 A1 JSON에 포함하지 않는다. 서버가 같은
  비밀번호를 두 번 받는 것은 계정 보안을 높이지 않기 때문이다.
- 붙여넣기, 비밀번호 관리자 자동 완성, 비밀번호 표시 전환을 허용한다.
- 두 입력을 `SavedStateHandle`, `Bundle`, DataStore, 진단 로그, 분석 이벤트나 크래시
  리포트에 저장하지 않는다. 화면 이탈 시 화면·ViewModel의 입력 상태 참조를 제거한다.

## 5. 저장 정책

### 5.1 Argon2id 프로필

신규 비밀번호는 Spring Security `PasswordEncoder`로 다음 Argon2id 프로필을 사용한다.

| 매개변수 | 값 |
| --- | ---: |
| Argon2 버전 | 19 (`0x13`) |
| memory | 65,536 KiB (64 MiB) |
| iterations | 3회 |
| parallelism | 4 |
| salt | CSPRNG로 비밀번호마다 새로 생성한 16바이트 |
| hash | 32바이트 |

`member.password_hash`에는 `{argon2id-v1}`과 Argon2 인코딩 문자열을 함께 저장한다. 인코딩
문자열에 알고리즘 버전, 비용 매개변수와 salt가 포함되므로 이후 비용 상향이나 알고리즘
교체 시 기존 값을 구분할 수 있다. 비교는 라이브러리의 상수 시간 검증 경로를 사용한다.

비밀번호 평문, NFC 정규화 값, Argon2 입력과 hash는 응답, 애플리케이션·프록시·Android
로그, 예외 메시지, `error_summary`에 기록하지 않는다. 평문을 별도 암호화하거나 복구 가능한
형태로 저장하지 않는다.

### 5.2 비용 변경

배포 환경에서 로그인 검증 부하를 측정하되 이 프로필보다 낮은 비용으로 자동 하향하지 않는다.
비용을 올리거나 새 알고리즘으로 바꿀 때는 새 식별자를 추가하고 정상 로그인 시 기존 hash를
새 프로필로 다시 인코딩할 수 있다. 로그인 검증과 재인코딩은 같은 공용 실행권을 계속 보유한
상태에서 순서대로 수행한다. 저장된 hash를 원문으로 되돌리는 마이그레이션은 없다.

### 5.3 Argon2 공용 실행권

A1의 신규 인코딩, A2의 실제·dummy hash 비교와 정상 로그인 시 비용 상향 재인코딩을 포함한
모든 요청 경로의 Argon2 작업은 하나의 대기열 없는 애플리케이션 프로세스 공용 실행권을
사용한다. MVP의 단일 API 인스턴스에서는 동시에 최대 4개 요청만 실행권을 보유한다. 즉시
획득하지 못하면 Argon2를 실행하지 않고 `AUTH-006`·HTTP 503·`Retry-After: 1`을 반환한다.
API를 여러 인스턴스로 확장할 때는 배포 전체 합계가 4를 넘지 않도록 공유 실행권으로 교체한
뒤 배포한다.

A1은 저비용 입력 검증과 불투명 가입 증명의 형식·secret hash·번호 binding 검사를 마친 뒤, 증명을 원자 선점하기
전에 실행권을 얻는다. 포화 시 증명 상태를 바꾸지 않는다. 실행권을 얻은 요청만 증명을
`ISSUED → CLAIMED`로 선점한다. 선점 성공 여부나 인코딩 성공·실패·예외와 관계없이
`finally`에 해당하는 경로에서 실행권을 반드시 한 번 반환한다. 선점 성공 시 Argon2id
인코딩 뒤 기존 회원 생성 트랜잭션을 진행한다. 인코딩 예외처럼 DB 트랜잭션 전 명확한 실패는
같은 요청 ID의 `CLAIMED`만 비교해 `ISSUED`로 복구하고, Redis 복구가 실패하면 증명을 만료까지
`CLAIMED`로 유지한다.

## 6. 로그인 검증과 실패 제한

### 6.1 계정 열거 방지

- A2 요청에서 `loginId` 또는 `password`가 누락·`null`이면 `COMMON-001`을 반환한다.
- 서버는 A2의 `password`도 A1과 똑같이 NFC 정규화하되 `trim`하거나 대·소문자를 바꾸지
  않는다. 형식 검사와 실제 `PasswordEncoder.matches`에는 같은 NFC 결과를 사용한다.
- 값이 존재하지만 아이디를 찾을 수 없거나 비밀번호가 틀렸거나 허용 길이·문자 형식이 아니면
  모두 `AUTH-001`과 같은 메시지를 반환하고 실패 제한에 포함한다.
- 존재하지 않는 로그인 ID 또는 형식이 허용되지 않는 비밀번호는 애플리케이션 시작 시 현재
  `{argon2id-v1}` encoder로 만든 dummy hash와 고정 dummy 입력을 한 번 비교한다. dummy
  hash는 실제 hash와 같은 알고리즘 식별자·64 MiB·3회·병렬도 4 매개변수를 사용하며 요청마다
  새로 생성하지 않는다. 이 비교 결과는 항상 버리고 인증 성공 판단에는 사용하지 않는다.
- 올바른 비밀번호를 확인한 뒤에만 회원 상태를 검사하고 사용할 수 없는 계정이면
  `AUTH-005`를 반환한다.

### 6.2 검증 순서와 동시 실행 상한

서버는 A2를 다음 순서로 처리한다.

1. 3.1의 body·정규화 전 필드 상한과 필수값을 검사한 뒤 `loginIdCanonical`과 NFC 비밀번호를
   만든다.
2. `member.login_id=loginIdCanonical`로 회원을 한 번 조회한다.
3. 계정·IP Redis 제한을 확인한다.
4. 5.3의 공용 Argon2 실행권을 얻는다. 즉시 얻지 못하면 `AUTH-006`·HTTP 503과
   `Retry-After: 1`을 반환한다.
5. 실행권을 얻은 뒤 Redis 제한을 다시 확인한다.
6. 회원이 존재하고 비밀번호 형식이 유효하면 실제 hash를 정확히 한 번 비교한다. 그렇지
   않으면 현재 프로필의 dummy hash를 정확히 한 번 비교하고 그 결과를 버린다.
7. 인증 성공은 `memberExists && passwordFormatValid && actualHashMatches`일 때만 성립한다.
   거짓이면 계정·IP 카운터를 원자 증가시키고 `AUTH-001`을 반환한다. 참이면 회원 상태를
   확인하고, 필요한 프로필 상향 재인코딩을 같은 실행권 안에서 처리한 뒤 계정 카운터를
   제거한다. 모든 분기에서 실행권을 반드시 반환한다.

실패 카운터는 hash 비교가 끝난 뒤 확정되므로 제한이 활성화되는 순간 이미 5단계를 통과한
검증은 끝까지 실행될 수 있다. 다만 공용 실행 상한 때문에 이런 작업은 최대 4개이며, 이후
요청은 실행권 획득 뒤 재검사에서 거부된다. 계정·IP 제한이 이미 활성화된 요청과 공용
실행권을 얻지 못한 요청은 Argon2id를 실행하지 않는다. 실행권 포화는 계정·IP 실패 횟수에
포함하지 않는다.

### 6.3 Redis 제한과 클라이언트 IP

공유 Redis 인증 캐시의 카운터 증가와 제한 전환은 Lua 스크립트 또는 동등한 원자 연산으로
처리한다.

| 기준 | 실패 창과 한도 | 제한 시간 | 성공 시 처리 |
| --- | --- | --- | --- |
| 계정 | 동일 `loginIdCanonical`로 15분 내 연속 5회 | 15분 | 계정 실패 횟수 제거 |
| IP | 동일 클라이언트 IP로 10분 내 20회 | 15분 | 제거하지 않고 TTL 만료 |

- 첫 실패에서 계정 카운터는 15분, IP 카운터는 10분 TTL로 생성하고 같은 창의 후속 실패는
  횟수만 원자 증가시키며 카운터 TTL을 연장하지 않는다.
- 5번째 계정 실패 또는 20번째 IP 실패 요청부터 `AUTH-004`와 HTTP 429를 반환한다.
- 제한 중인 요청은 카운터나 제한 TTL을 연장하지 않는다. 계정과 IP 제한이 동시에 있으면
  둘 중 더 긴 남은 시간을 `Retry-After`로 반환한다.
- 응답에는 남은 제한 시간을 초 단위 `Retry-After` header로 포함한다.
- 계정·IP 중 하나라도 제한 상태면 비밀번호 hash 비교를 수행하지 않는다.
- 계정 제한 키에는 `loginIdCanonical`의 UTF-8 바이트에 대한 HMAC-SHA-256만 사용한다.
- 현재처럼 API가 직접 연결을 받으면 socket의 direct peer 주소를 클라이언트 IP로 사용한다.
  TLS reverse proxy를 도입하면 API는 `AUTH_TRUSTED_PROXY_CIDRS`에 등록된 direct peer에서
  온 요청에만 `X-Forwarded-For`를 신뢰한다. 프록시는 외부 요청의 기존 forwarded header를
  제거하고 단일 client IP를 다시 기록해야 한다. 신뢰되지 않은 peer의 header는 무시하고,
  신뢰된 peer가 보낸 값이 없거나 여러 값·잘못된 주소면 A2를 `COMMON-001`로 거부한다.
- IP는 파싱한 network-order 바이트로 canonical화하고 IPv4-mapped IPv6는 동일한 IPv4
  4바이트로 축약한다. IP 제한 키에는 `login-ip\0`, 주소 종류 prefix와 canonical 바이트를
  순서대로 넣은 HMAC-SHA-256만 사용한다. OTP 발송 제한은 같은 Secret을 사용하더라도
  `otp-send-ip\0` domain prefix로 분리한다.
- 계정 제한은 `AUTH_LOGIN_ID_HMAC_KEY_V1`, IP 제한은 `AUTH_IP_HMAC_KEY_V1`을 사용한다.
  목적별 HMAC key는 Redis와 별도 Secret으로 주입하고 서로 재사용하지 않으며 로그인 ID·IP
  평문을 키나 값에 저장하지 않는다.
- 존재하지 않는 로그인 ID도 같은 계정 실패 제한을 적용한다. `AUTH-004` 메시지는 어떤 제한이
  발동했는지 또는 계정이 존재하는지 구분하지 않는다.
- 영구 잠금, 관리자 수동 해제, 비밀번호 찾기·재설정은 MVP에 포함하지 않는다. 제한 기간이
  지나면 새 로그인 시도를 허용한다.
- Redis 제한 상태를 안전하게 읽거나 갱신할 수 없으면 A2 로그인을 허용하지 않고
  보안 운영 정책 2.3과 동일하게 `AUTH-006`·HTTP 503과 `Retry-After: 1`로 처리한다.
  제한 장치 장애를 우회해 hash 검증을 계속하지 않는다.

| 설정 | 형식·값 |
| --- | --- |
| `AUTH_LOGIN_ID_HMAC_KEY_V1` | 로그인 ID 제한 전용. CSPRNG로 생성한 최소 32바이트 key의 Base64. 저장소·일반 `.env` 커밋 금지 |
| `AUTH_IP_HMAC_KEY_V1` | 로그인·OTP 발송 IP 제한 전용. 위 키와 별도로 생성한 최소 32바이트 key의 Base64 |
| `AUTH_TRUSTED_PROXY_CIDRS` | 쉼표로 구분한 trusted proxy CIDR. 기본값은 빈 목록이며 운영 proxy 구축 뒤 실제 ingress CIDR만 지정 |
| `AUTH_ARGON2_MAX_CONCURRENCY` | `4`. A1 인코딩·A2 비교·재인코딩이 함께 사용하며 운영에서 임의 자동 상향하지 않음 |
| `AUTH_REQUEST_MAX_BODY_BYTES` | `8192`. A1·A2의 압축되지 않은 JSON body streaming 상한 |
| `AUTH_INPUT_PRE_NORMALIZATION_MAX_CODE_POINTS` | `256`. loginId·password 각각의 NFC 전 상한 |

## 7. 오류 계약

| 상황 | HTTP | 코드 | 노출 가능한 정보 |
| --- | ---: | --- | --- |
| A1 비밀번호 생성 규칙 위반 | 400 | `COMMON-001` | `password` 필드와 충족해야 할 규칙 |
| A2 필수값 누락·`null` | 400 | `COMMON-001` | 누락된 필드명 |
| A2 아이디 없음·비밀번호 불일치·형식 불일치 | 401 | `AUTH-001` | 동일한 일반 실패 메시지 |
| 계정 또는 IP 로그인 제한 | 429 | `AUTH-004` | 일반 제한 메시지와 `Retry-After` |
| 올바른 자격 증명의 비활성 계정 | 403 | `AUTH-005` | 계정 사용 불가 메시지 |
| A1·A2 Argon2 실행권 포화 | 503 | `AUTH-006` | 일반 과부하 메시지와 `Retry-After: 1` |

`AUTH-004`의 안전한 메시지는 `로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.`로
고정한다.
`AUTH-006`의 안전한 메시지는 `인증 요청이 많습니다. 잠시 후 다시 시도해 주세요.`로 고정한다.

## 8. 필수 검증

- NFC 정규화 후 7자 거부, 8자·64자 허용, 65자 거부
- `Content-Length` 초과와 chunked 누적 8,193바이트를 정규화 전에 거부하고, loginId·password
  각각 NFC 전 257 코드 포인트를 필드 오류로 거부
- 로그인 ID의 NFC·대소문자 변형이 같은 `loginIdCanonical`, 회원 조회와 계정 실패 카운터로
  합쳐짐
- 한글·영문·숫자·일반 특수문자 허용과 문자 종류 조합 미강제
- 앞·뒤·중간 공백, 탭, 줄바꿈, zero-width space와 제어·형식 문자 거부
- 공백이 있는 비밀번호를 자동 `trim`하지 않고 A1 `COMMON-001`로 거부
- 차단 목록 전체 일치와 서비스명·로그인 ID 전체 일치 거부, 부분 문자열은 허용
- Android의 NFC 기준 확인값 불일치 시 A1 미호출과 A1 JSON의 `passwordConfirm` 미포함
- 비밀번호마다 다른 salt와 `{argon2id-v1}`·64 MiB·3회·병렬도 4 매개변수 저장
- A1과 A2의 같은 NFC 전처리 및 조합형·완성형처럼 canonically equivalent한 비밀번호 로그인
- 로그인 ID 미존재·비밀번호 불일치·형식 불일치의 같은 `AUTH-001` 메시지와 현재
  `{argon2id-v1}` 프로필의 dummy hash 1회 경로. dummy 비교의 `true` 결과도 버리고 실패
  카운터 증가
- 계정 5번째 실패와 IP 20번째 실패부터 `AUTH-004`, HTTP 429와 `Retry-After` 반환
- 제한 만료 후 재시도, 제한 전 성공 시 계정 횟수 초기화, IP 횟수 유지
- A1 인코딩·A2 실제/dummy 비교·프로필 상향 재인코딩이 같은 Argon2 공용 실행권을 사용하고
  동시 요청 최대 4개, 실행권 획득 뒤 제한 재검사와 포화 시 `AUTH-006`·`Retry-After: 1` 반환
- A1 실행권 포화 시 가입 증명이 `ISSUED`로 유지되고, 실행권 획득 후 증명 선점 실패 시
  실행권을 즉시 반환. 인코딩 예외를 포함한 모든 분기에서 실행권을 정확히 한 번 반환
- 제한 활성화 시 이미 재검사를 통과한 최대 4개만 완료되고 이후 요청은 hash 비교 전에 거부
- direct peer와 신뢰 proxy의 단일 forwarded IP, 위조 header 무시, IPv4-mapped IPv6의 같은
  IP 제한 키
- 고정 SecLists 파일의 commit·100,000줄·828,498바이트·SHA-256 검증과 누락·변조·해석 실패
  시 애플리케이션 시작 실패
- 원문·정규화 값·hash와 로그인 ID·IP 평문이 응답·로그·Redis 키에 포함되지 않음

## 9. 근거와 의도적 선택

- [NIST SP 800-63B](https://pages.nist.gov/800-63-4/sp800-63b.html)는 비밀번호 단독 인증에
  긴 비밀번호 입력 지원, 조합 규칙 금지, Unicode 사용 시 NFC 정규화, 취약 비밀번호 차단 목록과
  로그인 실패 제한을 요구하거나 권고한다. 이 MVP는 팀 결정에 따라 최소 8자를 적용한다.
- [RFC 9106](https://www.rfc-editor.org/rfc/rfc9106.html)의 메모리 제약 환경용 두 번째
  권장 Argon2id 프로필인 64 MiB·3회·병렬도 4와 16바이트 salt·32바이트 hash를 사용한다.
- [Spring Security Password Storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)의
  `PasswordEncoder`와 알고리즘 식별자 저장 방식을 사용해 이후 비용·알고리즘 교체가
  가능하게 한다.

NIST는 공백 허용을 권고하지만 이 프로젝트는 모바일 입력에서 앞·뒤 공백이나 보이지 않는
문자가 자격 증명에 포함되는 혼동을 막기 위해 모든 공백·제어·형식 문자를 의도적으로
거부한다. 대신 한글·문장 부호·기호와 긴 비밀번호를 허용하고 문자 종류 조합은 강제하지
않는다.
