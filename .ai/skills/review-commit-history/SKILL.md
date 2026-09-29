---
name: review-commit-history
description: Review Git commits for Conventional Commits format, clear messages, atomic purpose-based separation, Jira issue references, formatting isolation, and branch-context consistency. Use when reviewing commit history, checking commits before an MR, or deciding whether commits need cleanup.
---

# Review Commit History

먼저 `docs/commit-strategy.md`와 `docs/branch-strategy.md`를 읽는다.

## Workflow

1. 검사 범위를 정한다. 기본값은 현재 브랜치가 기준 브랜치에서 갈라진 이후의 커밋이다.
2. 각 커밋의 제목, 본문, 변경 파일, 변경 hunk를 확인한다.
3. 다음 규칙을 검사한다.
   - `<type>(<scope>): <subject>` 형식
   - 허용 type: `init`, `feat`, `fix`, `build`, `chore`, `ci`, `docs`, `style`, `refactor`, `test`, `perf`, `revert`, `release`
   - 제목은 짧고 명확하며 마침표로 끝나지 않음
   - 하나의 커밋에 하나의 작업 목적
   - 기능 변경과 대규모 포맷팅을 혼합하지 않음
   - 필요하면 본문에 변경 이유를 작성 (Jira 키 연결은 선택)
4. 브랜치 type과 커밋 type의 관계는 참고 정보로만 사용한다. 예를 들어 `feat/*`에서 `test`, `fix`, `docs` 커밋을 허용한다.
5. 커밋이 잘못되었다고 판단해도 자동 rebase, amend, squash, reset을 수행하지 않는다.

## Output

커밋별로 `PASS`, `WARNING`, `BLOCKER`를 표시하고, 전체적으로 다음을 제시한다.

- 그대로 MR에 사용 가능한지
- 합쳐야 할 커밋과 분리해야 할 커밋
- 추천하는 새 커밋 제목
- history 수정이 필요한 경우 사용자가 직접 실행할 다음 단계 (이 프로젝트는 rebase·force push를 사용하지 않으므로, 병합 전 정리가 필요하면 팀 공유 후 진행하도록 안내)
