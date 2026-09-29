# 보호동물 데이터 상태 API

`ingestion_run`에 기록된 공공 보호동물 수집 이력을 읽어 갱신 상태와 최신 일일 입소 요약을
제공한다. Spring Boot는 이 API에서 수집 실행을 만들거나 수정하지 않는다.

| API | 인증 | 동작 |
| --- | --- | --- |
| `GET /api/v1/data-sources/shelter-animals/status` | 필요 | 최신 운영 수집 상태와 마지막 성공 스냅샷 조회 |
| `GET /api/v1/data-sources/shelter-animals/daily-summary` | 불필요 | 최신 성공 일일 증분의 입소 요약 조회 |

## 갱신 상태

D1은 `ANIMAL_PROTECTION_API` 실행만 사용하며 `BACKFILL`을 제외한다. 일일 증분 실행이 하나라도
있으면 최신 `DAILY_INCREMENTAL`을 현재 시도로 사용하고, 아직 없으면 최신 `INITIAL_FULL`을
사용한다. `lastAttemptAt`은 이 실행의 `started_at`이다.

상태는 다음 순서로 결정한다.

1. 최신 운영 실행이 `RUNNING`이면 `RUNNING`
2. 최신 운영 실행이 `FAILED`이면 `FAILED`
3. 성공 이력이 없으면 `NEVER_SYNCED`
4. 마지막 성공 완료가 현재보다 36시간 이전이면 `DELAYED`
5. 나머지는 `SUCCEEDED`

마지막 성공 실행은 성공한 일일 증분을 우선하고, 아직 없으면 성공한 초기 전체 적재를 사용한다.
`lastSuccessfulAt`, `lastSourceUpdatedAt`과 다섯 집계값은 모두 같은 마지막 성공 실행에서 가져온다.
따라서 최신 실행이 실패하거나 실행 중이어도 마지막 정상 데이터의 시각과 집계는 유지된다. 성공
이력이 없으면 성공·원본 시각을 생략하고 집계는 0을 반환한다.

## 일일 입소 요약

D2는 최신 `SUCCEEDED DAILY_INCREMENTAL` 한 건만 `completed_at`, ID 내림차순으로 선택한다.
`INITIAL_FULL`, `BACKFILL`, `RUNNING`, `FAILED`는 제외하며 신규 동물이 0건이어도 정상 요약이다.
`summaryDate`는 실행의 `requested_to_date`이고, 과거 데이터에 값이 없으면 `completed_at`의
`Asia/Seoul` 날짜를 사용한다. 대상 실행이 없으면 `summary: null`과 200을 반환한다.

오류 요약, 요청 범위 시작일, 공공 원본 상세, 회원·기기·후보·채팅 정보는 응답에 포함하지 않는다.
D2는 조회 전용이며 `summary_published_at`을 변경하거나 FCM을 발송하지 않는다. FCM 단일 발송은
별도 Jira `#104` 범위다.

## FCM 일일 요약 발송

`FCM_DAILY_SUMMARY_ENABLED=true`일 때 Spring scheduler가 기본 1분 간격으로 미발송 실행을 한
건씩 처리한다. 한 트랜잭션에서 `SUCCEEDED DAILY_INCREMENTAL` 행을 `FOR UPDATE SKIP LOCKED`로
잠그고 data-only 메시지를 `daily-intake-summary` 토픽에 보낸 뒤 `summary_published_at`을
기록한다. 초기 전체 적재·BACKFILL·실패 실행과 이미 발송한 요약 날짜는 제외한다. 같은 날짜에
성공 실행이 여러 개면 최신 실행만 보낸다.

FCM 호출 제한 시간은 기본 10초이며 더 크게 설정할 수 없다. 호출 실패·불명확한 timeout은 DB
트랜잭션을 rollback해 다음 polling에서 재시도한다. FCM 접수 뒤 DB 기록 전에 장애가 발생하면
중복 발송될 수 있으므로 전달 의미는 at-least-once다. Android는 `ingestionRunId`로 중복 표시를
막는다.

현재 구현은 다중 worker 정상 완료 시 한 번만 발송하기 위해 FCM 호출 동안 DB connection과 행
잠금을 최대 10초 유지한다. 처리량 증가로 이 구간을 분리할 때는 단순히 트랜잭션 밖으로 호출을
옮기지 않는다. 먼저 영속적인 `CLAIMED/SENDING` 상태와 lease 만료·watchdog·조건부 완료 전이를
설계한 뒤 `[짧은 선점 트랜잭션] → [외부 발송] → [완료 트랜잭션]`으로 변경한다.

스케줄러 실패 로그는 `FCM-001`, 예외 타입, 최하위 원인 타입과 민감 상세를 제거한 애플리케이션
stack trace만 남긴다. provider 원문 메시지와 자격증명 값은 로그에 출력하지 않는다.

운영 활성화에는 Firebase project ID와 컨테이너 내부의 service account JSON read-only mount
경로가 모두 필요하다. 실제 자격증명은 저장소·이미지·환경변수 값에 넣지 않는다.

## 검증

`IngestionStatusApiTest`는 실제 PostgreSQL과 JWT 필터를 사용해 미동기화, 실행 중, 실패, 성공,
36시간 지연 경계, 초기 적재 대체, `BACKFILL` 제외, 0건 일일 요약과 익명 접근을 검증한다.
`DailySummaryPublishServiceTest`는 대상 선별, 0건 payload, 동일 날짜 최신 실행, 동시 선점,
발송 실패 rollback·재시도와 provider 접수 뒤 DB 기록 실패의 at-least-once 동작을 검증한다.
`DailySummaryPublishSchedulerTest`는 원인 타입과 stack trace를 유지하면서 provider 상세가 로그에
노출되지 않는지 검증하고, `DailySummaryNotificationConfigurationTest`는 필수 설정 누락과 timeout
범위 오류가 기동 전에 거부되는지 검증한다.

```bash
node scripts/gradlew.mjs backend test --tests '*IngestionStatusApiTest' --console=plain
npm run check
```

기준: [API §9](../../docs/api-spec.md), [ERD §4.13](../../docs/erd.md),
[백엔드 보안·운영 정책 §8](../../docs/backend-security-operations-policy.md).
