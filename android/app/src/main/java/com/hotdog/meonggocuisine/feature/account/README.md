# Account

마이페이지와 본인 계정 관리(닉네임·비밀번호 변경, 로그아웃, 계정 삭제)의 `ui`와 `data` 코드를 둔다.
로그인·회원가입·토큰 갱신은 `feature/auth`에 있다.

## 진입

- 모든 목록·홈 화면의 `BrandHeader` 오른쪽 **사람 아이콘**이 `MyPageRoute`를 연다 (예전 ☰ 드롭다운의 "내 게시물" 항목을 대체).
- 비로그인이면 `PendingDestination.MY_PAGE`를 적어 두고 로그인 뒤 마이페이지로 이어진다.

## 구성

- `ui/MyPageScreen.kt` · `ui/MyPageViewModel.kt`: 닉네임 카드, 내 게시물 · 닉네임 변경 · 비밀번호 변경 · 로그아웃(확인 대화상자) · 계정 삭제 메뉴. 화면이 다시 보일 때마다 A6으로 닉네임을 새로 읽는다.
- 메뉴는 제목 한 줄씩이고 높이가 같다(58dp). 밑에 붙이던 설명은 제목이 이미 말하는 것을 되풀이했고, 줄마다 있고 없고가 달라 높이가 들쭉날쭉했다(2026-09-25).
- 좋아요 목록 항목은 뺐다(2026-09-25). 찜한 동물은 소개팅 히스토리 탭에서 본다 — 자세한 까닭은 `feature/adoption/README.md`.
- `ui/NicknameEditScreen.kt`: 현재 닉네임을 경로 인자로 받아 채우고 A7로 저장. 성공하면 뒤로 간다.
- `ui/PasswordChangeScreen.kt`: 현재·새·확인 세 칸, A8. 성공하면 뒤로 간다 — 이 기기 세션은 서버가 남겨 두므로 재로그인이 없다.
- `ui/WithdrawScreen.kt`: 경고 문구, 현재 비밀번호, 확인 대화상자, A5. 성공하면 홈으로 되돌아가고 백스택을 비운다.
- `ui/AccountInputValidator.kt`: 회원가입과 같은 닉네임·비밀번호 규칙. 차단 목록·로그인 ID 동일성은 서버만 판정한다.
- `ui/AccountComponents.kt`: 공통 뼈대(`AccountScaffold`)와 `PersonIcon`(헤더 버튼·프로필 카드가 함께 쓴다).
- `data/AccountApi.kt`: A4 로그아웃, A5 탈퇴, A6 프로필, A7 닉네임, A8 비밀번호.
- `data/DefaultAccountRepository.kt`: NFC 정규화, 오류 코드 → 입력 칸 매핑(`AccountField`), 세션 갱신·삭제.

## 세션 처리 규칙

- `AUTH-002`/`AUTH-003`이 오면 어느 화면이든 로컬 세션을 지우고 `SessionExpired`로 알린다. 화면은 홈으로 돌아간다(`restartAtHome`).
- 로그아웃은 서버 통보가 실패해도 로컬 세션을 지운다. 사용자가 누른 "로그아웃"이 네트워크 때문에 무시되는 쪽이 더 나쁘다.
- 닉네임 변경·프로필 조회 성공은 `AuthSessionManager.updateMember`로 세션의 회원 정보를 맞춘다. 토큰은 건드리지 않는다.
