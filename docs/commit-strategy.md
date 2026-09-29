# 커밋 전략

> 강제 장치: 커밋 형식 → commitlint (lefthook `commit-msg` 훅 + CI `commitlint` 잡)
> 설정 원본: `commitlint.config.mjs` — 이 문서와 설정이 다르면 정책 충돌로 보고하고 설정을 먼저 확인합니다.

## 형식

```text
<type>(<scope>): <subject>
```

`scope`는 선택.

## 타입 13종

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

## 제목·본문 규칙

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

## 커밋 단위

**좋은 단위** — 기능 하나 / 버그 하나 / 리팩토링 목적 하나 / 문서 하나 / 설정 하나

**피할 것**

- 여러 기능·버그를 묶은 커밋
- 코드 변경 + 대규모 포맷팅 혼합
- 메시지만 보고 의도를 알 수 없는 커밋

## Jira 이슈 연결

필요하면 커밋 본문에 Jira 키(`#123`)를 포함해 티켓과 자동 연결할 수 있습니다 (선택 — 브랜치명에는 넣지 않는다, [branch-strategy.md](branch-strategy.md)).

```text
feat(auth): add login API

Refs #123
```

> 이슈 상태 전환(자동 close 등)은 커밋보다 **MR 본문에** 쓰는 것을 권장합니다.

## 브랜치 타입 × 커밋 타입

완전히 같을 필요는 없습니다. 브랜치는 큰 목적, 커밋은 각 변경의 실제 목적.

| Branch | 자주 쓰는 Commit Type |
|---|---|
| `feat/*` | `feat`, `fix`, `test`, `docs`, `refactor`, `chore` |
| `bugfix/*` | `fix`, `test` |
| `hotfix/*` | `fix`, `test`, `release` |
| `refactor/*` | `refactor`, `test` |
| `docs/*` | `docs` |
| `release/*` | `release`, `fix`, `docs`, `chore` |

## Gitmoji — 사용하지 않음

commitlint가 강제하는 Conventional Commits만 사용합니다 (ADR-001 D8 — 도구 없는 규칙은 만들지 않는다). 이모지가 붙은 커밋 메시지는 commit-msg 훅에서 거부됩니다.

## MR 전 dev 동기화 — merge 단일

```bash
git switch dev && git pull origin dev
git switch feat/login && git merge dev
```

**rebase · force push(`--force`, `--force-with-lease` 포함)는 이 프로젝트 워크플로우에서 사용하지 않습니다** (ADR-001 D8). 잘못된 force push로 인한 커밋 유실 가능성을 워크플로우 차원에서 차단합니다. 히스토리 수정이 정말 필요한 상황이면 혼자 결정하지 말고 팀에 공유한 뒤 진행합니다.
