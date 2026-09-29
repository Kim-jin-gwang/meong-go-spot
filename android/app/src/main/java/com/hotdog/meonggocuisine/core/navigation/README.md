# Navigation

앱 전체의 화면 경로와 화면 이동 조립을 담당합니다. 화면 UI, 화면 상태, API 호출은 이 패키지에 두지 않습니다.

## 파일별 수정 위치

| 수정하려는 내용 | 파일 |
| --- | --- |
| 앱 시작 화면과 전체 NavHost | `AppNavHost.kt` |
| 공개·로그인 필요 경로 구분 | `AppRoute.kt` |
| 로그인·회원가입·계정 찾기·마이페이지·회원 탈퇴 경로 | `AuthRoutes.kt` |
| 잃어버렸어요·보호 중 목록 경로 | `CommunityRoutes.kt` |
| 실종·보호 게시물 등록 경로 | `ReportRoutes.kt` |
| 게시물 상세·수정 경로 | `PostRoutes.kt` |
| 매칭 진행·후보·비교 경로 | `MatchRoutes.kt` |
| 채팅 목록·채팅방 경로 | `ChatRoutes.kt` |

## 기본 규칙

- 경로는 문자열을 직접 조합하지 않고 `@Serializable` 타입으로 정의합니다.
- 화면 이동에 필요한 `postId`, `chatRoomId` 같은 식별자만 경로 인자로 전달합니다. DTO나 화면 상태 전체를 전달하지 않습니다.
- 비로그인 진입이 가능한 공개 목록·게시물 상세는 `PublicRoute`를 구현합니다.
- 앱 시작 화면은 `LostPostListRoute`입니다. 와이어프레임 01의 홈 화면(서비스 소개·두 목록 진입)은
  아직 구현하지 않아 `HomeRoute`에 destination을 연결하지 않았고, 비로그인 사용자가 바로 공개
  목록을 보도록 목록에서 시작합니다. 뒤로 가기로 back stack이 비면 같은 목록으로 돌아갑니다.
- 등록, 수정, 매칭, 채팅처럼 인증이 필요한 경로는 `AuthRequiredRoute`를 구현합니다.
  게시물 상세는 읽기 전용이라 공개이며(QA 2026-09-21), 상세 안의 채팅·수정·종료 액션에서 인증을 확인합니다.
- 인증 확인과 로그인 후 원래 목적지 복귀는 `AppNavHost`와 `PendingDestination.kt` 한 곳에서 처리합니다. 각 화면에서 로그인 여부를 따로 판단하지 않습니다.
- 기능 화면에는 `NavController`를 직접 넘기지 않고 `onBack`, `onPostClick` 같은 이동 콜백을 전달합니다.
- 기능별 화면 등록 함수는 해당 `feature/*` 패키지에 두고 `AppNavHost`에서 조립합니다.

현재는 실제로 구현된 로그인, 회원가입, 잃어버렸어요 목록, 보호하고 있어요 목록, 관심 지역 선택, 게시물 상세, 채팅 시작·채팅방·채팅 목록 화면이 `AppNavHost`에 등록되어 있습니다. 회원가입 성공 또는 하단 로그인 선택 시 로그인 화면으로 돌아가고, 보호 목록은 관심 지역 선택 후 진입합니다. 나머지 Route는 최신 화면 흐름 문서의 화면 계약이며, 각 기능 화면을 구현할 때 대응하는 destination을 연결합니다.

## 새 화면을 추가하는 순서

1. 화면 성격에 맞는 `*Routes.kt`에 Route 타입을 추가합니다.
2. 해당 `feature/*/ui`에 Screen을 구현합니다.
3. 기능 패키지에 destination 등록 함수를 만들고 화면 이동은 콜백으로 노출합니다.
4. `AppNavHost.kt`에서 기능 등록 함수를 호출합니다.
5. 뒤로 가기와 로그인 필요 여부를 화면 흐름 문서 기준으로 확인합니다.

## 게시물 상세 후속 경로

- PostDetailRoute는 게시물 상세 화면으로 연결하며 비로그인도 진입합니다.
- InsightsRoute는 보호소 소식(인사이트 카드 5장) 화면입니다. 홈의 한 줄 소식 띠에서 들어오고 비로그인도 봅니다. 홈은 스크롤 없는 한 화면이라 카드 5장을 걸 자리가 없어 띠 하나만 두고 전체는 이 화면에 둔다(2026-09-22).
- PostEditRoute, PostCloseRoute, PostChatStartRoute, MatchProgressRoute는 상세 화면 액션에서 진입하는 인증 필요 경로입니다.
- PostChatStartRoute는 채팅방 준비(C1) 성공 시 ChatRoomRoute로 교체 이동해 뒤로 가기 시 시작 화면이 남지 않습니다.
- PostEditRoute는 게시물 수정 화면으로 연결하고, 저장에 성공하면 상세로 교체 이동해 뒤로 가기 시 수정 화면이 남지 않습니다.
- PostCloseRoute는 게시물 종료 화면으로 연결하고, 종료에 성공하면 종료된 상세로 돌아가지 않고 MyPostListRoute로 교체 이동합니다.

## 매칭 경로 연결

- `MatchProgressRoute`는 게시물 상세의 `유사도 분석하기`에서 진입한다.
- 분석이 끝나면 `MatchCandidatesRoute`로 이동하며 진행 화면은 back stack에서 제거한다.
- `MatchCandidatesRoute`는 후보 목록 화면으로 연결한다. 후보를 선택하면 `MatchComparisonRoute`로
  이동하고, 다시 분석하면 `MatchProgressRoute`로 돌아간다.
- `MatchComparisonRoute`는 비교 상세 화면으로 연결하고 `postId`와 `candidatePostId`를 함께 받는다.
- 비교 상세의 연락 동선은 화면 밖에서 조립한다. 보호소 전화는 `ACTION_DIAL`로, 작성자 채팅은
  `PostChatStartRoute`로 이동한다.

## 로그인 후 원래 목적지 복귀

비로그인 사용자가 인증이 필요한 기능을 선택하면 `navigateToLogin`으로 목적지를 적어 두고
로그인 화면으로 보낸다. 로그인에 성공하면 `consumePendingDestination`으로 목적지를 꺼내
로그인 화면을 닫은 뒤 이동한다.

- `savedStateHandle`은 Bundle에 담을 수 있는 값만 저장하므로 경로 객체 대신 목적지 종류
  이름과 식별자만 저장하고 `PendingDestination.toRoute`로 경로를 다시 만든다.
- 기록은 로그인 화면을 띄운 화면에 남으므로 `previousBackStackEntry`에서 읽고, 한 번 쓰면
  지워서 나중에 같은 화면으로 돌아왔을 때 다시 이동하지 않게 한다.
- 목적지를 추가할 때는 `PendingDestination`에 항목을 넣고 `toRoute`에 경로를 연결한다.
  식별자가 필요한 목적지는 식별자가 없으면 경로를 만들지 않아 잘못된 화면으로 가지 않는다.
- 현재 대상은 상세의 채팅 시작, 실종·보호 게시물 등록, 마이페이지, 채팅 목록이다.

`HomeRoute`(와이어프레임 01)와 `WithdrawRoute`(A5 회원 탈퇴)는 화면이 아직 없어 destination을
연결하지 않았다.
