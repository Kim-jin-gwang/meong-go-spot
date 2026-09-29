# KICKOFF — 새 프로젝트 하네스 구축 (Claude Code 첫 세션용)

이 디렉터리는 새 프로젝트의 하네스(에이전트 작업 환경 + CI/CD + 컨벤션)를 구축하기 위한
핸드오프 패키지다. 아래 순서로 읽고 작업하라.

## 읽기 순서

1. `ADR-001-인프라-결정기록.md` — **모든 결정의 원천.** 여기 적힌 결정과 어긋나는 것을 만들지 마라.
2. `인프라-구축-순서-체크리스트.md` — Stage 0~10 작업 순서. Stage 0은 완료됨(ADR). Stage 1부터 시작.
3. `harness_study-분석.md` — 이전 프로젝트 하네스의 분석. "잘 된 점"은 그대로 이식, "공백"은 이번에 메꾼다.
4. `harness_study-컨벤션-통합본.md` — 이식할 컨벤션 전체. **부록 A(모순 10건)는 이식 시 반드시 청소**, 부록 B(강제 장치 매트릭스)의 ❌ 항목이 이번 프로젝트의 핵심 신규 작업.
5. (참조용) `harness_study/` 원본 폴더 — 스킬 원문, Jenkinsfile, 설정 파일을 이식할 때 원본 확인용.

## 핵심 결정 요약 (ADR-001)

- 모노레포: `android/`(Kotlin + Jetpack Compose, JVM 17) + `backend/`(Spring Boot + Gradle + Java 21 + PostgreSQL)
- 플랫폼: **GitLab** — `.github/` 규격은 GitLab에서 동작하지 않으므로 절대 만들지 마라.
  템플릿은 `.gitlab/merge_request_templates/`, CI는 `.gitlab-ci.yml`.
- 브랜치: Git-Flow 축약형 (`main`+`dev`+`feat/*` 등). PR이 아니라 **MR**로 표기.
- 이슈: **Jira 일원화.** GitLab Issues 미사용. 이슈 템플릿을 GitLab에 만들지 마라.
- CI: GitLab CI = MR 게이트(lint+typecheck+test, 신규 구현) / Jenkins = 배포(기존 Jenkinsfile 이식 + Test 스테이지 추가)
- dev 동기화는 **merge 단일** (rebase·force push 금지), **Gitmoji 금지**, API는 **`/api/v1`**

## 첫 세션 작업 (Stage 1~2)

1. 레포 뼈대: `.gitignore` / `.gitattributes` / `.editorconfig`를 **첫 커밋으로** (원본 harness_study에서 이식, 내용 거의 그대로 사용 가능)
2. FE/BE 스캐폴딩 — 로컬에서 Hello World가 뜨는 상태
3. 단일 검증 명령: 루트에서 FE/BE 전체를 검사하는 `npm run check` (Windows 팀이므로 Makefile 금지)
   - Android: ktlint + unit test (Java/Kotlin 바이트코드 JVM 17)
   - BE: spotless + checkstyle(또는 팀 선택) + `gradlew test` (Gradle Toolchain `languageVersion = 21` 명시)
4. lefthook + commitlint 설정 (pre-commit: 변경 파일만, 5초 이내 / commit-msg: Conventional Commits 13종)

## 다음 세션 이후 (Stage 3~)

- `.gitlab-ci.yml` MR 게이트 (캐시 필수, 3분 이내 목표) → Protected branches 설정 안내 문서
- 컨벤션 문서 이식: 통합본 기준으로 하되 부록 A 10건 청소 + PR→MR 치환 + Gitmoji 절 삭제 + rebase 절 "사용하지 않음" 처리
- AGENTS.md(원본) + CLAUDE.md(`@AGENTS.md` import) + `.ai/skills/` 포인터 스텁 구조 이식
- Gemini AI 리뷰를 GitLab CI 잡으로 구현 (non-blocking, 마커 코멘트 업데이트 방식)

## 금지 사항

- ADR과 어긋나는 결정을 임의로 하지 마라. 어긋남을 발견하면 작업을 멈추고 보고하라.
- `.github/` 디렉터리를 만들지 마라.
- 강제 장치(린터 룰, CI 잡, 훅) 없는 새 규칙 문서를 쓰지 마라.
- 이전 프로젝트에서 실사용 검증이 안 된 자산(라벨 30종 체계, 사용 흔적 없는 스킬)을 통째로 복사하지 마라.
