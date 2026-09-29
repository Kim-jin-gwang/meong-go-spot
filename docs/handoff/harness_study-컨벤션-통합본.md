# harness_study 컨벤션 통합본

> 출처: `Desktop/harness_study` — `docs/` 11개 문서 + `.github/` 템플릿·라벨 + 루트 설정 파일
> 주제별로 재조립했습니다. 원문 위치는 각 섹션에 표기.

---

## 0. 컨벤션 지도

| 주제 | 원문 |
|---|---|
| 브랜치 | `docs/branch-strategy.md` |
| 커밋 | `docs/commit-strategy.md` |
| 코드 (공통) | `docs/code-convention.md` |
| 백엔드 응답·예외 | `backend/docs/backend-common-settings.md` |
| 백엔드 API 개발 | `backend/docs/backend-api-development-guide.md` |
| 이슈 | `docs/issue-template-guide.md` + `.github/ISSUE_TEMPLATE/` |
| PR | `docs/pr-template-guide.md` + `.github/PULL_REQUEST_TEMPLATE/` |
| 라벨 | `docs/label-strategy.md` + `.github/labels.json` |
| 파일 포맷 | `docs/editor-config.md`, `docs/git-attributes.md`, `docs/gitignore-guide.md` |
| 제품·문구 | `PRODUCT.md` |
| AI 에이전트 | `AGENTS.md`, `CLAUDE.md`, `docs/skill-guide.md` |
| 기여 절차 | `CONTRIBUTING.md` |

---

# I. Git 컨벤션

## 1. 브랜치

### 타입과 흐름

| 브랜치 | 생성 기준 | PR 대상 | 용도 |
|---|---|---|---|
| `main` | — | — | 항상 배포 가능한 안정 상태 |
| `dev` | `main` | — | 개발 통합, 다음 릴리즈 후보 |
| `feat/*` | `dev` | `dev` | 새 기능 |
| `bugfix/*` | `dev` | `dev` | 개발 중 발견한 일반 버그 |
| `refactor/*` | `dev` (또는 `feat/*`) | `dev` (또는 `feat/*`) | 동작 변경 없는 구조 개선 |
| `docs/*` | `dev` | `dev` | 문서 |
| `release/*` | `dev` | **`main` + `dev`** | 릴리즈 전 최종 검증 |
| `hotfix/*` | **`main`** | **`main` + `dev`** | 운영 긴급 수정 |

```text
feat/*      → dev
refactor/*  → dev
bugfix/*    → dev
docs/*      → dev
release/*   → main, dev
hotfix/*    → main, dev
```

> `release/*`와 `hotfix/*`는 **반드시 `dev`에도 반영**해서 브랜치 간 차이를 없앱니다.

### 네이밍

```text
<type>/<description>
<type>/<issue-number>-<description>
```

- 영문 소문자·숫자·하이픈만
- 공백 금지
- 브랜치명만 보고 목적을 알 수 있게
- 이슈 기반 작업이면 이슈 번호 포함

```text
feat/login          feat/12-login
bugfix/31-token-refresh
refactor/auth-service
docs/api-guide
release/1.0.0
hotfix/payment-timeout
```

### 기본 규칙

- 모든 병합은 **PR 기반**
- 팀 프로젝트는 **전원 approve 후 병합**을 원칙으로
- AI 자동 리뷰를 함께 사용
- `main`에 기능 브랜치를 직접 병합하지 않음
- 병합 후 작업 브랜치 삭제 (승인자가)

### 브랜치별 테스트 범위

| 브랜치 | 필요한 테스트 |
|---|---|
| `feat/*` | 단위 테스트 우선 |
| `bugfix/*` | 버그 재현 케이스 + 수정 확인 |
| `refactor/*` | **기존 테스트 전부 통과** |
| `release/*` | 통합 + 시나리오 + E2E |
| `hotfix/*` | 수정 범위 빠른 검증 후 배포, 이후 `dev` 반영 |

---

## 2. 커밋

### 형식

```text
<type>(<scope>): <subject>
```

`scope`는 선택.

### 타입 13종

| Type | 용도 | 예시 |
|---|---|---|
| `init` | 프로젝트 초기 설정 | `init: initialize Spring Boot project` |
| `feat` | 새 기능 | `feat(auth): add login API` |
| `fix` | 버그 수정 | `fix(auth): handle expired token` |
| `build` | 빌드 시스템·외부 의존성 | `build: add Dockerfile` |
| `chore` | 기능 변화 없는 기타·설정 | `chore: update env example` |
| `ci` | CI/CD 설정 | `ci: add test workflow` |
| `docs` | 문서 | `docs: update README` |
| `style` | 포맷팅·세미콜론·들여쓰기 | `style: format user service` |
| `refactor` | 기능 변경 없는 구조 개선 | `refactor(auth): split token service` |
| `test` | 테스트 추가·수정 | `test(auth): add login test` |
| `perf` | 성능 개선 | `perf(post): optimize list query` |
| `revert` | 이전 커밋 되돌리기 | `revert: revert login API change` |
| `release` | 버전 릴리즈 | `release: v1.0.0` |

### 제목·본문 규칙

**제목**

- 50자 이내 권장
- 마침표로 끝내지 않음
- 무엇을 했는지 드러나게

**본문** (필요할 때만)

- 무엇을 변경했는지 / **왜 변경했는지** / 리뷰어가 알아야 할 배경·제약
- 코드가 *어떻게* 동작하는지 길게 설명하지 말 것

```text
feat(auth): add login API

Add an access-token based login endpoint for user authentication.
This prepares the backend for frontend login integration.
```

### 커밋 단위

**좋은 단위** — 기능 하나 / 버그 하나 / 리팩토링 목적 하나 / 문서 하나 / 설정 하나

**피할 것**

- 여러 기능·버그를 묶은 커밋
- 코드 변경 + 대규모 포맷팅 혼합
- 메시지만 보고 의도를 알 수 없는 커밋

### 이슈 연결

```text
Refs #12    Closes #12    Fixes #12    Resolves #12
```

> 자동 close 키워드는 **PR 본문에** 쓰는 것을 권장.

### 브랜치 타입 × 커밋 타입

완전히 같을 필요는 없습니다. 브랜치는 큰 목적, 커밋은 각 변경의 실제 목적.

| Branch | 자주 쓰는 Commit Type |
|---|---|
| `feat/*` | `feat`, `fix`, `test`, `docs`, `refactor`, `chore` |
| `bugfix/*` | `fix`, `test` |
| `hotfix/*` | `fix`, `test`, `release` |
| `refactor/*` | `refactor`, `test` |
| `docs/*` | `docs` |
| `release/*` | `release`, `fix`, `docs`, `chore` |

### Gitmoji (선택)

```text
<emoji> <type>(<scope>): <subject>
```

| 🎉 `init` | ✨ `feat` | 🐛 `fix` | 🏗️ `build` | 🔧 `chore` | 👷 `ci` | 📝 `docs` |
|---|---|---|---|---|---|---|
| 🎨 `style` | ♻️ `refactor` | ✅ `test` | ⚡️ `perf` | ⏪️ `revert` | 🚀 `release` | |

> 팀에서 쓰기로 정했으면 **전 커밋에 일관되게**, 아니면 아예 쓰지 않습니다. 쓰더라도 타입은 유지.

### PR 전 dev 동기화

공유 브랜치는 `merge`, 개인 브랜치는 `rebase` (팀 규칙 우선).

```bash
# merge
git switch dev && git pull origin dev
git switch feat/login && git merge dev

# rebase
git switch feat/login && git fetch origin
git rebase origin/dev
```

rebase 후 force push가 필요하면 **반드시** `--force-with-lease`:

```bash
git push --force-with-lease origin feat/login
```

| 옵션 | 동작 |
|---|---|
| `--force` | 원격을 조건 없이 덮어씀 |
| `--force-with-lease` | 원격이 **내가 알던 상태일 때만** 덮어씀 |

---

# II. GitHub 컨벤션

## 3. 이슈

### 템플릿 3종

| 파일 | 용도 | 제목 prefix | 기본 라벨 |
|---|---|---|---|
| `bug_report.md` | 버그·오류 제보 | `fix: ` | `type/bug`, `needs/triage` |
| `feature.md` | 기능·개선 제안 | `feat: ` | `type/feature`, `needs/triage` |
| `refactor.md` | 구조 개선 제안 | `refactor: ` | `type/refactor`, `needs/triage` |

> **`[FEAT]`, `[BUG]`, `[REFACTOR]` 같은 대괄호 prefix는 사용하지 않습니다.**
> Conventional Commits 형식(`feat:`, `fix:`, `refactor:`)을 씁니다.

### front matter 필드

| 필드 | 의미 |
|---|---|
| `name` | 이슈 생성 화면에 표시될 템플릿 이름 |
| `about` | 템플릿 설명 |
| `title` | 제목 자동 prefix |
| `labels` | 자동 부여 라벨 (**저장소에 존재해야 함**) |
| `assignees` | 자동 지정 담당자 |

### 작성 규칙

- 재현 경로 / 기대 결과 / 현재 결과 / 영향 범위를 **구체적으로**
- 확인되지 않은 재현 방법·버전은 지어내지 말고 `확인 필요` 표시
- 관리자는 검토 후 `needs/triage` 제거 + 우선순위·영역 라벨 추가

---

## 4. Pull Request

### 브랜치 → 템플릿 매핑

| Source | Target | Template | 제목 prefix | 라벨 |
|---|---|---|---|---|
| `feat/*` | `dev` | `feat.md` | `feat: ` | `type/feature` |
| `bugfix/*` | `dev` | `bugfix.md` | `fix: ` | `type/bug` |
| `refactor/*` | `dev` | `refactor.md` | `refactor: ` | `type/refactor` |
| `docs/*` | `dev` | `docs.md` | `docs: ` | `type/docs` |
| `release/*` | `main` | `release.md` | `release: ` | `type/release` |
| `hotfix/*` | `main` | `hotfix.md` | `fix: ` | `type/hotfix`, `priority/high` |

> **GitHub는 브랜치명으로 PR 템플릿을 자동 선택하지 않습니다.** 작성자가 골라야 합니다.
> PR 템플릿 상단의 metadata 주석은 **사람이 참고하는 정보**일 뿐, GitHub가 라벨·제목·target을 자동 적용하지 않습니다.

URL 파라미터로 직접 열 수 있습니다.

```text
https://github.com/<owner>/<repo>/compare/dev...feat/login?quick_pull=1&template=feat.md
```

### PR 본문 필수 항목

- 변경사항 요약 / 변경 이유
- 변경 파일·모듈·API
- 테스트 결과
- 관련 이슈
- 리뷰어가 확인할 내용
- 배포 영향과 롤백 방법

### 공통 체크리스트 (템플릿 내장)

- [ ] PR 제목이 변경 내용을 명확히 설명
- [ ] 변경 범위가 하나의 단위로 제한
- [ ] 문서 수정이 필요하면 함께 반영
- [ ] 리뷰어 확인 사항을 본문에 작성
- [ ] 단위 테스트 수행 / 기존 기능 영향 없음 확인

---

## 5. 라벨 (30개 · 6 카테고리)

형식: `<category>/<name>` — 소문자와 하이픈만.

### type/* (8) — 최소 하나는 필수

| 라벨 | 제목 prefix |
|---|---|
| `type/feature` | `feat: ` |
| `type/bug` | `fix: ` |
| `type/refactor` | `refactor: ` |
| `type/docs` | `docs: ` |
| `type/hotfix` | `fix: ` |
| `type/release` | `release: ` |
| `type/test` | `test: ` |
| `type/chore` | `chore: ` |

### 나머지 카테고리

| 카테고리 | 라벨 |
|---|---|
| `priority/*` | `high` · `medium` · `low` |
| `status/*` | `todo` · `in-progress` · `review` · `done` · `stale` |
| `needs/*` | `triage` · `discussion` · `reproduce` |
| `area/*` | `backend` · `frontend` · `data` · `infra` · `docs` |
| `level/*` | `easy` · `medium` · `hard` |
| stale 예외 | `pinned` · `security` · `blocked` (카테고리 없는 예외) |

### 기본 규칙

- 하나의 이슈/PR에 **최소 하나의 `type/*`**
- 새 이슈는 `needs/triage`로 시작
- 우선순위·영역이 정해지면 `priority/*`, `area/*` 추가
- 진행되면 `status/*` 갱신
- **새 라벨 만들기 전에 기존 라벨로 표현 가능한지 먼저 확인**

### Triage 흐름

```text
1. needs/triage 이슈 확인
2. 내용 충분한지 검토
3. type/* 확인
4. area/* 추가
5. priority/* 추가
6. needs/triage 제거 → status/todo 추가
```

### 라벨 조합 예시

```text
버그      type/bug + area/backend + priority/high + needs/reproduce
기능 요청  type/feature + area/frontend + priority/medium + status/todo
문서 PR    type/docs + area/docs + status/review
```

### 동기화

`.github/labels.json`이 원본, `.github/workflows/sync-labels.yml`이 GitHub에 반영.

- `labels.json`에 있는 라벨 → 생성 또는 갱신
- **`labels.json`에 없는 기존 라벨 → 삭제** ⚠️

---

# III. 코드 컨벤션 (공통)

원문: `docs/code-convention.md`

## 6. 기본 원칙

- 코드는 **사람이 먼저 읽는 문서**
- 함수·클래스·모듈은 **하나의 책임**
- **중복 제거보다 의도 표현을 우선**
- 포맷팅은 개인 취향으로 수정하지 않고 도구에 맡김
- 큰 변경과 단순 포맷팅을 같은 커밋에 섞지 않음
- 공개 API·공통 모듈·설정 파일 변경은 PR 본문에 영향 범위 작성

## 7. 포맷팅

| 영역 | 도구 |
|---|---|
| Java, Kotlin | Checkstyle, Spotless, ktlint |
| Python | Black, Ruff, isort |
| JavaScript, TypeScript | Prettier, ESLint |
| Vue | Prettier, ESLint, Vue ESLint rules |
| SQL | SQLFluff |
| Markdown / YAML / JSON | Prettier |

- 포매터 설정 파일을 **저장소에 포함**
- IDE 기본 포맷보다 프로젝트 설정 우선
- 자동 포맷 결과를 수동으로 되돌리지 않음
- 린트 경고를 무시해야 하면 **코드 근처에 이유를 짧게 남김**

## 8. 네이밍

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

- Controller/Router — 요청·응답 흐름만
- Service — 비즈니스 규칙
- Repository/DAO — 데이터 접근
- DTO·Schema·Request·Response를 역할에 맞게 구분

### 프론트엔드

`UserProfileCard` · `useAuth` · `useChatStream` · `formatDate` · `handleSubmit`

- 컴포넌트는 PascalCase
- 훅·composable·util은 **동사 기반**
- 페이지 컴포넌트와 공용 컴포넌트 구분
- 이벤트 핸들러는 `handle` 접두사

## 9. 디렉터리

기술이 아니라 **역할과 변경 이유** 기준으로 나눕니다.

```text
backend/src/          frontend/src/         data/
  auth/                 pages/                jobs/
  user/                 components/           dags/
  common/               features/             connectors/
                        hooks/                common/
                        utils/
```

- 기능 단위 응집도 유지
- 공통 모듈이 무분별하게 커지지 않도록 사용 기준 설정
- 테스트 파일은 대상 코드와 찾기 쉬운 위치에

## 10. 함수 · 클래스 · 모듈

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
Controller/Router → Service → Repository → Database
Page → Feature Component → Shared Component → Utility
Job/DAG → Operator/Task → Connector → External System
```

## 11. API

```text
GET    /api/v1/users
GET    /api/v1/users/{userId}
POST   /api/v1/users
PATCH  /api/v1/users/{userId}
DELETE /api/v1/users/{userId}
```

- HTTP method는 리소스 동작에 맞게
- URL은 **명사 중심**
- 응답 구조를 프로젝트 전체에서 일관되게
- 에러 응답은 공통 포맷
- 페이지네이션·정렬·필터 파라미터 이름 통일

**버전 정책**

- 외부 클라이언트가 쓰는 API는 버전 명시
- 내부 실험·초기 MVP는 버전 없이 시작 가능, 공개되면 정책 확정
- breaking change 시 기존 버전 유지 + 새 버전 추가 우선

## 12. 에러 처리

- 예외를 숨기지 않음
- **사용자에게 보여줄 메시지와 로그에 남길 메시지를 구분**
- 공통 에러 코드/타입 사용
- 외부 시스템 오류는 원인 추적 가능하게 로깅
- 복구 가능/불가능 오류 구분

```json
{ "code": "USER_NOT_FOUND", "message": "User not found" }
```

## 13. 로깅

| Level | 용도 |
|---|---|
| `debug` | 개발 중 상세 흐름 |
| `info` | 주요 비즈니스 이벤트 |
| `warn` | 처리 가능하지만 확인 필요 |
| `error` | 실패한 요청·작업·외부 연동 |

- **민감 정보 금지**
- 요청 ID, 사용자 ID, 작업 ID 등 추적값 포함
- 단순 디버깅 로그는 **PR 전에 제거**

## 14. 설정

- 환경별 설정을 코드와 분리
- 민감 정보 커밋 금지
- `.env.example` 또는 샘플 설정 제공
- 기본값은 로컬 개발자가 바로 실행 가능하게
- 운영 설정은 CI/CD 또는 배포 환경에서 주입

**절대 커밋 금지** — 실제 DB 비밀번호 / 운영 API Key / 개인 access token / Slack webhook URL / 클라우드 secret key

## 15. 데이터베이스

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

## 16. 프론트엔드

```text
pages:             라우팅 단위 화면
features:          도메인 기능 단위 UI + 로직
components:        재사용 가능한 공용 UI
hooks/composables: 상태·동작 재사용
utils:             순수 유틸리티
```

- UI 상태 / 서버 상태 / 폼 상태를 구분
- 컴포넌트의 표시 책임과 데이터 처리 책임 분리
- **공용 컴포넌트는 도메인 로직을 갖지 않음**
- 접근성 속성 작성
- API 호출 로직을 컴포넌트에 흩뿌리지 않음

## 17. 테스트

| 종류 | 목적 |
|---|---|
| Unit | 함수·클래스·모듈 단위 |
| Integration | DB·외부 API·모듈 간 연동 |
| Scenario | 요구사항 기반 흐름 |
| E2E | 실제 사용자 흐름 |

- 테스트 이름은 **검증하려는 동작**을 설명
- 정상 + 실패 케이스 함께
- 외부 시스템은 mock / stub / test container
- 리팩토링 PR은 기존 테스트 통과 필수
- 버그 수정 PR은 가능하면 재현 테스트 포함

## 18. 주석

**좋은 주석** — 왜 이 처리가 필요한지 / 외부 시스템 제약 / 임시 우회 코드의 **제거 조건**

**피할 주석** — 코드와 같은 말 반복 / 현재 동작과 안 맞는 낡은 주석 / 주석으로만 설명되는 복잡한 로직

## 19. 코드 체크리스트

- [ ] 네이밍이 역할과 의도를 설명하는가
- [ ] 함수·클래스·모듈의 책임이 명확한가
- [ ] 파일 위치가 프로젝트 구조와 일관되는가
- [ ] API·에러 응답·로그 형식이 일관되는가
- [ ] 환경 설정·민감 정보가 코드에 포함되지 않았는가
- [ ] 불필요한 주석·디버깅 코드·임시 로그가 없는가
- [ ] 포매터·린터 결과를 임의로 되돌리지 않았는가

---

# IV. 백엔드 세부 컨벤션 (Spring Boot)

원문: `backend/docs/backend-common-settings.md`, `backend/docs/backend-api-development-guide.md`

## 20. 공통 응답 계약

모든 일반 JSON 응답을 `ApiResponse<T>`로 감쌉니다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `code` | `String` | 클라이언트 분기용 응답 코드 (항상 포함) |
| `message` | `String` | 사용자에게 안전한 메시지 (항상 포함) |
| `data` | `T` | 추가 데이터. **`null`이면 JSON에서 생략** |

```json
// 성공
{ "code": "SUCCESS", "message": "요청에 성공했습니다.", "data": { "id": 1 } }

// 오류
{ "code": "MEMBER-001", "message": "회원을 찾을 수 없습니다." }

// 검증 오류
{ "code": "COMMON-001", "message": "요청 값이 올바르지 않습니다.",
  "data": { "fieldErrors": [ { "field": "email", "reason": "..." } ] } }
```

> 검증 오류에 사용자가 입력한 **`rejectedValue`는 절대 포함하지 않습니다** (비밀번호·토큰 노출 위험).

**공통 래퍼를 적용하지 않는 응답:** `204 No Content` · 파일 다운로드 · 이미지/영상 스트리밍 · SSE · Actuator · Swagger/OpenAPI · 외부 규격 Webhook

## 21. HTTP 상태

| 상황 | 상태 |
|---|---:|
| 조회·수정 성공 | `200` |
| 생성 성공 | `201` |
| 본문 없는 삭제 성공 | `204` |
| 검증 실패 | `400` |
| 인증 실패 | `401` |
| 권한 부족 | `403` |
| 리소스 없음 | `404` |
| 미지원 메서드 | `405` |
| 중복·상태 충돌 | `409` |
| 미지원 미디어 타입 | `415` |
| 예상 못한 서버 오류 | `500` |

> **오류를 `200 OK`로 반환하지 않습니다.** 공통 응답으로 감싸더라도.

## 22. 오류 코드 체계

형식: `<DOMAIN>-<NUMBER>` — **이미 사용한 코드의 의미를 바꾸거나 재사용하지 않습니다.**

**공통 오류만** `CommonErrorCode`에:

| 상수 | 코드 | 상태 |
|---|---|---:|
| `INVALID_INPUT` | `COMMON-001` | 400 |
| `MALFORMED_JSON` | `COMMON-002` | 400 |
| `MISSING_PARAMETER` | `COMMON-003` | 400 |
| `TYPE_MISMATCH` | `COMMON-004` | 400 |
| `RESOURCE_NOT_FOUND` | `COMMON-404` | 404 |
| `METHOD_NOT_ALLOWED` | `COMMON-405` | 405 |
| `NOT_ACCEPTABLE` | `COMMON-406` | 406 |
| `MEDIA_TYPE_NOT_SUPPORTED` | `COMMON-415` | 415 |
| `INTERNAL_SERVER_ERROR` | `COMMON-500` | 500 |

**도메인 오류는 도메인 패키지 안에** (`member/exception/MemberErrorCode.java`).

## 23. 계층 책임

| 계층 | 반환/발생 |
|---|---|
| Controller | `ResponseEntity<ApiResponse<ResponseDto>>` |
| Service | 응답 DTO 또는 `BusinessException` |
| Repository | Entity, `Optional<Entity>` |
| GlobalExceptionHandler | 오류 유형에 맞는 `ApiResponse<?>` |

```text
Controller → Service → Repository → Database
```

**Service·Repository가 반환하면 안 되는 것:** `ApiResponse` · `ResponseEntity` · HTTP 상태 코드 · Controller 전용 타입

> 성공 응답은 **Controller**가, 오류 응답은 **GlobalExceptionHandler**가 만듭니다.
> Controller에서 `try-catch`로 `BusinessException`을 다시 포장하지 않습니다.

## 24. 패키지 구조

```text
org.example.backend.coupon
├── controller/  dto/  entity/
├── exception/   repository/  service/
```

- 기술별 전역 패키지가 아니라 **기능 단위**로 묶기
- DB 미사용 기능엔 `entity`·`repository`를 만들지 않음
- **두 개 이상 도메인에서 재사용되기 전에는 `global`로 옮기지 않음**

## 25. TDD 개발 순서

```text
1. API 계약 + 도메인 오류 코드 결정
2. 요청·응답 DTO 형태 결정
3. Service 정상 동작 테스트 작성
4. 테스트가 "기능 미구현" 때문에 실패하는지 확인 (RED)
5. 통과하는 최소 Service 구현
6. 비즈니스 오류 테스트 → 반복
7. Controller 정상 응답 테스트
8. 최소 Controller 구현
9. 검증 실패·오류 응답 테스트 추가
10. Repository·DB 통합 테스트
11. 중복 정리 (테스트 통과 유지)
12. 관련 테스트 → 전체 테스트 → 빌드
```

> **반드시 RED 상태를 먼저 확인합니다.** 구현 뒤에 테스트를 몰아 쓰면 처음부터 통과해서 요구사항 검증 여부를 알 수 없습니다.

## 26. 백엔드 금지 패턴

- Controller에 비즈니스 규칙·Repository 호출
- Service가 `ResponseEntity`/`ApiResponse` 반환
- 모든 오류를 `200 OK`로 반환
- 도메인 오류를 전부 `CommonErrorCode`에 추가
- Controller마다 동일한 `try-catch` 반복
- 사용자 입력값·내부 예외 메시지를 오류 응답에 포함
- 구현 뒤에 테스트 작성
- 테스트용 오류 API를 Profile 제한 없이 운영에 등록
- 필요하지 않은 계층·공통 추상화·DTO 미리 만들기

## 27. 기타 백엔드 규칙

- 생성·수정 시간이 필요한 엔티티는 `BaseTimeEntity` 상속, 시간 타입은 **`Instant`**
- 조회 전용 메서드는 `@Transactional(readOnly = true)`
- 개발 전용 API는 `@Profile("dev")`
- `spring.mvc.problemdetails.enabled: false`, Content-Type은 `application/json`
- 예상 못한 예외는 **스택 트레이스를 서버 로그에만**, 응답에는 안전한 공통 메시지만

---

# V. 파일·환경 설정

## 28. `.editorconfig`

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
indent_style = space
indent_size = 2
insert_final_newline = true
trim_trailing_whitespace = true

[*.{java,kt,kts,py}]
indent_size = 4

[*.{md,markdown}]
trim_trailing_whitespace = false   # 마크다운은 줄 끝 공백이 문법

[*.{bat,cmd}]
end_of_line = crlf
```

> `.editorconfig`는 포매터를 **대체하지 않습니다.** Prettier·Black·Spotless가 있으면 그쪽이 우선.

## 29. `.gitattributes`

```gitattributes
* text=auto eol=lf
*.bat text eol=crlf
*.cmd text eol=crlf

# binary: png jpg jpeg gif ico webp pdf zip gz tar jar war 7z
#         doc docx ppt pptx xls xlsx fig
```

| 파일 | 역할 |
|---|---|
| `.editorconfig` | **에디터**가 저장할 때의 포맷 |
| `.gitattributes` | **Git**이 commit·checkout·diff할 때의 처리 |

> 이미 파일이 들어간 뒤 추가했다면 정규화 커밋 1회 필요:
> `git add --renormalize .` → `git commit -m "chore: normalize line endings"`

## 30. `.gitignore`

**반드시 제외**

| 패턴 | 이유 |
|---|---|
| `.env`, `.env.*` | 실제 secret·로컬 환경값 |
| `*.pem`, `*.key`, `*.p12`, `*.pfx` | 인증서·private key |
| local DB 파일 | 로컬 데이터·개인정보 |
| 의존성 디렉터리 | 재설치 가능 |
| 빌드 산출물 | 재생성 가능 |

**반드시 추적** — `.env.example` · lockfile 전체(`package-lock.json`, `pnpm-lock.yaml`, `yarn.lock`, `poetry.lock`) · `requirements.txt` · `build.gradle`/`pom.xml` · `.editorconfig` · `.gitattributes`

**예외 규칙**

```gitignore
.env
.env.*
!.env.example

.vscode/*
!.vscode/extensions.json
!.vscode/settings.json
```

- 스택 확정 후 [gitignore.io](https://www.toptal.com/developers/gitignore) 결과를 **필요한 것만 병합**
- `git status --ignored`로 검증
- ⚠️ **이미 커밋된 secret은 `.gitignore` 추가만으로 해결되지 않습니다.** 즉시 폐기·교체하고 history 노출 여부를 점검하세요.

---

# VI. 제품·문구 컨벤션

원문: `PRODUCT.md`

- **게임 ID**: `air` / `hold` / `draw` / `rhythm` / `blink`
- **화면 표기**: "영문 게임명 (한글 설명)" 병기
- **점수 단위**: 게임별로 다름 (회 · 초 · 점)
- **UI 문구 톤**: 한국어 **해요체**, 밝고 친근하게
- **디자인 기준**: web_prototype이 룩의 단일 출처 (구속력 있음)
- **목 데이터 주의**: `frontend/src/mocks/`의 랭킹·닉네임·점수는 전부 자리표시용 — **실제 성과·후기처럼 표현 금지**

---

# VII. AI 에이전트 컨벤션

원문: `AGENTS.md`, `CLAUDE.md`, `docs/skill-guide.md`

## 31. 심각도 어휘 (전 스킬 공통)

| 상태 | 의미 | 처리 |
|---|---|---|
| `BLOCKER` | 커밋·PR 전 반드시 해결 | 즉시 해결 |
| `WARNING` | 영향도 확인 필요 | 확인 후 결정 |
| `INFO` | 참고 사항 | 기록 |
| `PASS` | 통과 | 조치 불필요 |

PR 준비 게이트: `READY` / `WARNING` / `BLOCKED`

출력 포맷:

```text
[BLOCKER|WARNING|INFO] path:line
규칙: 적용한 프로젝트 규칙
근거: 실제 코드에서 확인한 문제
제안: 구체적인 수정 방향
```

## 32. 에이전트 안전 규칙

- `main`에서 직접 작업·커밋 금지
- 요청하지 않은 `reset` / `rebase` / `push` / 파일 삭제 금지
- `git add .`, `git add -A` 자동 실행 금지
- 사용자 변경사항 덮어쓰기·되돌리기 금지
- Secret 출력·커밋·외부 전송 금지
- **`.gitignore` 추가만으로 추적 중인 secret이 제거됐다고 판단하지 않기**
- 커밋은 **계획 제시 → 명시적 승인 → 실행**, 커밋 후 자동 push 금지
- **문서와 실제 설정이 다르면 임의 판단하지 말고 "정책 충돌"로 보고**

## 33. 스킬 8종

| 종류 | 스킬 | 부작용 |
|---|---|---|
| 검사 | `review-code-convention` · `validate-branch-policy` · `review-commit-history` · `scan-secrets-and-config` | 없음 |
| 작성 | `draft-issue` · `draft-pr` | 없음 |
| 종합 | `pre-pr-readiness` | 없음 |
| 변경 실행 | `commit-staged-changes` | **Git 상태 변경** |

권장 흐름:

```text
validate-branch-policy → 코드 작성 → review-code-convention
→ scan-secrets-and-config → review-commit-history
→ pre-pr-readiness → draft-pr
```

---

# VIII. 부록 A — 발견된 충돌·미비 지점

정리 중 문서 간 어긋난 부분입니다. 다음 프로젝트로 옮기기 전에 정리하세요.

| # | 문제 | 위치 |
|---|---|---|
| 1 | **대괄호 prefix 모순** — `label-strategy.md`는 `[BUG]` 사용 금지라고 명시하는데, `issue-template-guide.md`의 front matter 예시는 `title: "[BUG] "` (실제 `bug_report.md`는 `fix: `가 맞음) | `docs/issue-template-guide.md` |
| 2 | **stale workflow 부재** — `github/stale-issues`를 전제하고 예외 라벨 3개까지 만들었지만 workflow 파일 없음 | `docs/label-strategy.md` |
| 3 | **semantic-pr 검사 부재** — PR 제목 Conventional Commits 검사를 `github/semantic-pr`이 한다고 서술하지만 해당 workflow 없음 | `docs/pr-template-guide.md` |
| 4 | **Discussions URL 플레이스홀더** — `<owner>/<repository>` 그대로 | `.github/ISSUE_TEMPLATE/config.yml` |
| 5 | **API 버전 정책 미적용** — `code-convention.md`는 `/api/v1` 예시, 백엔드 가이드는 `/api/coupons` (버전 없음), 프론트는 `VITE_API_BASE_URL=/api` | 3곳 불일치 |
| 6 | **포매터 미확정** — "권장 도구" 목록만 있고 이 프로젝트가 실제로 뭘 쓰는지 확정 서술 없음 | `docs/code-convention.md` |
| 7 | **merge/rebase 미결정** — "팀 규칙에 따라 선택"만 있고 결정 기록 없음 | `docs/commit-strategy.md` |
| 8 | **Gitmoji 미결정** — "정하면 일관되게"만 있고 결정 기록 없음 | 〃 |
| 9 | **템플릿 저장소 잔재** — `settings/*`, `github/*`, `api/*` 같은 템플릿 브랜치 규칙이 실제 프로젝트 문서에 남아 있음 | `docs/commit-strategy.md` 마지막 절 |
| 10 | **`.env.example` 0바이트** — 규칙은 "환경변수 목록을 문서화한다"인데 비어 있음 | 루트 |

---

# IX. 부록 B — 강제 장치 매트릭스

**각 컨벤션이 실제로 무엇에 의해 검증되는가.**

| 컨벤션 | 자동 검증 | 실제 강제 수단 |
|---|:---:|---|
| 파일 인코딩·개행 | ✅ | `.editorconfig` + `.gitattributes` |
| 라벨 목록 | ✅ | `sync-labels.yml` |
| 이슈 제목 prefix·기본 라벨 | ✅ | ISSUE_TEMPLATE front matter |
| 코드 스타일 | ❌ | 문서 서술만 (린터 실행 지점 없음) |
| 커밋 메시지 형식 | ❌ | 문서 + AI 스킬 (사람이 호출해야 함) |
| 브랜치명 | ❌ | 〃 |
| PR 대상 브랜치 | ❌ | 〃 (브랜치 보호 규칙 미확인) |
| PR 템플릿 선택 | ❌ | 작성자 수동 선택 |
| 테스트 통과 | ❌ | **CI 없음** |
| Secret 미포함 | ❌ | AI 스킬 (사람이 호출해야 함) |
| 백엔드 응답 계약 | ❌ | 문서 + 테스트 작성 규칙 (CI 실행 없음) |

> ✅ 3개 / ❌ 8개.
> 컨벤션의 대부분이 **문서와 사람의 성실성**에 의존합니다.
> 다음 프로젝트에서 가장 효과 큰 개선은 새 규칙을 쓰는 게 아니라,
> **이 표의 ❌ 중 자동화 가능한 것을 CI·pre-commit·commitlint로 내리는 것**입니다.
