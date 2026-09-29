# 백엔드 Jira 완료 조건 감사

> 기준 시각: 2026-09-10 KST
>
> 코드 기준: 최초 감사 `origin/dev` `1ef37e1`, `#65` 보완
> `bugfix/chat-log-redaction`
>
> Jira 조회: `project = <프로젝트 키> AND component = BE AND statusCategory != Done ORDER BY key ASC`

## 1. 목적과 판정 기준

이 문서는 Jira 상태가 `해야 할 일`이지만 구현이 이미 `dev`에 병합된 백엔드 이슈의 완료
조건을 코드·DB 제약·테스트에 연결한다. Jira 제목이나 MR 병합 여부만으로 완료를 판단하지
않고, 각 완료 조건에 관찰 가능한 증거가 있는지 확인한다.

| 판정 | 의미 | 후속 처리 |
| --- | --- | --- |
| `PASS` | 완료 조건과 실행 증거가 모두 있음 | 팀 절차에 따라 Jira 상태 정리 가능 |
| `EVIDENCE GAP` | 기능은 있으나 완료 조건을 직접 검증하는 증거가 부족함 | 작은 테스트 보완 뒤 상태 정리 |
| `PARTIAL` | 완료 조건의 동작 또는 운영 경로가 일부 없음 | 별도 기능·버그 수정 브랜치에서 구현 |
| `BLOCKER` | 정책·계약 결정 또는 외부 준비가 선행되어야 함 | 결정 전 임의 구현 금지 |

이번 감사는 Jira 읽기와 저장소 문서 작성만 수행한다. Jira 상태는 변경하지 않는다.

## 2. 감사 시점의 Jira와 실제 잔여 범위

| Jira | 라이브 상태 | 이번 판정 | 의미 |
| --- | --- | --- | --- |
| #59 | 해야 할 일 | `PASS` | 공통 응답·오류·401·403 계약 구현 및 테스트 완료 |
| #60 | 해야 할 일 | `PASS` | Flyway·PostgreSQL·`ddl-auto=validate` 계약 완료 |
| #63 | 해야 할 일 | `PASS` | 매칭 스키마·조회 저장소·공개 DTO·통합 테스트 완료 |
| #65 | 진행 중 | `PASS` | 정상·DB 실패 경로의 로그 비노출 회귀 테스트 완료 |
| #64 | 해야 할 일 | `PARTIAL` | 스키마는 있으나 DATA 소유 PostgreSQL 적재 경로가 없음 |
| #94 | 해야 할 일 | `PARTIAL/BLOCKER` | 온디맨드 worker·상태 전이·결과 적재와 공동 계약이 남음 |
| #104 | 해야 할 일 | `PARTIAL/BLOCKER` | FCM 발송·Android 구독과 Firebase 준비가 남음 |

> 상태 갱신 기록: MR !123이 `dev`에 병합된 뒤, 2026-09-10 KST에 `59`·`60`·`63`을
> Jira `완료`로 수동 전환했다. 이 표의 `해야 할 일`은 감사 당시의 원본 상태를 보존한 것이다.
> 같은 날 `65`는 `bugfix/chat-log-redaction` 작업을 시작하며 `진행 중`으로 전환했다. Jira
> `완료` 전환은 테스트 보완 MR이 `dev`에 병합된 뒤 수행한다.

`64`, `94`, `104`의 상세 잔여 범위와 선행 결정은
[Backend Codex 개발 시작 가이드](backend-codex-start-guide.md)의 0.4~0.6절을 따른다.

## 3. #59 공통 응답·오류·인증 실패

| Jira 완료 조건 | 구현·테스트 증거 | 판정 |
| --- | --- | --- |
| 성공 응답 공통 형식 | `ApiResponse.java:13-31`, `ApiResponseTest.java:11-26` | `PASS` |
| Bean Validation 오류의 필드·사유 형식 | `GlobalExceptionHandler.java:101-125`, `GlobalExceptionHandlerTest.java:34-48` | `PASS` |
| `BusinessException`의 도메인 코드·HTTP 상태 유지 | `GlobalExceptionHandler.java:58-63`, `GlobalExceptionHandlerTest.java:26-32` | `PASS` |
| 미인증 401 공통 오류 형식 | `RestAuthenticationEntryPoint.java:21-28`, `SecurityContractTest.java:33-46` | `PASS` |
| 권한 부족 403 공통 오류 형식 | `RestAccessDeniedHandler.java:21-28`, `SecurityContractTest.java:48-62` | `PASS` |

종합 판정은 `PASS`다. Jira 상태만 실제 구현보다 뒤에 있으며 재구현할 항목은 없다.

## 4. #60 Flyway 전환

| Jira 완료 조건 | 구현·테스트 증거 | 판정 |
| --- | --- | --- |
| Flyway와 PostgreSQL 확장 의존성 | `backend/build.gradle:31`, `backend/build.gradle:35` | `PASS` |
| 개발·운영 기본 설정에서 Hibernate 검증만 사용 | `application.yml:16-29`의 `ddl-auto: validate`, Flyway 활성화 | `PASS` |
| 테스트도 실제 PostgreSQL과 `validate` 사용 | `application-test.yml:3-13`의 Testcontainers JDBC URL과 `ddl-auto: validate` | `PASS` |
| 빈 DB에 V1 적용 후 승인 테이블 생성 | `InitialSchemaMigrationTest.java:130-146` | `PASS` |
| 승인 제약·인덱스 반영 | `InitialSchemaMigrationTest.java:148-162` | `PASS` |

종합 판정은 `PASS`다. 공유된 `V1__initial_schema.sql`은 수정하지 않으며 이후 스키마 변경은
새 migration으로만 추가한다.

## 5. #63 매칭 실행·후보 스키마

| Jira 완료 조건 | 구현·테스트 증거 | 판정 |
| --- | --- | --- |
| `match_run`, `match_candidate` 생성 | `V1__initial_schema.sql:196-229`, `InitialSchemaMigrationTest.java:20-34,130-146` | `PASS` |
| PENDING·RUNNING·SUCCEEDED·FAILED 표현 | `V1__initial_schema.sql:346-362`, `MatchCandidateApiTest.java:60-100` | `PASS` |
| rank 1~20, candidate count 0~20 제약 | `V1__initial_schema.sql:346-374`, `InitialSchemaMigrationTest.java:91-99` | `PASS` |
| 내부 점수·거리·시간차를 공개 DTO에서 제외 | `MatchCandidatesResponse.java:21-52`, `MatchCandidateApiTest.java:187-239` | `PASS` |
| 기준·후보 관계와 후보 정렬 | `V1__initial_schema.sql:208-229`, `MatchResultRepository.java:53-79`, `MatchCandidateApiTest.java:187-208` | `PASS` |

Jira 제안 본문에는 JPA 엔티티가 언급되지만, 현재 구현은 `MatchRequestRepository`와
`MatchResultRepository`의 JDBC 결과 모델을 사용한다. 완료 조건의 관계·상태·공개 분리 동작은
PostgreSQL 통합 테스트와 API 테스트로 충족하며, 동일 테이블을 위한 사용하지 않는 JPA 엔티티를
추가할 이유는 없다. 이 설계 차이를 Jira 완료 기록에 남긴다는 조건으로 종합 판정은 `PASS`다.

## 6. #65 채팅방·메시지 스키마

| Jira 완료 조건 | 구현·테스트 증거 | 판정 |
| --- | --- | --- |
| `chat_room`, `chat_message` 생성 | `V1__initial_schema.sql:87-118`, `InitialSchemaMigrationTest.java:20-34,130-146` | `PASS` |
| 동일 게시물·요청자 중복 방지 | `V1__initial_schema.sql:96`, `ChatWriteApiTest.createsOrReturnsOneRoomUnderEightParallelRequests`의 8개 병렬 요청 | `PASS` |
| 메시지와 방 참여자 관계 조회 | `V1__initial_schema.sql:97-117`, `ChatQueryApiTest.java:104-143` | `PASS` |
| 메시지 저장과 `last_message_at` 원자 갱신 | `ChatWriteApiTest.sendsNormalizedMessageAndTouchesRoomAtomically`, `ChatWriteRepository.java:153-156` | `PASS` |
| 메시지 본문·전화번호 로그 비노출 | `ChatWriteApiTest.successfulSendDoesNotLogMessageContentOrMemberPhone`, `ChatWriteApiTest.roomTouchFailureRollsBackMessageWithoutReflectingPrivateContent`가 `OutputCaptureExtension`으로 정상·DB 실패 로그를 검증 | `PASS` |
| 게시물 종료 후 기존 대화 유지·읽기 전용 | `ChatQueryApiTest.java:156-188`, `ChatWriteApiTest.senderScopedKeyRejectsCrossRoomAndContentButReplaySurvivesClosure` | `PASS` |

정상 전송 로그와 강제 DB 실패의 `COMMON-500` 로그를 직접 캡처해 메시지 본문·전화번호 표본이
포함되지 않는지 검증했다. 오류 응답 비노출과 트랜잭션 롤백 검증도 함께 유지하므로 종합 판정은
`PASS`다.

## 7. 실행 검증

다음 테스트를 2026-09-10 KST에 `origin/dev` `1ef37e1` 기준으로 다시 실행했다.

```text
ApiResponseTest
GlobalExceptionHandlerTest
SecurityContractTest
InitialSchemaMigrationTest
MatchRequestApiTest
MatchCandidateApiTest
ChatWriteApiTest
ChatQueryApiTest
```

결과: `BUILD SUCCESSFUL in 1m 29s` (`5 actionable tasks: 5 executed`).

`#65` 보완 브랜치에서는 Java 21·PostgreSQL 17 환경에서 다음 두 테스트를 별도로
실행했다.

```text
ChatWriteApiTest.successfulSendDoesNotLogMessageContentOrMemberPhone
ChatWriteApiTest.roomTouchFailureRollsBackMessageWithoutReflectingPrivateContent
```

결과: `BUILD SUCCESSFUL in 4m 36s` (`5 actionable tasks: 2 executed, 3 up-to-date`).

커밋 전 검증 결과는 다음과 같다.

- `npm run check:be`: `BUILD SUCCESSFUL in 3m 7s`
- `npm run check`: Android SDK 경로가 없어 `check:android`에서 중단됨. Android 변경은 없으며,
  이 결과를 전체 게이트 통과로 표현하지 않는다.

## 8. Jira 완료와 후속 구현 순서

1. `59`, `60`, `63`은 MR !123 병합 뒤 Jira `완료`로 정리했다.
2. `65`는 `bugfix/chat-log-redaction`에서 로그 비노출 회귀 테스트를 보완했다. MR을 `dev`에
   병합한 뒤 테스트 근거를 Jira에 기록하고 `완료`로 전환한다.
3. 다음 기능 구현은 `64`의 DATA 소유 공공 보호동물 PostgreSQL 멱등 적재다. 최초 적재·반복
   적재·최신/오래된 `updTm`·부분 실패·실행 실패를 실제 PostgreSQL에서 검증한다.
4. `64`가 `ingestion_run`과 공공 `animal_case.id`를 실제로 만들면, 준비가 끝난 순서대로
   `104` FCM 단일 발송 또는 `94` 온디맨드 worker 연동을 각각 별도 기능 브랜치에서 진행한다.
5. Jira 완료 처리는 각 이슈의 코드·테스트·운영 또는 외부 연동 검증까지 끝난 뒤 수행한다.
   이 증거표 작성만으로 `64`, `94`, `104`를 완료 처리하지 않는다.
