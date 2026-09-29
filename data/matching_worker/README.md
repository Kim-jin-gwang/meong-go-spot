# 온디맨드 매칭 worker

`#94`의 DATA 실행 경계다. Spring Boot가 만든 `match_run(PENDING)`을 PostgreSQL에서
선점하고, 장기 실행 DATA/AI 엔진에 계산을 위임한 뒤 결과와 상태를 원자적으로 저장한다.

## 상태 계약

1. `FOR UPDATE SKIP LOCKED`로 가장 오래된 PENDING 한 건을 `RUNNING`으로 바꾸고 즉시 커밋한다.
2. 요청 시점 case version, 현재 LOST 게시물 상태·사진, model tuple을 고정한 JSON만 엔진에 보낸다.
3. 엔진은 임계값과 Top-K를 적용한 최대 20건을 이미 정렬된 상태로 반환한다. worker와 Spring은
   점수를 계산하거나 순서를 바꾸지 않는다.
4. 후보 insert와 `SUCCEEDED`, `candidate_count`, `completed_at`은 같은 트랜잭션에서 처리한다.
5. timeout은 `MATCH_TIMEOUT`, 나머지 실패는 `MATCH_FAILED`만 저장한다. 외부 오류 원문·payload·
   사진 경로는 로그와 DB 오류 코드에 남기지 않는다.
6. 5분을 넘긴 RUNNING은 watchdog가 FAILED로 바꾸며 이후 도착한 결과는 상태 조건으로 폐기한다.

전달 의미는 at-least-once다. worker가 RUNNING 전이 직후 중단되면 watchdog가 실행을 실패로
종료하고 사용자가 다시 요청한다. 이미 종료된 실행을 다른 worker가 완료하거나 덮어쓰지 않는다.

## 엔진 HTTP 계약

worker는 `MATCH_ENGINE_URL`에 최대 60초 제한으로 POST한다. 이 endpoint는 모델과 스냅샷을 한 번
로딩한 장기 실행 프로세스여야 하며, 요청마다 새 YARN job이나 모델 프로세스를 시작하면 안 된다.

요청에는 `matchRunId`, query case/version, model tuple, 축종·사건일·5자리 지역 코드와 현재 사진의
ID·저장 locator만 포함한다. 회원·전화번호·정확한 위치·좌표·게시물 원문은 포함하지 않는다.

응답 예시:

```json
{
  "matchRunId": 123,
  "modelId": "dinov2_vitb14",
  "modelVersion": "v2",
  "candidates": [
    {
      "targetCaseId": 456,
      "rank": 1,
      "totalScore": 0.87,
      "imageScore": 0.9,
      "distanceKm": 1.25,
      "timeGapDays": 3
    }
  ]
}
```

rank는 1부터 연속이어야 하고 totalScore 내림차순이어야 한다. target은 같은 축종·실종일 이후의
ACTIVE/SHELTERING(공공 PUBLIC 또는 사용자 보호 게시물 USER, 삭제 안 됨 — 계약 0-0)이며 DB 완료
트랜잭션에서 다시 검사한다. 후보 0건은 빈 배열로 성공한다.

## 실행

```bash
python -m data.matching_worker.worker
python -m data.matching_worker.worker --once
```

필수 설정은 `MATCH_WORKER_POSTGRES_DSN`, `MATCH_ENGINE_URL`이다. 실제 DSN은 저장소·일반 env·로그에
남기지 않고 서버 Secret으로 주입한다. 엔진 URL에는 userinfo, query token, fragment를 허용하지 않는다.

## 검증

```bash
python -m pytest data/matching_worker/tests -q
npm run check:data
```
