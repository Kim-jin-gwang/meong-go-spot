# Gemini Code Review Guide

이 문서는 Gemini API를 사용해 Merge Request 코드 리뷰를 자동으로 실행하는 방법을 정의합니다.

## 구현 위치

```text
.gitlab-ci.yml        ai-review 잡 (review 스테이지, allow_failure: true)
scripts/ai-review-gemini.mjs   실제 리뷰 로직
```

이 잡은 MR diff와 `docs` 폴더의 문서를 Gemini API로 전달하고, 리뷰 결과를 MR 코멘트로 작성합니다.

> ✅ 검증 완료 (2026-08-12, test 레포 MR !4): 리뷰 코멘트가 실제로 달리고, 심어둔 결함(0으로 나누기)을 파일:라인 인용과 함께 지적했으며, 두 번째 커밋에서 **같은 코멘트가 갱신**(마커 방식)되는 것까지 확인. 이전 프로젝트에서 한 번도 실행되지 못했던 워크플로우가 이번에는 실동작한다. 본 레포에서는 CI 변수 2개 등록만 하면 된다.

## Required Settings

GitLab에서 CI/CD 변수를 추가합니다.

```text
Settings → CI/CD → Variables
```

| Name | 필수 | 설명 |
| --- | --- | --- |
| `GEMINI_API_KEY` | ✅ | Gemini API key. **Masked** 체크 |
| `GITLAB_TOKEN` | ✅ | `api` scope의 Project Access Token (리뷰 코멘트 작성용). **Masked** 체크 |
| `GEMINI_REVIEW_MODEL` | 선택 | 기본값 `gemini-3.6-flash` (구 기본값 2.5-flash는 신규 키에서 404) |

이 `GITLAB_TOKEN`은 GitLab CI의 리뷰 bot 전용이라 bot 이름으로 표시되는 것이 정상이다. 로컬
`.env`에서 사람 명의로 MR을 생성할 때는 같은 변수명에 본인 Personal Access Token을 사용하며,
CI 변수와 로컬 파일은 서로 공유하지 않는다.

> **Protected 체크는 하지 마세요.** MR 파이프라인은 보호되지 않은 작업 브랜치에서 실행되므로 Protected 변수를 읽지 못합니다.

## Review Scope

- MR diff를 기준으로 정확성, 보안, 검증 누락, 엣지 케이스, 테스트 공백을 검토합니다.
- `docs` 폴더의 Markdown, MDX, text 문서를 함께 읽고 문서화된 규칙과 요구사항을 따르는지 확인합니다.
- 문서 기준과 관련된 지적은 해당 문서 경로를 함께 언급하도록 요청합니다.
- `docs` 문서는 최대 20개, 문서당 최대 8,000자, 전체 최대 40,000자까지만 리뷰 컨텍스트에 포함합니다.
- diff는 최대 30파일, 60,000자까지 포함합니다.

## Behavior

- **non-blocking**: Gemini API 호출 실패, 토큰 미설정 등 어떤 실패도 파이프라인을 실패시키지 않습니다 (스크립트 exit 0 + `allow_failure: true` 이중 안전장치). AI 리뷰는 참고 정보이며 머지를 막지 않습니다.
- 마커(`<!-- ai-review-gemini -->`) 기반으로 기존 코멘트를 **업데이트**합니다 — 커밋마다 새 코멘트가 쌓이지 않습니다.
- 같은 MR에 새 커밋이 push되면 이전 실행은 취소됩니다 (`interruptible` + Auto-cancel redundant pipelines 설정).
- Draft MR(`Draft:` 제목)은 리뷰를 실행하지 않습니다.
- 이미지 파일 변경은 diff에서 제외합니다.

## Usage Notes

- 이 구현은 Gemini API 전용입니다. OpenAI, Claude, Copilot API key와 호환되지 않습니다.
- **private repository에서는 코드 diff와 `docs` 문서가 Google(Gemini API)로 전송됩니다. 잡을 활성화하기 전에 팀 전원과 합의하세요.**
- 민감한 운영 정보, secret, 외부 공유가 어려운 정책 문서는 `docs`에 두지 않거나 별도 관리합니다.
