# Network

Retrofit 설정, 공통 응답 처리와 인증 전송 경계를 둔다. API base URL은 `/api/v1`까지 포함한다.

## 현재 구성

- `NetworkModule.kt`: 공통 JSON, OkHttp와 Retrofit 객체를 Hilt로 제공합니다.
  타임아웃은 연결 15초, 쓰기·읽기 60초(진행이 멈춘 뒤), 호출 전체 3분이다 — 사진 10장 업로드가 기본 10초 안에 끝나지 않아 앱이 먼저 끊던 문제(2026-09-23) 때문에 늘렸다.
- `ApiResponse.kt`: Backend의 `code`, `message`, `data` 응답 형식을 정의합니다.
- Debug 기본 주소는 Android Emulator에서 로컬 PC를 가리키는 `http://10.0.2.2:8080/api/v1/`입니다.
- 다른 주소는 Gradle 실행 시 `-PAPI_BASE_URL=https://host/api/v1/`로 주입합니다.
- Debug 빌드에서만 로컬 HTTP 연결을 허용하며 Release 기본 주소는 통신에 실패하는 안전한 주소입니다.
- 인증 요청·응답 본문과 토큰을 출력하는 HTTP 로깅은 사용하지 않습니다.

## 인증 토큰 전송

- `AuthTokenProvider.kt`: 네트워크 계층이 토큰을 얻는 경계입니다. 구현은 인증 기능에 두어
  `core/network`가 기능 패키지를 참조하지 않게 합니다.
- `AuthTokenInterceptor.kt`: 보호 API 요청에 `Authorization: Bearer` header를 붙입니다.
- 인증 없이 호출하는 인증 API(A0-1, A0-2, A1, A2, A3)는 header를 붙이지 않고 갱신 대상에서도
  제외합니다. A4 로그아웃은 보호 API이므로 제외하지 않습니다.
- 401 응답 본문이 `AUTH-002`면 토큰을 한 번 갱신하고 원래 요청을 한 번만 재시도합니다.
  `AUTH-003`이면 갱신하지 않고 세션을 삭제합니다.
- 토큰과 `Authorization` header 값은 로그에 남기지 않습니다.
