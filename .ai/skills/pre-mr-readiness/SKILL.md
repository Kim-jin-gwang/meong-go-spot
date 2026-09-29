---
name: pre-mr-readiness
description: Run a read-only pre-MR readiness review combining branch policy, code conventions, commit quality, tests, secrets, configuration, MR template requirements, and branch-specific release rules. Use when asking whether current work is ready for an MR.
---

# Pre-MR Readiness

MR을 만들기 전에 다음 문서와 실제 파일을 읽는다.

- `docs/branch-strategy.md`
- `docs/commit-strategy.md`
- `docs/code-convention.md`
- `docs/jira-issue-guide.md`
- `docs/mr-guide.md`
- 관련 `.gitlab/merge_request_templates/` 템플릿

## Workflow

다음 순서로 읽기 전용 검사를 수행한다.

1. 현재 브랜치와 MR target 검증
2. working tree와 staged/unstaged 변경 구분
3. 커밋 단위와 메시지 검증
4. 변경 코드의 컨벤션 검증
5. 브랜치 type에 필요한 테스트 확인 — 이 프로젝트의 통합 검사 명령은 `npm run check`
6. Secret과 민감 설정 검사
7. MR 템플릿, 제목, 관련 Jira 이슈 확인
8. release/hotfix인 경우 버전, 롤백, `dev` 반영 계획 확인

사용 가능한 포매터·린터·테스트 명령이 실제 설정에서 확인될 때만 실행한다. 명령이 없거나 실행 환경이 없으면 `실행하지 못함`으로 표시한다.

## Gate

```text
READY    차단 사항 없음
WARNING  병합 전 확인 필요
BLOCKED  MR 전에 해결해야 함
```

검사 결과에는 차단 항목, 경고 항목, 통과 항목, 실행한 명령, MR 작성 시 남은 작업을 포함한다. 파일 수정, commit, push, MR 생성은 수행하지 않는다.
