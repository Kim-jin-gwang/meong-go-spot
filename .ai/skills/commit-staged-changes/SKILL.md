---
name: commit-staged-changes
description: Analyze only staged Git changes, group them by purpose, propose Conventional Commits messages, and create approved commits without touching unstaged changes. Use when the user asks to organize staged changes into commits or commit staged work according to this repository's commit strategy.
---

# Commit Staged Changes

먼저 `docs/commit-strategy.md`, `docs/branch-strategy.md`, `docs/code-convention.md`를 읽는다.

## Safety Rules

- 기본 동작은 분석과 커밋 계획 제시다.
- `git diff --cached`에 없는 변경사항은 절대 포함하지 않는다.
- `git add .`, `git add -A`, `git reset`, `git rebase`, `git push`를 자동으로 실행하지 않는다.
- 실제 commit은 커밋 그룹과 메시지를 사용자에게 보여주고 명시적으로 승인받은 뒤에만 실행한다.
- Secret이 발견되면 먼저 중단하고 경고한다.
- 한 파일에 서로 다른 목적의 hunk가 섞였고 안전하게 분리할 수 없으면 해당 그룹을 보류한다.

## Workflow

1. 현재 브랜치와 working tree 상태를 확인한다.
2. `git diff --cached --name-status`와 `git diff --cached`만 분석한다.
3. 변경 hunk를 다음 목적 중 하나로 분류한다.
   - 기능: `feat`
   - 버그: `fix`
   - 테스트: `test`
   - 문서: `docs`
   - 리팩토링: `refactor`
   - 설정·유지보수: `chore` 또는 `build`
   - CI/CD: `ci`
   - 순수 포맷팅: `style`
4. 각 그룹이 하나의 목적을 갖는지 확인한다.
5. 다음 계획을 먼저 출력한다.

```text
Commit 1
Files/hunks: ...
Message: feat(auth): add login API
Reason: ...
Risk: ...
```

6. 사용자가 계획을 승인하면 필요한 경우 pathspec 또는 대화형 hunk 선택으로 staging을 정리한 뒤 커밋한다.
7. 커밋 후 `git status`와 새 커밋 요약을 확인한다.

커밋 실행이 끝나면 push하지 않는다. 실행할 수 없는 분리가 있으면 어떤 hunk를 수동으로 선택해야 하는지 설명한다.
