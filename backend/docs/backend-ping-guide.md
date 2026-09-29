# Backend Ping API 가이드

Ping 도메인은 실행 중인 Spring Boot 서버의 HTTP 연결을 확인하고, 프로젝트의 성공·오류 응답 구현 방식을 예제로 제공합니다.
관련 개발 가이드는 [Backend 문서 인덱스](README.md)에서 확인합니다.

## 환경별 제공 범위

| Profile | 정상 Ping | Error Ping |
| --- | --- | --- |
| `dev` | 제공 | 제공 |
| `prod` | 제공 | 미제공 |

Error Ping은 의도적으로 오류와 500 로그를 만들기 때문에 `@Profile("dev")`에서만 등록됩니다. Spring Boot DevTools 사용 여부가 아니라 활성 Spring Profile을 기준으로 합니다.

## 정상 Ping

Git Bash, macOS, Linux:

```bash
curl http://localhost:8080/api/v1/ping
```

Windows PowerShell:

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/v1/ping"
```

응답:

```json
{
  "code": "SUCCESS",
  "message": "요청에 성공했습니다.",
  "data": {
    "status": "pong"
  }
}
```

처리 흐름은 `PingController → PingService → PingResponse → ApiResponse`입니다. Ping은 데이터베이스를 조회하지 않으므로 DB readiness를 보장하지 않습니다.

## 비즈니스 오류

Git Bash, macOS, Linux:

```bash
curl http://localhost:8080/api/v1/ping/errors/business
```

Windows PowerShell:

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/v1/ping/errors/business"
```

HTTP 상태는 `409 Conflict`이며 응답 코드는 `PING-001`입니다. `ErrorPingService`가 `BusinessException(PingErrorCode.BUSINESS_ERROR)`을 발생시키고 `GlobalExceptionHandler`가 공통 오류 응답으로 변환합니다.

```json
{
  "code": "PING-001",
  "message": "의도적으로 발생시킨 Ping 비즈니스 예외입니다."
}
```

처리 흐름:

```text
ErrorPingController
→ ErrorPingService
→ BusinessException
→ PingErrorCode
→ GlobalExceptionHandler
→ ApiResponse
```

## 요청값 검증 오류

Git Bash, macOS, Linux:

```bash
curl -X POST http://localhost:8080/api/v1/ping/errors/validation \
  -H "Content-Type: application/json" \
  -d '{"message":""}'
```

Windows PowerShell:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8080/api/v1/ping/errors/validation" `
  -ContentType "application/json" `
  -Body '{"message":""}'
```

빈 `message`는 `@NotBlank` 검증에 실패해 `400 Bad Request`, `COMMON-001`, `data.fieldErrors`를 반환합니다.

```json
{
  "code": "COMMON-001",
  "message": "요청 값이 올바르지 않습니다.",
  "data": {
    "fieldErrors": [
      {
        "field": "message",
        "reason": "메시지는 필수입니다."
      }
    ]
  }
}
```

유효한 메시지를 보내면 요청값 검증을 통과해 `200 OK`와 `pong`을 반환합니다.

```json
{
  "message": "valid"
}
```

## 접근 권한 오류

Git Bash, macOS, Linux:

```bash
curl http://localhost:8080/api/v1/ping/errors/forbidden
```

Windows PowerShell:

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/v1/ping/errors/forbidden"
```

dev 전용 `forbidden` 엔드포인트는 `@PreAuthorize("denyAll")`로 항상 접근을 거부합니다. 메서드 보안에서 발생한 오류도 `GlobalExceptionHandler`가 `403 Forbidden`, `AUTH-007` 공통 응답으로 변환합니다.

```json
{
  "code": "AUTH-007",
  "message": "접근 권한이 없습니다."
}
```

## 예상하지 못한 오류

Git Bash, macOS, Linux:

```bash
curl http://localhost:8080/api/v1/ping/errors/unexpected
```

Windows PowerShell:

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/v1/ping/errors/unexpected"
```

`ErrorPingService`가 `IllegalStateException`을 발생시키고 `GlobalExceptionHandler`가 `500 Internal Server Error`, `COMMON-500`으로 변환합니다.

```json
{
  "code": "COMMON-500",
  "message": "서버 내부 오류가 발생했습니다."
}
```

내부 예외 메시지와 스택 트레이스는 응답에 포함하지 않고 서버 로그에만 기록합니다.

## 컴포넌트별 책임

| 컴포넌트 | 책임 |
| --- | --- |
| `PingController` | 정상 요청을 받고 성공 DTO를 `ApiResponse`로 감쌈 |
| `PingService` | HTTP 타입에 의존하지 않고 `PingResponse` 반환 |
| `ErrorPingController` | 개발 환경에서 오류별 참고 엔드포인트 제공 |
| `ErrorPingService` | 비즈니스 예외 또는 예상하지 못한 예외 발생 |
| `PingErrorCode` | Ping 도메인의 HTTP 상태, 오류 코드, 안전한 메시지 정의 |
| `GlobalExceptionHandler` | 예외를 공통 오류 응답으로 변환 |
| `SecurityConfig` | 공개 경로와 기본 인증 정책, 메서드 보안 활성화 |

## 새로운 도메인에 적용하는 방법

1. Controller는 요청을 받고 성공 DTO를 `ApiResponse.success(...)`로 감쌉니다.
2. Service는 DTO를 반환하거나 `BusinessException`을 발생시킵니다.
3. 도메인 오류 enum은 `ErrorCode`를 구현하고 도메인 패키지에 둡니다.
4. 요청 DTO에 Bean Validation 애너테이션을 선언하고 Controller에서 `@Valid`를 사용합니다.
5. 예상하지 못한 예외는 임의로 변환하지 않고 `GlobalExceptionHandler`의 안전한 500 응답에 맡깁니다.

공통 응답과 오류 코드의 전체 규칙은 [Backend 공통 설정 가이드](./backend-common-settings.md)를 참고합니다.

## 운영 상태 확인과의 차이

`GET /api/v1/ping`은 Spring MVC 요청이 애플리케이션까지 도달하는지만 확인합니다. 다음 항목의 정상 여부를 보장하지 않습니다.

- PostgreSQL 연결
- 외부 API 연결
- Redis 연결
- 디스크와 메시지 브로커 상태

운영 환경에서 health/readiness가 필요하면 Ping에 DB 조회를 추가하지 않고 Spring Boot Actuator를 별도로 도입합니다.
