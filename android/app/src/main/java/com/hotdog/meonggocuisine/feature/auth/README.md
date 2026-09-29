# Auth

로그인, 회원가입과 휴대전화 인증 기능의 `ui`와 `data` 코드를 둔다.

## 로그인 구성

- `ui/LoginScreen.kt`: 로그인 입력 화면과 Preview
- `ui/LoginViewModel.kt`: 입력 검증, 중복 요청 방지, 오류와 재시도 대기 상태
- `ui/LoginUiState.kt`: 화면에 필요한 상태
- `data/AuthApi.kt`: Backend A2 로그인 API
- `data/DefaultAuthRepository.kt`: 입력 정규화와 API 오류 분류
- `data/AuthSessionManager.kt`: 메모리 access token과 인증 상태
- `data/KeystoreRefreshTokenStore.kt`: Android Keystore로 암호화한 refresh token 저장

로그인 성공 시 로그인 화면을 back stack에서 제거해 로그인 전에 요청했던 화면으로 돌아갑니다. 로그아웃·탈퇴·닉네임·비밀번호 변경은 `feature/account`(마이페이지)에 있습니다.

## 계정 찾기 구성 (A9, 2026-09-23 추가)

- `ui/recovery/AccountRecoveryScreen.kt`: 한 화면 세 단계 — 휴대전화 인증(번호·인증번호) → 아이디 확인 + (선택) 새 비밀번호 → 완료. 로그인 화면의 "아이디·비밀번호 찾기" 로 들어온다(`AccountRecoveryRoute`).
- `ui/recovery/AccountRecoveryViewModel.kt`: 요청·확인·재설정 중복 방지, 재전송 카운트다운, 증명 만료 시 처음 단계로(아이디는 유지). 입력 규칙은 회원가입의 `SignUpInputValidator` 를 그대로 쓴다.
- `data/AccountRecoveryRepository.kt`·`DefaultAccountRecoveryRepository.kt`: A9-1·A9-2·A9-3 호출과 오류 분류(`PHONE-002` 는 증명 만료로 취급).

아이디 찾기와 비밀번호 재설정을 한 흐름으로 묶었다 — 둘 다 같은 휴대전화 인증을 거치므로 갈래를 먼저 고르게 하지 않는다. 복구 증명은 메모리에만 두고 화면을 벗어나면 지운다. 가입 여부는 서버가 알려 주지 않으므로(미가입 번호에도 202) 인증번호가 오지 않으면 번호를 확인하라는 안내만 한다.

## 회원가입 구성

- `ui/signup/SignUpScreen.kt`: 회원가입 화면 전체 배치, 상태 연결과 Preview
- `ui/signup/SignUpFormFields.kt`: 비밀번호, 휴대전화 인증과 개인정보 동의 입력 UI
- `ui/signup/SignUpInputValidator.kt`: NFC 기준 입력 검증과 화면용 오류 문구
- `ui/signup/SignUpViewModel.kt`: 인증 상태, 중복 요청 방지와 서버 오류 표시
- `ui/signup/SignUpUiState.kt`: 입력·필드 오류·휴대전화 인증·재시도 대기 상태
- `data/SignupModels.kt`: Backend A0-1, A0-2, A0-3, A1 요청·응답 모델
- `data/SignupRepository.kt`: 화면에서 사용하는 휴대전화 인증·회원가입 결과 계약
- `data/DefaultSignupRepository.kt`: 입력 정규화, API 호출과 오류 코드·필드 오류 변환

로그인 ID 중복 확인 버튼은 A0-3을 호출해 결과를 로그인 ID 필드에 표시합니다. 이 결과는 아이디를 예약하지 않으므로 A1에서 `MEMBER-001`이 오면 같은 필드에 다시 안내합니다. 휴대전화 번호를 변경하면 진행 중인 요청·확인·재전송 카운트다운을 취소하고 기존 인증 증명을 즉시 폐기합니다. 화면을 벗어날 때 비밀번호·확인값·OTP와 인증 증명을 메모리에서 제거합니다. `passwordConfirm`은 화면에서 NFC 기준 일치 여부만 검사하고 A1 요청에는 포함하지 않습니다.

## 토큰 갱신 구성

- `data/AuthApi.kt`: Backend A3 토큰 갱신 API
- `data/AuthRepository.kt`: `refreshTokens()` 결과 계약(`Refreshed`, `SessionExpired`)
- `data/DefaultAuthRepository.kt`: A3 호출과 실패 시 세션·저장값 삭제
- `data/AuthSessionManager.kt`: `renew()`로 새 갱신 토큰을 먼저 저장한 뒤 세션을 교체
- `data/SessionAuthTokenProvider.kt`: `core/network`에 토큰을 공급하고 갱신을 single-flight로 수행

여러 요청이 동시에 갱신을 요구해도 A3은 한 번만 호출합니다. 잠금을 기다리는 동안 access token이
이미 바뀌었으면 A3을 다시 호출하지 않고 그 결과를 함께 사용합니다.

서버가 세션을 거부(`AUTH-003`)하거나 처리 여부를 알 수 없는 통신 실패가 나면 같은 토큰으로
재시도하지 않고 세션과 암호화 저장값을 삭제합니다. 세션이 사라지면 `MainActivity`가 구독하는
`session`이 null이 되어 인증이 필요한 화면은 다시 로그인을 요구합니다.

A3 응답에는 회원 정보가 없으므로 `AuthSession.member`는 갱신만으로 복구되지 않습니다. 되살리기가
끝나면 `AuthRepository.loadCurrentMember()`가 A6로 채웁니다. 마이페이지도 진입할 때 A6를 부르지만,
회원 번호는 채팅의 내 메시지 판별처럼 마이페이지를 거치지 않는 화면에서도 필요합니다.

## 앱 재시작 세션 되살리기

- `ui/AuthGateViewModel.kt`: 앱 시작 시 저장된 갱신 토큰으로 A3을 한 번 호출하고 되살리는 동안의
  상태를 `AuthGateState`로 알린다
- `MainActivity`: `RESTORING` 동안에는 `AppNavHost` 대신 대기 화면을 그린다
- `data/AuthApi.kt`: A6 `members/me` — 되살린 세션의 회원 정보를 채운다

access token은 메모리에만 있어 프로세스가 끝나면 사라지므로, 되살리지 않으면 앱을 켤 때마다 다시
로그인해야 합니다. 되살리기가 끝나기 전에 `AppNavHost`를 띄우면 로그인 여부를 비로그인으로 단정해,
그 사이에 누른 인증 필요 화면이 로그인 화면으로 빠집니다. 그래서 `AppNavHost`에 넘기는
`isAuthenticated`는 판정이 끝난 뒤에만 계산합니다.

저장된 갱신 토큰이 없으면 A3을 호출하지 않습니다. 실패 처리가 저장소를 지우면서 디스크에 쓰므로,
한 번도 로그인하지 않은 사용자에게는 불필요한 작업입니다. 화면 회전으로 되살리기를 다시 실행하지
않도록 `ViewModel`에 둡니다.
