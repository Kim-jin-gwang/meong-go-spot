# Protected Branches · MR 게이트 설정 절차

> GitLab 저장소 발급 직후 Maintainer가 **1회** 수행하는 설정.
> 이 설정이 끝나야 `.gitlab-ci.yml`의 MR 게이트가 "권고"가 아니라 "강제"가 된다.

## 0. 최초 push 후 파이프라인 검증 (가장 먼저)

> ✅ **이 절차는 2026-08-12에 같은 인스턴스의 개인 테스트 레포에서 이미 완주했다.**
> 통과 MR 그린(0.6분), 고의 실패 MR 2건이 정확한 잡에서 실패, rules:changes·캐시 동작 확인.
> 이 과정에서 commitlint 이미지 버그(node:24-alpine에 git 없음)도 잡아 수정했다.
> 본 레포에서는 러너 등록(§0.5) 후 정상 MR 1개로 빠른 재확인만 하면 된다.

1. `main`, `dev` push 후 파이프라인이 생성·통과하는지 확인
2. 일부러 규칙을 깬 MR 2개로 **실패가 실제로 나는지** 확인
   - 브랜치명 규칙 위반 (예: `test_branch`) → `branch-name` 잡 실패
   - 린트 깨진 코드 → `fe-check` 또는 `be-check` 실패
3. 통과 확인 후 이 문서의 "초안" 경고와 `.gitlab-ci.yml` 상단 경고 주석을 제거하는 MR을 올린다

## 0.5 러너 등록 (이것 없이는 파이프라인이 영원히 pending)

**사용한 사내 GitLab 인스턴스에는 공유 러너가 없었다** (2026-08-12 test 레포에서 확인 — `shared_runners_enabled=true`여도 실제 잡을 집어가는 러너가 0개). 프로젝트 러너를 직접 등록해야 한다.

1. **Settings → CI/CD → Runners → New project runner** — "Run untagged jobs" 체크 후 생성
2. 러너를 돌릴 머신(팀 서버 권장, 임시로는 팀원 PC의 Docker)에서:

```bash
docker volume create gitlab-runner-config
docker run --rm -v gitlab-runner-config:/etc/gitlab-runner gitlab/gitlab-runner:latest \
  register --non-interactive --url "<GitLab URL>" \
  --token "<러너 토큰(glrt-...)>" --executor docker --docker-image "alpine:latest"
docker run -d --name gitlab-runner --restart unless-stopped \
  -v gitlab-runner-config:/etc/gitlab-runner \
  -v /var/run/docker.sock:/var/run/docker.sock \
  gitlab/gitlab-runner:latest
```

- 서버 발급 전까지는 팀원 1명의 PC 러너로 버틸 수 있지만, PC가 꺼지면 파이프라인이 pending에 걸린다. **서버 발급 즉시 서버로 옮긴다.**
- 첫 실행은 이미지 pull(node:24, temurin 등) 때문에 3분 목표를 넘긴다. 두 번째 실행부터가 실측 기준.

## 1. Protected branches

**Settings → Repository → Protected branches**

| 브랜치 | Allowed to merge | Allowed to push and merge | 효과 |
|---|---|---|---|
| `main` | Maintainers | **No one** | 직접 push 차단, MR로만 병합 |

> **팀 결정 (2026-08-24): `dev`는 보호하지 않는다** (느슨한 운영). dev 직접 push가 기술적으로는 가능하지만 규약상 MR 경로를 사용하고, dev push에도 파이프라인이 돌아 깨짐은 사후 감지된다. 문제가 반복되면 dev 보호를 재검토한다.
>
> main은 GitLab이 기본 브랜치를 자동 보호하므로 별도 생성 없이 위 표와 일치하는지 확인만 하면 된다.

## 2. 파이프라인 통과를 병합 조건으로

**Settings → Merge requests**

- [x] **Pipelines must succeed** ← MR 게이트를 required로 만드는 스위치
- [x] Enable "Delete source branch" option by default (병합 후 작업 브랜치 삭제 규칙)
- Merge method: **Merge commit** (dev 동기화 merge 단일 정책과 일치 — ADR-001 D8)
- [x] Squash commits: Do not allow (커밋 단위 규칙을 히스토리에 보존)

**Settings → CI/CD → General pipelines**

- [x] **Auto-cancel redundant pipelines** (연속 push 시 이전 실행 취소)

## 3. GitLab Issues 비활성화 (Jira 일원화)

**Settings → General → Visibility, project features, permissions**

- Issues: **Disable** ← 계획·추적은 Jira 단일 (ADR-001 D5)

## 4. CODEOWNERS를 만들지 않은 이유

CODEOWNERS 기반 리뷰어 강제는 GitLab **Premium** 기능이다. CE에서는 파일이 있어도 아무것도 강제하지 않으므로, "강제 장치 없는 규칙 문서를 만들지 않는다"는 원칙에 따라 생성하지 않았다. Premium 확인 시 추가를 재검토한다.
