# 1:1 텍스트 채팅 API

활성 사용자 게시물의 작성자와 대화 요청자 사이에 방을 하나 만들고 REST로 메시지를 주고받는다.
JWT 인증을 사용하며 대화 갱신도 REST 증분 조회로 처리한다. 개인 채팅 알림은 PostgreSQL Outbox와
FCM data 메시지를 사용하고 별도 메시지 브로커·WebSocket·SSE 서버는 도입하지 않는다.

| API | 응답 | 동작 |
| --- | --- | --- |
| `POST /api/v1/posts/{postId}/chat-room` | 200 | 타인의 활성 USER 게시물에서 기존 방 반환 또는 생성 |
| `GET /api/v1/chat-rooms` | 200 | 내가 참여한 방 10개, updatedAt·ID 내림차순과 안 읽음 수 |
| `GET /api/v1/chat-rooms/{roomId}/messages` | 200 | 최신·과거 cursor 또는 `afterMessageId` 이후 메시지 20개와 두 참여자의 읽음 위치 |
| `POST /api/v1/chat-rooms/{roomId}/messages` | 201 | 텍스트 전송(욕설은 저장 전 `*` 마스킹 — `ProfanityMasker`, `chat/profanity-ko.txt`), 동일 요청은 최초 메시지 재반환 |
| `PUT /api/v1/chat-rooms/{roomId}/read` | 200 | 내 마지막 읽음 메시지 ID를 앞으로만 갱신 |
| `PUT /api/v1/members/me/push-devices/{installationId}` | 204 | 현재 계정의 개인 FCM 기기 등록·갱신 |
| `DELETE /api/v1/members/me/push-devices/{installationId}` | 204 | 현재 계정의 개인 FCM 기기 해제 |

## 접근과 보존

C1은 없는·삭제된·탈퇴 작성자의 게시물에 POST-001(404), 공공 게시물에 CHAT-001(409),
본인 게시물에 CHAT-002(409), 종료 게시물에 CHAT-004(409)를 반환한다. 이미 방이 있어도
종료 게시물에서는 C1을 거부하며 기존 방은 C2/C3에서 연다. Jira98의 CHAT-001/002 표기는
현재 API 오류 코드 표와 반대여서 API 명세를 기준으로 구현한다.

C2는 참여한 방만 반환한다. C3/C4는 없는 방과 비참여자 요청을 모두 CHAT-003(404)로 처리한다.
사용자 게시물·실제 작성자와 방의 owner가 일치해야 한다. 전화번호, 위치, 인증 정보는 조회하지 않는다.

게시물이 CLOSED/DELETED이면 기존 대화는 readOnly=true로 유지하고 새로운 메시지는 거부한다.
기존 메시지 조회와 읽음 위치 갱신은 허용한다.
상대 회원이 탈퇴하면 남은 활성 참여자는 보존 기간 내 기존 대화를 읽을 수 있지만 새 메시지는
보낼 수 없다. 탈퇴 회원의 닉네임은 `탈퇴한 회원`으로 표시한다. 실제 게시물/회원 파기 시
기존 FK CASCADE가 방·메시지를 제거한다. 이 기능은 별도 파기 스케줄을 구현하지 않는다.

방 목록의 사진 URL도 P8 접근 조건을 따른다. ACTIVE 사용자 사진은 표시하고 CLOSED는
작성자에게만 90일 이내 사진을 표시한다. 요청자의 종료 게시물 사진, 삭제/기간 경과 사진과
탈퇴 작성자의 사진 URL은 생략한다. 마지막 메시지가 없는 방은 lastMessage를 생략한다.

## 전송과 재시도

```json
{"clientMessageId":"be521d93-8531-4fb1-9a42-55ed3a93d540","content":"비슷한 아이를 보호하고 있어요."}
```

Content-Type은 application/json이다. 본문은 64KiB 이내이며 중복 키·알 수 없는 필드·후행
JSON을 거부한다. clientMessageId는 표준 UUID 문자열이며 content는 문자열이어야 한다.
원문 1000 Unicode 코드 포인트 상한을 먼저 적용하고 제어·형식·단독 surrogate 문자를 거부한다.
NFC 정규화와 앞뒤 Unicode 공백 제거 뒤 1~1000자로 검증하며 내부 일반 공백은 보존한다.

멱등성 키는 `(sender_member_id, client_message_id)`다. 요청 방과 정규화된 내용이 모두 같으면
최초 메시지를 201로 반환하고 방 시각은 갱신하지 않는다. 다른 방 또는 다른 내용에 키를 다시
사용하면 IDEMPOTENCY-001(409)다. 기존 ERD대로 request_hash는 정규화된 내용의 SHA-256이며
방 ID 일치 여부는 별도로 비교한다.

참여 권한 검사가 멱등성 조회보다 먼저다. 정상 재요청은 게시물 종료/삭제 후에도 기존 201을
반환하며 신규 메시지만 CHAT-004로 거부한다. 이때 기존 메시지를 다시 저장하지 않는다.

쓰기는 참여 회원 ID 오름차순 → 게시물 → 채팅방 순서로 잠근다. 잠금 뒤 권한·상태를 다시
검증하며 반대 방향 동시 전송과 게시물 종료/회원 탈퇴에 같은 순서를 적용한다. 신규 메시지
저장, 수신자 한 명의 `chat_notification_outbox`, 방 last_message_at·updated_at 변경은 한
트랜잭션이다. 정상 멱등 재요청은 Outbox를 다시 만들지 않는다. 요청·메시지 내용과 SQL 오류
원문을 애플리케이션 로그나 오류 응답에 남기지 않는다.

## 커서와 폴링

C2는 선택적 cursor를 받는다. C3은 `cursor` 또는 `afterMessageId` 하나만 받으며 함께 보내면
COMMON-001(400)이다. 손상되거나 다른 회원·방·종류에서 가져온 커서는 CURSOR-001(400), 해당
방 메시지가 아닌 `afterMessageId`는 CHAT-005(400)다. C3은 방 참여 여부를 조회 위치 검증보다
먼저 확인한다. 커서와 메시지 ID는 조회 위치이며 인증 수단이 아니다.

한 번 더 조회한 11번째/21번째 행으로 hasNext를 판단하고 다음 페이지가 있을 때만 nextCursor를
반환한다. 동일 타임스탬프는 ID로 구분한다. 방 목록은 고정 스냅샷이 아니므로 새 메시지로 방이
커서 앞쪽으로 이동하면 첫 페이지를 새로고침해 반영한다. C3은 항상 pollAfterMs=3000을 반환한다.
열린 대화는 마지막 수신 ID를 `afterMessageId`로 보내 새 메시지를 오래된 순서부터 받고, 20건을
넘으면 `nextAfterMessageId`로 연속 조회한다. 앱은 `messageId`로 중복을 제거한다.

## 읽음과 안 읽음

방은 `owner_last_read_message_id`, `requester_last_read_message_id` 두 nullable 컬럼만 사용한다.
C5는 참여자 역할에 맞는 컬럼을 잠그고 요청 ID가 같은 방 메시지인지 검사한 뒤 현재 값보다 클 때만
갱신한다. 같거나 작은 재요청은 성공으로 현재 값을 반환한다. 메시지 GET이나 FCM 수신은 읽음
위치를 바꾸지 않는다.

C2 `unreadCount`는 상대가 보낸 메시지 중 내 마지막 읽음 ID보다 큰 건수이고 내 메시지는 제외한다.
C3에서 내가 보낸 메시지의 ID가 `otherLastReadMessageId` 이하이면 상대가 확인한 것으로 표시할 수 있다.
읽음 위치가 `NULL`이면 상대가 보낸 모든 메시지가 안 읽음이다.

## 개인 채팅 알림

N1의 설치 UUID와 FCM 토큰은 각각 한 `auth_session`에만 속한다. 계정 전환으로 같은 설치 또는
토큰을 다시 등록하면 이전 세션의 `push_*` 등록을 비우고 현재 인증 세션으로 원자적으로 재귀속한다. 토큰은 AES-256-GCM 암호문과 전용
HMAC 조회값으로 저장하고 원문·암호문·조회값을 로그·오류·metric label·trace에 남기지 않는다.
N2는 현재 세션의 일치하는 `push_*` 등록을 멱등으로 비운다. 로그아웃은 로컬 세션 삭제 전에 N2를
best effort로 호출한다. N2가 실패해도 A4가 현재 세션을 폐기하면 worker 발송 대상에서 즉시 제외한다.
탈퇴·영구 FCM 토큰 오류에서도 서버가 해당 등록을 비운다.

Outbox worker는 커밋된 이벤트를 lease로 선점하고 현재 수신자의 활성 인증 세션 등록에 `type=CHAT_MESSAGE`,
`chatRoomId`, `messageId`, `postId`만 보낸다. 채팅 본문·닉네임·사진·위치는 payload에 포함하지
않는다. 적격 기기가 없으면 `SKIPPED`다. 영구 토큰 오류는 기기를 삭제하고, 일시 실패는 최대
1시간 지수 백오프로 10회 또는 생성 후 24시간까지 재시도한 뒤 `SKIPPED`로 종료한다. 한 기기
이상 성공하고 일시 실패가 남지 않으면 `SENT`다. 부분 성공 뒤 중복 알림은 Android가
`messageId`로 제거한다. FCM 결과는 메시지를
롤백하거나 읽음 처리하지 않으며 C2·C3이 연결 단절 뒤 복구 경로다.

## 검증과 범위

`ChatWriteApiTest`와 `ChatQueryApiTest`는 PostgreSQL/JWT 환경에서 방·메시지 권한,
동시 생성·전송, 멱등성, 입력 정규화, 종료/삭제·탈퇴 후 접근, cursor·증분 조회와 개인정보 제외를
검증한다. 읽음 위치 단조 증가·안 읽음 집계, 메시지·Outbox 원자성, worker 재시도와 기기 소유권은
별도 통합 테스트로 검증한다.
`ChatWriteApiTest`는 `OutputCaptureExtension`으로 정상 전송과 강제 DB 실패 로그를 캡처해
메시지 본문·회원 전화번호 표본이 남지 않는지도 검증한다.

```bash
node scripts/gradlew.mjs backend test --tests '*Chat*ApiTest' --console=plain
npm run check
```

구현 단계에서는 기존 `chat_room` 읽음 컬럼과 `auth_session.push_*` 컬럼, `chat_notification_outbox`,
조회·선점 인덱스를 새 Flyway migration으로 추가한다. FCM 자격증명과 토큰 암호화·조회 Secret은
배포 Secret으로 주입하고 로컬·테스트는 외부 호출 없는 Fake 공급자를 사용한다. 첨부파일·그룹
채팅·메시지 수정/삭제·차단·신고와 WebSocket·SSE는 범위에 포함하지 않는다.

기준: [API §8](../../docs/api-spec.md), [ERD §7.7](../../docs/erd.md),
[보안·운영 정책](../../docs/backend-security-operations-policy.md).
