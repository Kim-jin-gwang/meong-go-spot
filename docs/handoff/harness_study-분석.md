# harness_study 하네스 분석

> 대상: `Desktop/harness_study` (eye-dont-care 프로젝트에서 추출한 하네스)
> 분석일: 2026-08-11

---

## 0. 한 줄 요약

**"Git/GitHub 워크플로우 규율을 AI 에이전트에게 위임한 하네스."**

코드 품질 자동화(lint·test 게이트)보다 **프로세스 규율**(브랜치·커밋·PR·Secret·라벨)에 집중되어 있습니다. 문서화 밀도와 툴 중립성 설계는 학생 프로젝트 수준을 크게 넘어서지만, **결정론적 강제 장치가 거의 없다**는 구조적 공백이 있습니다.

---

## 1. 전체 구조 — 4계층

```
┌─ L1  진입점 (Entry)                                         ─┐
│   AGENTS.md      "Project Instructions for Codex"   3.0 KB   │
│   CLAUDE.md      "Project Instructions for Claude"  2.2 KB   │
│   CONTRIBUTING.md  사람용 기여 가이드                5.5 KB   │
└──────────────────────┬───────────────────────────────────────┘
                       │ "먼저 읽어라" 라우팅
┌──────────────────────▼───────────────────────────────────────┐
│  L2  정책 원본 (Source of Truth) — docs/  약 60 KB            │
│   code-convention · branch-strategy · commit-strategy         │
│   issue-template-guide · pr-template-guide · label-strategy   │
│   gitignore-guide · editor-config · git-attributes            │
│   skill-guide · ai-review-gemini                              │
└──────────────────────┬───────────────────────────────────────┘
                       │ 스킬이 "읽어서" 판단
┌──────────────────────▼───────────────────────────────────────┐
│  L3  절차 (Skills) — 8개                                      │
│   .ai/skills/       ← 원본 (canonical, 1.4~2.0 KB)            │
│   .agents/skills/   ← Codex 진입점 (포인터 스텁 ~0.5 KB)      │
│   .claude/skills/   ← Claude 진입점 (포인터 스텁 ~0.5 KB)     │
└──────────────────────┬───────────────────────────────────────┘
                       │ 검사 결과를 사람이 반영
┌──────────────────────▼───────────────────────────────────────┐
│  L4  강제·자동화 장치                                          │
│   .github/  ISSUE_TEMPLATE(3) · PULL_REQUEST_TEMPLATE(6)      │
│             labels.json(30) · workflows(2)                    │
│   Jenkinsfile (build & deploy + Mattermost 알림)              │
│   .editorconfig · .gitattributes · .gitignore                 │
└───────────────────────────────────────────────────────────────┘
```

---

## 2. 계층별 상세

### L1 — 진입점

| 파일 | 대상 | 특징 |
|---|---|---|
| `AGENTS.md` | Codex | Source of Truth 라우팅 → Required Workflow → Change Safety → Project Skills 표 → Response Format |
| `CLAUDE.md` | Claude Code | Skill Routing(`/명령` 형태) → Project Rules → Required Behavior |

두 파일의 **내용은 거의 동일하고 표현만 툴에 맞춰져** 있습니다. Claude 쪽은 `/review-code-convention` 슬래시 명령 형식, Codex 쪽은 자연어 스킬명 호출 형식.

공통으로 박혀 있는 안전 규칙:

- `main` 직접 작업·직접 커밋 금지
- 요청하지 않은 `reset` / `rebase` / `push` / 파일 삭제 금지
- 사용자 변경사항 덮어쓰기·되돌리기 금지
- Secret 출력·커밋 금지, `.gitignore` 추가만으로 제거됐다고 판단 금지
- 문서와 실제 설정이 다르면 **임의 판단하지 말고 "정책 충돌"로 보고**

### L2 — 정책 문서 (docs/)

| 문서 | 크기 | 핵심 |
|---|---|---|
| `code-convention.md` | 10.1 KB | 포매팅·네이밍·계층·API·에러·로깅·설정·DB·프론트·테스트·주석 (17개 섹션) |
| `commit-strategy.md` | 9.4 KB | Conventional Commits, 13개 type, Gitmoji, 브랜치-커밋 type 관계 |
| `label-strategy.md` | 7.6 KB | 5개 카테고리 30개 라벨 체계 |
| `branch-strategy.md` | 7.1 KB | 6개 브랜치 타입, merge flow, 타입별 테스트 규칙 |
| `skill-guide.md` | 9.0 KB | 스킬 사용법 + 4가지 추천 워크플로우 |
| `gitignore-guide.md` / `editor-config.md` / `git-attributes.md` | 11.8 KB | 설정 파일 근거 |
| `issue-template-guide.md` / `pr-template-guide.md` | 6.6 KB | 템플릿 사용 규칙 |
| `ai-review-gemini.md` | 2.1 KB | Gemini 리뷰 워크플로우 운영 가이드 |

여기에 백엔드 개발 가이드(`backend/docs/backend-api-development-guide.md` 14.3 KB 등)와 핸드오프 문서들이 별도로 있습니다.

### L3 — 스킬 8개

`docs/skill-guide.md`가 스킬을 **권한 등급으로 4분류**한 게 핵심입니다.

| 종류 | 스킬 | 부작용 |
|---|---|---|
| **검사** | `review-code-convention`, `validate-branch-policy`, `review-commit-history`, `scan-secrets-and-config` | 없음 (read-only) |
| **작성** | `draft-issue`, `draft-pr` | 없음 (본문만 출력, GitHub에 생성 안 함) |
| **종합 검사** | `pre-pr-readiness` | 없음 |
| **변경 실행** | `commit-staged-changes` | **유일하게 Git 상태 변경** — 계획 제시 → 명시적 승인 → 실행 |

추천 워크플로우(skill-guide.md):

```
일반 기능 개발
  validate-branch-policy → 코드 작성 → review-code-convention
  → scan-secrets-and-config → review-commit-history
  → pre-pr-readiness → draft-pr
```

### L4 — 자동화

| 장치 | 트리거 | 하는 일 |
|---|---|---|
| `ai-review-gemini.yml` | PR opened/sync/reopen/ready | PR diff + `docs/` 전체를 Gemini에 넣고 한국어 리뷰를 PR 코멘트로 |
| `sync-labels.yml` | `labels.json` push, 수동 | `labels.json`을 GitHub 라벨과 동기화 |
| `Jenkinsfile` | (Jenkins) | Validate → docker compose build & deploy → 상태 확인 → Mattermost 알림 |
| `.editorconfig` | 에디터 | LF, UTF-8, 언어별 들여쓰기(JS 2 / Java·Kotlin·Python 4) |
| `.gitattributes` | Git | `text=auto eol=lf`, `.bat/.cmd`만 CRLF, 바이너리·오피스 파일 diff 제외 |

---

## 3. 잘 설계된 점 (그대로 가져갈 것)

### ① 3-way 스킬 미러링 + canonical 포인터 — 이 하네스의 백미

`.ai/skills/`에 실제 내용을 두고, `.agents/skills/`와 `.claude/skills/`에는 **포인터 스텁만** 둡니다.

```markdown
---
name: draft-pr
description: Draft a GitHub Pull Request using ... (동일)
---

Read and follow the canonical project skill at `../../../.ai/skills/draft-pr/SKILL.md`.
Treat that file as the source of truth.
If it cannot be read, report the skill as blocked instead of inventing project rules.
```

주목할 점 두 가지:

- **frontmatter의 `description`은 진입점에 복제**되어 있습니다. 툴의 스킬 탐색·자동 트리거는 description을 보므로, 이건 복제가 맞습니다. 본문만 위임한 건 정확한 판단.
- **"읽을 수 없으면 blocked로 보고하고, 프로젝트 규칙을 지어내지 마라"** — 폴백 실패 모드까지 명시했습니다. LLM 하네스에서 가장 자주 빠지는 함정(파일 못 읽으면 그럴듯하게 지어내기)을 정확히 막았습니다.

### ② "규칙은 문서, 절차는 스킬" 분리

> 실제 프로젝트 규칙은 스킬에 복사하지 않고 `docs/`와 `.github/`의 파일을 기준으로 읽습니다.
> 따라서 규칙을 변경할 때는 스킬보다 프로젝트 문서를 먼저 수정해야 합니다.
> — `docs/skill-guide.md`

스킬 8개 전부가 첫 줄에서 "먼저 `docs/xxx.md`를 읽는다"로 시작합니다. 규칙이 한 곳에만 존재하므로 드리프트가 원천 차단됩니다.

### ③ 표준화된 심각도 어휘

발견 사항: `BLOCKER` / `WARNING` / `INFO` / `PASS`
게이트 판정: `READY` / `WARNING` / `BLOCKED`

출력 포맷까지 고정:

```text
[BLOCKER|WARNING|INFO] path:line
규칙: 적용한 프로젝트 규칙
근거: 실제 코드에서 확인한 문제
제안: 구체적인 수정 방향
```

→ 하네스 엔지니어링에서 말하는 **"LLM 소비에 최적화된 신호"**를 사람 손으로 구현한 사례입니다. 서로 다른 툴이 같은 어휘로 답하니 팀이 결과를 동일하게 해석할 수 있습니다.

### ④ 안전장치의 3중 배치

`git add .` / `git add -A` / `reset` / `rebase` / `push` 자동 실행 금지가 **AGENTS.md, CLAUDE.md, SKILL.md 세 곳에 중복 명시**되어 있습니다. 컨텍스트가 잘려도 하나는 살아남는 구조.

### ⑤ Gemini 리뷰 워크플로우의 세부 완성도

- **`docs/` 전체를 리뷰 컨텍스트로 주입** → 일반론이 아니라 "우리 팀 문서 기준"으로 지적하고, 문서 경로를 인용하게 프롬프트에 명시
- 상한 설정: 문서 20개 / 문서당 8,000자 / 전체 40,000자, diff 30파일 / 60,000자
- **마커(`<!-- ai-review-gemini -->`) 기반 코멘트 업데이트** → 커밋마다 새 코멘트가 쌓이는 스팸 방지
- `concurrency` + `cancel-in-progress` → 연속 push 시 이전 실행 취소
- API 실패 시 `core.warning`만 남기고 **워크플로우를 실패시키지 않음** → AI 리뷰가 머지를 막지 않는다는 명확한 입장
- draft PR·이미지 전용 PR 제외
- `docs/ai-review-gemini.md`에 **"private repo에서는 diff와 docs가 외부로 전송된다는 점을 팀과 합의하라"**는 경고까지 문서화

### ⑥ 선언적 라벨 관리

`labels.json`(30개, 5카테고리) → `sync-labels.yml`로 코드화. 라벨 체계가 Git 히스토리에 남고 리뷰 대상이 됩니다.

`type/*` (8) · `priority/*` (3) · `status/*` (5) · `needs/*` (3) · `area/*` (5) · `level/*` (3) + 예외 라벨 3개

---

## 4. 공백과 위험 — 다음 프로젝트에서 보완할 것

### 🔴 ① 계산적(결정론적) 컨트롤이 없다 — 가장 큰 구조적 공백

Martin Fowler의 분류로 보면 이 하네스는 **추론적(inferential) 컨트롤에 거의 100% 의존**합니다.

| 있어야 할 것 | 현재 상태 |
|---|---|
| CI lint / typecheck / test 게이트 | **없음** — GitHub Actions는 Gemini 리뷰 + 라벨 동기화 2개뿐 |
| pre-commit 훅 | **없음** — husky / lefthook / pre-commit 설정 미발견 |
| Jenkins 테스트 스테이지 | **없음** — Checkout → Validate(파일 존재 확인) → Build&Deploy → Status |
| 포매터·린터 실행 시점 | `code-convention.md`가 Checkstyle·Spotless·ktlint·Prettier·ESLint를 규정하지만, **어디서 실행되는지 정의된 곳이 없음** |

스킬들이 방어적으로 쓰인 문장이 이 공백을 그대로 드러냅니다.

> 사용 가능한 포매터·린터·테스트 명령이 **실제 설정에서 확인될 때만** 실행한다.
> 명령이 없거나 실행 환경이 없으면 `실행하지 못함`으로 표시한다. — `pre-pr-readiness`

즉 "AI가 눈으로 읽어서 지적"은 촘촘한데, **기계가 막아주는 건 하나도 없습니다.** AI가 놓치면 그대로 통과합니다.

> ※ `harness_study`는 소스 디렉터리(`frontend/`, `backend/`)를 뺀 추출본이라, 하위 폴더에 린터 설정이 있을 가능성은 있습니다. 다만 **CI/훅에서 호출하는 지점이 없다는 사실**은 그대로입니다.

### 🟠 ② 강제력 없음 — 전부 "권고"

스킬은 사람이 호출해야만 실행됩니다. 안 부르면 그만이고, 아무 경고도 없습니다.

- Claude Code `hooks`(자동 실행) 미사용
- CI required check 없음
- 브랜치 보호 규칙 문서화 없음

하네스는 "결과물의 하한선"을 잡는 장치인데, 지금은 하한선이 **개인의 성실성**입니다.

### 🟠 ③ `sync-labels.yml`이 파괴적

```javascript
for (const label of existingLabels) {
  if (!desiredByName.has(label.name)) {
    await github.rest.issues.deleteLabel({ ... });  // 무조건 삭제
  }
}
```

`labels.json`에 없는 라벨은 **전부 삭제**됩니다. 누군가 GitHub UI에서 급하게 만든 라벨이 조용히 사라지고, 그 라벨이 붙어 있던 이슈에서도 함께 사라집니다. dry-run도, 보호 목록도, 로그도 없습니다.

→ 다음엔 최소한 삭제 대상을 먼저 로그로 출력하고, `workflow_dispatch` 입력으로 `apply=true`일 때만 삭제하도록.

### 🟡 ④ 참조 무결성이 깨진 지점

| 위치 | 문제 |
|---|---|
| `docs/label-strategy.md` | `github/stale-issues` workflow를 전제하고 `pinned`·`security`·`blocked` 예외 라벨까지 만들어 뒀지만, **stale workflow 파일이 없음** |
| `.github/ISSUE_TEMPLATE/config.yml` | Discussions URL이 `https://github.com/<owner>/<repository>/discussions` — **플레이스홀더 그대로** |
| `.env`, `.env.example` | 둘 다 **0바이트** (스킬 `scan-secrets-and-config`가 "`.env.example` 없이 환경변수만 존재하는 경우"를 검사 항목으로 두고 있는데 정작 비어 있음) |

문서가 실제 설정보다 앞서간 전형적인 케이스입니다. 이 하네스 자신이 정한 "문서와 실제 설정이 다르면 정책 충돌로 보고"에 걸리는 항목들입니다.

### 🟡 ⑤ 문서 총량과 라우팅 부담

`docs/` 규율 문서만 ~60 KB + `CONTRIBUTING.md` 5.5 KB. AGENTS.md/CLAUDE.md가 "작업 영역에 따라 골라 읽어라"로 라우팅하는 설계 자체는 좋지만, `pre-pr-readiness` 하나만 실행해도 문서 6개를 읽어야 합니다. 실행 비용·컨텍스트 압박이 큽니다.

→ 다음엔 문서를 **"에이전트가 읽는 체크리스트"**와 **"사람이 읽는 배경 설명"**으로 분리하는 걸 권합니다.

### 🟡 ⑥ 외부 스킬 관리 일관성 없음

| 스킬 | 위치 | 크기 | lock 등록 |
|---|---|---|---|
| `design-taste-frontend` | `.claude/skills/` | 87 KB | ✅ `skills-lock.json` (GitHub `Leonxlnx/taste-skill`, 해시 고정) |
| `impeccable` v4.0.4 | `.claude/skills/` | 10.7 KB + reference 30개 + scripts 다수 (수 MB) | ❌ **미등록** |

`impeccable`은 버전 추적 밖에 있고, 둘 다 `.claude/`에만 있어서 **Codex 쓰는 팀원은 사용 불가**입니다. `.impeccable/config.json`에 프로젝트별 예외 규칙이 쌓이고 있는 걸 보면 실제로 쓰인 흔적이 있는데, 이 자산이 팀의 1/N에게만 적용되고 있었습니다.

### 🟡 ⑦ Antigravity 미지원 · Jira 없음

- 진입점이 Codex·Claude 2개뿐. Antigravity를 쓰려면 `AGENTS.md` 그대로 읽히긴 하지만, 스킬 진입점(`.agent/rules/` 등) 대응이 없습니다.
- 이슈 관리가 **전부 GitHub Issues 기반**입니다. 다음 프로젝트가 Jira라면 L4의 이슈·라벨·PR 템플릿 레이어를 통째로 다시 매핑해야 합니다.

---

## 5. 다음 프로젝트로 가져갈 체크리스트

### 그대로 이식

- [ ] `.ai/skills` 원본 + 툴별 포인터 스텁 구조 (**Antigravity 진입점만 추가하면 3툴 커버**)
- [ ] "읽을 수 없으면 blocked로 보고, 규칙을 지어내지 마라" 폴백 문구
- [ ] `BLOCKER` / `WARNING` / `INFO` / `PASS` + `READY` / `BLOCKED` 어휘
- [ ] "규칙은 문서, 절차는 스킬" 분리 원칙
- [ ] 스킬 권한 등급화 (검사 / 작성 / 종합 / 변경실행)
- [ ] Gemini 리뷰의 `docs/` 컨텍스트 주입 + 마커 코멘트 업데이트 + 실패해도 머지 안 막기
- [ ] `labels.json` 선언적 관리

### 반드시 추가

- [ ] **CI required check: lint + typecheck + test** ← 최우선
- [ ] **pre-commit 훅** (팀이 Windows이므로 `lefthook` 권장)
- [ ] `make check` / `npm run check` 같은 **단일 통합 명령**
- [ ] Jenkins에 test 스테이지
- [ ] `sync-labels`에 dry-run + 삭제 보호
- [ ] `.env.example` 실제 내용 채우기
- [ ] stale workflow 추가하거나, 없으면 라벨 전략에서 관련 서술 제거

### 구조 개선

- [ ] **AGENTS.md ↔ CLAUDE.md 중복 제거** — 지금은 같은 내용이 두 벌 유지되고 있어 드리프트 위험. AGENTS.md를 원본으로 두고 CLAUDE.md는 `@AGENTS.md` import + Claude 전용 보강만 (스킬에 이미 쓴 포인터 패턴을 진입점에도 적용)
- [ ] 문서를 "에이전트용 체크리스트" / "사람용 배경 설명"으로 분리
- [ ] 외부 스킬 전부 `skills-lock.json`에 등록, 툴 중립 위치로 이동
- [ ] AGENTS.md 제목에서 툴 이름 제거 (`Project Instructions for Codex` → 중립 표현)

### Jira 전환 시 매핑

| 기존 (GitHub) | Jira 대응 |
|---|---|
| `type/*` 라벨 8종 | Issue Type |
| `priority/*` 3종 | Priority 필드 |
| `status/*` 5종 | 워크플로우 상태 |
| `area/*` 5종 | Component |
| `level/*` 3종 | Story Point 또는 커스텀 필드 |
| `needs/*` 3종 | 라벨 또는 상태 유지 |
| ISSUE_TEMPLATE 3종 | Issue Type별 Description 템플릿 |
| PR 템플릿 6종 | GitHub 유지 (Jira는 Smart Commit으로 연동) |
| `sync-labels.yml` | Jira는 관리자 UI 관리 → **코드화 불가**, 별도 문서로 |

---

## 6. 총평

**"AI 에이전트를 위한 프로세스 하네스"로는 완성도가 높고, "코드 품질 하네스"로는 미완성입니다.**

가장 인상적인 건 툴 중립성을 포인터 스텁으로 푼 방식과, 규칙을 스킬에 복사하지 않겠다는 원칙입니다. 이 두 개는 하네스 엔지니어링 문헌에서 권장하는 패턴을 정확히 구현한 것이고, 그대로 다음 프로젝트로 가져갈 만합니다.

반대로 **피드포워드(가이드)만 있고 피드백(센서)이 비어 있습니다.** 규칙은 촘촘히 썼지만 그 규칙을 기계가 검증하는 지점이 없습니다. 다음 프로젝트에서 가장 큰 개선은 새 문서를 쓰는 게 아니라, **이미 문서화된 규칙 중 자동 검증 가능한 것을 CI와 pre-commit으로 내리는 작업**입니다.
