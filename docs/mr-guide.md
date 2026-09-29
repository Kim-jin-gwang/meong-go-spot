# MR 가이드

> 강제 장치: MR 대상 브랜치 → Protected branches / 파이프라인 통과 → "Pipelines must succeed"
> 템플릿 위치: `.gitlab/merge_request_templates/`

## 브랜치 → 템플릿 매핑

| Source | Target | Template | 제목 prefix |
|---|---|---|---|
| `feat/*` | `dev` | `feat.md` | `feat: ` |
| `bugfix/*` | `dev` | `bugfix.md` | `fix: ` |
| `refactor/*` | `dev` | `refactor.md` | `refactor: ` |
| `docs/*` | `dev` | `docs.md` | `docs: ` |
| `release/*` 또는 `dev` | `main` | `release.md` | `release: vx.y.z` |
| `hotfix/*` | `main` | `hotfix.md` | `fix: ` |

> **GitLab은 브랜치명으로 MR 템플릿을 자동 선택하지 않습니다.** MR 생성 화면의
> "Choose a template" 드롭다운에서 작성자가 직접 골라야 합니다.
> 템플릿 상단의 metadata 주석은 사람이 참고하는 정보일 뿐, GitLab이 제목·target을 자동 적용하지 않습니다.

## MR 본문 필수 항목

- 변경사항 요약 / 변경 이유
- 변경 파일·모듈·API
- 테스트 결과
- 관련 Jira 이슈 (`#123` — 본문에 키를 쓰면 티켓에 자동 연결)
- 리뷰어가 확인할 내용
- 배포 영향과 롤백 방법 (release/hotfix)

## 공통 체크리스트 (템플릿 내장)

- [ ] MR 제목이 변경 내용을 명확히 설명
- [ ] 변경 범위가 하나의 단위로 제한
- [ ] 문서 수정이 필요하면 함께 반영
- [ ] 리뷰어 확인 사항을 본문에 작성
- [ ] 단위 테스트 수행 / 기존 기능 영향 없음 확인

## dev → main 동기화 MR 주의

`dev`를 source로 `main`에 MR을 올릴 때는 **"Delete source branch" 체크를 반드시 해제**한다.
기본값이 켜져 있어 그대로 병합하면 영구 브랜치인 `dev`가 삭제된다 (일회용 작업 브랜치용 기본값).

제목은 **`release: vx.y.z`** 다. 예: `release: v0.2.0`.

**버전만 적고 설명을 덧붙이지 않는다.** `release: 2026-09-11 운영 배포 — TLS 종단…` 처럼
날짜나 요약을 넣지 않는다 — 날짜는 병합 기록에 이미 있고 요약은 본문에 쓴다. 제목은 배포된
결과물을 가리키는 식별자 하나면 된다.

버전 부여 규칙과 Android `versionCode`·`versionName` 과의 관계는
[deploy-guide.md](deploy-guide.md)의 "릴리즈 버전" 절에 있다.

## 작성자 귀속 규칙

MR·병합 기록이 실제 작업자와 일치해야 기여 추적과 관리가 된다. GitLab은 "요청을 인증한 계정"을 작성자로 기록하므로:

- MR은 **작업한 사람이 자기 계정으로 직접 생성**한다 — push 후 터미널에 뜨는 "Create merge request" 링크 또는 GitLab 웹 버튼 (별도 설정 불필요)
- 병합도 리뷰 승인 후 **작성자 본인이** 수행한다
- AI 에이전트로 MR을 만들 때는 **본인 토큰 환경에서 본인 작업만** — 다른 팀원의 작업을 대신 생성하지 않는다 (API로 만든 MR의 작성자는 토큰 주인으로 찍힌다)
- 공용 자동화(정기 배치 등)가 MR·코멘트를 만들 때는 프로젝트 액세스 토큰(bot 명의)을 사용해 사람 작업과 구분한다

## 병합 규칙

- 파이프라인(MR 게이트) 통과 필수 — 실패한 MR은 병합 버튼이 비활성화됨
- AI 리뷰([ai-review-gemini.md](./ai-review-gemini.md))는 참고용, 병합을 막지 않음
- 병합 후 source 브랜치 삭제
