# 배포 가이드

> 상태: **운영 중** — 2026-08-31 서버 1(`<server1-host>`)에서 첫 배포 성공
> (backend+postgres 기동, `/api/v1/ping` 외부 200 확인).

> **TLS 종단은 2026-09-11 구축, 2026-09-14 마무리됐다** — `https://api.meonggo.shop` (아래 "TLS 종단" 절).
> nginx 가 80·443 을 모두 잡고 80 은 HTTPS 로 301 한다. 인증서 갱신은 webroot 로 무중단이다.

> **`main` 배포 선결 조건은 2026-09-11 모두 충족됐다.** 기동 필수 설정 17개의 배선·생성이
> 끝났고 실제 기동을 확인했다. SOLAPI 는 실제 계정으로 교체돼 **가입 SMS 가 동작한다**(2026-09-22
> 운영자 확인, 발신번호는 운영자 개인 휴대전화). 아래 "운영 기동에 필요한 설정" 절.
>
> **가입 인증 수단은 휴대전화(SOLAPI)로 유지한다(2026-09-23 결정).** 이메일 인증 전환(V12, 회원·사용자
> 게시물 초기화·SMTP 필요)은 dev 에서 되돌렸다. 070 발신번호 전환은 통신사 매개번호 차단 정책·서류
> 절차 때문에 보류.

> **인증 출시 차단 조건:** A0~A5 인증·회원 API 를 운영에 활성화하기 전에 HTTP→HTTPS redirect,
> Flyway migration 단일 실행, checksum 으로 고정한 행정구역 기준 파일과 탈퇴 파기 job 을
> 구축해야 한다. 목적별 암호화·HMAC 키와 JWT Secret file mount, 내부 전용 Redis 는
> 2026-09-11 구축됐다 (아래 "운영 기동에 필요한 설정").

## 구성

클라이언트는 Android 앱(스토어/APK 배포)이므로 **이 파이프라인의 배포 대상은 backend뿐이다.**

```text
[Android 앱] ─ HTTPS :443 → nginx → 127.0.0.1:8080 ─→ backend 컨테이너 :8080
             ─ HTTP  :80  → nginx → HTTPS 301 + ACME 챌린지(/var/www/certbot)
             ─ HTTPS /img/ → nginx 캐시 → openapi.animal.go.kr (공공 사진)
                                                          ├─ postgres 컨테이너 (publish 없음)
                                                          └─ redis 컨테이너 (publish 없음)
```

2026-09-11~14 사이에는 backend 가 80 을 직접 쓰는 전환 구성이었다 — "TLS 종단" 절 참조.

| 파일 | 역할 |
|---|---|
| `backend/Dockerfile.prod` | Gradle 빌드 → JRE 21 런타임 이미지 |
| `compose.prod.yml` | 운영 3-컨테이너 구성(backend·postgres·redis). TLS 종단은 호스트 nginx, Secret 은 `/run/secrets` 마운트 |
| `infra/scripts/setup-prod-secrets.py` | 운영 키 자료 생성 (서버에서 실행, 값은 레포에 들어오지 않는다) |
| `infra/scripts/docker-user-rules.sh` | 컨테이너→서버 2 차단과 WebHDFS 예외 (부팅 시 `docker-user-rules.service` 가 멱등 실행) |
| `infra/reference/region-codes.csv` | 배포한 행정구역 기준 데이터 (비밀 아님 — 검수가 MR 리뷰에 남도록 버전 관리한다) |
| `data/reference/build_region_codes.py` | 법정동코드 전체자료 → 위 CSV 변환 |
| `data/reference/verify_region_codes.py` | 위 CSV 검증 (backend·수집기 양쪽 규칙 재현 + Android 대조) |
| `compose.dev.yml` | 로컬 PostgreSQL·인증 Redis·수집기 Kafka. 인증 설정은 backend/docs/member-signup-guide.md 참조 |
| `Jenkinsfile` | 배포 파이프라인 |

- TLS 종단은 호스트 nginx 가 담당한다 (아래 "TLS 종단" 절). 80 은 HTTPS 로 301 한다 — 평문 API 는 없다.
- 앱 배포(APK 서명·스토어 업로드)는 이 파이프라인 범위 밖이며 Android 담당이 별도로 관리한다.

## Jenkins 파이프라인

```text
Checkout → Validate → Test → Build & Deploy → Health check → Mattermost 알림
```

- **Test**: 배포 직전 최종 확인 — 임시 PostgreSQL·Redis 사이드카를 붙여 컨테이너에서 `gradlew check` 실행. MR 게이트 대용이 아니다 — 게이트는 GitLab CI가 담당한다.
- **Build & Deploy**: `IMAGE_TAG=<BUILD_NUMBER>`로 이미지를 빌드하고 `docker compose up -d`.
- **Health check**: nginx 443을 거쳐 `/api/v1/ping`을 확인한다 (호스트 80을 보면 배포가 backend를 루프백으로 옮긴 직후 실패한다). liveness 뒤 PostgreSQL·Redis·필수 키를 확인하는 readiness가 성공해야 배포 완료라는 기준은 아직 구현되지 않았다 — 현재 `/api/v1/ping` 검사는 종단 검증일 뿐 인증 출시 기준이 아니다.
- **알림**: 성공/실패 모두 Mattermost 전송. 알림 실패는 배포 결과에 영향을 주지 않는다.

## Jenkins 설정 (2026-08-31 구축 완료)

Jenkins는 서버 1에서 컨테이너로 돌며(`http://<server1-host>:9090`), 다음 구성이 전제다:

```bash
# 재생성 시 이 형태를 유지할 것 (경로 일치·docker 그룹·호스트 별칭이 전부 필수)
docker run -d --name jenkins --restart unless-stopped -p 9090:8080 \
  --group-add <docker-gid> \
  --add-host=host.docker.internal:host-gateway \
  -v /var/jenkins_home:/var/jenkins_home -e JENKINS_HOME=/var/jenkins_home \
  -v /var/run/docker.sock:/var/run/docker.sock \
  jenkins/jenkins:lts-jdk21
```

- **경로 일치** (`/var/jenkins_home` 컨테이너=호스트 동일): Test 스테이지가 워크스페이스를 호스트 데몬에 바인드 마운트하므로, 경로가 다르면 빈 폴더가 마운트된다
- **docker CLI는 컨테이너에 별도 설치** (`docker-ce-cli`, `docker-compose-plugin`) — 컨테이너를 새로 만들면 재설치 필요
- **`--add-host`**: Health check가 `host.docker.internal`로 호스트의 nginx(443)에 접근

등록된 credential 3개:

| ID | 종류 | 내용 |
|---|---|---|
| `prod-env-file` | Secret file | 루트 `.env.example`의 운영 키에 실제 값을 채운 env 파일 |
| `mattermost-webhook` | Secret text | Mattermost incoming webhook URL |
| `gitlab-deploy-token` | Username/password | GitLab Deploy Token (`read_repository`) — 잡의 clone 인증 |

> **키 자료는 Jenkins credential 이 아니라 서버 디렉터리 마운트로 전달한다** (2026-09-11 변경).
> JWT 개인키·JWKS, 전화·위치 keyring, HMAC 키, SOLAPI 자격증명, 행정구역 CSV, redis 비밀번호를
> Secret file credential 6개로 나눠 등록할 계획이었으나, 그러면 값이 Jenkins 를 거쳐야 하고
> 에이전트가 만들 수 없는 수동 단계가 6개 생긴다. 대신 서버의 한 디렉터리를 컨테이너
> `/run/secrets` 에 읽기 전용으로 붙인다 — 아래 "운영 기동에 필요한 설정" 절.
> `prod-env-file` credential 은 `POSTGRES_*` 세 값만 담당한다.

목적별 키는 서로 재사용하지 않는다 (생성 스크립트가 상호 상이를 보장한다). 파기 job은
`MEMBER_ERASURE_CRON`에 따라 단일 실행되며 30일 목표 초과 경보가 배포 완료 조건에 포함된다.

잡 `backend-deploy`: Pipeline script from SCM (Git, 브랜치 `main`, Script Path `Jenkinsfile`).

> **Docker와 UFW 주의**: Docker가 publish한 포트는 UFW를 우회해 즉시 인터넷에 노출된다.
> 컨테이너 포트를 publish하기 전에 반드시 공개 필요 여부를 판단할 것 (DB류는 publish 금지).

`Jenkinsfile`의 `COMPOSE_PROJECT`와 `compose.prod.yml`의 이미지 이름은 프로젝트 코드(`meong-go-spot`)로 설정되어 있다.

## 롤백

Flyway migration이 적용된 뒤에는 애플리케이션 이미지만 이전 버전으로 내리는 것이 안전하지 않을
수 있다. 각 migration MR은 이전 이미지와의 호환 여부와 forward-fix 절차를 기록하고, 배포 전에
암호화된 PostgreSQL 백업을 확인한다. 적용된 migration 파일은 수정하거나 삭제하지 않는다.

이미지 태그가 빌드 번호이므로 이전 이미지가 서버에 남아 있다. 서버에서:

```bash
# 1. 남아 있는 태그 확인
docker images | grep meong-go-spot

# 2. 이전 빌드 번호로 재기동 (이미지 재빌드 없이)
IMAGE_TAG=<이전 빌드 번호> docker compose \
  --env-file <env 파일 경로> \
  -f compose.prod.yml \
  -p meong-go-spot \
  up -d --no-build

# 3. 헬스 체크
curl -sf http://localhost/api/v1/ping
```

- 셸에서 지정한 `IMAGE_TAG`가 env 파일 값보다 우선한다.
- 디스크 정리 시 최근 태그 2~3개는 남긴다. `docker image prune -a`를 무심코 돌리면 롤백 경로가 사라진다.

## 릴리즈 버전

`main` 배포는 **`vx.y.z`** 를 붙인다. 릴리즈 MR 제목이 `release: vx.y.z` 이고
(`docs/mr-guide.md`) Android `versionName` 은 `v` 없는 `x.y.z` 다 — 스토어에 그대로 보이는
값이라 접두사를 넣지 않는다.

| 자리 | 올리는 때 |
|---|---|
| `x` | **1 부터 쓴다 (2026-09-15 팀 결정)** — 앱 정체성 정리(9/12)에서 `versionName` 이 `1.0.1` 로 올라갔고, 릴리즈 버전은 그 값과 같아야 하므로 `v0.3.0` 대신 `v1.0.1` 로 배포한다. 스토어 정식 출시를 `1.0.0` 에 남기던 옛 규칙은 폐기 |
| `y` | 사용자에게 보이는 기능이 늘거나 바뀌는 배포 |
| `z` | 기능 변화 없는 수정·설정·문서만 있는 배포 |

### 세 숫자가 서로 다른 것을 가리킨다

혼동하기 쉬워서 적어둔다.

| 값 | 정하는 주체 | 쓰임 |
|---|---|---|
| `vx.y.z` | 사람이 릴리즈마다 | 릴리즈 MR 제목, git 태그 (`versionName` 은 `v` 없이) |
| `versionCode` | 사람이 릴리즈마다 +1 | **Play Store 가 요구하는 단조 증가 정수.** 같은 값으로는 두 번 업로드할 수 없다 |
| `IMAGE_TAG` | Jenkins `BUILD_NUMBER` | 서버의 backend 이미지 태그. 롤백은 이 번호를 되돌린다 |

`IMAGE_TAG` 는 빌드마다 자동으로 바뀌므로 릴리즈 버전과 일치하지 않는다. **롤백은 `x.y.z` 가
아니라 `IMAGE_TAG` 로 한다** (위 "롤백" 절).

### 절차

1. **앱 코드가 바뀐 배포에 한해** `dev` 에서 `android/app/build.gradle.kts` 의 `versionName` 을
   새 버전으로, `versionCode` 를 +1 로 올린다.

   서버만 바뀌는 배포에서는 **올리지 않는다.** APK 를 새로 빌드·배포하지 않으므로 올려 봐야
   설치된 앱은 옛 버전을 표시하고 릴리즈 버전과 어긋난다. `versionCode` 는 Play Store 업로드와
   짝이지 릴리즈 횟수를 세는 값이 아니다. `main..dev` 에 `android/` 변경이 있는지로 판단한다.

   ```bash
   git diff --name-only origin/main..origin/dev -- android/
   ```
2. **앱 버전 안내 값을 올린다** (`compose.prod.yml`, V1 `GET /api/v1/app/version`).
   `APP_ANDROID_LATEST_VERSION_CODE` 를 새 `versionCode` 로 — 옛 앱에 "새 버전" 권고가 뜬다.
   서버 API 가 옛 앱과 **호환되지 않게** 바뀐 배포(예: 2026-09 전국 필터 400, 소개팅 401)면
   `APP_ANDROID_MIN_VERSION_CODE` 도 그 앱 버전으로 올린다 — 그 미만 앱은 업데이트 전까지 막힌다.
   단, LATEST 는 **스토어 심사가 끝난 뒤** 올려야 한다. 먼저 올리면 사용자가 스토어에서 받을 수 없는
   버전을 권고받는다. 서버만 바뀐 배포에서는 둘 다 건드리지 않는다.
3. 릴리즈 MR 제목을 `release: vx.y.z` 로 올린다 (docs/mr-guide.md)
4. 병합 후 `main` 에 태그를 붙인다 — `git tag -a v0.2.0 -m "…" && git push origin v0.2.0`
5. **앱이 바뀐 배포는 원스토어에 올린다** — Android 담당이 release 서명 빌드를 만들어 상품
   `0001009297` 에 업데이트 등록하고 심사(보통 하루 안)를 받는다. 서버 배포만으로는 사용자 폰의
   앱이 바뀌지 않는다. 심사가 끝나면 2번의 LATEST 를 올리는 서버 배포(설정만 바뀐 `z` 릴리즈)를 한다.

### v1.6.0 병합·배포 런북

이 릴리즈는 AD5·AD6 넘김 기록을 추가한다. backend를 앱보다 먼저 배포할 수 있도록
`GET /api/v1/adoptions`의 기존 공개 조회 계약은 유지하고, 인증된 요청에만 회원별 넘김 제외와 찜
상태를 적용한다. AD5·AD6은 계속 인증 필수다. 원스토어 심사 전 서버를 먼저 배포할 때는 아직 받을
수 없는 1.6.0으로 업데이트를 유도하지 않도록 `LATEST=8`, `MIN=0`을 유지한다.

1. 암호화 PostgreSQL 백업의 최신 성공 시각과 복구 가능 여부를 확인한다. 서버 1·2의 아래 수동
   배포 대상 파일도 각각 타임스탬프 백업한다.
2. `compose.prod.yml`의 `APP_ANDROID_LATEST_VERSION_CODE=8`,
   `APP_ANDROID_MIN_VERSION_CODE=0`을 확인한 뒤 `main`에 병합한다. Jenkins는 backend만 배포하며
   Flyway V13 `adoption_swipe`를 적용한다.
3. Jenkins 성공 뒤 1.5.0 앱의 비로그인 소개팅 목록과 로그인·찜이 기존처럼 동작하는지 먼저
   확인한다. 이어 내부 1.6.0 APK에서 넘김·히스토리를 확인한다.
4. 서버 2 수집기와 서버 1 매칭 엔진·worker 변경은 아래 절차로 별도 배포한다. 이 수동 배포는
   소개팅 히스토리 복구의 선행 조건은 아니다.
5. 원스토어에 서명 APK(`versionName=1.6.0`, `versionCode=9`)를 제출하고 실제 다운로드가 가능해진
   뒤에만 `LATEST=9`로 올린다. 공개 목록 계약을 유지하므로 다른 비호환 변경이 확인되지 않는 한
   `MIN=0`은 그대로 둔다.
6. 결과를 이 문서의 릴리즈 이력에 기록하고 `v1.6.0` 태그를 붙인 뒤 release 변경을 `dev`에도
   반영한다.

#### 서버 2 — 수집기

`data/collector/api.py`와 `data/collector/main.py`가 Jenkins 범위 밖이다. Airflow의
`collector_daily`가 실행 중이지 않을 때 배포한다.

```bash
# 서버 2: 현재 파일 백업
release_backup=~/release-backups/v1.6.0-$(date +%Y%m%d-%H%M%S)
install -d "$release_backup/collector"
cp -a ~/collector-app/api.py ~/collector-app/main.py "$release_backup/collector/"
~/airflow-venv/bin/airflow dags list-runs -d collector_daily --state running

# 저장소가 있는 작업 PC: 새 파일 전송
scp data/collector/api.py data/collector/main.py \
  ubuntu@<server2-host>:~/collector-app/

# 서버 2: 문법·DAG 실행 확인
~/collector-venv/bin/python -m py_compile ~/collector-app/api.py ~/collector-app/main.py
~/airflow-venv/bin/airflow dags trigger collector_daily
~/airflow-venv/bin/airflow dags list-runs -d collector_daily | head -5
```

성공 기준은 DAG 전 단계 `success`, `ingestion_run.status=SUCCEEDED`, 예상하지 않은
`failed_count` 증가 없음이다. 실패하면 백업한 두 파일을 `~/collector-app/`으로 되돌리고 다음
실행 전에 문법 검사를 다시 통과시킨다. 수집·적재는 멱등이므로 실패한 DAG는 원인 제거 후 재실행한다.

#### 서버 1 — 매칭 엔진·worker

`data/match_engine/engine.py`와 `data/matching_worker/worker.py`도 Jenkins가 배포하지 않는다.
worker가 새 작업을 가져가기 전에 엔진을 먼저 교체하고 `/health`가 정상인 것을 확인한다.

```bash
# 서버 1: 현재 파일 백업
release_backup=~/release-backups/v1.6.0-$(date +%Y%m%d-%H%M%S)
install -d "$release_backup/match-engine" "$release_backup/matching-worker"
cp -a ~/match-engine/engine.py "$release_backup/match-engine/"
cp -a ~/matching-worker/data/matching_worker/worker.py "$release_backup/matching-worker/"
sudo systemctl stop matching-worker

# 저장소가 있는 작업 PC: 새 파일 전송
scp data/match_engine/engine.py ubuntu@<server1-host>:~/match-engine/
scp data/matching_worker/worker.py \
  ubuntu@<server1-host>:~/matching-worker/data/matching_worker/

# 서버 1: 문법 검사 뒤 엔진 → worker 순서로 재기동
~/ai/.venv/bin/python -m py_compile ~/match-engine/engine.py
~/ai/.venv/bin/python -m py_compile ~/matching-worker/data/matching_worker/worker.py
sudo systemctl restart match-engine
for attempt in {1..30}; do
  curl -fsS http://127.0.0.1:8091/health && break
  sleep 1
done
curl -fsS http://127.0.0.1:8091/health
sudo systemctl restart matching-worker
systemctl is-active match-engine matching-worker
journalctl -u match-engine -u matching-worker -n 20 --no-pager
```

성공 기준은 두 unit 모두 `active`, 엔진 `/health` 200과 유효한 `snapshot.count`, 신규 분석 한 건의
`match_run.status=SUCCEEDED`다. 실패하면 각 백업 파일을 원래 위치로 복원하고 위 순서로 두 unit을
재기동한다. 새 worker가 만든 상태를 옛 worker가 읽을 수 있는지 확인하기 전에는 코드만 되돌리고
DB 행을 삭제하지 않는다.

#### backend·앱 스모크와 롤백

- `/api/v1/ping`과 `/api/v1/app/version`을 확인한다. 원스토어 공개 전 Android 응답은
  `latestVersionCode=8`, `minSupportedVersionCode=0`이어야 한다.
- 1.5.0 앱 또는 동일 요청으로 비로그인 `GET /api/v1/adoptions`가 200인지 확인한다. 로그인한
  1.5.0 앱에서는 목록과 찜 추가·해제가 계속 동작해야 한다.
- 로그인한 1.6.0 앱에서 소개팅 카드를 좋아요로 넘기고, 일반 넘김도 한 뒤 히스토리를 연다. 두 항목이
  최신순으로 보이고 좋아요 항목만 하트가 켜져 있어야 한다. 화면 재진입 뒤 넘긴 항목이 카드 덱에서
  제외되고, 히스토리에서 좋아요를 해제해도 넘김 기록은 남아야 한다.
- 본인 활성 `잃어버렸어요` 게시물로 분석을 요청해 완료까지 확인하고, 후보 목록에 동일 개체 확정이
  아니며 사진·날짜·지역을 직접 확인해야 한다는 안내가 보이는지 확인한다.
- backend 장애 시 이전 운영 이미지 `IMAGE_TAG=13`으로 재기동할 수 있다. V13은 새 테이블·인덱스만
  추가하므로 이전 이미지가 무시할 수 있지만, 1.6.0 앱의 AD5·AD6은 동작하지 않는다. 따라서 이
  롤백은 서비스 복구용 임시 조치이고 소개팅 히스토리 회복은 forward-fix를 우선한다. migration
  파일이나 `adoption_swipe` 행은 삭제하지 않는다.
- 앱을 이미 배포한 뒤에는 서버만 되돌려 1.5.0 계약으로 완전히 복구할 수 없다. 데이터 수동 배포는
  위 백업 파일 복원으로 각각 되돌리고, 원스토어·backend·데이터 프로세스의 실제 상태와 담당자를
  장애 기록에 함께 남긴다.

태그는 강제 장치가 없으니 빠뜨리기 쉽다. 태그가 없으면 "그때 배포한 코드" 를 가리킬 방법이
`IMAGE_TAG` 뿐이고, 그건 이미지를 지우면 사라진다.

`0.x` 로 시작했던 이유 — SemVer 는 `0.y.z` 를 "초기 개발" 로 정의해 `1.0.0` 을 정식 출시에 남겼다. 2026-09-12 앱 정체성
정리에서 `versionName` 이 `1.0.1` 이 됐고, 릴리즈 버전과 `versionName` 이 어긋나는 것이 더 나쁘다고 보아 2026-09-15 부터
`1.0.x` 를 쓴다. 앱 스토어 노출 여부와 무관한 내부 식별자다.

### 이력

| 버전 | 날짜 | 내용 |
|---|---|---|
| (없음) | 2026-08-31 | 첫 배포. backend+postgres 스캐폴드. `versionName` 은 초기값 `0.1.0` 이었고 올리지 않았다 |
| `v0.2.0` | 2026-09-11 | TLS 종단, 기동 설정 17개, Redis, 사진 WebHDFS 경로, 행정구역 데이터, Android 지역 목록. 유사도 분석·가입 SMS 는 노출하지 않는다 |
| `v1.0.1` | 2026-09-15 | 가입 인증 응답 수정(!160), 공공데이터 적재기·지역 해석·대괄호 URL·개·고양이만(!155·!164~!166), 사진 캐시 프록시+앱 디스크 캐시(!167), nginx 마무리(!169), 매칭 엔진·worker(!174), 분실 신고 스냅샷·클라이언트(!175), 공공 분실 신고 PUBLIC/LOST + **V3**(!176). `versionCode` 2 → 3(앱 변경 있음), `versionName` 1.0.1 유지. **배포 순간 JWT issuer/audience/client id 가 `fitthepet-*` → `meonggocuisine-*` 로 바뀌어 기존 토큰이 전부 무효 → 재로그인 필요**(앱은 client id 를 보내지 않고 서버가 발급 토큰에 넣으므로 로그인은 막히지 않는다). 옛 `v0.3.0` 계획은 이 릴리즈에 흡수. **배포 결과**: Jenkins #8(`IMAGE_TAG=8`, 롤백 7) 02:21 UTC, Flyway V3 적용, `lost-loader.timer` 활성화 첫 적재 136건(개 110·고양이 26, 축종 미상 27 제외), 운영 목록에 `PUBLIC_LOST` 노출 확인 |
| `v1.1.0` | 2026-09-15 | **마이페이지**(헤더 사람 아이콘, 닉네임·비밀번호 변경, 로그아웃, 계정 삭제 — !183·!184) + 본인 계정 API A5~A8, **홈 인사이트 카드 5장**(10초 자동 전환·원형 — !186·!187·!188·!189) + D3 `/insights` + **V4 `dashboard_stat`**, 공공 분실 신고 포털 링크(!181), 후보 탭→상세(!182), 홈 일일 요약 표기 수정(!180), 잃어버렸어요 지역 필터·복귀 수정(!185·!190), 홈 지역 동기화(!192). `versionCode` 3 → 4, `versionName` 1.0.1 → 1.1.0(사용자 기능 추가 → y 자리). JWT·compose 변경 없음 → 재로그인 없음. **배포 결과**: Jenkins #9(`IMAGE_TAG=9`, 롤백 8) 06:32 UTC, Flyway V4 적용(0.03초), `/insights` 200(마포구: 마감 임박 3·주간 입소 6·실종 30일 1), `shelter-outcomes.timer` 활성화(다음 실행 월 00:40 KST) + 1회 실행 13초 → 카드 ④ 반환 12.1%·입양 29.2%·공고 10.4일 노출, 태그 `v1.1.0`. 재로그인 없음 |
| `v1.2.0` | 2026-09-17 | **지역 범위 3단계**(시·군·구 5자리 / 시·도 전체 2자리 / 전국 없음 — 서버 !203 + 앱 지역 선택 전국·전체 !204), 유사도 분석 버튼 게이트 해제(!201), 상세 사진 전체 화면(!202), 팀원: 입양 카드 스택, 앱 재시작 로그인 유지 + 세션 회원 복구, 헤더 채팅 진입, **탈퇴 회원 데이터 파기 job**(V5·V6, `MEMBER_PHOTO_ERASURE_POLL_INTERVAL` 기본 PT1M), 개인정보 처리방침 페이지 `GET /privacy`(permitAll, nginx `location /` 로 통과), 순위 평가 하네스(DATA). `versionCode` 4 → 5, `versionName` 1.1.0 → 1.2.0. **주의**: dev 앱은 이미 전국·전체 요청을 보내므로 이 배포 전까지 운영 서버가 400 을 준다(순서가 뒤집힌 상태). JWT·시크릿 변경 없음 → 재로그인 없음. **배포 결과**: Jenkins #10(`IMAGE_TAG=10`, 롤백 9) 02:13 UTC, Flyway V5·V6 적용(0.04초), 기동 13.8초, `/posts?type=SHELTERING`(전국)·`regionCode=11` 200, `/insights` 전국 조각(마감 임박 575·주간 1,193), `/privacy` 200, 태그 `v1.2.0`. **후속 결함**: 앱 모델이 전국 조각의 `regionCode: null` 을 못 읽어 카드 5장이 준비 중으로 보임 → 앱 수정 !209(서버 무관). 재로그인 없음 |
| `v1.5.0` | 2026-09-23 | **계정 찾기 A9**(서버 !276 · 앱 !277 — 휴대전화 인증으로 아이디 확인·비밀번호 재설정, `recovery:` 키 공간, 미가입에도 202, 재설정 시 전 세션 폐기; MVP 제외였다가 사용자 결정으로 포함), **사진 EOI 뒤 데이터 허용**(!275 — 울트라 HDR 게인맵·MPF·모션 포토가 붙은 폰 카메라 JPEG 를 첫 이미지만 저장; 앱은 제출 단계에도 사진 오류 표시), 앱 사진 최소 64px(!271), 비밀번호 공백 즉시 오류(!274), 게시물 수정 폼(팀원 `feat/post-edit-form`). 매칭 엔진 DB 재접속(!272)은 서버 파일 교체·재시작으로 09-23 별도 반영됨. `versionCode` 7 → 8, `versionName` 1.4.0 → 1.5.0. **마이그레이션 없음, 새 필수 설정 없음, compose 변경 없음** → 롤백은 `IMAGE_TAG=12` 재기동만. `APP_ANDROID_*` 는 LATEST 6·MIN 0 유지(원스토어에 1.4.0 도 아직 미등록). JWT·시크릿 변경 없음 → 재로그인 없음. **배포 결과**: Jenkins #13(`IMAGE_TAG=13`, 롤백 12) 10:58 UTC, 스키마 11 그대로(마이그레이션 없음), 기동 13.8초, `/ping` 200, `/app/version` 200(latest 6·min 0), `/adoptions` 200, `POST /auth/account-recovery/phone-verifications` 미가입 번호 202, 태그 `v1.5.0`. postgres 컨테이너는 재생성되지 않아(Up 5일) 엔진 재접속 경로는 이번 배포에서 발동하지 않음. 재로그인 없음 |
| `v1.4.0` | 2026-09-23 | **홈·소식**: 홈 참고 디자인(스크롤 없는 한 화면, 파스텔 카드)·소식 띠 + 보호소 소식 화면(!248) · 소개팅 결과 통계 한 줄(!249). **QA 묶음**: 사진 최소 크기 64px(서버 !228)·보호 등록 카메라 촬영(!229)·실종 시각 오전/오후·시·분 + 실시간 검증(!230/!232)·공공 분실 신고 링크 성별·시도 축소(!231)·채팅 욕설 마스킹(!233)·글자 수 한도(!234/!235)·채팅 본문 보기(!235)·드롭다운·기본 그림·달력(!236~!238)·**사진 안내 창**(!266). **앱 버전 안내**: V1 `GET /api/v1/app/version` + 앱 업데이트 창(!241/!242, `APP_ANDROID_*` 은 이 배포에서 LATEST 6·MIN 0 유지). **입양**: 전국·시·도 조회(!252/!253)·화면 상단 지역 변경·관심 목록. **채팅 푸시**(V10 `auth_session` 푸시 열·`chat_notification_outbox`, V11 인덱스, 발송은 `FCM_CHAT_ENABLED=false` 로 꺼짐)·증분 폴링·목록 갱신·읽음 UI. **게시물**: 상세 비로그인 공개(!… 9/21)·목록 정렬·종 필터·4단계 등록 위저드·상세 디자인·"찾고 있어요→잃어버렸어요" 명칭. **로그인**: 화면 디자인·로그인 ID 중복 확인 A0-3. **되돌림**: 이메일 가입 전환(V12)은 revert(!268) — 가입 인증은 휴대전화(SOLAPI) 유지. `versionCode` 6 → 7, `versionName` 1.3.0 → 1.4.0. **배포 전 필수**: 서버에 `add-fcm-token-secrets.py` 로 FCM 토큰 키 2개 추가 — 없으면 새 이미지가 기동하지 않는다. **롤백**: `IMAGE_TAG=11`. V10·V11 은 열 추가·새 테이블·인덱스만이라 옛 이미지와 호환. JWT·compose 시크릿 변경 없음 → 재로그인 없음. **배포 결과**: Jenkins #12(`IMAGE_TAG=12`, 롤백 11) 02:18 UTC, Flyway V10·V11 적용(0.06초), 기동 14.4초, FCM 토큰 키 기동 통과(사전 추가), `/api/v1/ping` 200, `/app/version` 200(latest 6·min 0), `/posts/{id}` 비로그인 200, `/adoptions`(전국·`11`) 200, `/insights` 200, 태그 `v1.4.0`. 재로그인 없음 |
| `v1.3.0` | 2026-09-18 | 팀원 릴리즈 묶음: **입양 탐색**(후보 목록·관심 표시 API !211 + **V7 `adoption_favorite`**, 상태 읽기 안정화 !214, 앱 관심 버튼 !215 · 소개팅 화면 API 연결 !219, `GET /api/v1/adoptions` permitAll), **채팅 읽음 상태**(!218 + **V8** `chat_room` 읽음 위치 열 2개·인덱스, 읽음·푸시 계약 문서 !212·!216), **개인정보 수집·이용 고지·동의 기록**(!220 + **V9** `member.privacy_collection_agreed` 신설·백필 + CHECK 2개, 앱 가입 화면 동의), FAKE SMS 가입용 테스트 번호 `011-0000-0000`(!217 — `SMS_PROVIDER=FAKE` 에서만 동작, 운영 SOLAPI 무관), 순위 평가 교차 분석·결정 이력(DATA !213), 홈 인사이트 전국 응답 파싱 수정(!209, v1.2.0 후속). `versionCode` 5 → 6, `versionName` 1.2.0 → 1.3.0(사용자 기능 추가 → y 자리). **주의**: dev 앱(!219)이 이미 `GET /adoptions` 를 부르므로 이 배포 전까지 운영 서버가 401 을 준다(순서 뒤집힘, v1.2.0 과 같은 양상). **롤백 주의**: V9 CHECK 가 ACTIVE 회원에 `privacy_collection_agreed = true` 를 요구하는데 v1.2.0 코드는 이 열을 모르므로 `IMAGE_TAG=10` 으로 되돌리면 새 가입이 제약 위반으로 실패한다 — 되돌려야 하면 `ck_member_active_privacy_collection_agreed` 를 먼저 떨어뜨린다. JWT·compose 시크릿 변경 없음 → 재로그인 없음. **배포 결과**: Jenkins #11(`IMAGE_TAG=11`, 롤백 10) 06:31 UTC, Flyway V7·V8·V9 적용(0.11초), 기동 15.5초, `/adoptions?regionCode=11440` 200(9건), `/posts?type=SHELTERING` 전국·`regionCode=11` 200, `/insights` 200, 태그 `v1.3.0`. **후속 확인**: `/adoptions` 는 스펙대로 5자리 시·군·구만 받는다(2자리·없음 → 400). 앱 소개팅 화면이 통일 지역 코드를 그대로 보내므로 전국·시·도 전체를 고른 상태에서는 400 을 받는다 — 입양 담당 확인 필요. 재로그인 없음 |

## TLS 종단 (2026-09-11 구축)

`https://api.meonggo.shop` — Android 릴리스 빌드가 쓰는 종단이다. 릴리스 빌드는 평문을
허용하지 않으므로(`usesCleartextTraffic` 없음, `network_security_config` 없음) 이 종단 없이는
스토어 배포가 불가능하다.

### 왜 팀 도메인을 샀는가

기관 제공 도메인 `<server1-host>` 로는 Let's Encrypt 발급이 되지 않는다. secondary validation
단계에서 `DNS problem: networking error looking up A/AAAA` 가 재현되며, 다음을 모두 배제했다 —
위임 부재·등록소 위임 불일치·DNSSEC·CAA·TCP/EDNS·지리적 라우팅·일시성(20분 뒤 운영·스테이징 CA
양쪽에서 재현). 같은 서버에서 `<server1-public-ip>.sslip.io` 로는 실제 인증서를 받아 **우리 쪽 구성이
정상임을 증명**했다. 남은 가설은 기관 공용 도메인 Route 53 존의 요청 제한이다.

그래서 팀 도메인 `meonggo.shop` 을 Cafe24 에서 구매했다 (만료 2027-09-11, 갱신 71,200원,
자동갱신 꺼짐). 네임서버는 Cafe24 호스팅 네임서버, `api` A 레코드 → `<server1-public-ip>`.

### 구성 (2026-09-14 마무리)

nginx 가 **80·443 을 모두** 잡는다. 443 은 `upstream backend`(`127.0.0.1:8080`)로 프록시하고,
80 은 평문 API 를 서빙하지 않는다 — ACME 챌린지 경로(`/.well-known/acme-challenge/`)만
`/var/www/certbot` 에서 파일로 응답하고 나머지는 HTTPS 로 301 한다. backend 는 `compose.prod.yml`
대로 루프백 8080 에만 바인딩된다.

2026-09-11 구축 때는 backend 가 80 을 직접 쓰고 있어서 nginx 는 443 만 잡고 upstream 을
`8080 우선 / 80 backup` 으로 이중화했으며, 인증기가 `standalone` 이라 갱신 래퍼가 컨테이너를
잠시 내렸다. 이 전환 구성은 backend 가 8080 으로 옮겨진 뒤 2026-09-14 에 걷어냈다(이슈 -146).

### 서버 구성 파일

| 서버 경로 | 레포 원본 | 역할 |
|---|---|---|
| `/etc/nginx/sites-available/api.meonggo.shop` | `infra/nginx/api.meonggo.shop.conf` | 80 리다이렉트·ACME, 443 종단·프록시, 이미지 캐시 |
| `/etc/letsencrypt/renewal/api.meonggo.shop.conf` | (certbot 이 관리, 레포 없음) | `authenticator = webroot`, `webroot_path = /var/www/certbot`, deploy hook `systemctl reload nginx` |
| `/var/www/certbot/` | — | ACME 챌린지 webroot (비어 있음) |

`renew-cert.sh` 와 `certbot.service` drop-in 은 2026-09-14 제거했다 — 서버에서도 지웠다.

서버로 옮길 때는 scp 후 md5 를 대조한다. `sites-enabled/default` 는 제거했다.

설정에서 판단한 것:

| 항목 | 값 | 근거 |
|---|---|---|
| `upstream` | `127.0.0.1:8080` 하나 | 전환용 `80 backup` 은 backend 가 8080 으로 옮겨진 뒤 제거했다(2026-09-14). backend 가 내려가면 443 은 502 다 — 배포 중 Spring 기동 14~28초 동안이 그렇다 |
| 80 서버 블록 | 챌린지만 파일 응답, 나머지 301 | 평문 API 노출 없음. `location ^~` 로 정규식 location 보다 먼저 잡는다 |
| `client_max_body_size` | `60M` | `application.yml` 의 `max-request-size` 가 정확히 52428800B(50MB). nginx 가 먼저 413 을 내면 애플리케이션 오류 규약을 쓸 수 없다 |
| `X-Forwarded-For` | `$remote_addr` (덮어씀) | `backend/docs/member-signup-guide.md` — 신뢰 프록시는 외부 값을 덮어쓰고 실제 클라이언트 IP 하나만 전달한다. 관용적인 `$proxy_add_x_forwarded_for` 는 클라이언트가 보낸 값에 덧붙여 IP 위조를 허용한다 |
| `Forwarded` | `""` | RFC 7239 헤더도 클라이언트가 보낼 수 있어 지운다 |
| `listen 443 ssl http2` | 구형 표기 | nginx 1.24.0 이다. `http2 on;` 은 1.25+ 문법이라 여기서는 쓸 수 없다 |
| OCSP stapling | 끔 | Let's Encrypt 가 인증서에서 OCSP responder URL 을 없앴다(CRL 전환). 켜면 기동마다 경고만 남는다 |
| TLS 버전 | 1.2·1.3 | Android minSdk 26 은 TLS 1.2 를 지원하고 1.3 은 Android 10 부터다 |

### 인증서 갱신

인증기는 **`webroot`** 다. certbot 이 `/var/www/certbot/.well-known/acme-challenge/` 에 토큰 파일을
쓰고, 80 번 서버 블록이 그 경로를 파일로 응답한다. 갱신 중 nginx 도 backend 도 건드리지 않아
**무중단** 이다. 갱신이 성공하면 deploy hook `systemctl reload nginx` 가 새 인증서를 물린다
(reload 는 연결을 끊지 않는다).

`nginx` 플러그인 대신 `webroot` 를 고른 이유: nginx 인증기는 챌린지 때 우리 설정 파일에 임시
서버 블록을 써 넣고 reload 한 뒤 되돌린다. 레포에서 관리하는 파일을 certbot 이 고쳐 쓰는 것을
피했고, 챌린지 응답을 위해 필요한 것은 정적 경로 하나라 webroot 로 충분하다.

패키지가 등록한 `certbot.timer`(하루 2회) → `certbot.service`(`certbot -q renew`)를 **그대로** 쓴다.
drop-in 없음.

```bash
# 갱신 경로를 실제로 시험한다 (스테이징 CA, 챌린지·훅까지 수행)
sudo certbot renew --dry-run
# 다음 실행 시각 · 마지막 결과
systemctl list-timers certbot.timer --no-pager ; journalctl -u certbot.service -n 5 --no-pager
```

인증기 전환은 `certbot reconfigure --cert-name api.meonggo.shop -a webroot -w /var/www/certbot
--deploy-hook 'systemctl reload nginx'` 로 했다 — 스테이징 CA 로 dry-run 이 통과해야만 renewal conf 를
바꾼다. 실제 인증서는 다시 받지 않았다(만료 2026-12-10, 30일 전부터 자동 갱신).

**전환 전 구성이 남긴 교훈**: `renew-cert.sh` 는 `certbot renew` 가 실제로 갱신하는지와 무관하게
타이머가 돌 때마다(하루 2회) backend 컨테이너를 내렸다 — `/var/log/cert-renew.log` 에 매일
`backend restarted` 가 남아 있었다. 즉 잠정 구성 동안 매일 두 번 수십 초씩 API 가 내려가고
있었다. 래퍼로 우회할 때는 "우회가 몇 번 실행되는가"를 같이 봐야 한다.

## 공공 이미지 캐시 프록시 (2026-09-14 구축)

`https://api.meonggo.shop/img/{shelter|loss}/YYYY/MM/<파일>` — 앱이 공공 보호동물 사진을 받는 경로다.
원본은 `openapi.animal.go.kr/openapi/service/rest/fileDownloadSrvc/files/…` 이고 DB(`animal_photo.storage_uri`)와
API 응답(`thumbnailUrl`)에는 **원본 URL 그대로** 남는다. 바꾸는 곳은 앱의 `ApiImageUrlResolver` 하나다
(`BuildConfig.IMAGE_PROXY_BASE_URL`, 릴리스 기본 `https://api.meonggo.shop/img/`, 로컬 개발은 비움 = 원본 직접).

### 왜 두었나 — 2026-09-14 실측

| 측정 | 값 |
|---|---|
| 원본 TTFB (이 PC → 원본, 60장) | 중앙값 0.71s · p90 6.0s · 최대 11.3s — 로딩 시간의 82% 가 기다림 |
| 원본 TTFB (서버 1 → 원본, 15장) | 중앙값 0.40s · 최대 3.6s — 네트워크가 아니라 원본 서버가 느리다 |
| 파일 | 중앙값 365KB · 1207×1081 · 목록 한 페이지 10장 ≈ 3.6MB |
| 원본 응답 헤더 | `Cache-Control`·`ETag`·`Last-Modified` **없음**, `application/octet-stream` + `attachment` |
| 원본 404 | 60장 중 3장 (~5%) |
| 프록시 | MISS TTFB 0.15s → **HIT 0.035s**, 원본과 바이트 동일 |

원본이 캐시 헤더를 주지 않으므로 앱(Coil 2.7, 기본 `respectCacheHeaders=true`)은 스크롤로 돌아올 때마다
다시 받았다. 앱도 `respectCacheHeaders(false)` + 디스크 캐시 256MB 로 바꿨지만(`ApiImageLoader.kt`),
첫 로딩과 사용자 간 공유는 서버 캐시만 해결한다 — 한 사진은 전 사용자 통틀어 원본에서 한 번만 받는다.

### 설정 (`infra/nginx/api.meonggo.shop.conf`)

- `proxy_cache_path /var/cache/nginx/img … max_size=8g inactive=30d` — 현재 공공 사진 15,520장 × 365KB ≈ 5.5GB 전량이 들어간다
- `location ~* "^/img/(?<image>(shelter|loss)/…\.(jpg|jpeg|png))$"` — 경로 모양을 정규식으로 못 박아 **열린 프록시가 아니다**. `~*`(대소문자 무시)인 이유: 앱 리졸버는 원본 경로 접두만 보고 프록시로 넘기므로 `.JPG` 파일이 오면 정규식에 안 걸려 `location /`(백엔드)로 가 401 이 된다. 저장된 15,520 URL 은 전부 소문자지만(2026-09-14 조회) 앞으로 올 파일명은 통제할 수 없다.
  다른 경로는 `location /` 로 떨어져 백엔드가 401 을 낸다. `limit_except GET HEAD`.
  정규식은 **따옴표로 감싼다** — 안 감싸면 nginx 가 `{4}` 의 중괄호를 블록으로 읽어 기동에 실패한다(실제 발생)
- `rewrite … break; proxy_pass https://openapi.animal.go.kr;` — `proxy_pass` 에 변수를 쓰면 런타임 DNS(`resolver`)가 필요해
  502 "no resolver defined" 가 난다(실제 발생). 호스트를 고정해 기동 시 한 번 해석한다 — **원본 IP 가 바뀌면 `reload`**
- `proxy_ignore_headers Cache-Control Expires` + `proxy_cache_valid 200 30d / 404 10m`, `proxy_cache_lock`, `use_stale`
- `proxy_hide_header Content-Disposition` 만 지운다. `Content-Type` 은 그대로(jpg·png 혼재)
- 응답에 `Cache-Control: public, max-age=2592000, immutable` 과 `X-Cache-Status` 를 붙인다
- 대괄호 파일명(`…518[1].jpg`, 전체의 9%)은 `%5B%5D` 로 들어와 `$uri` 에서 디코드되므로 정규식이 `[ ]` 를 허용한다

### 운영

```bash
# 캐시 상태 분포 · 디스크
sudo awk '{print $9}' /var/log/nginx/img.access.log | sort | uniq -c ; sudo du -sh /var/cache/nginx/img
# 캐시 비우기 (원본 사진이 바뀌는 일은 없다 — 형식 실수 때만)
sudo rm -rf /var/cache/nginx/img/* && sudo systemctl reload nginx
```

2026-09-14 구축 직후 ACTIVE 첫 사진 4,392장을 프리웜했다(`~/img-prewarm/run.sh`, 원본 동시 4개).

### 이용조건

우리가 공공 이미지를 **저장해 서빙**하는 셈이다. 2026-09-03 에 남긴 미결 사항(포털 API "제한 없음" vs
animal.go.kr "다량저장 금지·출처명시") 과 맞물린다. 캐시 30일·출처 표기로 최소선을 지키되, 팀원이 확인하기로
했던 054-912-0525 문의는 여전히 열려 있다. 리사이즈(C 단계, `image_filter` 동적 모듈은 설치돼 있다)는 그 답을
보고 결정한다.

## 운영 기동에 필요한 설정

`main` 은 `dev` 보다 169 커밋 뒤처져 있고(595 파일) 그 사이 backend 가 기동 시 요구하는 설정이
늘었다. backend 는 `required()`·`requiredDuration()`·`requiredIdentifier()` 로 값을 읽어
**없거나 정책과 다르면 빈 생성에 실패한다.** 해당 `@Configuration` 4개
(`SessionTokenConfiguration`, `SignupConfiguration`, `PostLocationConfiguration`,
`PushDeviceConfiguration` — v1.4.0 채팅 푸시부터)에 `@Profile` 도 `@ConditionalOn` 도 없어 무조건
실행된다. `PushDeviceConfiguration` 은 `FCM_CHAT_ENABLED=false` 여도 토큰 보호 키를 요구한다.

전체 목록은 19개다(2026-09-11 17개 + v1.4.0 FCM 토큰 키 2개). 전달 경로는 셋으로 나눈다.

### ① 정책 고정값 — `compose.prod.yml` 리터럴

비밀이 아니다. 코드가 값을 요구하면서 **하드코딩된 상수와 일치하는지까지 검사**하므로 바꾸면
기동이 실패한다. 리터럴로 두어 변경이 리뷰에 드러나게 한다.

| 키 | 값 |
|---|---|
| `AUTH_ACCESS_TOKEN_TTL` | `PT15M` |
| `AUTH_REFRESH_TOKEN_TTL` | `P30D` |
| `AUTH_JWT_CLOCK_SKEW` | `PT30S` |
| `AUTH_JWT_ISSUER` | `meonggocuisine-auth` |
| `AUTH_JWT_AUDIENCE` | `meonggocuisine-api` |
| `AUTH_JWT_CLIENT_ID` | `meonggocuisine-android` |

같은 자리에 신뢰 프록시도 둔다. 정책 고정값은 아니지만 `compose.prod.yml` 의 고정 서브넷에서
나오는 값이라 원인과 같은 파일에 있어야 한다.

| 키 | 값 | 근거 |
|---|---|---|
| `AUTH_TRUSTED_PROXY_CIDRS` | `172.18.0.1/32` | 2026-09-11 실측 — 호스트 루프백에서 발행 포트로 들어오면 컨테이너가 보는 peer 는 docker 브리지 게이트웨이 `172.18.0.1` 이고, 비루프백·외부 트래픽은 출발지 IP 가 보존된다. 즉 nginx 경유만 신뢰된다 |

`ClientIpResolver` 는 **신뢰 peer 면 `X-Forwarded-For` 가 정확히 1개일 것을 요구하고 아니면
요청을 거부한다.** 그래서 nginx 가 `$remote_addr` 로 헤더를 덮어써야 한다 — 관용적인
`$proxy_add_x_forwarded_for` 는 클라이언트가 보낸 값에 덧붙여 콤마 목록을 만들므로 모든 요청이
거부된다. 이 값을 비우면 반대 문제가 생긴다. 모든 요청의 클라이언트 IP 가 `172.18.0.1` 하나로
뭉쳐 IP 기준 제한이 전체 사용자를 한 덩어리로 차단한다.

### ② 우리가 생성하는 키 자료 — 마운트된 `prod.yml`

`infra/scripts/setup-prod-secrets.py` 가 서버에서 만든다. 값은 출력하지도, 레포에 넣지도 않는다.
기존 디렉터리가 있으면 아무것도 덮어쓰지 않고 멈춘다 — 키를 교체하면 그 키로 암호화한
전화번호·위치를 읽을 수 없게 된다.

| 키 | 담기는 곳 |
|---|---|
| `AUTH_JWT_ACTIVE_KID` · `AUTH_JWT_PRIVATE_KEY_PATH` · `AUTH_JWT_PUBLIC_JWKS_PATH` | `prod.yml`, `jwt-private.pem`, `jwt-public-jwks.json` |
| `AUTH_LOGIN_ID_HMAC_KEY_V1` · `PHONE_LOOKUP_HMAC_KEY_V1` · `PHONE_OTP_HMAC_KEY_V1` · `AUTH_IP_HMAC_KEY_V1` | `prod.yml` |
| `PHONE_DATA_ENCRYPTION_KEYRING_PATH` · `LOCATION_DATA_ENCRYPTION_KEYRING_PATH` | `phone-keyring.json`, `location-keyring.json` |
| `FCM_TOKEN_ENCRYPTION_KEYRING_PATH` · `FCM_TOKEN_LOOKUP_HMAC_KEY_V1` (v1.4.0~) | `fcm-token-keyring.json`, `prod.yml` — **기존 서버에는 `infra/scripts/add-fcm-token-secrets.py --dir <비밀 디렉터리>` 로 추가**(setup 스크립트는 기존 디렉터리를 건드리지 않는다). 채팅 푸시 토큰 암호화·조회용. 새 설치는 setup 스크립트가 함께 만든다 |
| `SPRING_DATA_REDIS_URL` (비밀번호 포함) | `prod.yml`, `redis.conf` — 아래 "Redis" 절 |

코드가 검사하는 제약 — 스크립트가 지킨다.

- 두 키링은 속성이 정확히 2개(`currentKid`, `keys`)여야 하고 `keys` 는 1~2개다
- 모든 대칭키는 서로 달라야 한다 (`PhoneProtection.requireDistinctKeys`, 위치 키 ≠ 로그인 키)
- JWKS 의 활성 `kid` 공개키 모듈러스가 개인키와 일치해야 한다
- 디코딩한 키는 32바이트 이상이어야 한다

### ③ 외부 자료 — 2026-09-11 확보

#### 행정구역 CSV

원본은 **행정표준코드관리시스템**의 "법정동 코드
전체자료"다. CP949, 탭 구분, 컬럼 3개(법정동코드 10자리 / 법정동명 / 폐지여부).

    https://www.code.go.kr/stdcode/regCodeL.do  →  "법정동 코드 전체자료" 버튼

"사용자 검색자료" 버튼은 20,000건 제한이 있어 쓸 수 없다 (자료가 20,560건이다).
다운로드는 폼 POST + JS 확인창이라 스크립트로 받을 수 없다 — 사람이 브라우저로 받아야 한다.

변환과 검증은 레포 스크립트가 한다.

```bash
python data/reference/build_region_codes.py "법정동코드 전체자료.txt"     --version kldc-20260911-sgg --out infra/reference/region-codes.csv

python data/reference/verify_region_codes.py infra/reference/region-codes.csv     --version kldc-20260911-sgg --sha256 <출력된 값>     --android android/app/src/main/java/com/hotdog/meonggocuisine/feature/community/ui/RegionSelectionViewModel.kt
```

법정동코드는 `시도(2) + 시군구(3) + 읍면동(3) + 리(2)` 구조라 앱이 요구하는 5자리
`regionCode` 는 시군구 레벨 코드의 앞 5자리와 그대로 일치한다.

**읍면동 행은 만들지 않는다.** `emdCode` 는 선택이고(`docs/api-spec.md`) `RegionCodeCatalog` 는
읍면동 행이 0개여도 검증을 통과한다. 전국 읍면동 4만 행을 검수 대상에 올릴 이유가 없다.

**폐지된 시군구도 넣지 않는다.** `RegionCodeCatalog` 는 폐지 코드와 미등록 코드에 같은 거부
응답을 내므로(둘 다 "지원하는 시·군·구를 선택해 주세요") 넣어도 동작이 같고 검수 대상만
243행 늘어난다. 필요하면 `--include-abolished` 로 넣을 수 있다.

현재 배포본: **269행**, 15,451바이트, 전국 활성 시군구 전체.

| 설정 | 값 |
|---|---|
| `REGION_CODE_DATA_VERSION` | `kldc-20260911-sgg` |
| `REGION_CODE_DATA_SHA256` | `a2ca97c6e1c40cb18946706850c16951da4449b24a87025261005202099dbb31` |

#### SOLAPI

`solapi.properties` 는 `apiKey`·`apiSecret`·`senderNumber` 가 비어 있지 않고 `senderNumber` 가
숫자 8~15자리이면 기동한다. `SignupConfiguration` 은 파일을 읽고 **형식만** 검사하며 기동 시
SOLAPI 에 접속하지 않는다(`@PostConstruct` 도 없다). 첫 배포는 자리표시자로 열었고, 이후 실제
계정으로 교체됐다(2026-09-22 운영자 확인 — 가입 SMS 수신 정상).

**발신번호를 바꾸려면**(예: 070) SOLAPI 콘솔에서 그 번호를 먼저 등록·승인받은 뒤 같은 경로의 파일에서
`senderNumber` 만 바꾸고 backend 컨테이너를 재기동한다. 070 은 통신사가 매개번호를 차단할 수 있어
통신사 정상 할당 회선인지 먼저 확인한다(2026-09-23 보류). prod 프로파일에서 `SMS_PROVIDER` 는 기본 `SOLAPI` 이고 `FAKE` 는
명시적으로 거부되므로(`Fake SMS requires dev/test profile without prod`) 우회할 수 없다.
`FAKE` 를 prod 에서 허용하도록 코드를 고치는 것은 고정 OTP 로 누구나 가입할 수 있게 만드는
일이므로 하지 않는다 — 자리표시자는 가입이 실패하고, 그쪽은 가입이 성공한다.

#### 검수에서 나온 것 — Android 가 폐지된 행정구역을 제공한다

광주광역시(29)와 전라남도(46)가 폐지되고 **전남광주통합특별시(12)** 로 통합됐다. 인천도
중구·동구·남구·북구·서구가 폐지되고 제물포구·영종구·미추홀구·서해구·검단구로 재편됐다.
강원도(42)→강원특별자치도(51), 전라북도(45)→전북특별자치도(52), 제주도(49)→제주특별자치도(50)
도 마찬가지다. 현재 활성은 16개 시도 / 269개 시군구다.

`RegionSelectionViewModel.kt` 의 하드코딩 목록 71개 중 **7개가 폐지 코드**다.

| 코드 | Android 표기 | 공식 자료 |
|---|---|---|
| `28110` | 인천광역시 중구 | 폐지 |
| `29110` · `29155` · `29200` | 광주광역시 동구·남구·광산구 | 시도 자체가 폐지 |
| `46110` · `46130` · `46150` | 전라남도 목포시·여수시·순천시 | 시도 자체가 폐지 |

사용자가 이 지역을 고르면 서버가 거부한다. 앱에 `전남광주통합특별시` 가 아예 없다. 서버는
전국 269개를 지원하므로 앱 목록을 갱신하면 그대로 동작한다. Android 레인 과제다.

표기 차이도 1건 있다 — `36110` 을 Android 는 `세종특별자치시 세종시`, 공식은
`세종특별자치시` 다. 표시값은 서버가 만들므로 화면 라벨만 다르다.

> **수집기 쪽 후속 과제.** 표시값 포함 관계가 39쌍 있다 (`경기도 수원시` ⊂
> `경기도 수원시 장안구` 등 — 시 아래 구가 있는 경우). `public_ingestion.RegionCatalog.resolve`
> 는 부분 문자열로 매칭한 뒤 **파일 순서의 첫 번째**를 쓰므로, 코드순 정렬에서는 덜 구체적인
> `수원시` 가 선택되어 보호소 주소를 구 단위로 매핑하지 못한다. 다른 모듈의 순서 의존을 CSV
> 정렬로 우회하지 않고, 스프린트 4 의 PostgreSQL 적재에서 `resolve` 를 고친다.

### 소유자를 컨테이너 uid 에 맞춘다

backend 이미지는 **uid 999(`app`)** 로 돌고(`backend/Dockerfile.prod`) 비밀 디렉터리는 `0700`
이다. 소유자가 다르면 컨테이너가 파일을 열지 못하고 Spring 이
`Config data resource 'file [/run/secrets/prod.yml]' ... does not exist` 로 기동에 실패한다.
호스트 쪽을 `0644` 로 열지 않는 이유는 서버 1 에 gitlab-runner 와 Jenkins 가 함께 돌기
때문이다 — 호스트 경로를 읽을 수 있는 CI 잡에 JWT 개인키를 노출하지 않는다.

그래서 `Dockerfile.prod` 가 `useradd --system --uid 999 app` 로 uid 를 고정한다. 양쪽에 같은
숫자를 명시해 결합을 드러낸다. 이미지의 uid 를 바꾸면 비밀 디렉터리 소유자도 함께 바꿔야 한다.

`/home/ubuntu` 가 `0750` 이어도 문제없다 — bind 마운트 경로는 Docker 데몬(root)이 해석하므로
컨테이너 사용자의 부모 디렉터리 통과 권한과 무관하다 (2026-09-11 실측 확인).

### 값이 새면 교체한다 — `rotate-exposed-secrets.py`

`setup-prod-secrets.py` 는 처음 한 번 전부를 만들고 기존 디렉터리를 덮어쓰지 않는다. 값 하나가
새었다고 전부를 다시 만들면 **JWT kid 와 암호화 키링까지 바뀌어** 발급된 토큰과 이미 암호화된
개인정보가 전부 무효가 된다. 그래서 교체 대상을 좁힌 스크립트를 따로 둔다.

```bash
sudo python3 rotate-exposed-secrets.py --dir /home/ubuntu/.secrets/prod            # 대상만 보여준다
sudo python3 rotate-exposed-secrets.py --dir /home/ubuntu/.secrets/prod --confirm  # 실제 교체
docker restart meong-go-spot-redis-1 && sleep 5 && docker restart meong-go-spot-backend-1
```

| 교체 | 그대로 |
|---|---|
| `AUTH_LOGIN_ID_HMAC_KEY_V1` · `PHONE_LOOKUP_HMAC_KEY_V1` | JWT 개인키·JWKS·활성 kid |
| `PHONE_OTP_HMAC_KEY_V1` · `AUTH_IP_HMAC_KEY_V1` | 전화·위치 암호화 키링 |
| redis 비밀번호 (`redis.conf` + `SPRING_DATA_REDIS_URL`) | SOLAPI·행정구역 설정 |

**회원이 있으면 조회 HMAC 을 바꿀 수 없다.** `login_id_hash` 와 `phone_lookup_hash` 가 그 키로
만들어져 있어 교체하면 기존 회원이 전부 로그인 불가가 된다. 스크립트가 `--confirm` 을 요구하는
이유다. 교체가 필요해지면 회원 수를 먼저 확인한다.

```bash
docker exec meong-go-spot-postgres-1 sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "select count(*) from member"'
```

`ProtectSystem=strict` 같은 재기동 후 확인과 마찬가지로, 교체 뒤에는 **Redis 를 실제로 쓰는
경로**로 검증한다. 기동 성공만으로는 부족하다 — Spring 의 Redis 연결은 지연 생성이라 붙지
못해도 애플리케이션은 뜬다.

```bash
# AUTH-001(401)이면 정상 — Redis 의 로그인 제한 카운터를 읽고 썼다는 뜻이다.
# AUTH_UNAVAILABLE 이면 Redis 연결·인증 실패다.
curl -s -X POST --resolve api.meonggo.shop:443:127.0.0.1 https://api.meonggo.shop/api/v1/auth/login   -H 'Content-Type: application/json' -d '{"loginId":"probe","password":"not-a-real-password-1234"}'
```

#### 교체 이력

| 날짜 | 대상 | 계기 |
|---|---|---|
| 2026-09-11 | HMAC 4종 + redis 비밀번호 | 에이전트가 `prod.yml` 구조를 확인하면서 마스킹에 실패해 값을 출력했다. `prod.yml` 이 YAML 이 아니라 JSON 이라 `key: value` 를 가정한 정규식이 빗나갔다. 회원 0행일 때 교체해 영향이 없었다 |

**마스킹한다고 믿지 말고 키 이름만 뽑아라.** 값이 있는 줄을 정규식으로 가리는 방식은 형식이
예상과 다르면 그대로 노출된다. 구조를 볼 때는 파서로 키만 꺼낸다.

```bash
sudo python3 -c "import json;print(list(json.load(open('/home/ubuntu/.secrets/prod/prod.yml'))))"
```

### 배포 전 기동 확인 (스모크)

Jenkins 는 `up -d --build` 로 기존 컨테이너를 **먼저 교체한 뒤** 헬스체크를 하므로, 새 컨테이너가
기동 실패하면 롤백까지 서비스가 내려간다. **`main` 에 올리기 전에 반드시 격리된 compose
프로젝트로 기동을 확인한다.**

```bash
# 서버 1 에서. 운영 키를 쓰지 않고 임시 키로 배선만 검증한다.
mkdir -p /tmp/smoke && cd /tmp/smoke      # backend/ 와 compose.prod.yml 을 올려둔다
python3 setup-prod-secrets.py --dir /tmp/smoke/secrets --skip-chown
# region-codes.csv 와 solapi.properties 를 시험용으로 채우고 sha256 을 prod.yml 에 넣는다
sudo chown -R 999:999 /tmp/smoke/secrets

cat > smoke.env <<'EOF'
IMAGE_TAG=smoke
POSTGRES_DB=smoke
POSTGRES_USER=smoke
POSTGRES_PASSWORD=smoke-test-only
PROD_SECRETS_DIR=/tmp/smoke/secrets
EOF

# 운영과 겹치는 것을 모두 덮어쓴다 — 네트워크 서브넷(172.18.0.0/16)과 발행 포트
# (backend 8080, postgres 5432). 겹치면 스택이 뜨지 않거나 운영 포트를 빼앗는다.
cat > smoke.override.yml <<'EOF'
services:
  backend:
    image: meong-go-spot-backend:smoke
    ports: !override
      - "127.0.0.1:18080:8080"
  postgres:
    ports: !override
      - "127.0.0.1:15432:5432"
networks:
  default:
    ipam:
      config: !override
        - subnet: 172.30.0.0/16
EOF

docker compose --env-file smoke.env -f compose.prod.yml -f smoke.override.yml -p smoke up -d --build
curl -s http://127.0.0.1:18080/api/v1/ping

# 끝나면 반드시 정리한다
docker compose --env-file smoke.env -f compose.prod.yml -f smoke.override.yml -p smoke down -v
docker rmi meong-go-spot-backend:smoke && sudo rm -rf /tmp/smoke
```

기동 실패의 원인은 로그 한 줄에 그대로 나온다.

```bash
docker logs smoke-backend-1 2>&1 | grep -oE \
  "Required authentication setting is missing: [A-Z_0-9]+|Authentication (duration|identifier) violates policy: [A-Z_0-9]+|Cannot load [a-zA-Z ]+|Incomplete SOLAPI|Invalid SOLAPI" | sort -u
```

### Redis — 기동은 막지 않지만 로그인을 막는다

기동은 Redis 없이도 된다. `application.yml` 에 `spring.data.redis.url` 기본값이 있고 Lettuce 는
지연 연결이다 (2026-09-11 스모크에서 redis 없이 14.4초 기동 확인).

**그러나 로그인이 안 된다.** `LoginService` 는 회원을 조회하기 **전에**
`attempts.check(accountHash, ipHash)` 를 호출하고, 실패 시 `attempts.failure(...)` 로 한 번 더
쓴다. 이 저장소가 `RedisLoginAttemptStore` 이고 Lua 스크립트로 동작한다. 로그인이 막히면
게시물 등록·분석 요청 등 인증이 필요한 모든 경로를 테스트할 수 없다. 그래서 2026-09-11
`compose.prod.yml` 에 내부 전용 redis 서비스를 추가했다 — 외부 publish 없음, `requirepass`,
`noeviction`, 영속화 없음.

영속화를 끈 이유는 담기는 것이 짧은 TTL 인증 상태뿐이기 때문이다. 재기동 시 진행 중이던 OTP 와
제한 카운터가 사라지는 것은 감수하고, 대신 비밀 데이터를 디스크에 남기지 않는다.

#### 비밀번호는 URL 안에 넣어야 한다

`REDIS_CREDENTIALS_PATH` 로는 비밀번호가 전달되지 않는다. Spring Boot 는
`spring.data.redis.url` 이 설정되어 있으면 host·port·username·password 를 **전부 URL 에서만**
읽고 별도 `spring.data.redis.password` 를 무시한다. `application.yml` 이 `url` 에 기본값을 주므로
`url` 은 항상 설정된 상태다.

단일 변수만 바꾼 대조 실험으로 확인했다 (2026-09-11, 같은 코드·같은 redis).

| 비밀번호 위치 | 로그인 결과 |
|---|---|
| `SPRING_DATA_REDIS_URL` 안 (`redis://:PW@redis:6379`) | **401** `AUTH-001` — 정상 인증 실패. Redis 에 `mgbj:auth:login:account:*`·`:ip:*` 카운터가 실제로 기록됐다 |
| `REDIS_CREDENTIALS_PATH` 파일만 | **503** `AUTH-006` — Redis 실패의 fail-closed 처리 |

그래서 `setup-prod-secrets.py` 가 `SPRING_DATA_REDIS_URL` 을 비밀번호까지 포함해 `prod.yml` 에
쓴다. URL 을 비밀 파일에 두면 `compose.prod.yml` 이나 `docker inspect` 에 비밀번호가 남지 않는다.
redis 컨테이너도 같은 이유로 `--requirepass` 인자 대신 `redis.conf` 파일을 읽는다.

> **로컬 개발도 같은 영향을 받는다.** `compose.dev.yml` 의 redis 는 `--requirepass redis-local-only`
> 를 쓰고 `setup-auth-dev.mjs` 는 그 비밀번호를 `REDIS_CREDENTIALS_PATH` 로만 넘긴다. CI 는
> 비밀번호 없는 redis 를 쓰므로(`Jenkinsfile`) 이 경로가 검증된 적이 없다. 별도 추적 대상이다.

#### X-Forwarded-For 계약

`ClientIpResolver` 는 **신뢰 peer 면 `X-Forwarded-For` 가 정확히 1개일 것을 요구하고 아니면
요청을 거부한다.** 실측 결과다 (2026-09-11, `AUTH_TRUSTED_PROXY_CIDRS` 를 스모크 게이트웨이로
지정하고 로그인 호출).

| 신뢰 peer 가 보낸 것 | 결과 |
|---|---|
| 헤더 없음 | 400 `COMMON-001` |
| XFF 1개 | 401 `AUTH-001` — 정상 경로 |
| XFF 2줄 | 400 `COMMON-001` |
| XFF 콤마 목록 | 400 `COMMON-001` |

마지막 줄이 nginx 설정을 결정한다. 관용적인 `$proxy_add_x_forwarded_for` 는 클라이언트가 보낸
값에 덧붙여 콤마 목록을 만들므로 **인증 요청이 전부 400 이 된다.** 그래서 `$remote_addr` 로
덮어쓴다. 격리된 nginx 컨테이너에 같은 지시어만 떼어 확인했다 — 클라이언트가 XFF 를 1개,
콤마 목록, 2줄로 보내거나 `Forwarded` 를 보내도 백엔드는 **항상 XFF 1개 = 실제 peer** 만 받고
`Forwarded` 는 0개였다. 클라이언트의 IP 위조가 불가능하다.

## DATA 쪽 연결 — worker·적재기·엔진 배치

2026-09-11 점검에서 Backend ↔ Data 경계가 끊겨 있는 것을 확인했다. 원인이 셋이고 성격이 다르다.

### PostgreSQL 접근 — 해결

`compose.prod.yml` 이 5432 를 publish 하지 않아 compose 네트워크 밖에서는 닿지 않았다.
서버 2 에서 `172.26.3.162:5432` 연결이 실패하는 것을 실측했다.

**`127.0.0.1:5432` 로만 publish 한다.** 루프백은 외부에서 접근할 수 없으므로 "DB 류는 publish
금지" 의 취지(인터넷 노출 차단)에 어긋나지 않고, Docker publish 가 UFW 를 우회하는 문제도
해당하지 않는다. 그래서 **서버 1 의 호스트 프로세스**가 DB 에 붙는 구조를 택했다.

검토했다가 쓰지 않은 두 가지:

| 방식 | 쓰지 않은 이유 |
|---|---|
| 소비자를 compose 네트워크 컨테이너로 | 적재기가 서버 2 의 Kafka 9092 를 써야 해 `DOCKER-USER` 예외가 하나 더 필요하고, Airflow 가 서버 2 에 있어 서버 1 작업을 원격 실행해야 한다 |
| 5432 를 사설 IP 에 publish + `DOCKER-USER` 로 제한 | Docker publish 가 UFW 를 우회하므로 iptables 규칙이 유일한 방어선이 된다. 규칙이 빗나가면 PostgreSQL 이 인터넷에 노출된다 |

서버 1 호스트는 서버 2 의 Kafka·HDFS·엔진에 이미 닿는다 (서버 2 UFW 가 `172.26.3.162` 를 전부
허용). 그래서 호스트 프로세스 방식은 방화벽 예외를 하나도 추가하지 않는다.

### 공공데이터 적재 — 해결 (2026-09-11)

전국 보호소 입소 동물이 서비스 DB 에 하나도 없었다. `GET /api/v1/posts` 는 인증 없이 열려
있는데 `animal_case` 가 0행이라 앱을 깔아도 빈 목록만 보였다.

**서버 1 의 systemd 타이머가 Kafka `shelter.raw` 를 읽어 PostgreSQL 에 적재한다.**

| 파일 | 위치 | 하는 일 |
|---|---|---|
| `infra/systemd/shelter-loader.timer` | `/etc/systemd/system/` | 매일 23:10 KST (수집 DAG 22:30 뒤) |
| `infra/systemd/shelter-loader.service` | `/etc/systemd/system/` | oneshot, `ubuntu` 계정, 샌드박스 |
| `infra/scripts/run-shelter-loader.sh` | `/usr/local/sbin/` | KST 날짜를 계산해 인자로 넘긴다 |
| `infra/scripts/setup-loader-db.sh` | 1회 실행 | 전용 DB 역할과 DSN 파일 생성 |
| `data/collector/postgres_loader.py` 외 2개 | `/home/ubuntu/shelter-loader/` | 적재 본체 (venv 포함) |

#### 앱 비밀번호를 쓰지 않는다

적재기는 호스트 프로세스라 DB 비밀번호가 필요한데, 앱 계정의 비밀번호는 Jenkins credential
안에 있어 읽을 수 없고 읽어서도 안 된다. 그래서 `setup-loader-db.sh` 가 **전용 역할
`shelter_loader`** 를 서버에서 만들고 비밀번호도 서버에서 생성한다. 권한은 적재기가 실제로
건드리는 6개 테이블뿐이다.

```
animal_case · animal_case_location · ingestion_run · shelter · shelter_animal
                                    → SELECT, INSERT, UPDATE
animal_photo                        → + DELETE (사진이 줄면 뒤쪽을 지운다)
member · auth_session               → 권한 0개 (실측: permission denied)
```

DSN 은 `/home/ubuntu/.secrets/loader/shelter-loader.env` (0600) 에만 있고 systemd 의
`EnvironmentFile` 로 전달된다. 레포에도 Jenkins 에도 값이 없다.

#### Airflow DAG 에 두지 않는 이유

`collector_daily` 에 있던 `persist_public_records` 태스크를 **제거했다**. 서비스 PostgreSQL 은
서버 1 루프백에만 열려 있어 서버 2 의 Airflow 에서 닿지 않는다. 더 중요한 것은 둘 다
consumer group `postgres-public-ingestion` 을 쓰기 때문에 살려 두면 파티션이 갈려 한쪽이
빈손으로 끝난다는 점이다. (`data/airflow/tests/test_collector_daily.py` 가 이 불변식을 지킨다.)

#### 적재에서 걸린 것

| 증상 | 원인 | 조치 |
|---|---|---|
| 앱에서 썸네일이 전부 깨진다 | 공공 API 가 주는 사진 URL 이 `http://` 인데 Android 는 targetSdk 28 부터 평문을 기본 차단한다 | 같은 파일이 https 로도 동일하게 오는 것을 실측(518,341B 일치)하고 적재 시 https 로 저장. 백엔드도 https 만 내려보낸다 |
| systemd 로 돌리면 실패할 수 있었다 | `postgres_loader.py` 가 `loader.py` 와 달리 stdout 인코딩을 고정하지 않아 한글 진행 메시지에서 `UnicodeEncodeError` | 스크립트에서 UTF-8 고정 + 단위에 `PYTHONIOENCODING` |
| **전국 데이터가 97% 버려졌다** (2026-09-14 발견) | 발견 지역을 `happenPlace`(자유 지명, "봉정삼거리")로 풀어서 `LOCATION_NOT_MAPPED` — 첫 실행 11,626건 중 11,313건 실패. "적재 완료" 로그만 보고 `failed_count` 를 확인하지 않았다 | `EVENT` 는 `orgNm` 우선(→ `happenPlace` → `careAddr`), `CURRENT` 는 `careAddr`(→ `orgNm`), 개편 전 시도명 별칭 `region-aliases.csv`. 9월 표본 563건 1% → 100%. **`ingestion_run.failed_count` 와 `error_summary` 를 실행마다 본다** |
| **기타 축종이 목록에 보였다** (2026-09-14 결정) | 공공 API `upKindNm=기타`(토끼·닭·햄스터, 전체의 ~2%)를 `OTHER` 로 저장했고 앱은 축종 필터를 보내지 않는다. 서비스 범위는 개·고양이인데 어디에도 적혀 있지 않았다 | **적재에서 뺀다** — `SPECIES_NOT_SUPPORTED` 로 거부(`error_summary` 에 남음, 하루 ~2%가 정상). 기존 177건은 `status=DELETED, deleted_at, is_matchable=false` 로 소프트 삭제(되돌릴 수 있음). 전량 드라이런 7,035 → 통과 6,879 · 기타 155 · 사진 없음 1 |

| **썸네일이 없는 게시물 10%** (2026-09-14 발견) | 공공 API 파일명의 ~9% 가 `…518[1].jpg` 처럼 대괄호를 담는다(15,520장 중 1,398장). URI 경로에 허용되지 않는 문자라 백엔드 `java.net.URI` 가 거부 → 썸네일 null → ACTIVE 452건이 사진 없는 카드. 팀원은 "썸네일이 동물과 안 맞는다"로 인지했다 | 적재기가 `[ ]` 를 `%5B %5D` 로 저장, 백엔드 `PublicPhotoUrl` 도 파싱 전 인코딩(이전 값 대비). 파일 서버는 두 형태 모두 같은 파일을 준다(200, 바이트 동일) |

**재적재는 사진 URL 을 고치지 않는다.** `updTm` 이 바뀌지 않은 레코드는 멱등 규칙으로 건너뛰므로(`shelter_animal.source_updated_at` 비교) 같은 파일을 다시 넣어도 `신규 0 · 갱신 0` 이다. 저장 형식만 바뀐 경우 기존 행은 SQL 로 1회 정정한다 — 2026-09-14 실행:

```sql
UPDATE animal_photo SET storage_uri = replace(replace(storage_uri, '[', '%5B'), ']', '%5D')
 WHERE storage_type = 'PUBLIC_URL' AND storage_uri ~ '[][]';   -- UPDATE 1398
```

버려진 원문은 Kafka 에 다시 흐르지 않으므로(신규·변경분만) `postgres_loader.py --input` 으로 전량을 한 번
다시 넣었다 — 서버 2 에서 `api.fetch_all` 로 받은 파일을 서버 1 로 옮겨 실행(Kafka offset 미접촉).

```
재적재 (2026-09-14, run 5, INITIAL_FULL, 44초)
  수신 7,035 → 신규 6,852 · 갱신 3 · 실패 1 (MISSING_PUBLIC_PHOTO)
  결과   PUBLIC ACTIVE 4,470 · CLOSED 2,645 · shelter 288 · animal_photo 15,520
  시도별 ACTIVE 상위: 경기 814 · 전남광주 538 · 전북 451 · 경남 434 · 경북 358
```

별칭 파일은 `loader.env` 에 두 키로 넘긴다 (비밀 아님, 레포 `infra/reference/` 와 sha 일치 확인).

```
REGION_ALIAS_DATA_PATH=/home/ubuntu/shelter-loader/reference/region-aliases.csv
REGION_ALIAS_DATA_SHA256=<infra/reference/region-aliases.csv 의 sha256>
```

#### 검증 이력 (2026-09-11)

```
첫 실행        run=1 · 수신 11626 · 손상 메시지 0 · exit=0
적재 결과      animal_case 255 · shelter_animal 255 · animal_case_location 510
              · animal_photo 516 · shelter 20 · ingestion_run 1 (SUCCEEDED)
공개 API       GET /api/v1/posts?type=SHELTERING&regionCode=47113 → 실제 게시물 반환
썸네일         https URL 로 200, 409,313B 수신 (앱이 하는 요청과 동일)
systemd 실행   Result=success, 한글 로그 정상 출력
샌드박스       psycopg 연결 OK · 임시 파일 쓰기 OK · /home 쓰기 차단 확인
타이머         enabled/active, 다음 실행 2026-09-11 14:10 UTC (23:10 KST)
```

`regionCode` 는 **필수 파라미터**다. 빼면 `COMMON-001` 이 온다. 적재된 지역 상위는
포항 북구(47113) 150 · 양평군(41830) 82 · 포항 남구(47111) 58 순이다.

### 매칭 엔진 — 서버 1 상주 서비스 (2026-09-15 구축, 이슈 -144)

`match_run` 을 처리하는 두 프로세스가 **서버 1** 에 systemd 로 상주한다. 코드는 `data/match_engine/`(엔진)과
`data/matching_worker/`(worker), 계약은 `data/matching_worker/README.md` 와 `docs/data-ai-interface.md` §후보 출력 정책.

```text
Android ──M2──▶ backend ──insert PENDING──▶ match_run (postgres, 루프백 5432)
                                               ▲ FOR UPDATE SKIP LOCKED
matching-worker.service ───────────────────────┘ ──POST 127.0.0.1:8091/match──▶ match-engine.service
                                                                                  ├─ 질의 사진: WebHDFS OPEN (bd-master:9870, user.name=ubuntu)
                                                                                  ├─ 후보: PUBLIC·SHELTERING·ACTIVE·같은 축종·실종일 이후·같은 시·도 (SQL)
                                                                                  └─ 스냅샷 벡터 456,999장 (RAM, 22:30 파이프라인 뒤 자동 교체)
```

| 파일 | 위치 | 하는 일 |
|---|---|---|
| `infra/systemd/match-engine.service` | `/etc/systemd/system/` | 엔진. `127.0.0.1:8091`, ai venv, CPUQuota 300%·Nice 5·MemoryMax 6G, 설정은 `Environment=` 줄(임계값 0.60·K 20·지역 접두 2자리) |
| `infra/systemd/matching-worker.service` | `/etc/systemd/system/` | worker. `python -m data.matching_worker.worker --poll-seconds 2` |
| `infra/scripts/setup-match-db.sh` | 1회 실행 | 역할 `match_engine`(4개 테이블 SELECT)·`match_worker`(match_run UPDATE, match_candidate INSERT) + DSN 파일 `/home/ubuntu/.secrets/match/*.env` |
| `data/match_engine/engine.py` | `/home/ubuntu/match-engine/engine.py` | 엔진 본체. 스냅샷은 `~/match-engine/snapshot/` (HDFS 에서 자동 갱신) |
| `data/matching_worker/{__init__,worker}.py` | `/home/ubuntu/matching-worker/data/matching_worker/` | worker 본체 |
| `ai/` (서버 2 `~/ai` 복제) | `/home/ubuntu/ai/` | 모델 가중치 452MB + venv 5.9GB(torch·numpy·yaml + psycopg 추가). 서버 2 에서 `rsync -a ~/ai/ bd-worker1:~/ai/` 로 복제(6.4GB, 1분) |

#### 왜 서버 1 인가 — 2026-09-15 실측

| 항목 | 서버 1 (bd-worker1) | 서버 2 (bd-master) |
|---|---|---|
| 메모리 사용 / 가용 (낮) | 3.8GB / 12.0GB | 4.4GB / 11.4GB — **스왑 없음**, NameNode·RM·Kafka 동거 |
| 엔진 상주 크기 | 모델 1.35GB + 스냅샷 1.43GB → **실측 RSS 2.65GB** (요청 처리 후) | 같음 |
| 야간(22:30~) 추가 부하 | 없음 | 임베딩 태스크 20분(모델 1.4GB) + YARN 컨테이너(NodeManager 상한 6GB) |
| DB 접근 | postgres 가 **루프백 5432** — 같은 서버라 바로 붙는다 | 5432 가 서버 2 에 열려 있지 않다 (compose 가 127.0.0.1 만 publish) |
| worker 위치 | 같은 서버 → `MATCH_ENGINE_URL` 루프백, 방화벽 변경 없음 | 서버 1→2 포트를 열어야 함 |
| CPU | 4코어, 로드 0.0 — backend API 와 공유하므로 CPUQuota 300% | 4코어, 야간 임베딩·YARN 과 경합 |

서버 2 에도 메모리는 들어가지만, 스왑이 없는 NameNode 호스트에 2.7GB 상주 프로세스를 하나 더 두면 **엔진의
누수 하나가 클러스터 전체를 내린다**. 서버 1 은 여유가 있고 DB 가 루프백이라 worker 까지 같이 둘 수 있다.
시간 분리(야간에 엔진 정지)는 사용자 요청이 야간에도 오므로 택하지 않았다.

#### 첫 e2e (2026-09-15 00:22 UTC)

사용자 LOST 게시물(고양이·경기·2026-08-15)로 `match_run` 을 만들어 확인: 후보 285마리 → 임계값 0.60 통과 1건(6859,
0.6214), **SUCCEEDED 1.8초**(사진 읽기 0.0s·임베딩 1.7s·비교 0.1s 미만). 첫 시도는 `MATCH_FAILED` — WebHDFS 를
`user.name` 없이 열어 익명(dr.who) 권한으로 0600 사진에 403 을 받았다. backend 가 쓰는 `PHOTO_HDFS_USER=ubuntu`
를 엔진도 대도록 고쳤다(`MATCH_ENGINE_HDFS_USER`).

#### 운영

```bash
systemctl status match-engine matching-worker --no-pager
curl -s http://127.0.0.1:8091/health          # snapshot.count · builtAt · threshold
journalctl -u match-engine -n 20 --no-pager   # run=<id> 사진 N장 후보 M마리 → K건 (읽기 s 임베딩 s 전체 s)
journalctl -u matching-worker -n 20 --no-pager
# 스냅샷은 10분마다 HDFS meta.json 의 built_at 을 보고 새 빌드면 내려받아 교체한다 (이전 세대는 snapshot.previous)
# 임계값·지역 접두를 바꾸면: 유닛의 Environment= 수정 → daemon-reload → restart match-engine
```

- 오늘 들어온 공고 사진은 22:30 임베딩 전까지 벡터가 없어 후보에서 빠진다(엔진이 건너뛴다). 다음 날부터 후보가 된다.
- **장애 기록 2026-09-18~23**: v1.3.0 배포가 PostgreSQL 컨테이너를 다시 만들자 worker 는 죽어 systemd 가 살렸지만 엔진은 죽은 연결을 쥔 채 살아남아 모든 분석이 `MATCH_FAILED`(엔진 로그 `OperationalError`) 였다. 증상: 앱 "분석을 완료하지 못했어요", `match_run.status=FAILED` 연속, 엔진 `/health` 는 정상. 조치: `sudo systemctl restart match-engine`(기동 ~16초 — 그 사이 요청은 실패하니 `/health` 200 뒤에 재시도). 엔진은 이제 연결 오류에서 스스로 다시 붙는다(`CandidateRepository`, 2026-09-23). **배포·재기동 뒤 점검 항목에 `journalctl -u match-engine -n 5` 를 넣을 것.**
- `MATCH_ENGINE_REGION_PREFIX_LEN=2`(시·도)는 후보 100~400마리 조건에서 정한 임계값과 맞춘 값이다. 후보가 너무 적다는 피드백이 오면 0(전국)으로 넓힐 수 있지만, 전국 갤러리에서는 임계값이 의미를 잃는다(계약 0-3).
- 엔진 스냅샷·모델은 `dinov2_vitb14/v2` 로 못 박혀 있다. `ACTIVE` 포인터·`model.yaml`·요청의 모델이 하나라도 다르면 기동 거부 또는 409 — 모델 교체는 임계값 재측정과 함께 사람이 한다.

### 분석 UI 게이트 (2026-09-15 가림 → 2026-09-17 해제)

엔진이 없는 동안 분석을 요청하면 `match_run` 이 영구히 `PENDING` 에 머문다. 깨진 기능을
출시하지 않기 위해 **Android 의 진입점을 `MATCHING_ENABLED=false` 로 막았다** (당시 기본값).
화면과 내비게이션 경로는 그대로 두고 `PostDetailScreen` 의 버튼만 숨긴다 — 되돌리기가
한 줄이고 코드를 지우지 않는다.

`canAnalyzeMatch`("이 사용자가 이 게시물을 분석할 권한이 있는가")는 API 에서 오는 사실이고
`MATCHING_ENABLED`("그 기능이 출시됐는가")는 다른 관심사다. 그래서 권한 모델과 그 테스트를
건드리지 않고 UI 게이트에서 막았다.

로컬에서 켜려면 `-PMATCHING_ENABLED=true`.

**켜기 전에 확정돼야 했던 것 — 2026-09-15 모두 확정됐다.**

| 항목 | 상태 |
|---|---|
| 점수 집계 규칙 | ✅ 쌍 코사인 최댓값 (계약 0-2, 2026-09-14 실측 — `data/experiments/2026-09-14-score-aggregation/`) |
| 점수 임계값 수치 | ✅ v2 = 0.60 (계약 0-3) — 엔진 유닛의 `MATCH_ENGINE_THRESHOLD` |
| 엔진 배치 | ✅ 서버 1 상주 (위 "매칭 엔진" 절, 메모리 실측 포함) |

**2026-09-17 게이트 해제** — 기본값을 `true` 로 바꿨다. 팀이 "내 게시글에서 유사도 분석이 사라졌다" 고 보고한 것이
이 게이트였다: 9/15 검증은 `-PMATCHING_ENABLED=true` 로 빌드한 APK 였고, 이후 기본값 빌드에서는 버튼이 숨어 있었다.
엔진은 v1.0.1 부터 운영에 상주하므로 더 막을 이유가 없다. 끄려면 `-PMATCHING_ENABLED=false`.

### 공공 분실 신고 PostgreSQL 적재 (2026-09-15, 이슈 -145)

서버 1 의 `lost-loader.timer`(매일 **23:20 KST**)가 그날 HDFS 스냅샷 `/data/lost/raw/dt=오늘/records.jsonl`(서버 2, 22:30 뒤
생성, 연락처·상세주소 이미 제거)을 WebHDFS 로 읽어 `animal_case(PUBLIC/LOST, is_matchable=false)`·`animal_case_location(EVENT)`·
`animal_photo`·`lost_report` 에 적재한다. 스냅샷에 없는 ACTIVE 건은 CLOSED 로 내린다(원천에 상태가 없어 "목록에서 사라짐" 이
유일한 신호). 빈 스냅샷은 "다 사라졌다" 가 아니라 "못 받았다" 로 보고 아무것도 닫지 않는다.

| 파일 | 위치 | 하는 일 |
|---|---|---|
| `infra/systemd/lost-loader.timer` · `.service` | `/etc/systemd/system/` | 23:20 KST oneshot, `shelter-loader` 와 같은 DSN·지역 기준 데이터 env |
| `infra/scripts/run-lost-loader.sh` | `/usr/local/sbin/` | KST 날짜로 그날 파티션 지정 |
| `data/collector/lost_ingestion.py` | `/home/ubuntu/shelter-loader/` | 적재 본체 (`public_ingestion` 의 지역 카탈로그·URL 정규화 재사용) |
| `backend/.../V3__public_lost_reports.sql` | Flyway | `ck_animal_case_source_type_pair` 완화 + `lost_report` 테이블 — **main 배포(-142) 때 적용**. 그 전에는 적재가 제약 위반으로 실패한다 |

축종은 스냅샷의 `species`(품종 사전 유도)를 그대로 쓰고 DOG/CAT 이 아니면 `SPECIES_NOT_SUPPORTED` 로 실패 집계한다(17%).
`setup-loader-db.sh` 재실행 또는 V3 의 DO 블록이 `shelter_loader` 에 `lost_report` 권한을 준다.

### 보호소 결과 통계 배치 (2026-09-15, 홈 대시보드 D3)

서버 1 의 `shelter-outcomes.timer`(**매주 월요일 00:40 KST**)가 서버 2 HDFS 백필 `/data/shelter/backfill/yyyymm=*`(WebHDFS) 중
최근 3년 창에 걸치는 달을 훑어 종결 건의 반환·입양 비율과 평균 공고 기간을 `dashboard_stat('shelter_outcomes','00000')` 에
upsert 한다. 백엔드 D3 가 그 payload 를 홈 카드에 전달한다.

| 파일 | 위치 | 하는 일 |
|---|---|---|
| `infra/systemd/shelter-outcomes.timer` · `.service` | `/etc/systemd/system/` | 월 00:40 KST oneshot, `shelter-loader` 와 같은 DSN env(역할 `shelter_loader`) |
| `infra/scripts/run-shelter-outcomes.sh` | `/usr/local/sbin/` | KST 오늘을 기준일로 넘긴다(창 끝 = 오늘-60일) |
| `data/collector/shelter_outcomes.py` | `/home/ubuntu/shelter-loader/` | 배치 본체. `--print-only` 로 DB 없이 점검 |
| `backend/.../V4__dashboard_stat.sql` | Flyway | `dashboard_stat` 생성 + `shelter_loader` GRANT — **main 배포 때 적용**. 그 전에는 타이머를 켜지 않는다(테이블 없음) |

**상태 (2026-09-15)**: 파일·유닛은 서버 1 에 설치했고 타이머는 **비활성**. V4 가 운영에 적용된 뒤
`sudo systemctl enable --now shelter-outcomes.timer && sudo systemctl start shelter-outcomes.service` → `journalctl -u shelter-outcomes` 에서
`dashboard_stat shelter_outcomes/00000 갱신` 확인 → 홈 카드에 숫자.

**실측 (2026-09-15 `--print-only`, 서버 1)**: 창 2023-07-17~2026-07-17, 37개월 WebHDFS 스트리밍 **20초**. 종결 286,134건 —
반환 12.1%(34,605) · 입양 29.2%(83,574) · 자연사 29.4% · 안락사 22.2% · 기증 5.2% · 방사 1.8%, 평균 공고 기간 10.4일(표본 99.99%).
연도별 반환율 2023 12.2% / 2024 11.8% / 2025 11.9% / 2026(~7월) 13.2%. 창 안 `보호중` 12,013건은 분모에서 뺐다.

### 공공데이터 PostgreSQL 적재

DAG 에는 `persist_public_records` 태스크를 **두지 않는다**(`data/airflow/tests/test_collector_daily.py` 가 막는다). 레포와
서버의 DAG 는 같은 7태스크(수집·분실 스냅샷·적재·이미지·임베딩·배정·스냅샷)다. 그래서 야간 파이프라인은 영향받지 않는다.

배포하면 실패한다. 서버 2 → 5432 접근 불가이고 `SHELTER_POSTGRES_DSN` 이
`airflow-local.env` 에 없다. **적재기를 서버 1 호스트로 옮기는 작업과 함께 배포한다.**
Kafka(서버 2)는 서버 1 호스트에서 닿고 PostgreSQL 은 루프백으로 붙는다.

## 검증 이력

| 항목 | 상태 |
|---|---|
| `backend/Dockerfile.prod` 빌드 | ✅ 로컬 Docker에서 확인 (2026-08-12) |
| `compose.dev.yml` / `compose.prod.yml` config 파싱 | ✅ 로컬 Docker에서 확인 (2026-08-12, 구성 변경 후 2026-08-25 재확인) |
| Jenkins 파이프라인 실행 | ✅ 서버 1 첫 배포 성공 — 전 스테이지 통과 (2026-08-31, build #1) |
| 실서버 배포 (postgres 포함 compose 기동, 외부 200) | ✅ 2026-08-31 |
| 실서버 롤백 | ❌ 두 번째 빌드 이후 검증 예정 |
| TLS 인증서 발급 (Let's Encrypt, `api.meonggo.shop`) | ✅ 2026-09-11 — 만료 2026-12-10 |
| 외부 HTTPS 응답 | ✅ 2026-09-11 — 200, 인증서 검증 0, HTTP/2, TLS 1.2·1.3 |
| 인증서 갱신 경로 | ✅ 2026-09-14 — `webroot` 인증기로 `certbot renew --dry-run` 통과(무중단). 2026-09-11 에는 standalone + 컨테이너 정지 래퍼였다 |
| 업로드 한도 경계 | ✅ 2026-09-11 — 55MB 통과, 65MB 413 (`client_max_body_size 60M`) |
| 운영 키 자료 생성 | ✅ 2026-09-11 — 서버 1 `/home/ubuntu/.secrets/prod`, uid 999 / 0600, 코드 제약 전부 통과 |
| prod 기동 스모크 (격리 compose, 임시 키) | ✅ 2026-09-11 — prod 프로파일로 14.4초 기동, `/api/v1/ping` 200, Flyway 정상 |
| 비밀 디렉터리 컨테이너 읽기 | ✅ 2026-09-11 — uid 999 로 `prod.yml`·`jwt-private.pem` 읽기 확인 |
| Redis 인증·로그인 경로 | ✅ 2026-09-11 — 로그인 401 정상 응답, Redis 에 제한 카운터 실제 기록 |
| Redis 비밀번호 전달 방식 | ✅ 2026-09-11 — 대조 실험으로 URL 포함만 동작함을 확인 |
| X-Forwarded-For 계약 | ✅ 2026-09-11 — 1개만 허용 확인, nginx 가 항상 1개로 덮어씀 확인 |
| 미인증 게시물 등록 차단 | ✅ 2026-09-11 — 401 `AUTH-002` (500 아님) |
| 행정구역 CSV 변환·검증 | ✅ 2026-09-11 — 269행, 실제 Java 로더가 받아들여 28.4초 기동 |
| 행정구역 체크섬 가드 | ✅ 2026-09-11 — 틀린 sha256 에 기동 거부 확인 (역검증) |
| `main` 배포 선결 조건 | ✅ 2026-09-11 — 17개 설정 전부 배치. SOLAPI 는 이후 실계정으로 교체(2026-09-22 확인). **v1.4.0 은 FCM 토큰 키 2개 추가가 선결**(`add-fcm-token-secrets.py`) |
| nginx upstream 이중화 | ✅ 2026-09-11 — 8080 없음→backup(80) 200/37ms, 8080 있음→8080 우선 확인. **2026-09-14 backup 제거** |
| HTTP→HTTPS 리다이렉트 | ✅ 2026-09-14 — `http://…/api/v1/ping` → 301 `https://…`, 80 은 nginx 만 듣는다 |
| ACME 챌린지 경로 | ✅ 2026-09-14 — `/var/www/certbot` 에 둔 파일이 `http://…/.well-known/acme-challenge/` 로 200 |
| 갱신 무중단 | ✅ 2026-09-14 — `certbot renew --dry-run` 통과, backend 컨테이너 `StartedAt` 불변 |
| Jenkins 헬스체크 HTTPS 경로 | ✅ 2026-09-11 — jenkins 컨테이너에서 `--resolve` 로 200, TLS 검증 0 |
| 사진 저장 WebHDFS 경로 | ✅ 2026-09-11 — compose 네트워크에서 2단계 쓰기 201, 읽기 일치, 삭제 200 |
| 사진 저장소 빈 생성 | ✅ 2026-09-11 — 스모크에서 `GET /photos/1` 이 `PHOTO-007`(저장소 정상), `STORAGE_UNAVAILABLE` 아님 |
| Android ↔ Backend 경로 | ✅ 2026-09-11 — Retrofit 호출 18개 전부 대응 컨트롤러 존재 |
| PostgreSQL 루프백 publish | ⏳ MR 병합 후 배포에서 확인 |
| 매칭 worker·엔진 | ❌ 미배포·미구현 — 위 "DATA 쪽 연결" 절 |

> 과거 nginx(웹 프론트) 구성의 검증 기록은 클라이언트가 Android로 바뀌면서 폐기했다 (2026-08-25, ADR-001 D3 변경 참조).
