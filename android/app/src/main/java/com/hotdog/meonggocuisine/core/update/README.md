# Update

앱이 켜질 때 서버의 버전 안내(V1 `GET /api/v1/app/version`, docs/api-spec.md)를 읽어 업데이트를 권고하거나 강제한다.

## 구조

- `AppVersionApi.kt` · `AppVersionResponse.kt`: V1 호출과 응답 모델
- `UpdateDecision.kt`: 내 `versionCode` 와 서버 값의 비교 규칙(`decideUpdatePrompt`), 원스토어 딥링크(`oneStoreDeepLink`)
- `UpdateGateRepository.kt`: 조회(실패는 null)와 권고를 닫은 날 저장(`SharedPreferences`)
- `UpdateGateViewModel.kt`: 프로세스당 한 번 조회해 `UpdatePrompt` 를 정한다. `MainActivity` 가 NavHost 위에 `UpdatePromptDialog` 를 띄운다.

## 동작 규칙

- `versionCode` < 최소 지원 → **강제**. 바깥·뒤로 가기로 닫히지 않고 "업데이트" 만 있다. 서버 API 가 옛 앱과 호환되지 않게 바뀐 배포에서 서버가 최소 지원 값을 올린다.
- `versionCode` < 최신 → **권고**. "나중에" 로 닫을 수 있고 같은 날에는 다시 보이지 않는다.
- 서버 값이 0 이거나 조회에 실패하면 안내 없이 들어간다. 업데이트 확인이 앱을 막으면 안 된다.
- "업데이트" 는 원스토어 앱 딥링크(`onestore://common/product/{PID}`)를 먼저 열고, 원스토어가 없으면 서버가 준 웹 링크를 브라우저로 연다.
