# Android 프로젝트 구조

Android 앱은 `app` 단일 모듈로 시작하며, 기능 단위 패키지를 기본 경계로 사용한다.
빌드 시간이나 기능 간 결합이 실제 문제가 될 때 필요한 영역부터 멀티 모듈 분리를 검토한다.

```text
com.hotdog.meonggocuisine
├── core
│   ├── designsystem  공통 테마와 재사용 UI
│   ├── navigation    앱 경로와 화면 이동
│   └── network       API 클라이언트와 공통 네트워크 처리
└── feature
    ├── auth          로그인, 회원가입, 휴대전화 인증
    ├── community     잃어버렸어요·보호하고 있어요 목록
    ├── report        실종·보호 동물 등록과 수정
    ├── post          게시물 상세와 내 게시물 관리
    ├── match         유사도 분석과 후보 조회
    └── chat          사용자 간 1:1 채팅
```

각 기능은 구현을 시작할 때 다음 경계를 만든다.

- `ui`: `Screen`, 기능 전용 Composable, `ViewModel`, `UiState`
- `data`: `Repository` 구현과 기능 전용 API·DTO

기능별 비즈니스 규칙이 복잡해지기 전에는 `domain` 계층이나 `UseCase`를 미리 만들지 않는다.
테스트 대역이나 여러 데이터 출처가 필요해질 때 `Repository` 인터페이스와 구현을 분리한다.

`Screen`은 `UiState`를 표시하고 사용자 이벤트를 `ViewModel`에 전달한다. API를 직접 호출하지 않는다. `ViewModel`은 화면 상태와 사용자 동작을 관리하며 `Repository`에 데이터 작업을 요청한다. `Repository`는 데이터 출처를 감추고 UI에 필요한 결과를 반환한다.

## 화면 상태 관리

각 화면의 `ViewModel`은 `StateFlow<UiState>`로 화면 상태를 제공한다. `Screen`은 상태를 생명주기에 맞게 구독하고, 사용자 입력과 클릭을 콜백으로 `ViewModel`에 전달한다.

```text
ViewModel ── StateFlow<UiState> ──→ Screen
ViewModel ←──── 사용자 이벤트 ───── Screen
```

`UiState`에는 입력값과 로딩·콘텐츠·빈 결과·실패처럼 화면을 그리는 데 필요한 상태만 둔다. `Screen`은 API나 `Repository`를 직접 호출하지 않는다. 실제 `UiState`와 `ViewModel`은 해당 기능을 구현할 때 추가한다.

`core`에는 두 기능 이상에서 함께 사용하는 코드만 둔다. `core/designsystem`은 도메인 규칙을 포함하지 않으며, `core/network`가 Retrofit 등 외부 네트워크 라이브러리의 경계를 맡는다. 기능 간 직접 참조와 순환 의존은 허용하지 않는다.

## Core 경계

- `core/designsystem`: 앱 전체의 Theme, 디자인 토큰과 도메인 로직이 없는 공통 Composable
- `core/navigation`: 앱 Route, NavHost와 화면 이동 규칙
- `core/network`: Retrofit 생성, API 기본 주소, JSON 변환과 공통 네트워크 처리

`core/designsystem`은 원본 값인 `token`, Material 역할을 연결하는 `theme`, 두 기능 이상에서 재사용하는 `component`로 나눈다. 상세 구조와 변경 위치는 [`core/designsystem/README.md`](app/src/main/java/com/hotdog/meonggocuisine/core/designsystem/README.md)를 따른다.

`core/navigation`은 타입으로 정의한 Route와 앱의 NavHost를 관리한다. 화면별 경로, 인증 필요 여부, 새 화면 연결 순서는 [`core/navigation/README.md`](app/src/main/java/com/hotdog/meonggocuisine/core/navigation/README.md)를 따른다.

기능별 API와 DTO는 각 기능의 `data`에 둔다. 게시물 카드처럼 특정 도메인을 아는 UI는 여러 화면에서 사용하더라도 `core/designsystem`에 두지 않는다. `utils`, `common`, `base` 같은 포괄적인 공통 패키지는 미리 만들지 않고, 실제 중복이 생겼을 때 책임을 설명하는 이름으로 분리한다.

Theme과 디자인 토큰, 공통 UI, Route와 NavHost, Retrofit 객체, 로그인 토큰 저장 경계, 휴대전화 인증 기반 회원가입 흐름, 비로그인 실종 게시물 목록, 관심 지역 기반 보호 게시물 목록이 구현되어 있다. 앱 재시작 토큰 갱신, 로그아웃과 보호 API 인증 재시도는 각각의 후속 Jira 업무에서 요구사항을 확인한 뒤 구현한다.

## 데이터와 오류 처리

기능별 API, 요청·응답 DTO와 `Repository`는 해당 기능의 `data`에 둔다. `ViewModel`은 API를 직접 호출하지 않고 `Repository`를 통해 데이터를 요청한다. 서버 응답을 `Screen`에 그대로 전달하지 않으며, `Repository`가 앱에서 사용할 결과로 변환하고 `ViewModel`이 이를 `UiState`로 바꾼다.

```text
Screen ↔ ViewModel ↔ Repository ↔ API ↔ Backend
```

일반 JSON 응답은 Backend 계약의 `code`, `message`, `data` 구조를 공통으로 해석한다. HTTP 상태와 `AUTH-*`, `POST-*` 같은 오류 코드를 보존한 뒤 공통 통신 오류는 `core/network`에서 분류하고, 기능별 오류는 해당 기능에서 화면 상태로 변환한다. 서버의 안전한 `message`와 필드 검증 오류는 기능 요구사항에 맞게 표시하되, 내부 예외나 민감정보를 별도로 노출하거나 로그에 남기지 않는다.

`204 No Content`와 사진 바이너리 응답은 JSON 공통 응답으로 해석하지 않는다. API 기본 주소는 `/api/v1`까지 포함한다. 요청 제한 응답에서 필요한 `Retry-After` 같은 Header 정보도 버리지 않는다.

`Repository` 구현이 하나뿐이면 클래스로 시작한다. 테스트 대역이나 API·기기 저장소처럼 여러 데이터 출처가 필요해질 때 인터페이스와 구현을 분리한다. 실제 API 타입과 오류 모델은 해당 기능 API를 연결할 때 명세를 확인하고 추가한다.

## 의존성 주입

앱의 의존성 주입에는 Hilt를 사용한다. `MeonggoApplication`이 앱 범위의 Hilt 컨테이너를 시작하고, Android 진입점은 `@AndroidEntryPoint`로 연결한다. 기능을 구현할 때 `ViewModel`은 `@HiltViewModel`과 생성자 주입으로 `Repository`를 받고, `Repository`는 생성자 주입 또는 기능에 필요한 Hilt Module을 통해 API를 받는다.

```text
Hilt → ViewModel → Repository → API
```

객체를 전역 변수나 Composable 내부에서 직접 생성하지 않는다. 앱 전체에서 하나만 공유해야 하는 Retrofit 같은 객체만 Singleton 범위로 제공하고, 모든 객체에 불필요하게 Singleton 범위를 적용하지 않는다. 단위 테스트에서는 Hilt 컨테이너 대신 ViewModel과 Repository 생성자에 가짜 의존성을 직접 전달한다.

현재 등록된 라이브러리의 용도는 다음과 같다.

- Navigation Compose: 화면 경로와 이동
- Lifecycle ViewModel/Compose: 화면 상태와 생명주기 연동
- Kotlin Coroutines: 비동기 작업
- Retrofit과 Kotlin Serialization converter: Backend API 통신
- Coil Compose: 원격 이미지 표시
- Hilt와 KSP: 의존성 주입 코드 생성

실종동물 등록 화면은 `feature/report/ui/lost`에 두고, multipart 등록 요청과 DTO는 `feature/report/data`에서 관리한다. 화면 디자인 수정은 `LostPostCreateScreen.kt`, 입력 검증 수정은 `LostPostCreateInputValidator.kt`, 서버 요청 필드 수정은 `DefaultLostPostCreateRepository.kt`에서 한다.

보호 동물 등록 화면은 `feature/report/ui/sheltering`에 두고, 발견 위치는 `eventLocation`, 현재 보호 위치는 `currentLocation`으로 분리해 서버에 전송한다. 보호 등록 성공 후에는 매칭 실행 없이 보호 목록으로 이동한다.

## 사용자 게시물 상세

- 사용자 게시물 상세는 eature/post에서 담당하며 실종, 사용자 보호, 보호소 보호동물 상세를 하나의 화면 모델로 표현합니다.
- 상세 화면 액션은 응답의 작성자 여부, 게시물 상태, 출처에 따라 노출합니다.
- 위치 정보는 서버가 공개 조건에 맞춰 내려준 공개 지역 또는 정확한 위치만 표시합니다.
