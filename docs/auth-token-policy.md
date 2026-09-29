# 인증 토큰 정책

이 문서는 Android 앱과 Spring Boot API 사이의 access token·refresh token 계약과
운영 키 관리 기준을 정의한다. 제품 범위와 ADR을 우선하며, 그 범위 안의 토큰 구현은 이
문서를 기준으로 한다. 외부 API 형식은 [API 명세](api-spec.md)의 A2~A4를 함께 따른다.

`meonggocuisine-auth`·`meonggocuisine-api`·`meonggocuisine-android`는 출시 전에 고정한 기술 식별자이며 사용자에게
보이는 현재 서비스명 `멍고반점`과 별개다. 토큰 호환성을 깨뜨리는 브랜드명 치환은 하지 않는다.

## 1. 확정 결정

| 항목 | 정책 |
| --- | --- |
| access token | `Authorization: Bearer`로 전달하는 `RS256` 서명 JWT |
| access token TTL | 발급 시점부터 15분 |
| refresh token | `v1.{selector}.{secret}` 형식의 불투명 토큰 |
| refresh token TTL | 로그인 시점부터 30일 고정. 회전해도 만료 시각을 연장하지 않음 |
| refresh token 회전 | 정상 갱신마다 secret을 교체하고 직전 secret을 즉시 무효화 |
| 로그아웃 | 현재 세션을 폐기하고 Android의 두 토큰을 삭제. 같은 세션의 JWT도 즉시 거부 |
| 전송 | 인증 API 운영 전 HTTPS 종단을 필수로 구축하고 URL·query parameter로 토큰을 전달하지 않음 |

JWT 서명은 토큰 위변조를 막고, `sid`가 가리키는 서버 세션 상태는 로그아웃·탈퇴를 즉시
적용한다. Spring Boot API는 서명 검증 뒤 모든 보호 API에서 `auth_session`의 회원 일치,
고정 만료와 폐기 여부를 확인한다. 따라서 완전 무상태 JWT보다 DB 조회가 추가되지만,
로그아웃 후 인증 요청은 즉시 재로그인을 요구한다는 MVP 인수 조건을 우선한다.

## 2. Access token

### 2.1 형식과 claim

JWT header는 다음 값을 사용한다.

| 필드 | 값 |
| --- | --- |
| `typ` | `at+jwt` |
| `alg` | `RS256` |
| `kid` | 발급에 사용한 운영 키 식별자 |

payload는 다음 claim만 포함한다.

| claim | 규칙 |
| --- | --- |
| `iss` | 설정값 `meonggocuisine-auth`와 정확히 일치 |
| `aud` | 설정값 `meonggocuisine-api`를 포함 |
| `sub` | 회원 ID의 문자열 표현 |
| `sid` | `auth_session.id`의 문자열 표현 |
| `client_id` | Android 앱 식별값 `meonggocuisine-android` |
| `jti` | 토큰마다 새로 생성한 128비트 이상 난수 식별자 |
| `iat` | 발급 시각 |
| `nbf` | 발급 시각과 같음 |
| `exp` | 발급 시각 + 15분 |

전화번호, 로그인 ID, 닉네임, refresh token selector, 비밀번호 정보는 JWT에 넣지 않는다.
JWT payload는 암호화된 데이터가 아니므로 Android도 claim을 사용자 정보 저장소나 정책
판단의 근거로 사용하지 않는다.

### 2.2 검증

Spring Security는 허용 알고리즘을 `RS256` 하나로 고정한다. `none`이나 다른 알고리즘,
알 수 없는 `kid`, 잘못된 서명, 다른 `typ`·`iss`·`aud`·`client_id`, 누락된 필수 claim,
유효하지 않은 `sub`·`sid`, 만료된 토큰은 모두 거부한다. `exp`·`nbf` 검증의 서버 간 시계
오차는 앞뒤 30초까지만 허용한다. 서명 검증 뒤 `sid`로 `auth_session`을 조회해 세션의
`member_id`가 `sub`와 같은지, `revoked_at IS NULL`인지, `expires_at`이 지나지 않았는지와
회원 상태가 `ACTIVE`인지 모든 인증 요청에서 확인한다.

access token이 없거나 JWT 형식·서명·header·claim·시간 검증을 통과하지 못하면
`AUTH-002`와 401을 반환한다. 서명은 유효하지만 연결된 세션이 없거나 만료·폐기됐거나
회원이 일치하지 않거나 `ACTIVE`가 아니면 `AUTH-003`과 401을 반환한다. 비활성 회원에
남은 세션이 있으면 함께 폐기한다. `AUTH-005`는 로그인 A2에서 사용할 수 없는 계정임을
알릴 때만 사용한다. 오류 응답과 애플리케이션·프록시 로그에는 JWT 원문, claim 전체,
`Authorization` header를 남기지 않는다.

A4 로그아웃만 멱등 재시도를 위해 폐기 세션 검사의 예외로 둔다. JWT의 형식·서명·시간을
먼저 검증하고 `sub`·`sid`와 요청 refresh token이 같은 세션 소유임을 확인한 경우,
저장된 현재 secret 해시까지 일치하면 `revoked_at`이 이미 있어도 204를 반환한다. 폐기된
JWT로 다른 보호 API를 호출하거나 이전 refresh token을 재사용하는 것은 허용하지 않는다.

## 3. Refresh token과 재사용 탐지

### 3.1 발급과 저장

refresh token은 CSPRNG로 selector 16바이트와 secret 32바이트를 각각 생성하고 padding 없는
Base64 URL 형식으로 인코딩한다. ASCII 기준 전체 길이는 정확히 69자다.

```text
v1.{22자 selector}.{43자 secret}
```

selector는 자격 증명이 아니라 세션 조회 식별자이며 `auth_session.refresh_token_selector`에
저장한다. secret 원문은 응답에서 한 번만 전달하고 DB·Redis·로그에는 저장하지 않는다.
DB에는 UTF-8 secret의 SHA-256 결과를 소문자 16진수 64자로 저장한다. secret은 256비트
균등 난수이므로 저엔트로피 비밀번호용 반복 해싱은 적용하지 않는다.

각 로그인은 별도 `auth_session`을 만든다. 여러 기기 로그인을 허용하며 한 기기의
로그아웃이나 세션 만료가 다른 기기 세션을 폐기하지 않는다. 회원 탈퇴는 모든 세션을
폐기한다. 비밀번호 변경(A8)은 **요청한 세션만 남기고** 나머지를 폐기한다 — 비밀번호를
바꾼 사람이 쓰는 기기는 그대로 두고, 비밀번호를 알고 있었을지 모르는 다른 기기는 끊는다.

### 3.2 회전 절차

토큰 갱신은 다음 순서를 하나의 트랜잭션과 행 잠금 또는 동등한 원자적 조건부 갱신으로
처리한다.

1. 전체 69자 길이, 버전과 각 구성 요소의 길이·Base64 URL 형식을 검증한다.
2. selector로 `auth_session`을 조회하고 만료·폐기·회원 상태를 확인한다.
3. 전달받은 secret의 SHA-256과 현재 해시를 상수 시간 방식으로 비교한다.
4. 일치하면 새 32바이트 secret과 access token을 발급하고, 현재 해시와
   `last_used_at`만 갱신한다. 최초 `expires_at`은 변경하지 않는다.
5. selector는 존재하지만 secret이 일치하지 않으면 회전된 토큰의 재사용 또는 탈취로
   간주해 해당 세션의 `revoked_at`을 기록한다.

동일 refresh token의 동시 요청에서는 정확히 하나만 성공해야 한다. 나머지는 세션을
폐기하고 `AUTH-003`을 반환한다. Android가 여러 요청에서 동시에 갱신하지 않도록
single-flight를 적용하는 이유다. 서버 처리 여부를 알 수 없는 네트워크 실패 뒤 A3를
같은 토큰으로 자동 재시도하지 않으며, 안전하게 재로그인을 요구한다.

재사용을 탐지한 트랜잭션은 401 응답을 만들더라도 `revoked_at` 변경을 반드시 commit한다.
오류 예외로 전체 트랜잭션이 rollback되어 세션이 다시 유효해지지 않도록 별도 트랜잭션이나
rollback 대상이 아닌 명시적 결과 처리를 사용한다.

형식 오류, 알 수 없는 selector, 해시 불일치, 만료, 로그아웃으로 폐기된 세션과 비활성
회원은 모두 동일한 `AUTH-003`과 401로 응답해 내부 판정 결과를 노출하지 않는다. 비활성
회원에 남은 세션은 함께 폐기한다. Android는 자격 증명을 삭제한다. refresh token 원문,
selector, secret 해시는 오류 응답이나 로그에 남기지 않는다.

A2 로그인과 A3 갱신 응답에는 `Cache-Control: no-store`와 `Pragma: no-cache`를 포함한다.
인증 API를 포함한 HTTPS 응답을 중간 프록시나 Android HTTP cache에 저장하지 않는다.

## 4. Android 저장과 갱신

- access token은 프로세스 메모리에만 둔다.
- refresh token ciphertext는 다른 일반 설정과 분리한 전용 로컬 저장소에 보관한다.
- 암호화에는 Android Keystore에 생성한 추출 불가능한 AES-256-GCM 키를 사용한다. 키
  원문과 평문 refresh token을 파일·DataStore·SharedPreferences에 직접 저장하지 않는다.
- 토큰 저장 파일은 Android 백업과 기기 이전 대상에서 제외한다.
- 앱 재시작 시 refresh token이 있으면 A3으로 access token을 새로 받고, JWT payload를
  직접 해석해 로그인 여부나 만료를 판단하지 않는다.
- 서버가 반환한 `accessTokenExpiresAt` 30초 전부터 선제 갱신할 수 있다. 동시에 여러
  요청이 갱신을 요구해도 한 요청만 A3을 호출하고 나머지는 그 결과를 기다린다.
- 갱신 성공 시 새 refresh token ciphertext를 내구성 있게 저장한 뒤에만 대기 중인 요청을
  재개한다.
- 인증 API가 아닌 요청에서 `AUTH-002`를 받으면 A3을 최대 한 번 호출하고, 성공하면 원래
  요청을 한 번만 재시도한다. 보호 API나 A3의 `AUTH-003`, 또는 A3의 모호한 네트워크
  실패면 두 토큰과 암호화 저장값을 삭제하고 로그인 화면으로 이동한다.
- 토큰, `Authorization` header와 인증 요청·응답 본문을 앱 로그·분석 이벤트·크래시
  리포트에 남기지 않는다.

## 5. 서명 키 운영

RSA 키는 최소 2048비트로 생성하며 개인키는 PKCS#8 PEM, 검증 키 묶음은 `kid`를 포함한
JWKS로 관리한다. 키 ID는 재사용하지 않는다.

| 설정 | 값·주입 방식 |
| --- | --- |
| `AUTH_ACCESS_TOKEN_TTL` | `PT15M` |
| `AUTH_REFRESH_TOKEN_TTL` | `P30D` |
| `AUTH_JWT_CLOCK_SKEW` | `PT30S` |
| `AUTH_JWT_ISSUER` | `meonggocuisine-auth` |
| `AUTH_JWT_AUDIENCE` | `meonggocuisine-api` |
| `AUTH_JWT_CLIENT_ID` | `meonggocuisine-android` |
| `AUTH_JWT_ACTIVE_KID` | 현재 발급 키 ID |
| `AUTH_JWT_PRIVATE_KEY_PATH` | Jenkins credential `auth-jwt-private-key`를 mount한 PKCS#8 개인키 경로 |
| `AUTH_JWT_PUBLIC_JWKS_PATH` | credential `auth-jwt-public-jwks`를 mount한 현재·이전 공개키 JWKS 경로 |

위 설정명은 인증 구현에 연결되어 있으며 로컬 준비 절차는
[로그인·인증 세션 개발 안내](../backend/docs/auth-session-guide.md)를 따른다.
인증 API를 운영에 활성화하기 전에 `compose.prod.yml`과 Jenkins에 환경 변수 전달, 개인키와
JWKS read-only mount를 구현하고 실제 컨테이너에서 파일 접근을 검증해야 한다.

운영 개인키는 저장소, 컨테이너 이미지, 일반 `.env`, CI 로그나 빌드 산출물에 넣지 않는다.
운영 프로필은 설정 누락, RSA 2048비트 미만, active `kid`와 개인키·JWKS 불일치 시 시작에
실패해야 한다. 로컬 개발은 `.gitignore` 대상 `.secrets/`에 별도 키를 두고, 테스트는 실행
중 임시 키를 생성한다. 개발 키를 운영 fallback으로 사용하지 않는다.

키 교체 순서는 다음과 같다.

1. 새 키 쌍과 재사용하지 않을 `kid`를 만든다.
2. 모든 API 인스턴스의 JWKS에 새 공개키를 먼저 추가하고 기존 키로 계속 발급한다.
3. 모든 인스턴스가 두 공개키를 검증하는지 확인한 뒤 active `kid`와 개인키를 새 키로
   전환한다.
4. 마지막 기존 키 발급 시점부터 access token TTL 15분과 clock skew 30초가 지난 뒤
   기존 공개키를 제거한다.

교체 도중에는 현재·이전 공개키를 모두 검증하되 발급은 active 개인키 하나로만 한다.
개인키 노출이 의심되면 즉시 새 키로 전환하고 기존 공개키를 제거한다. 이 경우 기존
access token은 TTL을 기다리지 않고 전부 무효가 되며 사용자는 refresh 또는 재로그인한다.

## 6. 검증 기준

- JWT의 정상 발급과 `typ`·`alg`·`kid`·서명·필수 claim·issuer·audience·client ID·만료·
  30초 clock skew 경계를 검사한다.
- 정상 JWT라도 `sid` 세션이 없거나 만료·폐기됐거나 `sub` 회원과 다르면 거부하고,
  로그아웃 직후 같은 JWT의 보호 API 요청이 `AUTH-003`인지 검사한다.
- A4는 같은 `sub`·`sid`·refresh token으로 재요청할 때만 폐기 세션에도 204를 반환하고,
  다른 보호 API에는 이 예외가 적용되지 않는지 검사한다.
- 다른 알고리즘, `none`, 알 수 없는 `kid`, 개인정보 claim이 있는 발급 결과를 거부한다.
- refresh token 정상 회전, 고정 30일 만료, 동일 토큰 동시 갱신 한 건 성공을 검사한다.
- 회전된 토큰 재사용, 변조된 secret, 만료·로그아웃 세션에서 해당 세션이 폐기되고
  `AUTH-003`이 반환되는지 검사한다. 비활성 회원도 남은 세션을 폐기하고 같은 오류인지
  검사한다.
- 다중 기기 세션 중 현재 세션 로그아웃과 회원 탈퇴 전체 폐기를 구분한다.
- Android의 single-flight, 원 요청 1회 재시도, 앱 재시작 복구, 로그아웃·갱신 실패 시
  안전한 삭제와 백업 제외를 검사한다.
- 애플리케이션·프록시·Android 로그와 오류 응답에 토큰·selector·해시·claim 전체가 없는지
  검사한다.

## 7. 근거와 제외 범위

- [RFC 8725](https://www.rfc-editor.org/rfc/rfc8725.html)는 JWT 알고리즘 고정, issuer와
  audience 검증, 명시적 타입 구분을 권고한다.
- [RFC 9068](https://www.rfc-editor.org/rfc/rfc9068.html)은 JWT access token의 비대칭
  서명과 `at+jwt` 타입, 필수 claim·검증 규칙을 정의한다.
- [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html)은 공개 클라이언트의 refresh
  token을 매번 회전하고 이전 토큰과의 관계를 유지해 재사용을 탐지하도록 요구한다.
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)는 키를
  기기에서 추출하기 어렵게 보관하는 Android 표준 수단이다.

MVP에서는 DPoP·mTLS 기반 sender-constrained token, access token denylist, 웹 쿠키 인증,
원격 JWKS endpoint와 사용자별 세션 관리 화면을 제공하지 않는다.

TLS 종단은 2026-09-11 구축됐다 — 호스트 nginx 가 `https://api.meonggo.shop` 443을 받아
backend로 프록시한다. 다만 포트 80은 여전히 backend 컨테이너가 평문으로 직접 서빙하며
HTTP→HTTPS redirect는 없다. 인증 API를 운영에 활성화하기 전 redirect와 Secret file mount를
구축하고 검증해야 한다. 절차와 잠정 구성의 제약은 `docs/deploy-guide.md`의 "TLS 종단" 절에 있다.
