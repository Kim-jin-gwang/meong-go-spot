---
name: review-code-convention
description: Review code changes against this repository's coding conventions, formatting configuration, naming rules, architecture, API, error handling, logging, configuration, database, frontend, testing, and comment rules. Use when the user asks to review code quality, check convention compliance, inspect a diff, or prepare code for review.
---

# Review Code Convention

프로젝트 루트에서 작업하고, 먼저 다음 문서를 읽는다.

- `docs/code-convention.md`
- `docs/editor-config.md`
- `docs/git-attributes.md`
- 변경 영역과 관련된 실제 포매터·린터 설정 파일

## Workflow

1. 검사 범위를 결정한다. 사용자가 범위를 지정하지 않으면 `git diff`, 추적 중인 변경 파일, 관련 테스트를 기준으로 한다.
2. 실제 프로젝트 설정 파일과 문서 규칙을 확인한다. 설정이 없으면 특정 도구의 실행을 추측하지 않는다.
3. 다음 순서로 검토한다.
   - 책임 분리와 계층 방향
   - 이름, 파일 위치, 모듈 구조
   - API와 데이터 형식 (`/api/v1` prefix 포함)
   - 예외 처리와 로그
   - 환경 설정과 민감정보
   - 테스트와 주석
   - 포매터·린터 적용 여부 (`npm run check`가 통과하는지)
4. 포맷팅 문제와 동작·설계 문제를 구분한다.
5. 기본적으로 파일을 수정하지 않고 결과만 보고한다. 사용자가 수정을 요청한 경우에도 먼저 변경 계획을 제시한다.

## Output

각 발견 사항을 다음 형식으로 작성한다.

```text
[BLOCKER|WARNING|INFO] path:line
규칙: 적용한 프로젝트 규칙
근거: 실제 코드에서 확인한 문제
제안: 구체적인 수정 방향
```

마지막에 `통과한 항목`, `추가 확인이 필요한 항목`, `실행한 검사`를 요약한다. 근거가 없는 추측은 결함으로 보고하지 않는다.
