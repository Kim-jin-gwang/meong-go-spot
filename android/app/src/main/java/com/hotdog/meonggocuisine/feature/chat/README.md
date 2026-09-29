# Chat

사용자 간 1:1 채팅 기능의 `ui`와 `data` 코드를 둔다.

## 구조

- `data/ChatApi.kt`: 채팅 API C1~C5 (`docs/api-spec.md` 8장)
- `data/ChatModels.kt`: 요청·응답 DTO
- `data/ChatRepository.kt`: 결과 모델과 Repository 인터페이스
- `data/DefaultChatRepository.kt`: 오류 코드(CHAT-001~004, IDEMPOTENCY-001, CURSOR-001) 매핑
- `ui/PostChatStartScreen.kt`: 게시물에서 채팅방 조회·생성(C1) 후 채팅방으로 교체 이동
- `ui/ChatRoomScreen.kt`: 메시지 조회(C3)·전송(C4)·읽음 갱신(C5). 화면이 보이는 동안 `afterMessageId` 증분 폴링, `messageId` 기준 중복 제거, 읽기 전용·상대 읽음 상태 표시
- `ui/ChatListScreen.kt`: 안 읽음 배지가 있는 내 채팅방 목록(C2), `updatedAt` 최신순 커서 페이지네이션과 수동·생명주기·foreground 푸시 갱신
- `../push/data`: 설치 UUID와 개인 FCM 토큰의 N1 등록·N2 해제 및 네트워크 재시도
- `../push/chat`: 채팅 data payload 검증, `messageId` 중복 제거, 알림 표시·탭 이동·현재 방 억제
- `../push/service/MeonggoFirebaseMessagingService.kt`: FCM 토큰 변경과 채팅 data 메시지 수신

## 동작 규칙

- WebSocket·SSE 없이 폴링만 사용한다. 폴링은 `LifecycleStartEffect`로 화면 이탈 시 중단한다.
- 폴링은 마지막 수신 `messageId`를 `afterMessageId`로 보내며, `nextAfterMessageId`가 있으면 한 주기 안에서 끝까지 조회한다. 중간 요청이 실패하면 이미 받은 메시지는 유지하고 다음 주기에 마지막 수신 위치부터 재시도한다.
- 읽음 위치(C5)는 서버가 확인한 방별 위치보다 큰 값만 전송한다. 실패한 요청은 확인 위치로 기록하지 않아 같은 값으로 재시도할 수 있다.
- 화면에 반영된 최신 메시지를 읽음 위치로 전송한다. 읽기 전용 방에도 같은 규칙을 적용하고, 내 메시지는 `otherLastReadMessageId`로 상대의 읽음 여부를 표시한다.
- 채팅 목록은 최초 진입, foreground 복귀, 채팅방에서 돌아온 시점과 사용자의 새로고침 요청에 첫 페이지를 다시 조회한다. 첫 화면 재개는 초기 조회와 중복 호출하지 않는다.
- 채팅 목록 화면이 보이는 동안 유효한 채팅 FCM을 받으면 첫 페이지를 갱신한다. 다른 목록 요청과 겹치면 현재 요청 직후 한 번만 이어서 갱신한다.
- 로그인·세션 복구 뒤 현재 FCM 토큰을 N1에 등록하고, `onNewToken`에서도 같은 설치 UUID로 갱신한다. 일시 실패는 앱 실행 중 백오프로 재시도하며 다음 세션 복구 때도 다시 동기화한다.
- 로그아웃은 로컬 인증정보를 지우기 전에 N2를 최선 노력으로 호출한다. N2 실패가 A4 로그아웃과 로컬 세션 삭제를 막지 않는다.
- FCM 토큰은 파일·로그에 저장하지 않는다. 설치 UUID만 백업 제외 SharedPreferences에 저장하며 `google-services.json`은 Git 추적 없이 빌드 환경에서 제공한다.
- 채팅 FCM은 `type=CHAT_MESSAGE`, `chatRoomId`, `messageId`, `postId`가 모두 유효할 때만 처리한다. 알림에는 본문·닉네임을 넣지 않고, 같은 `messageId`는 한 번만 표시한다.
- 앱이 foreground이고 같은 채팅방이 보일 때는 시스템 알림을 억제한다. background·종료 상태의 알림을 누르면 대상 방으로 이동하며, 비로그인 상태라면 로그인 완료 뒤 이동한다.
- Android 13 이상의 알림 권한이 거부되어도 메시지는 DB 조회와 폴링으로 복구하며 채팅 기능을 막지 않는다.
- 전송은 클라이언트 생성 UUID `clientMessageId`로 멱등을 보장한다. 같은 내용 재시도는 같은 UUID를 재사용하고, 내용이 바뀌면 새 UUID를 만든다.
- `readOnly=true` 응답과 CHAT-004(409) 수신 시 입력창을 비활성화하고 안내를 표시한다.
- 메시지 한도는 1000자(코드 포인트, 서버 C4 와 동일 — `CHAT_MESSAGE_MAX_LENGTH`). 넘는 붙여넣기는 한도에서 잘라 넣고, 800자부터 입력 칸 아래 `n/1000` 을 보여 준다(한도에 닿으면 빨강).
- 300자 또는 8줄을 넘는 메시지는 8줄까지만 보이고 "본문 보기" 로 펼친다(메시지별 기억). 서버는 저장 전에 욕설을 `*` 로 가리므로(!233) 앱은 본문을 그대로 보인다.
