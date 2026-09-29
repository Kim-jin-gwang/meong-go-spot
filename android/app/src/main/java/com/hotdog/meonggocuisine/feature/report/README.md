# Report

실종·보호 동물 등록 기능의 `ui`와 `data` 코드를 둔다.

등록한 게시물의 수정과 종료는 기존 게시물(`posts/{postId}`)을 다루고 폼 초기화에 상세(P2)가
필요해 `feature/post`의 `ui/edit`, `ui/close`에 둔다.

## 실종동물 등록

- `ui/lost/LostPostCreateScreen.kt`: 사진 선택, 기본 정보, 실종 정보, 위치 공개 동의와 등록 버튼 화면을 담당한다.
- `ui/lost/LostPostCreateViewModel.kt`: 입력 상태, 필수값 검증, 등록 요청 중 상태와 성공 이벤트를 관리한다.
- `ui/lost/LostPostCreateInputValidator.kt`: 사진 수, 날짜, 시간, 실종 장소 입력 규칙을 검증한다.
- `ui/EventTimeInput.kt`·`ui/EventTimeFields.kt`: 시각을 오전/오후·시·분 세 칸으로 받고 서버에는 `HH:mm` 으로 보낸다. 두 등록 화면이 같이 쓴다.
- `ui/EventDateField.kt`: 날짜를 Material 달력(`DatePickerDialog`)에서 고른다. 값은 `yyyy-MM-dd` 문자열 그대로라 뷰모델·검증기는 바뀌지 않고, 미래 날짜는 달력에서 고를 수 없다. 두 등록 화면과 게시물 수정 화면이 같이 쓴다.

글자 칸은 `ui/PostTextLimits.kt` 의 서버 한도(이름 50·품종 100·색상 100·장소 200·특징 2000, 보호 상태 100 — 보호 등록의 특징은 보호 상태 한 줄을 붙여 보내므로 1880)에서 코드 포인트 단위로 잘라 넣고, 칸 아래에 `사용/최대` 를 보여 준다. 넘친 채 보내면 서버가 어느 칸인지 말해 주지 않는 400 을 주기 때문이다. 게시물 수정 화면(`feature/post/ui/edit`)도 같은 한도를 쓴다.

사진을 더할 때 규격에 안 맞는 사진(JPEG·PNG 외, 가로·세로 64px 미만, 10MB 초과, 읽기 실패)이나 장수 한도에 걸린 사진이 있으면 `PhotoRejectionDialog` 가 "N장 중 M장을 넣지 못했어요" 와 이유별 줄, 등록 가능한 사진 기준 한 줄(`PHOTO_REQUIREMENTS`)을 보여 준다(`ui/PhotoRejectionNotice.kt`). 검사 기준은 `data/PhotoInputInspector.kt` 에 있고 서버(`docs/photo-upload-policy.md`)와 같아야 한다.

검증은 실시간이다. 각 칸의 오류는 그 칸을 떠났거나 다 채운 뒤에 빨갛게 보이고(치는 도중에는 보이지 않음), 시 `13` 처럼 더 쳐도 맞을 수 없는 값은 바로 보인다. 등록 버튼은 오류가 하나라도 있으면 잠기고 아래에 "등록 전 확인할 항목: 사진 · 실종 시간" 처럼 남은 칸을 적는다. 서버가 400 으로 짚은 칸은 같은 자리에 보이고 그 칸을 고치면 지워진다(`UiState.externalErrors`).
- `data/ReportApi.kt`: `POST /api/v1/posts` multipart 요청을 정의한다.
- `data/DefaultLostPostCreateRepository.kt`: `payload(application/json)`와 `photos` multipart part를 만들어 서버에 전송한다.

실종 장소의 정확한 위치 공개를 켜면 `exact-location-v1` 정책 버전을 함께 보낸다. 요청 실패 시 입력값과 선택 사진은 `UiState`에 유지된다.

## 보호 동물 등록

- `ui/sheltering/ShelteringPostCreateScreen.kt`: 사진, 기본 정보, 발견 정보, 현재 보호 정보와 두 위치 공개 동의 화면을 담당한다. 사진은 앨범 선택과 카메라 촬영 두 입구가 있다.
- `core/media/UploadPhotoShrinker.kt`(공용): 올리기 전에 긴 변 2,048px·품질 90 JPEG 로 줄인다(작은 JPEG 는 그대로). 등록·수정 저장소가 `photoPart` 에서 쓴다 — 열 장 25MB 가 타임아웃으로 실패한 QA(2026-09-23) 뒤.
- `ui/CameraCapture.kt`: 카메라 앱으로 한 장 찍어 앱 캐시(`camera/`, FileProvider `${applicationId}.fileprovider`)에 받는다. CAMERA 권한 없이 `TakePicture` 계약만 쓰고, 결과는 갤러리 사진과 같은 content URI 로 검사·업로드 경로를 탄다.
- `ui/sheltering/ShelteringPostCreateViewModel.kt`: 보호 등록 입력 상태, 요청 중 상태, 실패 유지와 성공 이벤트를 관리한다.
- `ui/sheltering/ShelteringPostCreateInputValidator.kt`: 사진 수, 발견 날짜·시간·장소, 현재 보호 상태·장소 입력 규칙을 검증한다.
- `data/DefaultShelteringPostCreateRepository.kt`: `type=SHELTERING`, `eventLocation`, `currentLocation`을 포함한 multipart 등록 요청을 만든다.

보호 동물 등록 성공 후에는 역방향 매칭 화면으로 이동하지 않고 보호 목록으로 돌아간다. 발견 장소와 현재 보호 장소의 정확한 위치 공개 동의는 서로 독립적으로 처리한다.
