@AGENTS.md

## Claude Code 전용 보강

- 스킬은 `/` 명령으로 직접 실행한다: `/review-code-convention`, `/validate-branch-policy`, `/review-commit-history`, `/draft-mr`, `/pre-mr-readiness`, `/scan-secrets-and-config`, `/commit-staged-changes`
- 진입점은 `.claude/skills/`의 포인터 스텁이다. 스텁이 가리키는 `.ai/skills/` 원본을 반드시 읽고 따른다. 원본을 읽을 수 없으면 규칙을 지어내지 말고 blocked로 보고한다.
- `commit-staged-changes`는 커밋 계획을 먼저 보여주고 명시적 승인을 받은 뒤 실행한다. 커밋 후 자동으로 push하지 않는다.
- **Jira 이슈는 원칙적으로 매주 월요일 팀 회의에서 팀원이 직접 만든다** (`docs/jira-issue-guide.md`). 예외 하나(2026-09-01 개정): **사용자가 명시적으로 지시하면 사용자 본인 담당 이슈에 한해 에이전트가 API로 생성·상태 전환할 수 있다** (작성자·담당자=사용자 계정). 지시 없이 선제 생성하거나 타인 담당 이슈를 만들지 않는다. **브랜치명에 이슈 번호를 넣지 않는 규칙은 유지.** 티켓 컨텍스트가 필요하면 `atlassian` MCP로 읽는다 (`.mcp.json`, 최초 1회 `/mcp`에서 OAuth).
