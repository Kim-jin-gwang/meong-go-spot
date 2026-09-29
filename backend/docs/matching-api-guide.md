# 매칭 요청·후보 조회

M2는 본인 활성 `USER_POST/LOST`에 대한 분석을 접수하고, M1은 DATA가 저장한 실행 상태와
후보를 조회한다. PostgreSQL `match_run(PENDING)` 행이 내구성 있는 작업 큐다.

## API와 권한

| API | 응답 | 동작 |
| --- | --- | --- |
| `POST /api/v1/posts/{postId}/match-runs` | 202 | 본문 없이 분석 요청; 진행 중 실행은 같은 ID로 반환 |
| `GET /api/v1/posts/{postId}/candidates` | 200 | 실행 상태, 이전 성공 결과와 현재 노출 가능한 후보 |

두 API 모두 JWT 인증이 필요하다. 없는·삭제된 게시물과 탈퇴 작성자의 게시물은 `POST-001(404)`,
PUBLIC·SHELTERING은 `MATCH-001(409)`, 타인 LOST는 `POST-002(403)`, 본인 CLOSED는
`POST-003(409)` 순서로 거부한다. 일반 상세를 읽을 수 있어도 분석 결과는 읽을 수 없다.

M2는 회원 → 게시물 순서로 행을 잠근 뒤 활성 실행을 확인하고 없을 때만 요청 시점 version을
복사한 PENDING 행을 만든다. `animal_case.version`·`updated_at`은 바꾸지 않는다.
`idx_match_run_active_query`가 DB에서도 진행 중 실행을 게시물당 하나로 제한한다.
등록·수정·사진 교체·M1 조회는 분석을 자동 실행하지 않는다.

## 상태와 이전 결과

M1은 한 read-only repeatable-read 트랜잭션에서 권한, 최신 요청, 최신 성공, 후보를 읽는다.
최신 요청은 `created_at DESC, id DESC`, 최신 성공은 `completed_at DESC, id DESC`다.

| 조건 | analysisStatus | 결과·폴링 |
| --- | --- | --- |
| 실행 없음 | NOT_REQUESTED | 빈 후보, latestRun/resultRunId 생략 |
| 최신 실행 PENDING/RUNNING | 같은 상태 | recommendedPollAfterMs=1000; 이전 성공이 있으면 유지 |
| 최신 실행 SUCCEEDED | SUCCEEDED | 해당 성공 후보; 0건도 정상 완료 |
| 최신 실행 FAILED | FAILED | 안전한 errorCode; 이전 성공이 있으면 유지 |
| 최신 성공의 queryCaseVersion < 현재 version | STALE | 후보와 resultRunId 유지; latestRun은 실제 실행 상태 |

`usingPreviousResult`는 표시하는 성공 실행 ID가 최신 요청 ID와 다를 때 true다.
STALE과 새 실행의 처리 중 상태가 겹치면 `analysisStatus=STALE`을 우선한다. 클라이언트는
`latestRun.status`와 `recommendedPollAfterMs`로 폴링을 계속하고 결과가 오래됐음도 표시한다.
공개 동의만 변경하면 version이 유지되어 STALE이 되지 않는다.

FAILED의 공개 `errorCode`는 `MATCH_TIMEOUT` 또는 일반 실패 `MATCH_FAILED`로 제한한다.
알 수 없는 저장 오류 값은 `MATCH_FAILED`로 바꾸며 원문을 응답하거나 기록하지 않는다.
이는 HTTP 오류가 아니라 HTTP 200의 `latestRun` 안에 있는 비동기 실행 결과다.

## 후보 공개 범위

- DB의 rank 오름차순으로 최대 20건을 반환한다. 점수 재계산·재정렬·rank 재부여는 하지 않는다.
- USER 후보는 ACTIVE·isMatchable=true·활성 작성자만, PUBLIC은 ACTIVE/CLOSED·isMatchable=true만 허용한다.
- 삭제, 매칭 불가, 잘못된 유형, 출처 또는 EVENT 정보가 없는 항목은 제외한다.
- EVENT.publicLocation과 기본 동물 요약만 반환한다. USER의 author에는 nickname만,
  PUBLIC의 shelter에는 name과 공식 phone만 담는다. 공식 phone이 없으면 null 필드를 유지한다.
- 정확한 위치·좌표·CURRENT·회원 ID/전화·보호소 주소·원시 점수·정확한 거리·시간차는 반환하지 않는다.
- USER 썸네일은 P8 경로다. PUBLIC 썸네일은 자격증명이 없는 유효한 http(s) URL만 허용한다.
- `latestRun.candidateCount`는 성공 시 저장한 역사적 후보 수다. 조회 시 제외된 후보 때문에
  실제 `candidates.length`가 더 작을 수 있으며 기존 count/후보를 수정하지 않는다.

## DATA 연동과 모델 설정

`MATCH_MODEL_ID=dinov2_vitb14`, `MATCH_MODEL_VERSION=v2`가 현재 승인된 `ai/model.yaml` 기준
기본값이다. application 설정 및 운영 Compose 환경으로 덮어쓸 수 있다. 새 요청에 기록한
모델 tuple은 실행 도중 변경하지 않는다. 모델 교체 시 API 설정, 모델 산출물, HDFS
`/embeddings/ACTIVE`와 worker의 사용 가능 모델을 함께 맞춰야 한다.

**DATA 실행 연동(#94)은 `data/matching_worker/`의 독립 프로세스가 담당한다.** Spring
API는 계산과 worker 실행을 소유하지 않는다. worker는 다음 계약을 구현하며 실제 환경에서는
장기 실행 DATA/AI 엔진 endpoint와 함께 검증해야 한다.

1. `FOR UPDATE SKIP LOCKED`로 PENDING 선점, 조건부 RUNNING 전이와 started_at 기록 후 잠금 해제.
2. queryCaseVersion과 현재 입력·유효 사진 ID, 요청의 modelId/modelVersion 일치를 확인.
   다른 버전 사진·모델로 조용히 대체하지 않는다.
3. 계산·임계값·Top-K는 DATA/AI가 담당하고 후보 적재와 SUCCEEDED 전이를 같은 트랜잭션으로 완료.
4. 같은 matchRunId 재전달을 멱등 처리하고 실패에는 안전한 분류 코드만 저장.
   V1 제약상 PENDING에서 바로 실패시에도 started_at·completed_at을 모두 기록해야 한다.
5. 실제 작업 60초 hard timeout, RUNNING 5분 watchdog와 늦은 완료 차단을 구현.

worker는 최대 60초의 내부 HTTP 계약으로 장기 실행 엔진에 요청하며, engine 응답의 run·model
tuple, 연속 rank, 최대 20건, 점수 범위와 안정 정렬을 검증한 뒤 순서를 바꾸지 않고 저장한다.
회원·전화번호·정확한 위치·좌표·게시물 원문은 엔진 요청과 로그에 포함하지 않는다. 엔진 URL에는
userinfo·query token·fragment를 허용하지 않는다.

Spring은 작업자·watchdog·점수 산출기를 실행하지 않는다. 실제 결과 SLO나 HDFS·AI 연결은
비운영 엔진 smoke test에서 별도로 검증한다. V2는 모든 상태의 최신 요청을 조회하는
`idx_match_run_latest_request(query_case_id, created_at DESC, id DESC)` 인덱스만 추가한다.
기존 V1과 행 데이터는 바꾸지 않는다. Flyway 적용 시 인덱스 생성 동안 쓰기가 대기할 수 있으므로
기존 match_run 규모를 확인한다. 앱 롤백 시 인덱스는 유지해도 이전 앱과 호환된다.

## 검증

`MatchRequestApiTest`는 PostgreSQL/JWT 환경에서 접수·권한·중복 동시 요청·버전 스냅샷을 검증한다.
`MatchCandidateApiTest`는 상태별 응답, 이전 성공, 실제 P4 동의/내용 수정 후 신선도,
후보 출처/상태 필터와 응답 개인정보 제외를 검증한다. DATA 결과는 테스트 DB fixture로 적재한다.
`data/matching_worker/tests`는 엔진 계약, 안전한 실패 코드, 중복 선점, 0건 완료, 후보 원자 적재,
5분 watchdog와 늦은 완료 차단을 검증한다. PostgreSQL 통합 테스트는 CI에서 V1 스키마에 실행된다.

```bash
node scripts/gradlew.mjs backend test --tests '*MatchRequestApiTest' --tests '*MatchCandidateApiTest' --console=plain
npm run check
```

기준: [API §7](../../docs/api-spec.md), [보안·운영 §6](../../docs/backend-security-operations-policy.md),
[DATA–AI 인터페이스](../../docs/data-ai-interface.md).
