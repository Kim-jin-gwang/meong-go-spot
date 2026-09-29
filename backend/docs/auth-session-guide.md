# 로그인·인증 세션 개발 가이드

관련 이슈: #61, #68, #72, #73, #74.
계약은 [인증 토큰 정책](../../docs/auth-token-policy.md), [비밀번호 정책](../../docs/password-policy.md),
[API 명세 A2–A4](../../docs/api-spec.md)를 따른다.

## 로컬 실행

1. [가입 개발 가이드](member-signup-guide.md)에 따라 PostgreSQL·Redis와 가입용 키를 준비한다.
2. `node scripts/setup-session-dev.mjs`를 한 번 실행한다. RSA 개인키·공개 JWKS와 로그인 ID
   HMAC 키가 `.secrets/auth-sessions-dev/`에 생성된다. 기존 가입 키는 바꾸지 않는다.
3. 출력된 `SPRING_CONFIG_ADDITIONAL_LOCATION`을 실행 환경에 설정한다. 가입·세션 설정 두
   파일을 쉼표로 함께 지정해야 한다. `npm run dev:be`를 실행한다.

키 생성 스크립트는 기존 디렉터리가 있으면 덮어쓰지 않는다. 개인키는 PKCS#8 PEM,
공개키는 `kid`를 가진 JWKS다. 테스트는 실행 중 임시 RSA 키를 만들며 운영 artifact에
테스트 초기화 코드가 포함되지 않는다.

## API와 세션 동작

- `POST /api/v1/auth/login`: canonical ID·NFC 비밀번호를 비교해 기기별 세션을 만든다.
  성공 응답에는 회원 ID·닉네임과 Bearer 토큰 쌍이 있다.
- `POST /api/v1/auth/tokens/refresh`: 같은 세션의 secret을 원자적으로 교체한다. 로그인
  시점의 30일 만료는 연장하지 않는다. 재사용이 감지되면 해당 세션을 폐기한다.
- `POST /api/v1/auth/logout`: access token과 현재 refresh token이 같은 세션이면 폐기하고
  빈 204를 반환한다. 같은 토큰 쌍으로 다시 로그아웃해도 204다.

JWT는 `sub`·`sid`를 principal로 전달하며 보호 API마다 회원과 세션 상태를 확인한다.
서명·claim·시간 오류는 AUTH-002, 폐기·만료·회원 불일치 등 세션 오류는 AUTH-003이다.
다른 기기의 정상 세션은 유지한다. 비활성 회원의 남은 세션은 모두 폐기한다.
회원 탈퇴 API와 개인정보 파기 작업은 별도 개발 묶음이다.

계정·IP 실패 제한은 Redis Lua로 처리하고, 키에는 목적별 HMAC만 저장한다. 회원가입과
로그인은 최대 4개의 같은 Argon2 실행권을 공유한다. 로그인 성공은 계정 실패 횟수만
초기화하며 IP 실패 횟수는 고정 TTL까지 유지한다.

## 운영 설정

`.env.example`의 `AUTH_JWT_*`, access/refresh TTL, `AUTH_LOGIN_ID_HMAC_KEY_V1`을 실제 실행
환경에 주입한다. JWT 설정 누락·잘못된 RSA 키·active kid 불일치는 시작 오류다.
HMAC 키는 최소 32바이트의 독립 난수이며 기존 전화번호·OTP 키를 재사용하지 않는다.

운영 활성화에는 기존 정책대로 HTTPS 종단, Jenkins credential과 compose의 개인키·JWKS
read-only mount가 필요하다. 로컬 생성 키를 운영 fallback으로 사용하지 않는다. 요청·응답
본문 및 Authorization 헤더를 프록시·앱·분석 로그에 남기지 않는다.
