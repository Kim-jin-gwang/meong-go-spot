# 브랜치 전략

> 강제 장치: 브랜치명 → CI `branch-name` 잡(정규식) / `main` 직접 push 차단 → Protected branches
> (`dev`는 팀 결정으로 비보호 — 직접 push는 규약으로 금지, push 파이프라인이 사후 감지. 설정 절차: [protected-branches-setup.md](./protected-branches-setup.md))

## 타입과 흐름

| 브랜치 | 생성 기준 | MR 대상 | 용도 |
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

## 네이밍

```text
<type>/<description>
```

- **브랜치명에 Jira 이슈 번호를 넣지 않는다** (2026-08-31 팀 결정 — 이슈는 매주 월요일 회의에서 수동 관리, 브랜치와 분리). 기존에 만든 키 포함 브랜치는 CI가 계속 허용한다
- 설명 부분은 영문 소문자·숫자·하이픈만, 공백 금지
- 브랜치명만 보고 목적을 알 수 있게

```text
feat/login
bugfix/token-refresh
refactor/auth-service
docs/api-guide
release/1.0.0
hotfix/payment-timeout
```

> `release/*`는 버전만 사용하고, `dev`는 dev→main 동기화 MR의 source로 예외 허용됩니다.

## 기본 규칙

- 모든 병합은 **MR 기반**
- AI 자동 리뷰를 함께 사용 (non-blocking — [ai-review-gemini.md](./ai-review-gemini.md))
- `main`에 기능 브랜치를 직접 병합하지 않음
- 병합 후 작업 브랜치 삭제 (GitLab "Delete source branch" 기본 활성)

## dev 동기화 — merge 단일

모든 브랜치에서 `git merge dev`로 통일합니다. **rebase와 force push는 이 프로젝트 워크플로우에서 사용하지 않습니다** (ADR-001 D8 — 히스토리 사고 가능성 차단).

```bash
git switch dev && git pull origin dev
git switch feat/login && git merge dev
```

## 브랜치별 테스트 범위

| 브랜치 | 필요한 테스트 |
|---|---|
| `feat/*` | 단위 테스트 우선 |
| `bugfix/*` | 버그 재현 케이스 + 수정 확인 |
| `refactor/*` | **기존 테스트 전부 통과** |
| `release/*` | 통합 + 시나리오 + E2E |
| `hotfix/*` | 수정 범위 빠른 검증 후 배포, 이후 `dev` 반영 |
