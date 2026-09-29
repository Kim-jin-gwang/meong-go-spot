# Backend Codex 개발 시작 가이드

이 문서는 백엔드 담당자가 Jira 이슈를 Codex와 함께 구현할 때 필요한 문서, 시작 프롬프트,
구현 순서와 완료 조건을 한곳에 정리한 실무 runbook입니다. 프로젝트 전체 온보딩은
[팀 온보딩 가이드](../../docs/onboarding.md), 강제 규칙은 루트 [AGENTS.md](../../AGENTS.md)를 우선합니다.

## 0. 현재 백엔드 작업 인계 상태

이 절은 새 Codex 채팅이 과거 대화를 몰라도 현재 위치에서 시작하기 위한 인계 기록이다.
아래 스냅샷은 **2026-09-10 KST** 기준이며, 작업 시작 때 Jira와 `origin/dev`를 다시 조회한다.
Jira 상태와 코드가 다르면 이 표만 믿고 상태를 바꾸거나 같은 기능을 다시 만들지 말고, Jira 완료
조건·병합 MR·현재 테스트를 대조한다.

### 0.1 기준점과 작업 묶음 원칙

| 항목 | 현재 기준 |
| --- | --- |
| 통합 브랜치 | `origin/dev` |
| 확인한 `origin/dev` 커밋 | `12039de` — `feat/daily-intake-fcm` 병합 커밋 |
| 마지막 백엔드 MR | !133 일일 입소 요약 FCM |
| 마지막 완료 Jira | `#102`, `#103` |
| 실제 기능 잔여 | `#94` engine·실환경 smoke, `104` Firebase·Android smoke |
| Jira 상태 감사 대상 | `#59`, `60`, `63`, `65` |

백엔드 이슈는 담당자나 이슈 제목보다 **같은 데이터·트랜잭션·API 흐름을 함께 바꾸는 기능
단위**로 묶는다. 서로 다른 외부 연동이나 장애 모델은 같은 MR에 넣지 않는다. 공공데이터의
PostgreSQL 서비스 적재 `64`는 매칭 후보 ID와 일일 요약 실행 이력의 선행 작업이다. 그 뒤의
MapReduce worker 연동 `94`와 FCM 발송 `104`는 의존성·운영 방식·실패 복구가 다르므로 별도
기능 단위로 진행한다. AI 리뷰가 한 번에 이해할 수 있도록 한 MR은 하나의 사용자 흐름 또는
하나의 운영 경계만 다룬다.

### 0.2 지금까지 병합한 백엔드 기능 단위

먼저 완료된 기반 이슈는 `#17`(공통 스택), `26`(API 명세), `28`·`37`(문서 계약
정합화)다. 이후 구현은 다음 묶음 순서로 진행했다.

| 순서 | 기능 단위와 MR | 연결 Jira | 구현 결과 | 다음 묶음으로 넘긴 범위 |
| ---: | --- | --- | --- | --- |
| 1 | !78 백엔드 보안·DB 기반 `feat/backend-foundation` | `59`, `60` | 공통 401·403 처리, Security 기본 보호, Flyway V1 전체 스키마와 PostgreSQL 계약 테스트 | 개별 도메인 API와 엔티티·저장소 |
| 2 | !94 전화 인증 회원가입 `feat/member-signup` | `61` 일부, `69`~`71` | 휴대전화 OTP, 가입 증명, 개인정보 보호 저장, 회원가입 | 로그인 세션·JWT |
| 3 | !96 로그인·JWT 세션 `feat/auth-sessions` | `61`, `68`, `72`~`74` | JWT 필터, 로그인, refresh 회전·재사용 차단, 로그아웃 | 회원 탈퇴 등 별도 정책 기능 |
| 4 | !98 게시물 공통 도메인·사진 `feat/post-photos` | `62`, `82` | 게시물 aggregate, 위치·공개 정책, 사진 검증·HDFS 저장과 권한 조회 | 게시물 CRUD API |
| 5 | !99 게시물 등록 `feat/post-creation` | `83`, `84` | LOST·SHELTERING multipart 등록, 멱등성, 사진 발행과 위치 동의 | 목록·상세·수정 |
| 6 | !101 게시물 조회 `feat/post-queries` | `85`~`88` | 공개 LOST, 지역 혼합 SHELTERING, 출처별 상세, 내 게시물 커서 조회 | 변경·종료 |
| 7 | !102 게시물 관리 `feat/post-management` | `89`~`91` | 부분 수정, 사진 전체 교체, 종료와 공개·후보 제외 | 매칭 실행·후보 조회 |
| 8 | !103 매칭 API `feat/matching-api` | `92`, `93`, `95`~`97` | PENDING 실행 접수, 상태·이전 성공·STALE·후보 공개 정책 | DATA/AI worker 연동 `94` |
| 9 | !107 1:1 채팅 API `feat/chat-api` | `98`~`101` | 채팅방 생성·목록, 메시지 커서 조회·멱등 전송, 참여자·종료 게시물 권한 | 최초 범위의 읽음·알림 제외는 [#178 계약](../../docs/api-spec.md#8-채팅-api)으로 대체. WebSocket·SSE·첨부는 제외 |
| 10 | !109 수집 상태·일일 요약 `feat/data-source-status` | `102`, `103` | D1 인증 갱신 상태, D2 공개 일일 입소 요약, 마지막 성공 스냅샷 | FCM 단일 발송 `104` |
| 11 | !122 종료 게시물 보존 경계 `bugfix/post-retention-boundary` | 없음 | P2·P7·P8·C2의 종료 후 90일 판정을 PostgreSQL 시계로 통일 | 없음 |

각 묶음의 상세 계약은 [회원가입](member-signup-guide.md), [인증 세션](auth-session-guide.md),
[사진](post-photo-guide.md), [게시물 등록](post-creation-guide.md),
[게시물 조회](post-query-guide.md), [게시물 관리](post-management-guide.md),
[매칭](matching-api-guide.md), [채팅](chat-api-guide.md),
[수집 상태](ingestion-status-api-guide.md) 가이드에서 확인한다.

### 0.3 Jira에는 남았지만 먼저 상태를 감사할 이슈

라이브 Jira에서 다음 이슈는 `해야 할 일`이지만, 관련 스키마나 실행 코드는 이미 `dev`에 있다.
제목만 보고 새 구현 브랜치를 만들면 중복 코드나 V1 migration 수정이 생길 수 있다.

| Jira | 이미 있는 근거 | 시작할 때 확인할 완료 조건 | 처리 원칙 |
| --- | --- | --- | --- |
| `59` 공통 응답·오류·인증 실패 | MR !78, `global/common`, `global/error`, `global/security`, `SecurityContractTest` | 성공·검증·도메인·401·403 응답 테스트 | 빠진 조건이 없으면 Jira 상태만 팀 절차로 정리 |
| `60` Flyway 전환 | MR !78, `V1__initial_schema.sql`, 모든 프로필 `ddl-auto=validate`, `InitialSchemaMigrationTest` | 빈 PostgreSQL에서 Flyway 적용과 전체 테스트 | 공유된 V1을 수정하지 말고 누락 시 새 migration 사용 |
| `63` 매칭 스키마 | V1의 `match_run`·`match_candidate`, MR !103의 JDBC 저장소·통합 테스트 | 상태·rank·candidate count 제약, 관계·정렬·내부 점수 비공개 | JPA entity 문구와 현재 JDBC 설계 차이를 먼저 판정 |
| `65` 채팅 스키마 | V1의 `chat_room`·`chat_message`, MR !107의 JDBC 저장소·통합 테스트 | 중복 방, 참여자, `last_message_at`, 종료 후 읽기 전용, 로그 비노출 | 현재 API 테스트가 완료 조건을 충족하는지 감사 |

이 네 이슈는 **새 기능 잔여 개수에 포함하지 않는다.** 완료 조건 감사에서 실제 누락이 나오면
누락만 작은 `bugfix/*` 또는 `feat/*`로 보완한다. Jira 상태 변경은 사용자 지시 없이 실행하지 않는다.

### 0.4 실제 남은 기능 1 — `#64` 공공 보호동물 PostgreSQL 적재

#### 이미 준비된 것

- Flyway V1에 `animal_case`, `shelter`, `shelter_animal`, `animal_case_location`,
  `animal_photo`, `ingestion_run`과 원본 키·관계·상태 제약이 있다.
- `data/collector`가 공공 API 원문을 수집해 Kafka `shelter.raw`와 HDFS 원문·이미지에
  at-least-once로 적재한다. 하류 멱등 키는 `desertionNo`와 `updTm`이다.
- ERD에 공공 API 필드의 PostgreSQL 매핑, 위치·상태·사진·`listed_at` 정규화 규칙이 있다.
- MR !109의 D1·D2는 `ingestion_run`을 읽는 조회 경로와 계약을 검증했다.

#### 실제로 남은 것

현재 `data/collector`는 Kafka에서 HDFS로 원문과 이미지만 적재하며 PostgreSQL 쓰기 경로가
없다. Backend의 `ingestion` 패키지도 D1·D2 조회 전용이다. 따라서 V1에 테이블이 있다는
이유로 `64`를 완료 처리하면 안 된다. DATA 소유 적재 단계에서 다음을 구현해야 한다.

1. 실행 시작 때 `ingestion_run(RUNNING)`을 만들고 성공·실패와 요청 범위, insert·update·실패·
   보호소 수, 마지막 원본 수정 시각을 기록한다.
2. `careRegNo`로 보호소를, `desertionNo`로 공공 동물을 멱등 upsert하며 같은 입력 재처리가
   `animal_case`를 늘리지 않게 한다.
3. 공공 건 하나에 `PUBLIC/SHELTERING` case, `shelter_animal`, EVENT·CURRENT 위치, 정상 사진
   메타데이터를 계약대로 연결하고 원본 상태 변경을 기존 행에 반영한다.
4. HDFS 사진 논리 키 `{desertionNo}_{1|2}`를 PostgreSQL `animal_photo.id`와 연결할 수 있게 하고,
   이후 `94`가 `desertionNo`를 `match_candidate.target_case_id`로 안전하게 바꿀 경로를 제공한다.

쓰기 코드를 단지 Jira Component가 BE라는 이유로 Spring Boot에 넣지 않는다. 원문 해석과
파이프라인 재시도는 DATA가 소유하고, BE는 공유 스키마·목록·상세·매칭·D1·D2 계약을 검토한다.
구현 전에 입력을 Kafka와 HDFS 중 어디서 읽을지, 한 실행의 commit·재시작 단위, 위치 코드
매핑 실패와 이미지 결손을 실패 건으로 셀 기준을 확정한다.

완료 검증은 실제 PostgreSQL에서 최초 적재, 같은 원문 반복 적재, 더 최신 `updTm` 갱신,
오래된 이벤트 재전달, 일부 레코드 실패, 실행 전체 실패를 다룬다. 같은 `desertionNo`는 하나의
`animal_case`만 가져야 하고, 실행 건수와 서비스 목록·D1·D2 조회가 실제 적재 결과와 일치해야
한다. 이 묶음은 `94`와 `104`보다 먼저 진행하며, 적재기와 계약 테스트가 크면 리뷰 가능한 두
MR로 나눌 수 있지만 Jira 완료는 end-to-end 반복 적재까지 확인한 뒤 처리한다.

### 0.5 실제 남은 기능 2 — `#104` FCM 일일 요약

#### 이미 준비된 것

- `ingestion_run.summary_published_at`과 실행 유형·상태 제약이 V1에 있다.
- D2는 최신 성공 `DAILY_INCREMENTAL` 요약을 익명 조회하며 0건도 정상으로 반환한다.
- FCM 토픽은 `daily-intake-summary`, payload는 `ingestionRunId`, `summaryDate`,
  `animalCount`, `shelterCount`로 계약돼 있다.
- 초기 전체 적재, BACKFILL, 실패 실행, 개별 후보·점수·회원·채팅 정보는 제외한다.

#### 아직 없는 것

- Android의 토픽 구독·해제, 알림 권한, `ingestionRunId` 기반 중복 표시 방지가 없다.
- 실제 Firebase 프로젝트, Backend service account의 운영 read-only mount와 비운영 end-to-end
  발송 검증이 없다.

#### Backend 구현에서 확정한 것

1. 같은 `summaryDate`의 최신 성공 실행만 발송하고, 해당 날짜에 발송 이력이 있으면 이후 재실행은
   제외한다.
2. Spring scheduler가 기본 1분 간격으로 한 건씩 polling하며 FCM 호출 timeout은 최대 10초다.
3. data-only 메시지만 사용하고 실패·불명확한 timeout은 rollback 후 다음 polling에서 재시도한다.
4. 실제 자격증명은 컨테이너 내부 read-only mount 경로에서만 읽고 기능 기본값은 비활성화다.

#### 운영 활성화 전에 남은 것

1. Firebase 프로젝트와 Backend service account를 만들고 Jenkins Secret file mount를 연결한다.
2. Android의 `google-services.json` 배포 방식과 토픽 구독 담당 MR·담당자를 확정한다.
3. Android가 foreground·background·재기동에서 실행 ID를 한 번만 표시하도록 검증한다.
4. 비운영 Firebase에서 0건·정상·timeout·중복 수신 smoke test를 수행한다.

현재 ERD 계약에 가장 직접적인 구현은 한 트랜잭션에서 대상 행을 잠그고 FCM을 호출한 뒤 성공
시 `summary_published_at`을 기록하는 방식이다. 동시 worker는 `SKIP LOCKED`로 건너뛴다. 이
방식은 외부 호출 동안 DB 연결과 행 잠금을 유지하므로 엄격한 FCM timeout과 작은 처리 묶음이
필요하다. 호출 전에 잠금을 풀고 싶다면 별도 선점 상태·lease가 필요하므로 migration과 계약을
먼저 추가해야 한다. FCM 성공 직후 프로세스가 종료되면 DB 기록 없이 푸시만 나갈 수 있어 재시도
중복은 완전히 제거할 수 없다. 서버의 발송 시도는 **at-least-once 재시도**로 설계하되 FCM부터
기기까지의 end-to-end 도달을 보장한다고 표현하지 않는다. Android는 확정한 렌더링 방식에 따라
실행 ID로 중복 표시를 막고, 푸시를 받지 못한 기기는 D2에서 같은 요약을 확인한다. 외부 호출과
DB 쓰기가 원자적이라고 주장하면 안 된다.

테스트는 fake FCM adapter로 대상 실행 선별, 신규 0건, 동시 선점, 성공 기록, 실패 rollback,
재시도, provider 접수 직후 프로세스 종료, 결과가 불명확한 timeout, 동일 일자의 복수 성공 실행,
payload와 민감정보 비포함을 검증한다. Android는 foreground·background·프로세스 재기동과 동일
실행 재수신을 검증한다. 실제 Firebase 자격증명이 없으면 adapter 단위까지는 구현할 수 있지만
운영 완료로 처리하지 않는다. 이 기능은 다른 Jira와 묶지 않고 단독 MR로 낸다.

### 0.6 실제 남은 기능 3 — `#94` MapReduce 작업 연동

#### 이미 준비된 것

- M2가 본인 활성 LOST 게시물에 대해 `match_run(PENDING)`을 만들고 중복 진행 실행을 재사용한다.
- M1이 PENDING·RUNNING·SUCCEEDED·FAILED, 이전 성공 결과와 STALE 후보를 조회한다.
- `data/mapreduce/`에는 K-Means, kNN 조인, 행렬곱, 쎄타조인, 자카드 셀프조인 5종과 실측
  도구가 구현돼 있다. `data/mapreduce/README.md`가 진입점이다.
- HDFS `/embeddings/ACTIVE`, 768차원 벡터, 모델 ID·버전과 후보 공개 계약이 문서화돼 있다.

#### 핵심 오해를 피할 것

MapReduce 알고리즘 구현 완료가 온디맨드 요청 연동 완료를 뜻하지 않는다. YARN 잡은 기동에 약
15초가 걸리므로 M2 요청마다 새 MapReduce 잡을 실행하지 않는다. 현재 계약은 PostgreSQL
`match_run(PENDING)`을 내구성 큐로 사용하고 장기 실행 DATA/AI worker가
`FOR UPDATE SKIP LOCKED`로 선점하는 구조다. Spring Boot가 점수를 계산하거나 Top-K를 다시
정렬하면 안 된다. 온디맨드 `94`는 ADR-002의 K-Means 사전 색인과 worker 직접 비교 경로를
사용한다. kNN은 P1 지속 감시, 행렬곱·자카드는 배치 재식별·입양 경로이며, 쎄타조인도 별도
ADR 변경 없이 M2 요청마다 실행하거나 필수 출력으로 조합하지 않는다. 별도 팀 결정 없이 Kafka
publisher를 Spring에 추가하지 않는다.

#### 아직 없는 것

- 실제 HDFS·AI 산출물을 사용하는 장기 실행 engine endpoint와 확정 임계값 주입.
- 서버 2 systemd 설치, 최소 권한 PostgreSQL 역할과 내부 engine 네트워크 연결.
- 실제 PENDING 실행의 성공·후보 0건·실패·timeout 및 M1 조회 smoke test 기록.

#### worker 구현에서 확정한 것

- `data/matching_worker/`가 `FOR UPDATE SKIP LOCKED` 선점과 RUNNING 전이를 짧은 트랜잭션으로
  수행하고, 60초 제한의 내부 HTTP 계약으로 장기 실행 DATA/AI engine에 요청한다.
- engine 응답 순위를 재계산하지 않고 후보 적재와 SUCCEEDED를 한 트랜잭션으로 완료한다.
- 실패 코드는 `MATCH_FAILED`·`MATCH_TIMEOUT`으로 제한하며 5분 watchdog와 늦은 완료 차단을
  실제 PostgreSQL V1 통합 테스트로 검증한다.
- systemd 배포 단위는 추가했지만 실제 endpoint와 Secret을 저장소에 넣지 않으며, smoke test 전
  Jira를 완료로 보지 않는다.

#### 실제 engine 구현·운영 검증 전에 DATA·AI·BE가 확정할 것

1. worker는 이 저장소 `data/matching_worker/`와 서버 2 systemd로 정했다. 실제 engine의 HDFS·AI
   접근 방식과 내부 endpoint 배포는 남아 있다.
2. 사진 1~10장의 게시물 단위 점수 집계(max/mean 등)와 최종 임계값.
3. 활성 모델의 K-Means `centers.tsv`·assignment를 읽어 nprobe 후보를 고르고 직접 유사도를
   계산하는 형식과 worker가 직접 적용할 날짜·지역·선택 태그 필터.
4. worker 상태 전이 SQL, 실패 코드와 재시작 복구는 구현했다. 운영 연결 후 실제 장애 주입으로
   한 번 더 검증한다.

현재 MR은 계산 규칙을 추측하지 않고 worker 선점·상태 전이와 engine 결과 적재 경계를 구현한다.
engine 계산·실제 HDFS/AI 연결은 위 결정을 확정한 뒤 별도 리뷰 가능한 변경으로 추가할 수 있다.
Jira 완료는 실제 환경에서 PENDING → RUNNING → SUCCEEDED/FAILED, 후보 0건, 중복 요청, timeout과
늦은 완료를 검증한 뒤 처리한다.

`npm run check`와 `npm run check:data`는 `data/mapreduce` Gradle 모듈을 검사하지 않는다. `94`가
이 모듈이나 색인 산출물 계약을 변경·소비하면 `cd data/mapreduce && ./gradlew test jar`를 별도로
실행한다. Jira 완료 전에는 활성 모델 색인과 실제 PostgreSQL·HDFS를 사용한 YARN/worker smoke
test로 한 PENDING 실행의 성공·후보 0건·실패·timeout 경로를 확인한다.

### 0.7 다음 작업 선택 순서

1. 새 채팅에서 Jira `59`, `60`, `63`, `65`의 완료 조건과 현재 코드·테스트를 감사한다.
   누락이 없으면 사용자에게 Jira 상태 정리 대상으로 보고하고 기능을 재구현하지 않는다.
2. `64`의 DATA 소유 PostgreSQL 적재와 반복 적재 멱등성을 먼저 구현한다. 이 경로가 실제
   `ingestion_run`과 공공 `target_case_id`를 제공해야 `104`와 `94`를 운영 검증할 수 있다.
3. Firebase 프로젝트·Secret·Android 토픽 구독 작업이 준비됐으면 `104`를 단독 기능으로 한다.
4. `94`의 worker 상태 경계는 구현됐으므로 DATA/AI engine 계산 규칙과 서버 2 endpoint를 확정하고
   실제 smoke test를 수행한다. 임의 Kafka publisher는 추가하지 않는다.
5. 둘 다 선행 조건이 없으면 기능 개발을 멈춘 것이 아니라 **외부 연동 준비가 다음 작업**이다.
   필요한 계정·배포·계약을 완료한 뒤 구현한다.

현재 `64`·`104`의 Backend 구현과 `94`의 worker 상태 경계까지 준비됐다. 다음 우선순위는
**`94 engine 규칙 확정·실환경 smoke → 104 Firebase/Android smoke`**이며 서로 다른 운영 경계이므로
같은 브랜치나 MR로 합치지 않는다.

### 0.8 새 채팅에서 그대로 사용할 시작 프롬프트

```text
멍고반점 백엔드 작업을 이어서 진행하자.

1. AGENTS.md와 backend/docs/backend-codex-start-guide.md의 0절을 먼저 읽어.
2. git status, 현재 브랜치, origin/dev 최신 커밋을 확인하고 사용자 변경을 보존해.
3. Atlassian MCP로 Component=BE이며 Done이 아닌 Jira를 다시 조회해.
4. Jira 상태와 실제 dev 코드가 다른 59, 60, 63, 65는 완료 조건·MR·테스트를 감사하고
   제목만 보고 재구현하지 마.
5. 부분 완료인 64는 PostgreSQL 쓰기 경로와 반복 적재 멱등성이 실제로 없는지 확인하고,
   DATA 소유 적재 구현을 94와 104보다 먼저 진행해.
6. 실제 신규 기능 후보 94와 104의 선행 결정이 준비됐는지 확인해.
7. 담당자 이름이 아니라 같은 데이터·트랜잭션·외부 연동을 기준으로 작업 묶음을 제안해.
8. 선택할 이슈의 구현 범위, 제외 범위, 필요한 결정, 변경 파일과 RED-GREEN 테스트를 먼저
   설명하고 내 승인을 받은 뒤 구현해.

현재 기준은 2026-09-10 dev의 MR !109 병합 이후지만, 반드시 라이브 Jira와 origin/dev로
변경 여부를 검증해. 64는 스키마와 조회 API만 있고 DATA의 PostgreSQL 적재가 남아 있어.
MapReduce 5종은 data/mapreduce에 이미 구현되어 있고, 94의 미완료 부분은 온디맨드 worker·
상태 전이·결과 적재 연동이야. 104의 미완료 부분은 Firebase/Android 준비와 FCM 단일 발송이야.
```

작업 시작 시 다음 명령과 조회를 먼저 수행한다.

```bash
git status --short --branch
git fetch origin
git log --oneline --decorate -10 origin/dev
```

변경 파일이나 진행 중인 기능 브랜치가 있으면 그 자리에서 원인을 확인하고 사용자 변경을 보존한다.
새 작업을 시작하며 작업 트리가 깨끗할 때만 아래처럼 로컬 `dev`를 갱신하고 정책에 맞는 새 브랜치를
만든다. 기존 작업 브랜치 동기화는 [브랜치 전략](../../docs/branch-strategy.md)에 따라 `git merge dev`를 사용한다.

```bash
git switch dev
git pull origin dev
git switch -c feat/<기능-이름>
```

Jira에서는 `project = <프로젝트 키> AND component = BE AND statusCategory != Done ORDER BY key ASC`를
조회한다. 특정 이슈를 고른 뒤 `git log --all --grep='#<번호>'`와 `rg`로 기존 구현을
찾는다. 이 절의 기준 커밋보다 `origin/dev`가 앞서 있으면 새 MR·Jira 완료 상태를 먼저 반영해
인계 표를 갱신한다.

### 0.9 이 인계 기록을 갱신하는 시점

백엔드 기능 MR이 병합될 때마다 다음을 같은 문서 MR 또는 바로 다음 문서 정리 MR에 반영한다.

- 기준 날짜, `origin/dev` 병합 커밋과 마지막 백엔드 MR
- 완료된 기능 단위, Jira 번호, 실제 포함·제외 범위와 새 도메인 가이드 링크
- 남은 이슈의 라이브 상태와 새로 발견한 선행 결정·외부 의존성
- Jira와 코드 상태가 다른 이슈의 감사 결과
- 다음 새 채팅 프롬프트에서 더 이상 유효하지 않은 문장

MR이 병합됐다는 이유만으로 Jira 완료 조건을 충족했다고 단정하지 않는다. 반대로 Jira가
`해야 할 일`이라는 이유만으로 이미 병합된 기능을 다시 구현하지 않는다. 코드·테스트·계약과
Jira 완료 조건을 함께 확인한 결과가 인계 기록의 근거다.

## 1. 시작 전에 준비할 것

- 저장소 루트에서 `npm run setup`을 한 번 실행합니다.
- Docker Desktop 또는 Docker Engine을 실행합니다. 백엔드 테스트는 별도 DB를 만들지 않아도
  Testcontainers가 PostgreSQL 17을 자동으로 준비합니다.
- 개발 서버를 실행할 때는 `docker compose -f compose.dev.yml up -d postgres`로 로컬 DB를
  먼저 실행합니다.
- 커밋 전 전체 게이트를 실행하려면 Android SDK 경로도 필요합니다. Android를 직접 개발하지
  않더라도 `npm run check`가 Android와 Backend를 함께 검사합니다.
- Jira 내용을 Codex가 직접 읽게 하려면 개인 Atlassian MCP 인증을 완료합니다. 연결할 수 없는
  환경에서는 이슈 Description과 완료 조건을 프롬프트에 붙여 넣습니다.

## 2. Codex에게 처음 요청하는 방법

이슈 번호만 던지고 바로 코드를 작성하게 하지 말고, 계약과 의존성을 먼저 확인하도록 요청합니다.

```text
Jira #<번호> 백엔드 이슈를 구현하려고 해.

먼저 다음 작업만 해줘.
1. git status와 현재 브랜치를 확인한다.
2. origin/dev와 라이브 Jira를 갱신하고, Jira 상태와 이미 병합된 코드가 같은지 확인한다.
3. Jira의 Epic, Description, 완료 조건, 의존 이슈, 담당자와 Sprint를 확인한다.
4. AGENTS.md와 backend/docs/backend-codex-start-guide.md의 0절을 읽는다.
5. 이 이슈에 필요한 정책 문서와 API·DB 계약, 기존 도메인 가이드를 찾아 읽는다.
6. git log --all --grep와 rg로 같은 Jira 또는 기능의 기존 구현이 있는지 찾는다.
7. 구현 범위, 제외 범위, 예상 변경 파일, 테스트 방법과 blocker를 보고한다.

아직 코드를 수정하거나 커밋·push·MR을 만들지 말고 짧은 설계를 먼저 제시해줘.
```

Codex의 분석이 Jira 완료 조건과 문서 계약에 맞으면 다음처럼 구현을 승인합니다.

```text
제시한 범위로 진행해줘. 테스트를 먼저 작성해 예상한 이유로 실패하는 RED를 확인한 뒤
최소 구현으로 GREEN을 만들고, 관련 회귀 테스트와 npm run check:be를 실행해줘.
커밋과 push는 하지 마.
```

## 3. 반드시 읽는 순서

모든 백엔드 이슈에서 문서 전체를 무조건 읽을 필요는 없습니다. 다음 공통 문서를 읽고, 작업
종류에 맞는 문서를 추가합니다.

### 공통 필수 문서

| 순서 | 문서 | 확인할 내용 |
| ---: | --- | --- |
| 1 | [AGENTS.md](../../AGENTS.md) | 금지 작업, 필수 명령, Source of Truth, 프로젝트 스킬 |
| 2 | Jira 이슈 | Epic, 완료 조건, 범위, 의존성, 담당자, Sprint |
| 3 | [MVP 요구사항](../../docs/product/mvp-user-requirements-spec.md) | 기능 범위와 인수 조건, MVP와 P1 경계 |
| 4 | [API 명세](../../docs/api-spec.md) | Method, Path, 인증, 요청·응답, 상태 코드와 오류 코드 |
| 5 | [Backend API 개발 가이드](backend-api-development-guide.md) | 계층 책임, 패키지 구조, RED-GREEN-REFACTOR 순서 |
| 6 | [코드 컨벤션](../../docs/code-convention.md) | 이름, 구조, 오류 처리, 로깅, 테스트 규칙 |
| 7 | [브랜치 전략](../../docs/branch-strategy.md) | 브랜치 출발점·이름·MR 대상과 `merge dev` 규칙 |

커밋할 때는 [커밋 전략](../../docs/commit-strategy.md), MR을 준비할 때는
[MR 가이드](../../docs/mr-guide.md)를 그 시점에 추가로 읽습니다.

### 작업 종류별 추가 문서

| 작업 종류 | 추가로 읽을 문서 |
| --- | --- |
| 공통 응답·예외·Security | [Backend 공통 설정](backend-common-settings.md), [Ping 예제](backend-ping-guide.md) |
| 회원가입·로그인·JWT·세션 | [인증 토큰 정책](../../docs/auth-token-policy.md), [비밀번호 정책](../../docs/password-policy.md), [보안·운영 정책](../../docs/backend-security-operations-policy.md), [ERD](../../docs/erd.md) |
| 휴대전화 인증·회원 탈퇴·개인정보 | [보안·운영 정책](../../docs/backend-security-operations-policy.md), [ERD](../../docs/erd.md), [API 명세](../../docs/api-spec.md) |
| 게시물 등록·수정·종료·조회 | [게시물 날짜·위치 정책](../../docs/post-date-location-policy.md), [사용자 흐름](../../docs/product/user-flow.md), [ERD](../../docs/erd.md) |
| 사진 업로드·조회·HDFS | [사진 업로드 정책](../../docs/photo-upload-policy.md), [ERD](../../docs/erd.md), [아키텍처](../../docs/architecture.md) |
| 매칭 실행·후보 조회 | [DATA·AI 인터페이스](../../docs/data-ai-interface.md), [아키텍처](../../docs/architecture.md), [ERD](../../docs/erd.md) |
| 채팅 | [API 명세](../../docs/api-spec.md), [사용자 흐름](../../docs/product/user-flow.md), [ERD](../../docs/erd.md), [보안·운영 정책](../../docs/backend-security-operations-policy.md) |
| 공공데이터 적재·일일 요약 | [DATA·AI 인터페이스](../../docs/data-ai-interface.md), [빅데이터 클러스터](../../docs/bigdata-cluster.md), [ERD](../../docs/erd.md) |
| DB 테이블·인덱스·엔티티 | [ERD](../../docs/erd.md), [보안·운영 정책](../../docs/backend-security-operations-policy.md) |
| 배포·운영 설정 | [배포 가이드](../../docs/deploy-guide.md), [인프라 ADR](../../docs/handoff/ADR-001-인프라-결정기록.md) |

문서와 코드 또는 실제 설정이 다르면 Codex가 임의로 한쪽을 선택하면 안 됩니다. 파일 경로와
근거를 제시해 `BLOCKER` 정책 충돌로 보고하고 팀 결정을 기다립니다.

## 4. 현재 베이스 코드에서 재사용할 것

아래 Java 경로는 `backend/src/main/java/com/meonggo/backend/`, 테스트 경로는
`backend/src/test/java/com/meonggo/backend/`를 기준으로 적었습니다.

| 목적 | 기준 파일 |
| --- | --- |
| 의존성과 품질 검사 | `backend/build.gradle` |
| 환경변수, JPA validate, Flyway | `backend/src/main/resources/application.yml` |
| PostgreSQL 초기 스키마 | `backend/src/main/resources/db/migration/V1__initial_schema.sql` |
| 성공·오류 JSON envelope | `global/common/response/ApiResponse.java` |
| 공통 오류 계약 | `global/error/ErrorCode.java`, `BusinessException.java` |
| MVC 예외 변환 | `global/error/GlobalExceptionHandler.java` |
| 생성·수정 시각 | `global/common/entity/BaseTimeEntity.java` |
| 공개 경로와 기본 보호 정책 | `global/security/SecurityConfig.java` |
| 인증·인가 오류 | `auth/exception/AuthErrorCode.java`, `global/security/*Handler.java` |
| 구현 예제 | `ping/controller`, `ping/service`, `ping/dto`, `ping/exception` |
| 휴대전화 가입 | `member/*`, `member-signup-guide.md` |
| JWT 로그인 세션 | `auth/*`, `auth-session-guide.md` |
| 사진 저장·조회 | `photo/*`, `post-photo-guide.md` |
| 게시물 등록·조회·관리 | `post/*`, `post-creation-guide.md`, `post-query-guide.md`, `post-management-guide.md` |
| 매칭 요청·후보 조회 | `matching/*`, `matching-api-guide.md` |
| 1:1 채팅 | `chat/*`, `chat-api-guide.md` |
| 수집 상태·일일 요약 | `ingestion/*`, `ingestion-status-api-guide.md` |
| DB 계약 테스트 | `database/InitialSchemaMigrationTest.java` |
| Security 계약 테스트 | `global/security/SecurityContractTest.java` |

현재 베이스라인에는 JWT 검증, 회원가입·로그인, 게시물·사진, 매칭 API, 채팅, 수집 상태·일일
요약의 실제 도메인 로직이 이미 있다. 새 이슈는 위 구현을 확장하며, 같은 Controller·Service·
Repository나 응답 DTO를 평행 구조로 다시 만들지 않는다. 특히 매칭 계산 worker와 FCM 발송은
아직 없지만, 이를 이유로 이미 완료된 요청·조회 API나 D2 요약 API를 다시 구현하지 않는다.

## 5. 이슈 하나를 구현하는 표준 순서

### 5.1 상태와 범위 확인

1. `git status`와 현재 브랜치를 확인합니다.
2. 새 작업은 최신 `dev`에서 `feat/<description>`을 만듭니다. 기존 작업 브랜치 동기화는
   `git merge dev`만 사용하고 rebase하지 않습니다.
3. 다른 사람이 만든 미커밋 변경은 덮어쓰거나 되돌리지 않습니다.
4. Jira 완료 조건을 API 명세와 연결하고, 이번 이슈에서 하지 않을 범위도 적습니다.
5. 선행 API·테이블·다른 개발자 작업이 필요한지 확인합니다.

### 5.2 계약을 먼저 고정

코드 작성 전에 다음을 한 번에 설명할 수 있어야 합니다.

- HTTP Method와 `/api/v1` Path
- 공개 API인지, 인증만 필요한지, 소유권·참여자 권한까지 필요한지
- 요청 DTO 필드와 검증 규칙
- 정상 HTTP 상태와 `ApiResponse`의 `data`
- 실패 HTTP 상태, 도메인 오류 코드와 안전한 메시지
- 트랜잭션 경계, 멱등성, 동시성 규칙
- 사용할 테이블·인덱스와 개인정보·로그 제한

계약이 Jira와 문서에서 일치하지 않으면 구현 전에 질문합니다.

### 5.3 테스트부터 구현

동작 하나마다 다음 순서를 반복합니다.

1. 관찰 가능한 동작을 검증하는 테스트를 작성합니다.
2. 테스트를 실행해 기능 미구현 때문에 실패하는 RED를 확인합니다.
3. 테스트를 통과시키는 최소 구현을 작성합니다.
4. 같은 테스트로 GREEN을 확인합니다.
5. 중복과 이름을 정리하고 관련 회귀 테스트를 실행합니다.

API는 정상 응답뿐 아니라 검증 실패, 인증·인가 실패, 리소스 없음, 상태 충돌과 내부 정보
미노출을 함께 검증합니다. Repository나 DB 제약이 핵심이면 H2가 아니라 PostgreSQL
Testcontainers 통합 테스트를 사용합니다.

### 5.4 베이스라인 규칙 적용

- Controller는 HTTP와 `ApiResponse.success(...)`만 담당합니다.
- Service는 비즈니스 규칙과 트랜잭션을 담당하고 DTO 또는 `BusinessException`을 반환합니다.
- Repository는 데이터 접근만 담당합니다.
- 도메인 오류는 `<domain>/exception/*ErrorCode`에 두고 이미 배포된 코드 의미를 바꾸지 않습니다.
- 정상 Ping과 dev Error Ping 외의 경로는 기본적으로 인증이 필요합니다. 공개 API를 추가하면
  `SecurityConfig` 변경과 익명 접근 계약 테스트를 함께 추가합니다.
- `ddl-auto`는 모든 프로필에서 `validate`를 유지합니다.
- 공유 브랜치에 반영된 Flyway migration은 수정하지 않습니다. 초기 V1 이후 변경은 다음 버전의
  새 migration으로 추가합니다.
- DB 스키마와 JPA Entity를 같은 변경에서 일치시키고 애플리케이션 기동 테스트를 포함합니다.
- 토큰, 비밀번호, 전화번호, 정확한 위치, 암호화 키와 내부 저장 경로를 응답이나 로그에 남기지
  않습니다.

### 5.5 단계별 검증

```bash
# 작업 중: 바뀐 테스트만 빠르게 반복
cd backend
./gradlew test --tests '<테스트 클래스>' --console=plain

# 백엔드 구현 완료
cd ..
npm run fix:be
npm run check:be

# 커밋 전 프로젝트 전체 게이트
npm run check
```

`npm run check`가 환경 문제로 실패하면 실패 지점과 원문을 보고하고, 통과하지 않은 전체 게이트를
통과했다고 표현하지 않습니다. Docker가 없으면 DB 테스트가, Android SDK가 없으면 Android
검사가 실행되지 않습니다.

## 6. Codex 결과를 검토하는 기준

Codex의 완료 보고에는 다음 내용이 있어야 합니다.

- 실제 변경한 기능과 의도적으로 제외한 기능
- 주요 변경 파일과 줄 번호
- RED에서 확인한 실패 이유와 GREEN 결과
- 실행한 focused test, `check:be`, `npm run check`의 실제 결과
- 남은 `BLOCKER`, `WARNING`, 후속 Jira 의존성
- 커밋·push·MR 생성 여부

테스트 실행 없이 “완료”, 환경 오류가 있는데 “전체 통과”, Jira에 없는 기능의 선제 구현,
문서와 다른 임의 계약은 승인하지 않습니다.

## 7. 커밋과 MR 요청 프롬프트

구현 검토가 끝난 뒤에만 커밋을 별도로 요청합니다.

```text
현재 이슈 변경 파일만 정확히 스테이징하고 commit-staged-changes 스킬을 사용해줘.
변경을 목적별로 나눈 커밋 계획과 Conventional Commits 메시지, Jira 연결 문구를 먼저 보여주고
내 승인을 기다려. git add . 또는 git add -A는 사용하지 마.
```

MR 준비는 다음처럼 요청합니다.

```text
pre-mr-readiness로 현재 브랜치를 읽기 전용 검사해줘.
통과하면 draft-mr로 dev 대상 GitLab MR 초안을 작성하되, 아직 push하거나 MR을 생성하지 마.
```

MR이 생성된 뒤에는 파이프라인뿐 아니라 AI 리뷰 코멘트도 확인합니다. 유효한 지적은 현재 MR에
반영하거나 후속 Jira 이슈로 명시하고, 오탐은 코드와 테스트 근거로 설명합니다.

## 8. Codex가 임의로 하면 안 되는 것

- `main`이나 `dev`에서 직접 작업·커밋·push
- rebase, force push, reset, 사용자 변경 되돌리기
- `git add .`, `git add -A`로 범위를 확인하지 않은 일괄 스테이징
- 승인 없는 커밋·push·MR·Jira 상태 변경
- Jira 완료 조건 밖의 선제 기능 구현
- 문서 충돌을 임의로 해석하거나 V1 migration을 공유 뒤 수정
- 실제 Secret·개인정보를 출력하거나 코드·로그·문서에 기록
- 테스트 실패 또는 미실행 상태를 성공으로 보고

## 9. 완료 체크리스트

- [ ] Jira 완료 조건과 제외 범위를 설명할 수 있다.
- [ ] 작업별 필수 정책·API·ERD 문서를 읽었다.
- [ ] 브랜치가 정책에 맞고 다른 사람의 변경을 침범하지 않았다.
- [ ] 테스트가 구현 전 예상한 이유로 실패하는 RED를 확인했다.
- [ ] 최소 구현으로 GREEN을 만들고 관련 회귀 테스트를 통과했다.
- [ ] API 응답, 오류 코드, Security와 로그가 공통 계약을 따른다.
- [ ] DB 변경은 PostgreSQL migration과 통합 테스트를 포함한다.
- [ ] `npm run fix:be`, `npm run check:be`, `npm run check` 결과를 확인했다.
- [ ] Secret·로컬 설정·불필요한 파일이 diff에 없다.
- [ ] 커밋과 MR은 별도 승인 절차를 거쳤다.
- [ ] MR의 GitLab 파이프라인과 실제 AI 리뷰 코멘트를 확인했다.
- [ ] 병합된 기능 단위, 완료 Jira와 새 blocker를 0절 인계 기록에 반영했다.
- [ ] Jira 상태와 코드가 다르면 재구현하지 않고 완료 조건 감사 결과를 남겼다.
