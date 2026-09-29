# 코드 컨벤션

> 강제 장치: 포맷팅·린트 → `npm run check` (로컬 훅 + CI MR 게이트에서 동일 실행)
> 설정 파일이 곧 컨벤션입니다. 이 문서와 실제 설정이 다르면 임의 판단하지 말고 정책 충돌로 보고합니다.

## 0. 이 프로젝트의 확정 도구

| 영역 | 도구 | 설정 원본 |
|---|---|---|
| Kotlin (Android) 포맷·린트 | ktlint | `android/app/build.gradle.kts` + 루트 `.editorconfig`의 `[*.{kt,kts}]` 절 |
| Java 포맷 | Spotless (google-java-format, AOSP 4칸) | `backend/build.gradle` |
| Java 린트 | Checkstyle (네이밍·import·구조만) | `backend/config/checkstyle/checkstyle.xml` |
| 인코딩·개행 | `.editorconfig` + `.gitattributes` | 루트 |

실행: `npm run check` (검사) / `npm run fix` (자동 수정). 커밋 시 lefthook이 스테이징된 파일에 자동 적용.

## 1. 기본 원칙

- 코드는 **사람이 먼저 읽는 문서**
- 함수·클래스·모듈은 **하나의 책임**
- **중복 제거보다 의도 표현을 우선**
- 포맷팅은 개인 취향으로 수정하지 않고 도구에 맡김
- 큰 변경과 단순 포맷팅을 같은 커밋에 섞지 않음
- 공개 API·공통 모듈·설정 파일 변경은 MR 본문에 영향 범위 작성

## 2. 포맷팅

- 포매터 설정 파일을 **저장소에 포함** (위 표의 설정 원본)
- IDE 기본 포맷보다 프로젝트 설정 우선
- 자동 포맷 결과를 수동으로 되돌리지 않음
- 린트 경고를 무시해야 하면 **코드 근처에 이유를 짧게 남김**

## 3. 네이밍

### 공통

- 축약어는 널리 쓰이는 것만
- 불리언은 **긍정문**
- 컬렉션은 복수형
- 임시·무의미·타입 반복 이름 금지

```text
좋음: isActive, users, accessToken, paymentRequest
피함: flag, data, temp, obj, userListData
```

### 백엔드

`UserController` · `UserService` · `UserRepository` · `CreateUserRequest` · `UserResponse`

- Controller — 요청·응답 흐름만
- Service — 비즈니스 규칙
- Repository — 데이터 접근
- DTO·Request·Response를 역할에 맞게 구분

### Android

`UserProfileCard`(Composable) · `LoginViewModel` · `PetRepository` · `formatDate` · `onSubmit`

- Composable 함수는 PascalCase (ktlint가 `@Composable` 예외로 허용하도록 설정됨)
- 클래스 역할 접미사: `~Screen`(화면 Composable) · `~ViewModel` · `~Repository`
- 일반 함수·변수는 camelCase, util은 **동사 기반**
- 콜백 파라미터는 `on` 접두사 (`onClick`, `onSubmit`)

## 4. 디렉터리

기술이 아니라 **역할과 변경 이유** 기준으로 나눕니다.

```text
backend/src/main/java/com/meonggo/backend/     android/app/src/main/java/com/hotdog/meonggocuisine/
  <domain>/  (auth, user, ...)                 <feature>/   (기능 단위: match, report, ...)
    controller/  dto/  entity/                   ui/          (Screen·Composable)
    exception/   repository/  service/           data/        (Repository·API 클라이언트)
  global/                                      core/        (공용 UI·유틸 — 2회 이상 쓰일 때만)
```

- 기능 단위 응집도 유지
- 공통 모듈이 무분별하게 커지지 않도록 사용 기준 설정
- 테스트 파일은 대상 코드와 찾기 쉬운 위치에

## 5. 함수 · 클래스 · 모듈

**함수**

- 한 가지 목적
- 이름만 보고 결과·부수효과 예측 가능
- 매개변수 많아지면 객체/DTO로 묶기
- 숨겨진 전역 상태 의존 금지
- 복잡한 조건문은 의도를 드러내는 함수로 분리

**금지 패턴**

- 한 함수에서 검증 + 비즈니스 + DB 저장 + 응답 생성을 모두 수행
- 이름과 다르게 외부 상태를 변경
- 여러 의미를 가진 `boolean` 매개변수

**클래스·모듈**

- 변경 이유가 하나에 가깝게
- **공통 모듈은 실제로 두 번 이상 필요해졌을 때** 분리
- 순환 의존성 금지
- 외부 라이브러리 의존성은 경계 모듈 안에 가둠

```text
Controller → Service → Repository → Database
Page → Feature Component → Shared Component → Utility
```

## 6. API

**이 프로젝트의 모든 API URL은 `/api/v1` prefix를 사용합니다** (ADR-001 D8). Android 앱의 API base URL도 `/api/v1`까지 포함해 구성합니다.

```text
GET    /api/v1/users
GET    /api/v1/users/{userId}
POST   /api/v1/users
PATCH  /api/v1/users/{userId}
DELETE /api/v1/users/{userId}
```

- HTTP method는 리소스 동작에 맞게
- URL은 **명사 중심**
- 응답 구조를 프로젝트 전체에서 일관되게 (`ApiResponse` — [backend-common-settings.md](../backend/docs/backend-common-settings.md))
- 에러 응답은 공통 포맷
- 페이지네이션·정렬·필터 파라미터 이름 통일
- breaking change 시 기존 버전 유지 + 새 버전 추가 우선

## 7. 에러 처리

- 예외를 숨기지 않음
- **사용자에게 보여줄 메시지와 로그에 남길 메시지를 구분**
- 공통 에러 코드/타입 사용
- 외부 시스템 오류는 원인 추적 가능하게 로깅
- 복구 가능/불가능 오류 구분

```json
{ "code": "USER_NOT_FOUND", "message": "User not found" }
```

## 8. 로깅

| Level | 용도 |
|---|---|
| `debug` | 개발 중 상세 흐름 |
| `info` | 주요 비즈니스 이벤트 |
| `warn` | 처리 가능하지만 확인 필요 |
| `error` | 실패한 요청·작업·외부 연동 |

- **민감 정보 금지**
- 요청 ID, 사용자 ID, 작업 ID 등 추적값 포함
- 단순 디버깅 로그는 **MR 전에 제거**

## 9. 설정

- 환경별 설정을 코드와 분리
- 민감 정보 커밋 금지
- `.env.example` 또는 샘플 설정 제공
- 기본값은 로컬 개발자가 바로 실행 가능하게
- 운영 설정은 CI/CD 또는 배포 환경에서 주입

**절대 커밋 금지** — 실제 DB 비밀번호 / 운영 API Key / 개인 access token / webhook URL / 클라우드 secret key

## 10. 데이터베이스 (PostgreSQL)

```text
테이블: snake_case
컬럼:   snake_case
인덱스: idx_<table>_<columns>
외래키: fk_<from_table>_<to_table>
```

- 마이그레이션은 **되돌릴 수 있는 형태**로
- 인덱스 추가는 조회 패턴과 함께 검토
- 대량 변경 쿼리는 운영 영향도 확인
- 애플리케이션 코드와 스키마 변경 **순서** 고려

## 11. 프론트엔드

```text
pages:      라우팅 단위 화면
components: 재사용 가능한 공용 UI
hooks:      상태·동작 재사용 (전역 상태 라이브러리는 필요해지는 시점에 결정)
utils:      순수 유틸리티
```

- UI 상태 / 서버 상태 / 폼 상태를 구분
- 컴포넌트의 표시 책임과 데이터 처리 책임 분리
- **공용 컴포넌트는 도메인 로직을 갖지 않음**
- 접근성 속성 작성
- API 호출 로직을 컴포넌트에 흩뿌리지 않음

## 12. 테스트

| 종류 | 목적 |
|---|---|
| Unit | 함수·클래스·모듈 단위 |
| Integration | DB·외부 API·모듈 간 연동 |
| Scenario | 요구사항 기반 흐름 |
| E2E | 실제 사용자 흐름 |

- 테스트 이름은 **검증하려는 동작**을 설명 (Java는 camelCase — Checkstyle이 강제)
- 정상 + 실패 케이스 함께
- 외부 시스템은 mock / stub / test container
- 리팩토링 MR은 기존 테스트 통과 필수
- 버그 수정 MR은 가능하면 재현 테스트 포함

## 13. 주석

**좋은 주석** — 왜 이 처리가 필요한지 / 외부 시스템 제약 / 임시 우회 코드의 **제거 조건**

**피할 주석** — 코드와 같은 말 반복 / 현재 동작과 안 맞는 낡은 주석 / 주석으로만 설명되는 복잡한 로직

## 14. 코드 체크리스트

- [ ] 네이밍이 역할과 의도를 설명하는가
- [ ] 함수·클래스·모듈의 책임이 명확한가
- [ ] 파일 위치가 프로젝트 구조와 일관되는가
- [ ] API·에러 응답·로그 형식이 일관되는가
- [ ] 환경 설정·민감 정보가 코드에 포함되지 않았는가
- [ ] 불필요한 주석·디버깅 코드·임시 로그가 없는가
- [ ] 포매터·린터 결과를 임의로 되돌리지 않았는가
