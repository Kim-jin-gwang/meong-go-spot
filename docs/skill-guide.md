# AI Skill Guide

이 문서는 이 저장소에서 제공하는 AI 에이전트용 개발 지원 스킬의 종류와 사용 방법을 설명합니다.

## Skill Structure

```text
.ai/skills/       공통 스킬 원본 (source of truth)
.agents/skills/   Codex용 진입점 (포인터 스텁)
.claude/skills/   Claude용 진입점 (포인터 스텁)
```

실제 프로젝트 규칙은 스킬에 복사하지 않고 `docs/`와 `.gitlab/`의 파일을 기준으로 읽습니다.
따라서 규칙을 변경할 때는 스킬보다 프로젝트 문서를 먼저 수정해야 합니다.

## Skill Types

스킬은 목적에 따라 다음 네 종류로 나뉩니다.

| 종류 | 설명 | 해당 스킬 |
| --- | --- | --- |
| 검사 | 현재 상태를 분석하고 문제를 보고 | `review-code-convention`, `validate-branch-policy`, `review-commit-history`, `scan-secrets-and-config` |
| 작성 | MR에 사용할 내용을 작성 | `draft-mr` |
| 종합 검사 | 여러 검사를 순서대로 실행해 상태를 판단 | `pre-mr-readiness` |
| 변경 실행 | 승인된 계획에 따라 Git 상태를 변경 | `commit-staged-changes` |

검사와 작성 스킬은 기본적으로 파일, 브랜치, 커밋, GitLab·Jira 리소스를 변경하지 않습니다.
`commit-staged-changes`만 실제 커밋을 만들 수 있으며, 항상 계획을 먼저 확인한 뒤 실행합니다.

## Available Skills

### `review-code-convention`

코드가 프로젝트 규칙에 맞는지 검사합니다.

검사 대상: 함수·클래스·모듈의 책임 / 네이밍과 파일 위치 / 계층 구조와 의존성 방향 / API, 에러 응답, 로그 형식 / 환경변수와 민감정보 처리 / 테스트와 주석 / 포매터·린터 설정

```text
현재 변경사항을 code convention 기준으로 리뷰해줘.
review-code-convention을 사용해서 src/auth 변경사항을 확인해줘.
```

### `validate-branch-policy`

브랜치 이름과 브랜치 흐름이 전략에 맞는지 검사합니다.

| 브랜치 | 기준 브랜치 | MR 대상 |
| --- | --- | --- |
| `feat/*` | `dev` | `dev` |
| `bugfix/*` | `dev` | `dev` |
| `refactor/*` | `dev` | `dev` |
| `docs/*` | `dev` | `dev` |
| `release/*` | `dev` | `main` (+ `dev` 반영) |
| `hotfix/*` | `main` | `main` (+ `dev` 반영) |

```text
현재 브랜치가 브랜치 전략에 맞는지 확인해줘.
feat/login 브랜치의 기준 브랜치와 MR 대상이 올바른지 검사해줘.
```

### `review-commit-history`

커밋 메시지와 커밋 단위가 적절한지 검사합니다.

검사 대상: Conventional Commits 형식 / 커밋 제목의 명확성 / 하나의 커밋에 하나의 작업 목적 / 기능 변경과 포맷팅 변경의 혼합 여부 / Jira 이슈 연결 여부 / MR 전에 정리해야 할 커밋 여부

브랜치 이름과 커밋 type이 반드시 같아야 하는 것은 아닙니다.
예를 들어 `feat/*` 브랜치에서도 `test`, `fix`, `docs` 커밋을 사용할 수 있습니다.

```text
현재 브랜치의 커밋을 MR 전에 리뷰해줘.
최근 커밋 중 여러 작업이 섞인 커밋을 찾아줘.
```

### `draft-mr`

브랜치 종류에 맞는 MR 템플릿과 본문을 작성합니다.

작성 대상: source/target branch / MR 제목 / 변경사항 요약 / 관련 Jira 이슈 / 테스트 결과 / 리뷰어 확인 사항 / 배포·롤백 영향

```text
현재 feat/login 브랜치의 MR 초안을 작성해줘.
변경사항과 테스트 결과를 기준으로 draft-mr을 실행해줘.
```

이 스킬은 MR 본문을 작성할 뿐, GitLab에 실제 MR을 생성하지 않습니다.

### `pre-mr-readiness`

MR을 만들기 전에 여러 검사 결과를 한 번에 확인합니다.

```text
브랜치 규칙
  → 변경사항 구분
  → 커밋 규칙
  → 코드 컨벤션
  → 테스트 (npm run check)
  → Secret과 설정
  → MR 템플릿과 Jira 이슈
```

| 상태 | 의미 |
| --- | --- |
| `READY` | MR을 만들어도 되는 상태 |
| `WARNING` | MR 전 확인이 필요한 항목이 있음 |
| `BLOCKED` | MR 전에 반드시 해결해야 하는 문제 |

```text
현재 작업이 MR을 만들 수 있는 상태인지 pre-mr-readiness로 확인해줘.
```

### `scan-secrets-and-config`

코드와 Git 변경사항에 Secret이나 위험한 설정이 포함되었는지 검사합니다.

검사 대상: `.env`, API Key, access token / private key와 인증서 / 운영 DB 비밀번호 / webhook URL / 로그에 포함된 개인정보 / Git이 이미 추적 중인 민감 파일 / `.gitignore`와 `.env.example` 문제

Secret의 실제 값은 출력하지 않습니다.

```text
커밋 전에 Secret과 설정 파일을 검사해줘.
현재 diff에 민감정보가 포함되어 있는지 확인해줘.
```

### `commit-staged-changes`

스테이징된 변경사항만 기능별로 나누어 커밋 계획을 만들고, 승인 후 커밋합니다.

사용 흐름:

1. `git diff --cached`를 확인합니다.
2. 변경사항을 `feat`, `fix`, `test`, `docs`, `refactor`, `chore` 등으로 분류합니다.
3. 커밋 그룹과 메시지를 먼저 보여줍니다.
4. 사용자가 승인하면 필요한 hunk를 분리합니다.
5. 커밋을 실행합니다.
6. `git status`로 결과를 확인합니다.

안전 규칙:

- unstaged 변경사항은 포함하지 않습니다.
- `git add .`와 `git add -A`를 자동 실행하지 않습니다.
- `reset`, `rebase`, `push`를 자동 실행하지 않습니다.
- 사용자의 승인 없이 commit하지 않습니다.
- Secret이 발견되면 커밋을 중단합니다.

## Recommended Workflows

### 일반 기능 개발

```text
1. validate-branch-policy
2. 코드 작성
3. review-code-convention
4. scan-secrets-and-config
5. review-commit-history
6. pre-mr-readiness
7. draft-mr
```

### 여러 변경사항을 한 번에 작업한 경우

```text
1. review-code-convention
2. scan-secrets-and-config
3. commit-staged-changes
4. review-commit-history
5. pre-mr-readiness
6. draft-mr
```

### 버그 수정

```text
1. validate-branch-policy
2. 코드 수정과 재현 테스트 작성
3. review-code-convention
4. pre-mr-readiness
5. draft-mr
```

### 문서나 설정만 수정한 경우

```text
1. validate-branch-policy
2. review-code-convention
3. scan-secrets-and-config
4. review-commit-history
5. draft-mr
```

## Using with Codex

Codex에서는 자연어로 스킬 이름과 원하는 작업을 함께 요청합니다. 진입점은 `.agents/skills/`.

```text
review-code-convention을 사용해서 현재 diff를 검토해줘.
pre-mr-readiness로 MR 준비 상태를 확인해줘.
```

## Using with Claude Code

Claude Code에서는 스킬 이름을 `/` 명령으로 직접 실행할 수 있습니다. 진입점은 `.claude/skills/`.

```text
/review-code-convention 현재 src/auth 변경사항만 검사해줘
/commit-staged-changes 커밋 계획을 먼저 보여줘
```

## Using with Antigravity

Antigravity는 `AGENTS.md`를 그대로 읽으므로 자연어로 스킬 이름을 요청하면 `.ai/skills/` 원본을 따라 동작합니다.

## Result Interpretation

1. `BLOCKER`: 커밋이나 MR 전에 반드시 해결합니다.
2. `WARNING`: 영향도를 확인한 뒤 해결 여부를 결정합니다.
3. `INFO`: 참고 사항으로 기록합니다.
4. `PASS`: 추가 조치가 필요하지 않습니다.

스킬은 프로젝트 규칙을 대신 결정하지 않습니다. 문서와 실제 설정이 서로 다르면 임의로 판단하지 않고 정책 충돌로 보고해야 합니다.

## Source Documents

- `docs/code-convention.md`
- `docs/branch-strategy.md`
- `docs/commit-strategy.md`
- `docs/jira-issue-guide.md`
- `docs/mr-guide.md`
- `docs/gitignore-guide.md`
- `docs/editor-config.md`
- `docs/git-attributes.md`
- `.gitlab/merge_request_templates/`
