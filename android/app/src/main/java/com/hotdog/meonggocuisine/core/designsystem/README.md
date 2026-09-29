# Design system

앱 전체에서 재사용하는 Theme, 디자인 토큰과 공통 Composable을 둔다. 원본 디자인 값과 UI 역할, 공통 컴포넌트, 기능 화면을 분리해 전체 스타일과 특정 화면을 독립적으로 수정한다.

## 구조

| 경로 | 책임 |
|---|---|
| `token/Palette.kt` | 브랜드 원본 색상 |
| `token/MeonggoSpacing.kt` | 4dp 기준 공통 간격 |
| `token/MeonggoRadius.kt` | 공통 모서리 크기 |
| `token/MeonggoCardColors.kt` | 홈 메인 카드 세 장의 면 색. Material 역할로 묶이지 않는 값이라 이름 있는 토큰으로 둔다 |
| `theme/ColorScheme.kt` | 원본 색상을 Material UI 역할에 연결 |
| `theme/Typography.kt` | 제목, 본문, 라벨 글자 스타일 |
| `theme/Shapes.kt` | 버튼, 입력창, 카드의 기본 모양 |
| `theme/Theme.kt` | 색상, 글자, 모양을 앱 Theme으로 조립 |
| `component/MeonggoButton.kt` | 주요, 보조, 텍스트 버튼과 로딩 상태. `MeonggoDangerButton`은 되돌릴 수 없는 동작(계정 삭제)용으로 색만 `error`다 |
| `component/MeonggoTextField.kt` | 라벨, 안내, 오류, 아이콘을 지원하는 입력창. 여러 줄을 받을 때는 `singleLine = false`와 `maxLines`를 함께 넘긴다 |
| `component/MeonggoScreenHeader.kt` | 탭 바 없이 들어갔다 나오는 화면의 머리줄. 가운데 제목 + 꺾쇠, 오른쪽 `actions` 슬롯. **새 화면은 이것을 쓴다** |
| `component/MeonggoTopBar.kt` | Material `TopAppBar` 기반 옛 헤더. 소식 화면에만 남아 있고 새로 쓰지 않는다 |
| `component/MeonggoSurfaces.kt` | 떠 있는 면의 공통 값(22dp 모서리·1dp 그림자·16dp 좌우 여백)과 `meonggoFloatingShadow`·`meonggoDashedBorder` |
| `component/MeonggoLoadingIndicator.kt` | 접근성 설명을 포함하는 로딩 표시 |
| `component/MeonggoPhotoPlaceholder.kt` | 사진 없음 자리표시자. 축종(DOG·CAT·그 밖)에 맞는 Canvas 그림 + "사진 없음", 72dp 미만이면 그림만 |
| `component/MeonggoPhotoThumbnail.kt` | 목록에 올리는 사진 한 칸. 사진이 없거나 원본이 404면 자리표시자로 떨어진다 |
| `component/MeonggoDropdownMenu.kt` | 선택 목록(드롭다운)과 앵커용 화살표. 흰 면·옅은 테두리·12dp 모서리·선택 항목 강조, 320dp 넘으면 스크롤 |
| `component/HomeIcon.kt` · `component/SearchIcon.kt` · `component/HeartIcon.kt` | 하단 탭 아이콘 세 개. 기기 글꼴마다 굵기가 달라지는 글리프 대신 직접 그린다 |
| `component/PawIcon.kt` · `component/CalendarIcon.kt` · `component/LocationPinIcon.kt` · `component/CheckIcon.kt` · `component/ChevronLeftIcon.kt` · `component/ChevronRightIcon.kt` · `component/FilterIcon.kt` · `component/PlusIcon.kt` · `component/ChatIcon.kt` | 본문에서 쓰는 그림들. 같은 이유로 직접 그린다 — 🐾·📍·🗓·✓·‹ 같은 글리프는 기기마다 모양과 굵기가 달라진다 |

한 기능에서만 사용하는 UI는 `feature/<기능>/ui/component`에 둔다. 비밀번호 입력처럼 기능 규칙이 있는 UI는 공통 입력창을 조합하되 해당 기능 안에서 구현한다.

## 변경 위치

| 변경하려는 항목 | 수정 위치 | 영향 범위 |
|---|---|---|
| 서비스 대표 원본 색상 | `token/Palette.kt` | 해당 색상을 연결한 전체 UI |
| 배경, 글자, 테두리의 역할별 색상 | `theme/ColorScheme.kt` | 해당 Material 역할을 쓰는 전체 UI |
| 전체 제목, 본문, 라벨 스타일 | `theme/Typography.kt` | 해당 글자 스타일을 쓰는 전체 UI |
| 버튼과 카드의 기본 모서리 | `theme/Shapes.kt` | Material 기본 Shape을 쓰는 전체 UI |
| 공통 간격 기준 | `token/MeonggoSpacing.kt` | 해당 간격 토큰을 쓰는 UI |
| 전체 공통 헤더나 버튼 | `component/`의 해당 파일 | 그 공통 컴포넌트를 쓰는 화면 |
| 특정 기능이나 화면 | `feature/<기능>/ui/` | 해당 기능이나 화면 |

화면 코드에서 색상 코드와 반복 간격을 직접 선언하지 않는다. 현재 `feature/` 아래에 직접 선언한 색상은 없다. 먼저 Material Theme의 의미 기반 값(`primary`, `surface`, `onSurface` 등)을 사용하고, Material Theme으로 표현할 수 없는 공통 값만 이름 있는 토큰으로 추가한다.

## 현재 결정

- 사용자에게 보이는 Theme 이름은 `MeonggoBanjeomTheme`으로 한다.
- 브랜드 색상은 갈색 계열이다. 배경은 따뜻한 쪽으로 한 방울 기운 흰색(`Neutral25`)을 쓰고 강조·주요 버튼에 `Brown600`을 쓴다. 순백(`Neutral0`)은 바탕 위에 떠 있어야 하는 면(하단 탭·헤더 버튼)에만 `surfaceContainerLowest`로 쓴다.
- MVP는 라이트 Theme만 제공하고 시스템 동적 색상은 사용하지 않는다.
- 별도 폰트를 추가하지 않고 Android 기본 한글 시스템 글꼴을 사용한다.
- 간격은 4dp 단위, 모서리는 8dp, 12dp, 20dp를 기본으로 한다.
- 색상만으로 상태를 구분하지 않고 텍스트, 아이콘, 접근성 설명을 함께 사용한다.
- 떠 있는 카드는 `MeonggoSurfaces.cardShape`(22dp)에 `meonggoFloatingShadow`만 쓴다. 테두리는 두르지 않는다 — 바탕이 따뜻한 흰색이라 순백 면과 옅은 갈색 그림자로 경계가 선다. 예외는 홈 메인 카드 세 장으로, 면 색이 저마다 달라 모서리를 정리하는 선을 함께 두른다.
- 화면 좌우 여백은 `MeonggoSurfaces.gutter`(16dp)다. 화면을 옮겨도 글이 같은 세로선에서 시작해야 한다.
- 그림 문자(이모지)를 UI 에 쓰지 않는다. 기기와 OS 판마다 모양·색·크기가 달라 한 화면만 다른 그림책처럼 보인다. 필요한 그림은 `component/`에 Canvas 로 그려 둔다 (2026-09-25 — 유사도 분석·채팅의 🐾📍🗓⚠️🔍🔒 를 걷어냈다).
- 아이콘 자리에 글꼴 글리프(`‹`·`›`·`✓`)를 쓰지 않는다. 굵기가 기기 글꼴을 따라가 옆의 제목과 어긋난다. 문장 끝에 붙어 "이어진다"만 알리는 `›`(홈 소식 띠·게시물 상세 링크)는 글자의 일부라 그대로 둔다.

## 공통 컴포넌트 사용

```kotlin
MeonggoTopBar(
    title = "잃어버렸어요",
    navigationIcon = { /* 화면의 뒤로 가기 버튼 */ },
    actions = { /* 화면별 우측 동작 */ },
)

MeonggoButton(
    text = "등록하기",
    onClick = onSubmit,
    isLoading = uiState.isSubmitting,
)

MeonggoTextField(
    value = uiState.title,
    onValueChange = onTitleChange,
    label = "제목",
    errorMessage = uiState.titleError,
)
```

각 공통 컴포넌트 파일에는 기본, 비활성, 로딩, 오류 같은 주요 상태를 확인하는 Compose Preview를 둔다. 화면별 문구와 클릭 동작, 아이콘은 호출하는 기능에서 전달한다.

## 색상 역할

| 역할 | 값 | 쓰는 곳 |
| --- | --- | --- |
| `primary` | `Brown600` | 주요 버튼, 선택된 항목, 강조 글자, 스위치 켜짐 |
| `onPrimary` | `Neutral0` | 주요 버튼 위 글자 |
| `primaryContainer` | `Brown50` | 강조 영역의 연한 바탕, 아이콘 배경 |
| `secondaryContainer` | `Sand300` | 사진 자리, 배지처럼 따뜻하게 채울 영역 |
| `background` · `surface` | `Neutral25` | 화면 배경과 카드 |
| `surfaceContainerLowest` | `Neutral0` | 바탕 위에 떠 있는 순백 면 — 하단 탭, 헤더 버튼 |
| `surfaceContainerHigh` · `surfaceContainerHighest` | `Neutral50` · `Neutral100` | Material 대화상자(`AlertDialog`)·메뉴·바텀시트가 기본으로 쓰는 면. 비워 두면 연보라 기본값이 나온다(2026-09-23 로그아웃 창) |
| `surfaceVariant` | `Brown50` | 카드 안에서 한 단계 눌러 둘 영역 |
| `onSurfaceVariant` | `Neutral500` | 보조 설명 글자 |
| `outline` | `Neutral200` | 입력창·카드 테두리 |
| `error` | `Red600` | 종료, 오류 안내 |

배경이 거의 흰색이므로 영역을 나눌 때는 회색 배경 대신 `outline` 테두리나 `surfaceVariant`를 쓴다.

색상을 바꿀 때는 `token/Palette.kt`의 값만 고친다. 화면은 Material 역할만 참조하므로 함께 따라온다.
각 값의 대비는 흰 글자 또는 흰 배경 기준 4.5:1 이상으로 맞춰 두었고, 값을 바꾸면 대비를 다시 확인한다.
