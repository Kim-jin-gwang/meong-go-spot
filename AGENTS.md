# Project Instructions

이 저장소에서 작업하는 모든 AI 에이전트(Codex, Claude Code, Antigravity 등)에 적용되는 공통 지침입니다.

## Project Overview

**멍고반점** — 잃어버린 반려동물 사진을 전국 보호소 입소 동물(공공 API, 매일 갱신)과 AI 임베딩 유사도로 매칭해 후보를 찾아주는 유기동물 라이프사이클(실종→구조→보호→입양/반환) 플랫폼.

- 핵심 흐름: MVP는 본인 실종 게시물의 버튼 요청 → 비동기 Top-K 후보 조회와 일일 신규 입소 건수 요약까지다. 신규 입소 기반 자동 재분석·개별 후보 알림과 입양 추천은 MVP 이후(P1)다.
- 기술 구도: AI(YOLO26l-seg + DINOv2 ViT-B/14 임베딩)는 입력 생성 도구이고, **빅데이터 분산 처리(Kafka·HDFS·MapReduce — kNN 조인·행렬곱·쎄타조인·자카드 셀프조인·K-Means)가 매칭 본체**다. 람다 아키텍처(배치 전체 계산 + 실시간 증분)
- 모노레포: `android/` (Kotlin + Jetpack Compose) · `backend/` (Spring Boot, Java 21, Gradle, PostgreSQL)
- 플랫폼: **GitLab** (MR 기반 리뷰) + **Jira** (이슈 추적 — GitLab Issues 미사용)
- 인프라 결정 기록: `docs/handoff/ADR-001-인프라-결정기록.md` — 여기 적힌 결정과 어긋나는 것을 만들지 않는다

## Commands

| 명령 | 동작 |
|---|---|
| `npm run setup` | 클론 직후 1회 — 의존성 설치 + Git 훅 등록 |
| `npm run check` | Android+BE 전체 검사 (lint + test) — **커밋 전 반드시 실행** |
| `npm run check:data` | `data/` Python 단위 테스트(수집기·임베딩 러너, MapReduce 제외) — `data/` 변경 시 실행 (pytest·confluent-kafka 필요, CI `data-check`와 동일) |
| `npm run fix` | 자동 수정 가능한 것 전부 (ktlintFormat + spotlessApply) |
| `npm run dev:be` | 백엔드 개발 서버 (8080, 로컬 postgres 필요) |
| `npm run build:android` | Android 디버그 APK 빌드 (앱 실행·에뮬레이터는 Android Studio) |

MR 게이트(GitLab CI)는 `npm run check`와 동일한 검사를 실행한다. 로컬에서 통과하면 CI도 통과한다.

## Source of Truth

작업 영역에 따라 다음 문서를 먼저 읽는다.

- 코드 작성·수정: `docs/code-convention.md`
- 브랜치 생성·MR 대상 확인: `docs/branch-strategy.md`
- 커밋 작성·정리: `docs/commit-strategy.md`
- Jira 이슈 작성: `docs/jira-issue-guide.md`
- MR 작성: `docs/mr-guide.md`
- 백엔드 개발 문서 진입점: `backend/docs/README.md`
- 백엔드 Jira 이슈를 Codex로 시작: `backend/docs/backend-codex-start-guide.md`
- 백엔드 API·응답 규칙: `backend/docs/backend-common-settings.md`, `backend/docs/backend-api-development-guide.md`
- 기능 범위·인수 조건 확인: `docs/product/mvp-user-requirements-spec.md`
- 기능·화면 흐름 확인: `docs/product/user-flow.md`, `docs/product/wireframes/README.md`
- API 구현·수정: `docs/api-spec.md` (규칙 문서와 함께 참조)
- 인증 토큰·개인정보 암호화·DB 마이그레이션·비동기 작업·운영 안전장치: `docs/auth-token-policy.md`, `docs/backend-security-operations-policy.md`
- 사진 업로드·조회·HDFS 저장: `docs/photo-upload-policy.md` (API·ERD 문서와 함께 참조)
- 게시물 날짜·정확한 위치 공개: `docs/post-date-location-policy.md` (API·URS·ERD·Android 화면 문서와 함께 참조)
- DB 스키마·엔티티 작업: `docs/erd.md` (ERDCloud 표기 규칙은 `docs/erd-table-guide.md`)
- 데이터 파이프라인·임베딩 연동 작업: `docs/data-ai-interface.md`
- 시스템 구조·NFR 확인: `docs/architecture.md`
- 설계 결정의 근거 확인·변경: `docs/adr/ADR-002-제품-스택-결정기록.md` (제품 스택), `docs/handoff/ADR-001-인프라-결정기록.md` (인프라·협업 도구)
- 빅데이터 클러스터(Hadoop·Kafka) 운영·접속: `docs/bigdata-cluster.md`
- 배포·롤백: `docs/deploy-guide.md`
- Secret·설정 파일: `docs/gitignore-guide.md`, `docs/editor-config.md`, `docs/git-attributes.md`

실제 프로젝트 설정 파일과 문서가 다르면 임의로 한쪽을 선택하지 말고 **정책 충돌로 보고**한다.

## Required Workflow

- 작업을 시작하기 전에 `git status`와 현재 브랜치를 확인한다.
- `main`에서 직접 작업하거나 직접 커밋하지 않는다.
- 일반 작업은 `dev`에서 `feat/*`, `bugfix/*`, `refactor/*`, `docs/*` 브랜치를 만들어 진행한다.
- dev 동기화는 `git merge dev` 단일 — **rebase·force push는 이 프로젝트에서 사용하지 않는다.**
- 커밋 전에 `npm run check`를 실행한다.
- MR 전에는 `pre-mr-readiness`를 사용한다.
- MR 생성 후 **AI 리뷰 코멘트를 확인**한다: 유효한 지적은 반영(즉시 수정 또는 후속 이슈)하고, 오탐이면 판단 근거를 보고한다. 리뷰를 읽지 않고 병합을 권하지 않는다.
- 스테이징된 변경사항을 커밋할 때는 `commit-staged-changes`를 사용하고, 계획을 먼저 제시한다.

## Change Safety

- 사용자가 만든 변경사항을 덮어쓰거나 되돌리지 않는다.
- 요청하지 않은 `reset`, `rebase`, `push`, 파일 삭제를 수행하지 않는다.
- `git add .`, `git add -A`를 자동 실행하지 않는다.
- Secret, access token, 비밀번호, private key를 출력하거나 커밋하지 않는다.
- `.gitignore`에 추가하는 것만으로 이미 추적된 Secret이 제거되었다고 판단하지 않는다.
- 외부 AI 서비스로 전송되는 CI 잡(AI 리뷰)이 있으므로 diff에 민감정보가 들어가지 않게 한다.

## Project Skills

스킬 원본은 `.ai/skills/`에 있다. Codex는 `.agents/skills/`, Claude Code는 `.claude/skills/` 진입점을 사용하고, Antigravity는 이 문서와 `.ai/skills/` 원본을 직접 읽는다.

| 요청 | 사용할 스킬 |
| --- | --- |
| 코드 규칙 검사 | `review-code-convention` |
| 브랜치 규칙 검사 | `validate-branch-policy` |
| 커밋 검사 | `review-commit-history` |
| MR 초안 작성 | `draft-mr` |
| MR 전 종합 검사 | `pre-mr-readiness` |
| Secret·설정 검사 | `scan-secrets-and-config` |
| 스테이징 변경사항 커밋 | `commit-staged-changes` |

스킬은 프로젝트 규칙을 대신 결정하지 않는다. 항상 저장소의 `docs/`와 `.gitlab/` 파일을 기준으로 판단한다. 스킬을 수정하면 `node scripts/generate-skill-stubs.mjs`로 스텁을 재생성한다.

## Response Format

검사 결과는 다음 상태를 사용한다.

- `BLOCKER`: 커밋이나 MR 전에 반드시 해결해야 하는 문제
- `WARNING`: 영향도를 확인해야 하는 문제
- `INFO`: 참고 사항
- `PASS`: 확인을 통과한 항목

발견 사항에는 가능한 한 파일 경로, 줄 번호, 적용 규칙, 근거, 수정 방향을 포함한다.
