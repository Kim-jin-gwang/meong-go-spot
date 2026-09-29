# 외부 연동 준비 가이드

`#104`(FCM 일일 요약)과 `#94`(온디맨드 매칭 worker)는 이미 있는
API·스키마를 다시 구현하는 작업이 아니다. 이 문서는 외부 계정·Secret·실행 책임이 준비된 뒤에만
각 기능 MR을 시작하도록 하는 인계·운영 체크리스트다.

## 현재 기준

| 연동 | Jira | 현재 코드 경계 | 구현 시작 상태 |
| --- | --- | --- | --- |
| FCM 일일 입소 요약 | `#104` | Backend 선점·발송·재시도 구현, D2는 조회 전용 유지 | Firebase 프로젝트·service account mount·Android 수신 검증 필요 |
| DATA/AI 매칭 worker | `#94` | DATA worker의 claim·완료·watchdog와 engine HTTP adapter 구현 | 실제 engine endpoint·서버 2 배포·HDFS/AI smoke test 필요 |

둘은 실패 모델과 운영 소유자가 다르므로 같은 브랜치나 MR로 구현하지 않는다. 이 문서는 준비
증거만 다룬다. 실제 service account 파일, worker adapter, Kafka publisher를 선행 결정 없이
추가하지 않는다.

## FCM 일일 요약 준비

### 고정 계약

- 대상은 `summary_published_at IS NULL`인 `SUCCEEDED` + `DAILY_INCREMENTAL` 실행뿐이다.
  `INITIAL_FULL`, `BACKFILL`, `RUNNING`, `FAILED`는 제외한다.
- 토픽은 `daily-intake-summary`이며 data payload에는 `ingestionRunId`, `summaryDate`,
  `animalCount`, `shelterCount`만 넣는다. 후보·점수·회원·전화번호·채팅·원문은 넣지 않는다.
- 신규 0건 성공 실행도 대상이다. D2는 푸시 수신 여부와 무관한 앱 내 확인 경로다.
- 외부 FCM 호출과 `summary_published_at` 기록은 원자적일 수 없다. 서버는 at-least-once로
  재시도될 수 있고 Android가 `ingestionRunId`로 중복 표시를 막는다.

### 구현을 시작하기 전 준비 증거

| 항목 | 담당 경계 | 필요한 증거 |
| --- | --- | --- |
| Firebase 프로젝트 | 팀/인프라 | 프로젝트 ID와 운영·비운영 환경 구분. 값 자체는 저장소·Jira·MR에 쓰지 않는다 |
| Backend 자격증명 | 인프라 | Jenkins Secret file로 service account JSON을 read-only mount한 경로. Git 추적·이미지 포함 금지 |
| Android 수신 | Android | 알림 권한, `daily-intake-summary` 구독·해제, foreground/background/restart에서 실행 ID 1회 표시 확인 |
| 발송 실행 방식 | BE/인프라 | 단일 또는 다중 Spring 인스턴스 수, polling 주기, provider timeout·재시도 상한 합의 |
| 운영 검증 | BE/Android | 비운영 Firebase에서 0건·정상·실패·timeout·재시도·중복 수신을 확인한 기록 |

### Backend 구현 상태

1. `FOR UPDATE SKIP LOCKED` 선점, 기본 1분 polling, 최대 10초 FCM timeout을 구현했다.
2. 같은 `summaryDate`에는 최신 성공 실행만 보내며 이미 그 날짜를 발송했다면 재실행 결과를
   보내지 않는다.
3. provider 실패 rollback·재시도와 접수 뒤 DB 기록 실패 시 at-least-once 재발송을 실제
   PostgreSQL 통합 테스트로 검증했다.
4. 실제 adapter는 read-only Secret file 경로에서만 자격증명을 읽고, 기능은 기본적으로 꺼져
   있다. Firebase 프로젝트·Secret mount·Android 검증 전에는 활성화하지 않는다.
5. 현재 단일 발송 보장을 위해 provider 호출 중 DB 잠금을 최대 10초 유지한다. 처리량 확장 시에는
   영속 claim/lease와 stale claim 복구를 먼저 추가한 뒤 외부 I/O를 트랜잭션 밖으로 분리한다.

## DATA/AI 매칭 worker 준비

### 고정 계약

- PostgreSQL `match_run(PENDING)`이 내구성 큐다. Spring Boot는 점수·임계값·Top-K·후보 rank를
  계산하거나 MapReduce job을 요청마다 기동하지 않는다.
- worker는 `FOR UPDATE SKIP LOCKED`로 PENDING을 선점해 `RUNNING`과 `started_at`을 기록한 뒤
  DB 잠금을 해제한다.
- worker만 현재 query case version, 유효 사진 ID, `modelId`/`modelVersion`, HDFS 활성 모델과
  후보 조건을 검증하고 `match_candidate`와 `candidate_count`, `SUCCEEDED`를 같은 완료
  트랜잭션으로 기록한다.
- 실패는 `MATCH_FAILED` 또는 `MATCH_TIMEOUT`처럼 안전한 분류만 기록한다. 원문, 경로,
  stack trace, 점수는 저장·로그·응답에 넣지 않는다.
- RUNNING hard timeout은 60초, stale watchdog 기준은 5분이며 늦은 완료는 상태 조건으로 막는다.

### 구현을 시작하기 전 준비 증거

| 항목 | 담당 경계 | 필요한 증거 |
| --- | --- | --- |
| worker 배포 단위 | DATA/AI/인프라 | 저장소 위치, 실행 호스트, systemd·컨테이너 여부, 재시작·로그 수집 책임 |
| 네트워크·권한 | 인프라 | worker의 PostgreSQL 최소 권한, HDFS 읽기 범위, AI 모델·`/embeddings/ACTIVE` 접근 확인 |
| 입력·출력 계약 | DATA/AI/BE | claim SQL, 상태 전이 조건, 후보 필터·점수 집계·Top-K·멱등 키를 문서와 fixture로 합의 |
| 복구 정책 | DATA/AI/BE | 중복 claim, worker 중단, provider/모델 timeout, 늦은 완료, 후보 0건의 처리 기준 |
| 실환경 smoke test | DATA/AI/BE | PENDING → RUNNING → SUCCEEDED/FAILED, 0건, timeout, 재시작 후 재처리의 PostgreSQL·HDFS 증거 |

### Worker 구현 상태

1. `data/matching_worker/`가 PENDING 선점, RUNNING 전이, 60초 engine timeout, 후보·성공 원자
   완료, 안전한 실패 코드와 5분 watchdog를 구현한다.
2. 점수·임계값·Top-K는 장기 실행 DATA/AI engine이 소유한다. worker는 run·model tuple과 결과
   형식을 검증하지만 계산하거나 재정렬하지 않는다.
3. 실제 PostgreSQL V1 통합 테스트가 다중 worker 단일 선점, 0건 성공, 후보 적재, 실패,
   watchdog와 늦은 완료 차단을 검증한다.
4. 서버 2 systemd 배포와 실제 HDFS·AI engine endpoint 연결 및 M1 조회 smoke test는 운영 완료
   전에 남아 있다. 이 증거 전에는 Jira를 완료 처리하지 않는다.

## 공통 Secret·배포 규칙

- 실제 JSON, access token, private key, DB DSN, HDFS 인증정보는 `.env`, `.secrets/`, Jenkins
  credential에만 두고 커밋·MR·Jira 댓글·애플리케이션 로그에 넣지 않는다.
- `.env.example`에는 값이 아닌 키·파일 경로 정책만 추가한다. 코드가 아직 읽지 않는 환경변수를
  추측해 추가하지 않는다.
- 외부 호출 timeout과 실패 분류는 구현 MR에서 테스트 가능한 코드 상수 또는 검증된 설정으로
  확정한다. 무제한 재시도나 비밀값 포함 예외 출력은 허용하지 않는다.
- 준비 증거가 없는 상태에서는 fake adapter나 빈 worker를 배포 완료로 표현하지 않는다.

## 완료 판정

이 문서의 체크리스트가 채워졌다고 `104`나 `94`가 완료되는 것은 아니다. 각 Jira 완료 조건의
코드·회귀 테스트·비운영 또는 실제 환경 검증이 모두 끝난 뒤에만 Jira를 완료 처리한다.

기준 문서: [Backend Codex 시작 가이드](backend-codex-start-guide.md),
[매칭 API 가이드](matching-api-guide.md), [수집 상태 API 가이드](ingestion-status-api-guide.md),
[DATA·AI 인터페이스](../../docs/data-ai-interface.md), [ERD](../../docs/erd.md),
[보안·운영 정책](../../docs/backend-security-operations-policy.md).
