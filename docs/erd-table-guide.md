# ERD 테이블 설명서

> 대상: 멍고반점 MVP의 ERD를 처음 확인하는 백엔드·Android·DATA·AI 팀원
>
> 기준 문서: [erd.md](erd.md), [api-spec.md](api-spec.md), [architecture.md](architecture.md)
>
> 이 문서는 테이블이 존재하는 이유와 데이터 흐름을 설명한다. 전체 컬럼 타입, CHECK 제약조건과 인덱스는 [erd.md](erd.md)를 기준으로 한다.

## 1. 전체 구조

MVP와 입양 탐색 P1 계약은 17개 테이블을 사용한다. 기능별로 나누면 다음과 같다.

| 영역 | 테이블 | 핵심 역할 |
|---|---|---|
| 회원·인증 | `member` | 회원 계정과 화면 표시용 닉네임 |
| 회원·인증 | `auth_session` | 갱신 토큰 세션과 로그아웃 처리 |
| 동물 게시 건 | `animal_case` | 사용자·공공 동물 데이터의 공통 본체 |
| 동물 게시 건 | `animal_case_location` | 역할별 공개 위치, 정확한 위치·공개 여부와 좌표 |
| 동물 게시 건 | `user_post` | 사용자 게시물의 작성자·공개 동의 증빙 |
| 내부 채팅 | `chat_room` | 게시물 작성자와 요청자의 1:1 대화방 |
| 내부 채팅 | `chat_message` | 대화방의 텍스트 메시지 |
| 공공데이터 | `shelter` | 보호소 정보 |
| 공공데이터 | `shelter_animal` | 공공 API 구조동물의 상세 정보 |
| 공공데이터 | `lost_report` | 공공 분실 신고의 출처 상세 |
| 이미지 | `animal_photo` | 게시 건별 사진 1~10장 |
| 이미지 | `member_photo_erasure_task` | 탈퇴 회원의 HDFS 사용자 사진 삭제 재시도 outbox |
| AI 매칭 | `match_run` | 한 번의 매칭 실행과 처리 상태 |
| AI 매칭 | `match_candidate` | 매칭 실행에서 나온 후보와 점수 |
| 공공데이터 | `ingestion_run` | 공공데이터 수집 실행 이력 |
| 공공데이터 | `dashboard_stat` | 홈 인사이트용 사전 계산 통계 |
| 입양 탐색 | `adoption_favorite` | 회원이 명시적으로 선택한 공공 보호동물 찜 |
| 입양 탐색 | `adoption_swipe` | 회원이 넘긴 공공 보호동물. 다시 보여 주지 않으려고 남긴다 |

핵심 관계는 다음처럼 이해하면 된다.

```text
member
├── auth_session
├── user_post ───────────────┐
├── chat_room ── chat_message│
├── adoption_favorite ───────┤
└── adoption_swipe ──────────┤
                             │
animal_case ─────────────────┤ 사용자 게시물인 경우 1:1
├── animal_case_location     │ EVENT/CURRENT 역할별 1:N
├── animal_photo             │
├── user_post ───────────────┘
├── chat_room ── chat_message      사용자 게시물의 1:1 채팅
├── shelter_animal ── shelter       공공데이터인 경우 1:1
├── match_run ── match_candidate    잃어버렸어요 기준 매칭
├── match_candidate                 보호 중 후보로 참조될 수 있음
├── adoption_favorite               공공 보호동물 찜으로 참조될 수 있음
└── adoption_swipe                  넘긴 기록으로 참조될 수 있음

ingestion_run ── shelter_animal / lost_report   마지막 공공데이터 수집 실행·일일 요약 원본
dashboard_stat                                  홈 인사이트 사전 계산 결과

member_photo_erasure_task           관계형 파기 전에 보존한 HDFS 삭제 작업, 성공 시 행 제거
```

`animal_case`는 실제 동물 한 마리를 뜻하지 않는다. 사용자가 작성한 게시물이나 공공 API의 구조 신고처럼 한 번의 관찰·등록 건을 뜻한다. 서로 같은 동물로 추정되더라도 행을 합치지 않고 `match_candidate`로 연결한다.

## 2. 회원·인증 테이블

### 2.1 `member`

회원의 로그인 계정과 서비스에서 사용하는 기본 정보를 저장한다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 로그인 ID, 비밀번호 해시, 닉네임, 인증된 전화번호 보호값, 개인정보 수집·이용 동의 여부·버전·시각 |
| 생성 시점 | 회원가입 성공 시 |
| 주요 작성자 | Spring Boot 인증 API |
| 주요 관계 | `auth_session`, `user_post`, `chat_room`, `chat_message`와 1:N |

중요 컬럼은 다음과 같다.

- `login_id`: 입력을 `NFC(NFC(value).toLowerCase(Locale.ROOT))`로 변환한 canonical 로그인 ID만 저장한다. `idx_member_login_id_canonical` 유일 인덱스가 가입·로그인·실패 제한에서 같은 계정을 가리키게 한다.
- `password_hash`: 비밀번호마다 새 16바이트 salt를 사용하는 `{argon2id-v1}` Argon2id(memory 64 MiB, iterations 3, parallelism 4, hash 32바이트) 인코딩 문자열만 저장한다. 평문·NFC 정규화 값은 저장하지 않으며 상세 기준은 [비밀번호 정책](password-policy.md)을 따른다.
- `status`: `ACTIVE` 또는 `WITHDRAWN`으로 계정 사용 가능 여부를 나타낸다.
- `privacy_collection_agreed`, `privacy_collection_policy_version`, `privacy_collection_consented_at`: 현재 `privacy-collection-v1` 고지에 동의한 여부·버전·서버 처리 시각이다. 활성 회원은 모두 필수이고 탈퇴 30일 후에는 함께 파기한다.
- `deleted_at`: 탈퇴 직후 즉시 물리 삭제하지 않고 정리 일정을 추적한다.
- `personal_data_erased_at`, `relational_data_erased_at`: 개인정보 파기와 관계형 데이터 파기의 독립 커밋 완료 시각이다. 뒤 단계 완료 시각은 앞 단계보다 빠를 수 없다.

개인정보 수집·이용 고지는 가입 정보와 기능 사용 시 처리하는 게시물·사진·위치·선택 좌표·채팅, 목적과 보유 기간을 포함한다. 마케팅 동의와 제3자 제공 동의는 MVP에 포함하지 않는다. 전화번호 평문 대신 AES-256-GCM `phone_ciphertext`와 중복 확인용 `phone_lookup_hash`를 분리해 저장한다. 조회 키는 공백 없는 UTF-8 E.164 번호와 전용 `PHONE_LOOKUP_HMAC_KEY_V1`을 사용한 HMAC-SHA-256 소문자 16진수 64자로 고정한다. 전화번호는 다른 회원이나 일반 API 응답에 포함하지 않고, 중복 번호 가입에는 기존 계정 정보를 공개하지 않은 문의 안내만 제공한다. 활성 번호의 동시 중복은 부분 유일 인덱스로 막고, 가입 서비스는 상태·경과 시간과 무관하게 같은 조회 해시가 남은 모든 회원 행을 거부한다. 휴면과 닉네임 중복 제한은 두지 않는다. 탈퇴 30일 뒤 회원 개인정보 파기 트랜잭션은 전화번호 보호값·인증 시각·동의 버전·시각을 `NULL`로 만들고 로그인 ID·닉네임·비밀번호 자격 증명을 탈퇴 값으로 치환한다. 커밋 후 `personal_data_erased_at`을 기록해 번호 재사용을 허용하며, 실패로 해시가 남아 있으면 차단을 유지한다. 관계형 데이터 정리 커밋은 `relational_data_erased_at`을 기록한다. `idx_member_data_erasure_due`는 두 단계 중 하나라도 끝나지 않은 탈퇴 회원만 조회 대상으로 유지한다. 상세 암호화·키 교체는 [백엔드 보안·운영 정책](backend-security-operations-policy.md)을 따른다.

로그인 실패 횟수는 관계형 테이블에 저장하지 않는다. 공유 Redis 인증 캐시에서 canonical
로그인 ID와 검증된 IP의 HMAC-SHA-256 키만 사용해 계정 15분 내 5회·IP 10분 내 20회
실패를 원자적으로 세고, 한도부터 15분 동안 `AUTH-004`로 제한한다. A1 인코딩·A2 실제/dummy
비교·재인코딩은 대기열 없는 같은 Argon2 공용 실행권으로 최대 4개만 동시에 실행하며 A2는
실행권 획득 뒤 제한을 다시 확인한다. 포화는 `AUTH-006`으로 실패 제한과 구분한다. 성공 시
계정 횟수만 제거하며 영구 잠금은 하지 않는다.

### 2.2 `auth_session`

갱신 토큰 단위의 로그인 세션을 관리한다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | refresh token selector·secret 해시, 고정 만료·폐기·마지막 사용 시각 |
| 생성 시점 | 로그인 성공 시 |
| 주요 작성자 | Spring Boot 인증 API |
| 주요 관계 | 여러 세션이 하나의 `member`를 참조 |

중요 동작은 다음과 같다.

- refresh token은 `v1.{selector}.{secret}` 형식이며 selector는 16바이트, secret은
  32바이트 난수를 padding 없는 Base64 URL로 인코딩한다.
- selector는 `refresh_token_selector`에 저장해 세션을 조회하고 회전 전후 토큰 관계를
  유지한다. secret 원문은 저장하지 않고 SHA-256 결과만 `refresh_token_hash`에 저장한다.
- 토큰 갱신 시 selector로 행을 잠금 조회해 secret 해시, 로그인 시점부터 30일인 고정
  만료 시각과 폐기 여부를 확인한다. 정상 요청만 새 secret 해시로 원자 교체한다.
- selector는 같지만 secret 해시가 다르면 회전된 토큰의 재사용 또는 탈취로 보고 해당
  세션을 폐기한다. 동일 token 동시 갱신은 정확히 하나만 성공한다.
- access JWT는 `sid` claim으로 이 행을 참조한다. 모든 보호 API는 JWT 검증 뒤 세션의
  회원 일치·고정 만료·폐기 여부를 확인해 로그아웃·탈퇴를 즉시 반영한다.
- A4만 같은 `sub`·`sid`·refresh token의 로그아웃 재시도를 폐기 세션에도 204로 처리한다.
- 로그아웃 시 행을 삭제하는 대신 `revoked_at`을 기록해 재사용을 차단한다.
- 회원 탈퇴 시 해당 회원의 모든 활성 세션을 폐기한다.

회원 한 명이 여러 기기에서 로그인할 수 있으므로 `member`와 1:N 관계로 분리한다.

## 3. 동물 게시 건 테이블

### 3.1 `animal_case`

모든 동물 게시 건의 공통 본체다. 사용자 `잃어버렸어요`, 사용자 `보호하고 있어요`, 공공 보호동물을 같은 목록과 매칭 후보군에서 처리하기 위해 사용한다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 게시 유형, 출처, 상태, 동물 특징, 발생 날짜·시각 |
| 생성 시점 | 사용자 게시물 등록 또는 공공 구조동물 최초 수집 시 |
| 주요 작성자 | 사용자 건은 Spring Boot, 공공 건은 DATA 수집 파이프라인 |
| 주요 관계 | 역할별 위치와 1:N, 출처 상세와 1:1, 사진·매칭 실행과 1:N |

핵심 구분 값은 다음과 같다.

| 컬럼 | 값 | 의미 |
|---|---|---|
| `case_type` | `LOST` | 사용자가 찾고 있는 동물 |
| `case_type` | `SHELTERING` | 사용자 또는 보호소가 보호 중인 동물 |
| `source_type` | `USER` | 사용자가 등록한 게시물 |
| `source_type` | `PUBLIC` | 공공 API에서 수집한 데이터 |
| `status` | `ACTIVE` | 공개 목록에 표시되는 상태 |
| `status` | `CLOSED` | 반환·인계·공공 상태 종료 등으로 닫힌 상태 |
| `status` | `DELETED` | 논리 삭제되어 외부에 노출하지 않는 상태 |

`listed_at`은 목록 등록순·커서 기준이다. 사용자 건은 등록 시각을 사용하고, 공공 건은 공고 시작일을 최우선으로 하며 없으면 원천 사건 시각, 모두 없으면 적재 시각을 사용한다. 기본 `LATEST`는 같은 시각의 `id`까지 내림차순, 선택 `OLDEST`는 같은 시각의 `id`까지 오름차순으로 정렬한다.

`is_matchable`은 목록 공개 상태와 별개다. 예를 들어 공공 보호동물이 현재는 `CLOSED`여도 과거 실종 건을 소급 검색할 가치가 있으면 매칭 후보로 유지할 수 있다. 사용자 게시물이 종료되거나 삭제되면 즉시 `false`로 바꾼다.

`version`은 사용자가 동시에 같은 게시물을 수정할 때 마지막 저장 내용을 실수로 덮어쓰지 않도록 하는 낙관적 잠금 값이다.

사용자 건의 `event_date`는 P3·P4 서비스가 명시적인 `Asia/Seoul` `Clock`으로 오늘 이하인지
검증한다. 현재 날짜에 따라 달라지는 조건은 DB `CHECK`로 두지 않으며 공공데이터 적재에는 이
사용자 입력 상한을 적용하지 않는다.

### 3.2 `animal_case_location`

동물 게시 건의 위치 정보를 사건 장소 `EVENT`와 현재 보호 장소 `CURRENT` 역할별 행으로 저장한다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 위치 역할, 행정구역 코드·표시값, 암호화된 정확한 위치, 위치별 공개 여부·동의 증빙, 위도·경도 |
| 생성 시점 | `animal_case` 생성과 같은 트랜잭션 |
| 주요 작성자 | 사용자 건은 Spring Boot, 공공 건은 DATA 수집 파이프라인 |
| 주요 관계 | 여러 역할 행이 하나의 `animal_case`를 참조하는 1:N 관계 |

위치 정보는 공개 범위에 따라 나뉜다.

- `location_type`: `EVENT`는 실종·발견 장소, `CURRENT`는 보호 중 동물의 현재 보호 장소다.
- `region_code`, `emd_code`: 검색에 사용하는 5자리 시·군·구 코드와 선택 10자리 읍·면·동 코드다. 목록 지역 검색은 `EVENT.region_code`만 사용한다.
- `public_location`: 행정구역 코드에서 만든 표시 전용 문자열이다.
- `exact_location_ciphertext`: 사용자가 입력한 상세 위치를 AES-256-GCM envelope로 저장한다.
- `exact_location_visible`: 해당 역할 위치의 정확한 위치 공개 선택이다. Android 기본값과 DB 기본값은 `false`다.
- `disclosure_policy_version`, `disclosure_consented_at`: 해당 역할 위치의 마지막 공개 동의 버전·시각이다.
- `latitude`, `longitude`: 거리 계산 등 내부 매칭에 사용하며 API 응답에는 직접 포함하지 않는다.

복합 PK `(animal_case_id, location_type)`가 같은 게시 건에 역할별 행이 중복되는 것을 막는다. 저장해야 하는 행은 다음과 같다.

| 데이터 종류 | `EVENT` | `CURRENT` |
|---|:---:|:---:|
| 사용자 `LOST` | 필수 | 금지 |
| 사용자 `SHELTERING` | 필수 | 필수 |
| 공공 `SHELTERING` | 필수 | 필수 |

별도 테이블로 분리하면 공개 목록 조회가 민감한 정확한 위치와 좌표를 실수로 함께 읽는 위험을 줄일 수 있다. DB는 위치 역할, 코드 형식, 좌표 쌍·범위, 공개가 true일 때 암호문·동의 버전·시각 필수를 `CHECK`로 방어한다. 코드의 상하위 소속, 출처·유형별 행 수와 공공 위치의 암호문 미사용·비공개 정책은 서비스 트랜잭션과 통합 테스트가 검증한다.

공공 보호동물은 **개·고양이만 적재한다** (2026-09-14 팀 결정). 공공 API의 `upKindNm`이 `기타`인 레코드(토끼·닭·햄스터 등, 전체의 약 2%)는 적재기가 `SPECIES_NOT_SUPPORTED`로 거부하며 조회 단계에서 가리지 않는다 — 실행 이력의 `error_summary`에 사유가 남아 정책으로 뺀 것임이 실행마다 보인다. `species=OTHER`는 사용자 게시물을 위해 값으로는 유지한다. 결정 전 들어온 공공 `OTHER` 177건은 `status=DELETED`로 소프트 삭제했다(되돌릴 수 있다).

공공 보호동물의 `EVENT`는 **`orgNm`(관할 시군구)** 을 먼저 기준 데이터로 풀고, 시도명만 있으면(제주특별자치도) `happenPlace` → `careAddr` 순으로 내려간다. `CURRENT`는 `careAddr`를 먼저 풀고 안 되면 `orgNm`으로 내려간다. 보호소 주소는 개편 전 이름("강원도 화천군", "광주광역시 북구")을 그대로 쓰는 경우가 많아 `infra/reference/region-aliases.csv`(폐지 시군구 → 현재 표시명, `build_region_codes.py --aliases-out`이 시도 개편표로만 생성)를 함께 읽는다 — 저장되는 값은 항상 현재 코드·표시명이다. `happenPlace`를 첫 기준으로 쓰지 않는 이유: 실제 값이 "봉정삼거리", "○○아파트 앞" 같은 자유 지명이라 시군구가 거의 없다(2026-09-14 실측 1~10% 해석, `orgNm`은 95~98%). 세 후보를 모두 못 풀 때만 실패로 기록하고 임의 지역으로 적재하지 않는다. 전체 보호소 주소는 `shelter.address`에 유지하며, 공공 위치 두 행은 암호문·동의 증빙 없이 `exact_location_visible=false`다.

응답에서는 요약과 상세의 경계를 분리한다. 비로그인 목록과 로그인 목록·검색·후보 요약은 `EVENT.public_location`만 반환하고 `CURRENT`와 정확한 위치를 포함하지 않는다. 상세(비로그인 포함)는 `eventLocation`을 반환하고 `SHELTERING`이면 `currentLocation`도 반환한다. 다른 로그인 회원에게는 게시물이 `ACTIVE`이고 해당 역할 행의 `exact_location_visible=true`이며 저장 동의 버전이 현재 버전일 때만 정확한 위치를 포함한다. 비로그인 상세에는 정확한 위치를 포함하지 않는다. 작성자 관리용 상세는 비공개·`CLOSED` 상태를 포함한 자신의 저장 위치와 공개 상태를 반환할 수 있다. 공공 게시물에는 정확한 위치를 반환하지 않고 좌표는 작성자에게도 제외한다. 전체 보호소 주소는 공공 보호동물 상세의 `shelter.address`에서만 반환한다.

### 3.3 `user_post`

사용자가 등록한 `animal_case`의 작성자, 등록 재시도 식별자와 종료 사유를 저장한다. 사용자 `LOST`와 사용자 `SHELTERING` 모두 이 테이블을 사용한다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 작성 회원, `client_request_id`, `request_hash`, 종료 사유 |
| 생성 시점 | 사용자 게시물 등록 시 |
| 주요 작성자 | Spring Boot 게시물 API |
| 주요 관계 | `animal_case`와 1:1, `member`와 N:1 |

이 테이블로 다음 질문에 답할 수 있다.

- 이 게시물을 누가 작성했는가?
- 현재 요청한 회원이 수정·종료 권한을 가지고 있는가?
- 같은 클라이언트 등록 요청을 이미 처리했는가?
- 게시물을 왜 종료했는가?

`(member_id, client_request_id)`는 유일하다. 같은 ID·같은 canonical `request_hash`의 P3 재요청은 기존 게시물을 반환하고 다른 hash면 충돌로 거부한다. 정확한 위치 공개 여부·버전·시각은 각 `animal_case_location` 행에서 관리한다. 날짜와 공개 범위의 상세 계약은 [게시물 날짜·정확한 위치 공개 정책](post-date-location-policy.md)을 따른다.

### 3.4 `animal_photo`

동물 게시 건에 연결된 사진을 저장한다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 저장 방식·URI, MIME, 바이트 수, 가로·세로, 표시 순서, 체크섬 |
| 생성 시점 | 사용자 게시물 등록·사진 교체 또는 공공데이터 수집 시 |
| 주요 작성자 | 사용자 건은 Spring Boot, 공공 건은 DATA 수집 파이프라인 |
| 주요 관계 | 여러 사진이 하나의 `animal_case`를 참조 |

`storage_type`으로 사용자 업로드(`USER_UPLOAD`)와 공공 이미지 URL(`PUBLIC_URL`)을 구분한다.
사용자 `storage_uri`는 `/data/user/images/{postId}/{photoId}.jpg` HDFS 절대 경로이며 API에
노출하지 않는다. 공공 이미지 바이너리는 별도 HDFS TAR로 보존하고 `PUBLIC_URL`은 화면 표시용
출처 URL로 사용한다.

`sort_order`는 0부터 9까지 사용하며 0번 사진이 대표 사진이다. `(animal_case_id, sort_order)`가 유일하므로 같은 게시물 안에서 순서가 중복되지 않는다. 사진을 별도 테이블로 둬야 게시 건 하나에 1~10장을 순서대로 저장할 수 있다.

사용자 사진은 서버가 정규화한 JPEG이므로 `content_type=image/jpeg`, 양수 `byte_size`,
`width_px`·`height_px`와 `checksum_sha256`이 모두 필수다. checksum은 저장 파일의 무결성과
P8 응답 `ETag`에 사용한다. 입력 검증과 HDFS 수명 주기는
[사진 업로드·저장소 정책](photo-upload-policy.md)을 따른다.

### 3.5 `member_photo_erasure_task`

회원 탈퇴 후 관계형 데이터와 HDFS를 서로 독립적으로 안전하게 파기하기 위한 outbox다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 검증된 사용자 HDFS 경로, 탈퇴 후 30일 목표 시각, 시도 횟수, 재시도·lease 시각, 안전한 오류 코드 |
| 생성 시점 | 회원 관계형 파기 트랜잭션에서 `animal_photo` 삭제 직전 |
| 주요 작성자 | Spring Boot 회원 데이터 파기 작업 |
| 주요 관계 | 개인정보 최소화를 위해 회원·게시물 FK를 두지 않고 HDFS 경로만 성공할 때까지 유지 |

작업자는 실행당 100건을 `FOR UPDATE SKIP LOCKED`로 선점한다. HDFS 삭제와 이미 없는 파일은
성공으로 처리해 행을 제거하고, 실패는 `PHOTO-006`만 기록해 최대 24시간 백오프로 재시도한다.
경로 패턴은 `/data/user/images/{postId}/{photoId}.jpg`로 제한해 공공 이미지와 staging 파일을
삭제 대상으로 넣지 않는다.

## 4. 내부 채팅 테이블

MVP 채팅의 메시지와 참여자별 읽음 위치는 기존 방·메시지 테이블에 저장한다. 개인 FCM 등록은
기존 `auth_session`의 `push_*` 컬럼에 저장하고, 개인 채팅 알림을 위해서는 Outbox 테이블만
추가한다. WebSocket·SSE, 첨부파일과 그룹 채팅은 제공하지
않는다. 일일 입소 요약 토픽은 수집 실행 이력을 원본으로 하며 개인 채팅 알림과 분리된다.

### 4.1 `chat_room`

사용자 게시물 작성자와 대화를 요청한 회원을 연결하는 1:1 대화방이다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 대상 게시물, 작성 회원, 요청 회원, 참여자별 마지막 읽음 메시지 ID, 마지막 메시지 시각 |
| 생성 시점 | 다른 회원의 활성 사용자 게시물에서 채팅을 처음 시작할 때 |
| 주요 작성자 | Spring Boot 채팅 API |
| 주요 관계 | `animal_case`·두 `member`와 N:1, `chat_message`와 1:N |

`(animal_case_id, requester_member_id)`를 유일하게 해 같은 사용자가 같은 게시물에 여러 방을 만드는 것을 막는다. `owner_member_id`와 `requester_member_id`는 서로 달라야 한다. 공공 보호동물, 본인 게시물, 종료·삭제 게시물에는 새 방을 만들지 않는다.

별도 방 상태 컬럼은 없다. 연결된 `animal_case.status`가 `ACTIVE`이면 메시지를 보낼 수 있고,
`CLOSED` 또는 `DELETED`이면 기존 대화 조회와 읽음 위치 갱신만 할 수 있다. 두 읽음 ID는 같은
방의 화면 표시 메시지만 가리키며 명시적인 C5 요청으로 앞으로만 이동한다.

### 4.2 `chat_message`

대화방에서 주고받은 텍스트 한 건을 저장한다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 대화방, 발신 회원, `client_message_id`, request hash, 1~1000자 내용, 생성 시각 |
| 생성 시점 | 대화 참여자가 메시지를 보낼 때 |
| 주요 작성자 | Spring Boot 채팅 API |
| 주요 관계 | `chat_room`, 발신 `member`와 N:1 |

발신자가 방의 작성자 또는 요청자인지는 서비스가 검사한다. `(sender_member_id, client_message_id)`는
유일하며 같은 hash 재요청에는 기존 메시지를 반환한다. 과거 메시지는
`(chat_room_id, created_at, id)` 커서, 새 메시지는 `(chat_room_id, id)`와 `afterMessageId`로
조회한다. Android는 대화 화면이 열려 있을 때 약 3초마다 증분 요청한다.

### 4.3 `auth_session`의 개인 푸시 등록

N1은 현재 `auth_session`에 설치 UUID, 플랫폼, FCM 토큰 암호문·조회 HMAC, 마지막 확인 시각을
기록한다. 같은 설치 UUID나 토큰이 다른 세션에 있으면 기존 세션의 `push_*` 등록을 비우고 현재
세션으로 재귀속한다. worker는 활성 회원의 미폐기·미만료 세션만 사용하며 N2와 영구 FCM 오류는
해당 세션의 등록을 비운다. 토큰 보호값은 응답·로그·metric label·trace에 남기지 않는다.

### 4.4 `chat_notification_outbox`

메시지 저장과 FCM 호출을 분리하되 알림 이벤트를 잃지 않기 위한 내구성 Outbox다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 메시지, 수신 회원, 상태, 시도 횟수, 재시도·lease·완료 시각, 비민감 오류 코드 |
| 생성 시점 | 신규 `chat_message`와 같은 DB 트랜잭션 |
| 주요 작성자 | Spring Boot 채팅 API와 Outbox worker |
| 주요 관계 | `chat_message`와 1:0..1, 수신 `member`와 N:1 |

메시지 ID 유일 제약으로 멱등 재요청의 중복 이벤트를 막는다. worker는 lease로 선점해 본문 없는
data 알림을 보내며, 일시 실패는 최대 10회·24시간 재시도한다. 기기가 없거나 재시도가 소진되면
`SKIPPED`, 한 기기 이상 성공하고 일시 실패가 남지 않으면 `SENT`다. 완료 행은 30일 뒤 정리한다.

## 5. 공공데이터 테이블

### 5.1 `shelter`

공공 API가 제공하는 보호소 정보를 저장한다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 보호소 등록번호, 이름, 전화, 주소, 관할기관 |
| 생성·갱신 시점 | 공공 보호동물 수집 중 보호소 정보가 확인될 때 |
| 주요 작성자 | DATA 수집 파이프라인 |
| 주요 관계 | 하나의 보호소가 여러 `shelter_animal`을 보호 |

`care_reg_no`는 공공 API의 보호소 식별자이며 upsert 기준이 된다. 같은 보호소의 이름과 주소를 동물마다 반복 저장하지 않도록 별도 테이블로 정규화했다.

보호소 이름과 전화번호는 회원 개인정보가 아니라 공공 기관 정보다. 후보·상세에 표시할 수 있으며, 일반 비로그인 목록에는 출처 배지만 표시한다. 보호소 주소는 목록과 후보에서 제외하고 상세에서 제공한다.

### 5.2 `shelter_animal`

공공 API에서 수집한 구조동물만 가지고 있는 상세 정보를 저장한다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 구조번호, 보호소, 공고 기간, 원본 상태, 나이·체중·중성화 정보 |
| 생성·갱신 시점 | 공공 보호동물 수집 시 |
| 주요 작성자 | DATA 수집 파이프라인 |
| 주요 관계 | `animal_case`와 1:1, `shelter`·`ingestion_run`과 N:1 |

`desertion_no`는 공공 API 구조번호이며 중복 수집을 방지하는 유일한 upsert 키다. 동일한 구조번호가 다시 수집되면 새 `animal_case`를 생성하지 않고 기존 데이터를 갱신한다.

`process_state_raw`, `end_reason_raw`, `age_text`, `weight_text`는 제공기관의 원문 표현을 보존한다. 화면과 검색에 필요한 공통 값은 `animal_case`의 정규화 컬럼으로 저장하고, 공공데이터 특화 원문은 이 테이블에 둔다.

`ingestion_run_id`는 이 동물의 마지막 동기화 실행을 가리킨다. 모든 변경 이력을 담는 관계는 아니며, 수집 실행 이력이 보존 기간 후 삭제되면 `NULL`이 될 수 있다.

### 5.3 `ingestion_run`

한 번의 공공데이터 수집 작업이 언제 시작되고 어떻게 끝났는지 기록한다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 요청 날짜 범위, 수신·삽입·갱신·실패 건수, 실행 상태와 오류 요약 |
| 생성 시점 | 공공데이터 수집 시작 시 |
| 주요 작성자 | DATA 수집 파이프라인 |
| 주요 관계 | 여러 `shelter_animal`이 마지막 수집 실행을 선택적으로 참조 |

실행 유형은 `INITIAL_FULL`, `DAILY_INCREMENTAL`, `BACKFILL`이고 상태는 `RUNNING`, `SUCCEEDED`, `FAILED`다. 수집이 실패해도 기존 보호동물 데이터와 마지막 성공 실행을 삭제하지 않는다. 성공한 `DAILY_INCREMENTAL`의 `inserted_count`와 `shelter_count`만 일일 요약 원본이다.

- 현재 수집이 진행 중인지
- 최근 실행이 실패했는지
- 마지막으로 정상 갱신된 시각이 언제인지
- 몇 건을 받아 새로 저장하거나 갱신했는지

`error_summary`에는 운영자가 이해할 수 있는 비민감 오류 코드와 요약만 저장하고 토큰, 요청 원문과 스택 트레이스는 저장하지 않는다.

`summary_published_at`은 해당 실행의 요약을 FCM 토픽에 정상 발송한 시각이다. 알림 API는 이 값이 없는 성공한 일일 증분 실행을 행 잠금으로 선점해 발송한다. 외부 FCM 호출과 DB 갱신 사이 장애 뒤에는 재시도로 중복될 수 있으므로 Android가 실행 ID 기반 알림 태그와 DataStore의 마지막 확인 실행 ID로 같은 요약을 1회 표시한다. 기기별 FCM 전송·읽음 이력은 저장하지 않는다.

### 5.4 `lost_report`

공공 분실 신고 출처만 가진 상세 식별자와 관측 기간을 저장한다. `PUBLIC/LOST`의
`animal_case`와 1:1이며 신고자 연락처·상세 주소는 저장하지 않는다. 이 데이터는 게시물 목록에
표시할 수 있지만 실종 동물 매칭의 기준이나 후보로 사용하지 않는다.

### 5.5 `dashboard_stat`

요청 때마다 계산하기 무거운 홈 인사이트 통계를 DATA 배치가 `stat_key`, `region_code`별 JSON으로
저장한다. Spring Boot는 읽기만 하며 payload가 없거나 형식이 맞지 않으면 해당 카드만 비운다.

### 5.6 `adoption_favorite`

입양 탐색에서 회원이 하트를 누른 공공 보호동물 찜만 저장한다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 회원 ID, 공공 보호동물 게시 건 ID, 최초 찜 시각 |
| 생성 시점 | 로그인 회원이 현재 입양 후보 카드에 하트를 누를 때 |
| 주요 작성자 | Spring Boot 입양 탐색 API |
| 주요 관계 | `member`, `animal_case`와 각각 N:1 |

복합 PK `(member_id, animal_case_id)`로 중복을 막는다. 원천 상태가 바뀌어 후보 자격을 잃어도
행은 유지하고 찜 목록에서 `UNAVAILABLE`로 계산해 사용자가 해제할 수 있게 한다. 가정 환경·성격
프로필, 추천 점수와 이용 가능 여부는 저장하지 않는다. 넘긴 기록은 `adoption_swipe`가 따로 맡는다.
회원 관계형 데이터나 참조 공공 게시 건을 최종 삭제할 때 FK `CASCADE`로 제거한다.

### 5.7 `adoption_swipe`

입양 탐색에서 회원이 넘긴 공공 보호동물을 저장한다. 같은 동물을 두 번 묻지 않기 위한 기록이다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 회원 ID, 공공 보호동물 게시 건 ID, 최초로 넘긴 시각 |
| 생성 시점 | 로그인 회원이 카드를 왼쪽이나 오른쪽으로 넘길 때 |
| 주요 작성자 | Spring Boot 입양 탐색 API |
| 주요 관계 | `member`, `animal_case`와 각각 N:1 |

복합 PK `(member_id, animal_case_id)`로 중복을 막는다. **어느 쪽으로 넘겼는지는 저장하지 않는다** —
찜 여부는 `adoption_favorite` 하나가 정답이고, 두 곳에 두면 찜을 해제했을 때 어긋난다. 그래서
"하트로 넘긴 목록"이 필요하면 `adoption_favorite`를 보고, "이미 본 목록"이 필요하면 이 표를 본다.

`adoption_favorite`와 달리 **추가할 때 후보 자격을 검사하지 않는다.** 카드를 보고 넘기는 사이에
원천 상태가 바뀔 수 있는데, 그때 기록이 거절되면 그 동물이 다음 조회에 다시 올라오기 때문이다.
회원 관계형 데이터나 참조 공공 게시 건을 최종 삭제할 때 FK `CASCADE`로 제거한다.

## 6. AI 매칭 테이블

### 6.1 `match_run`

`잃어버렸어요` 게시 건을 기준으로 실행한 한 번의 매칭 작업을 나타낸다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 기준 게시 건·버전, 실행 상태, 모델 정보, 후보 수, 오류 코드 |
| 생성 시점 | 본인 활성 LOST 상세에서 사용자가 분석 버튼을 누를 때 |
| 주요 작성자 | Spring Boot가 온디맨드 실행을 생성하고 MapReduce가 처리 상태·결과 갱신 |
| 주요 관계 | 기준 `animal_case`와 N:1, `match_candidate`와 1:N |

MVP에서 `query_case_id`는 사용자 `LOST` 건만 참조한다. 사용자 또는 공공 `SHELTERING` 건을 기준으로 역방향 매칭 실행을 만들지 않는다.

상태 의미는 다음과 같다.

| 상태 | 의미 |
|---|---|
| `PENDING` | 실행 요청이 만들어졌지만 아직 처리 전 |
| `RUNNING` | DATA·AI 매칭 경로가 처리 중 |
| `SUCCEEDED` | 정상 종료. `candidate_count`는 임계값 통과 후 저장된 후보 수인 0~20 |
| `FAILED` | 처리 실패. 안전한 `error_code`를 함께 기록 |

후보가 없는 정상 결과와 시스템 실패를 구분하기 위해 실행 상태를 별도 테이블로 둔다. 실행 이력을 덮어쓰지 않으므로 새 실행이 실패해도 이전 `SUCCEEDED` 결과를 계속 보여줄 수 있다.

`query_case_version`은 요청 당시 게시물 버전이다. 이후 매칭 입력이 수정되면 실행 이력은 보존하고 API가 결과를 `STALE`로 표시하며 자동 재실행하지 않는다. `model_id`, `model_version`은 어떤 모델과 전처리 규격으로 결과를 만들었는지 추적하는 값이다.

### 6.2 `match_candidate`

하나의 매칭 실행에서 계산된 보호 중 동물 후보와 순위를 저장한다.

| 구분 | 설명 |
|---|---|
| 주요 데이터 | 실행 ID, 후보 게시 건, 순위, 최종·이미지 점수, 거리·날짜 차이 |
| 생성 시점 | DATA·AI 매칭 계산 완료 시 |
| 주요 작성자 | DATA·AI 매칭 경로 |
| 주요 관계 | `match_run`과 N:1, 후보 `animal_case`와 N:1 |

`target_case_id`는 `SHELTERING`이면서 매칭 가능한 사용자 보호 게시물 또는 공공 보호동물을 참조한다. USER_POST 후보는 `ACTIVE`이면서 `is_matchable=true`인 경우에만 노출한다. SHELTER 후보는 과거 입소분 소급 검색을 위해 `CLOSED`여도 `is_matchable=true`이면 상태와 함께 노출할 수 있다. `DELETED` 또는 `is_matchable=false`인 건은 출처와 관계없이 제외한다. Spring Boot는 이 테이블을 조회해 후보를 반환할 뿐 점수를 직접 계산하거나 수정하지 않는다.

모델 버전별 임계값을 통과한 후보를 `total_score DESC, target_case_id ASC`로 정렬한 뒤 상위 20건만 저장한다. `rank`는 실행 안에서 1부터 20 사이이며 중복될 수 없다.

`total_score`와 `image_score`는 0부터 1 사이 값이지만 동일 개체일 확률을 뜻하지 않는다. `distance_km`와 `time_gap_days`를 포함한 이 값들은 내부 정렬·필터링·평가용으로만 사용한다. 공개 API와 Android 화면에는 원시 점수·백분율·정확한 거리 값을 제공하지 않고 `rank`와 후보의 날짜·공개 지역·출처·현재 상태를 제공한다.

동일한 후보는 한 실행에 한 번만 들어갈 수 있지만, 다른 실행에서는 다시 후보가 될 수 있다.

## 7. 출처별 저장 조합

같은 `animal_case`에 `user_post`와 `shelter_animal`을 동시에 만들지 않는다.

| 데이터 종류 | `animal_case` | 출처 상세 | 위치 역할 행 | 사진 | 매칭 실행 |
|---|---|---|---|---|---|
| 사용자 `잃어버렸어요` | `USER / LOST` | `user_post` | `EVENT` 필수, `CURRENT` 금지 | 1~10장 | 상세 버튼 요청 시 생성 |
| 사용자 `보호하고 있어요` | `USER / SHELTERING` | `user_post` | `EVENT`, `CURRENT` 필수 | 1~10장 | 역방향 실행 없음 |
| 공공 보호동물 | `PUBLIC / SHELTERING` | `shelter_animal` | `EVENT`, `CURRENT` 필수 | 정상 URL만 저장 | 역방향 실행 없음 |
| 공공 분실 신고 (2026-09-15) | `PUBLIC / LOST`, `is_matchable=false` | `lost_report` | `EVENT` 필수, `CURRENT` 금지 | 1장(URL) | 기준도 후보도 아님 |

API 응답에서는 내부 `source_type=USER`를 `USER_POST`, `source_type=PUBLIC`을 `SHELTER`(SHELTERING) 또는 `PUBLIC_LOST`(LOST) 출처 라벨로 변환한다. 이 조합별 위치 행 수와 공공 위치 정책은 Spring Boot 게시물 서비스 또는 DATA 수집 서비스의 트랜잭션과 통합 테스트에서 강제한다.

## 8. 기능별 데이터 흐름

### 8.1 회원가입과 로그인

```text
전화번호 인증
├── 운영 SOLAPI·로컬/테스트 Fake 공급자로 6자리 OTP 발송
└── 공유 Redis 인증 캐시에서 3분 OTP·10분 일회성 가입 증명과 발송·실패 제한을 원자적으로 관리

회원가입
└── 현재 개인정보 고지 버전 동의와 불투명 인증 증명 검증 후 member 생성(전화번호 AES-GCM 암호문·조회 HMAC·인증 시각·동의 버전·시각 포함)

로그인
member 인증
└── auth_session 생성 → ID를 sid로 갖는 15분 RS256 JWT 발급
    └── refresh selector·secret 해시 저장

토큰 갱신
└── selector로 세션 잠금 → secret 해시 검증 → 새 secret으로 원자 회전

로그아웃
auth_session.revoked_at 기록

회원 탈퇴
└── 현재 비밀번호 재인증 → WITHDRAWN + 전체 세션 폐기 + 소유 게시물 비공개
    └── 30일 뒤 개인정보 파기 커밋 후 같은 번호 재가입 가능
```

MVP 회원가입은 현재 개인정보 고지 버전 동의가 `true`일 때만 인증 완료된 전화번호를 `member`에 보호된 형태로 저장한다. OTP는 전용 HMAC, 가입 증명은 `pv1.{selector}.{secret}`의 selector·secret hash·전화번호 HMAC만 공유 Redis에 두고 PostgreSQL에는 영구 인증 이력 테이블을 만들지 않는다. 가입 증명은 다른 입력 검증 후 요청 ID로 원자 선점하고 회원 생성 커밋 후 소비한다. 탈퇴 30일 뒤 계정 보호값 파기 커밋으로 번호 재사용을 허용하고 사용자 게시물·위치·매칭·채팅과 HDFS 사진은 별도 정리 단계가 완료될 때까지 재시도한다. 게시물 위치별 정확한 위치 공개 여부와 동의 증빙은 각 `animal_case_location` 행에 기록한다.

### 8.2 사용자 `잃어버렸어요` 등록

```text
animal_case(USER, LOST)
├── animal_case_location(EVENT) 1건
├── user_post
└── animal_photo 1~10건
```

게시 건, `EVENT` 위치 한 행, 사용자 상세와 사진 메타데이터는 한 트랜잭션에서 저장하고 `CURRENT` 행은 생성하지 않는다. `user_post`의 회원·`client_request_id` 유일성으로 P3 재시도를 멱등 처리한다. 등록은 후보 분석을 시작하지 않으며, Android는 게시물 상세에서 `NOT_REQUESTED` 상태를 표시한다.

분석 버튼을 누르면 `match_run(PENDING)`을 만들고 DATA/AI worker가 이 행을 선점해 준비된 검색 자산과 MapReduce가 소유한 규칙으로 최종 Top-K를 적재한다. 처리 중 실행이 있으면 같은 실행을 202로 반환하며, 5분 초과 RUNNING은 실패로 회수한다.

### 8.3 사용자 `보호하고 있어요` 등록

```text
animal_case(USER, SHELTERING)
├── animal_case_location(EVENT) 1건
├── animal_case_location(CURRENT) 1건
├── user_post
└── animal_photo 1~10건
```

발견 장소인 `EVENT`와 현재 보호 장소인 `CURRENT`를 같은 트랜잭션에서 각각 저장한다. 등록 즉시 보호 중 목록과 `잃어버렸어요`의 후보군에 포함한다. 이 게시물을 기준으로 하는 역방향 `match_run`은 MVP에서 생성하지 않는다.
따라서 등록 응답에도 매칭 실행을 포함하지 않으며 Android는 후보 화면으로 이동하지 않는다.

### 8.4 공공 보호동물 수집

```text
ingestion_run(RUNNING)
├── shelter upsert              care_reg_no 기준
├── animal_case upsert
│   ├── animal_case_location(EVENT)   happenPlace 공개 발견 장소
│   ├── animal_case_location(CURRENT) careAddr에서 추출한 공개 지역
│   ├── shelter_animal upsert   desertion_no 기준
│   └── animal_photo
└── SUCCEEDED 또는 FAILED
```

반복 수집은 `care_reg_no`, `desertion_no`를 기준으로 멱등하게 처리한다. 공공 위치 두 행은 정확한 위치 암호문 없이 비공개로 저장하고, 전체 `careAddr`는 `shelter.address`에 유지한다. 실패한 실행 때문에 기존 정상 데이터를 삭제하지 않는다.

### 8.5 게시물 수정과 종료

수정 요청은 `user_post.member_id`로 소유권을 확인하고 `animal_case.version`으로 동시 수정 충돌을 검사한다. 수정 후에도 `LOST`는 `EVENT`만, `SHELTERING`은 `EVENT`·`CURRENT`를 각각 한 행 유지한다. LOST의 사진·날짜·`EVENT` 위치·동물 특징처럼 매칭 입력이 바뀌면 버전만 올리고 기존 결과는 `STALE`로 표시한다. 새 `match_run`은 사용자가 다시 버튼을 눌렀을 때만 만든다.

종료할 때는 다음 값을 한 트랜잭션에서 변경한다.

- `animal_case.status=CLOSED`
- `animal_case.is_matchable=false`
- `animal_case.closed_at`
- `user_post.close_reason`

종료된 사용자 게시물은 공개 목록과 새로운 매칭 후보군에서 즉시 제외한다.

### 8.6 사용자 게시물 채팅

```text
활성 사용자 게시물 상세
└── chat_room 조회 또는 생성
    └── chat_message 0~N건
```

방 생성은 동일 게시물·동일 요청자에게 항상 같은 방을 반환한다. 메시지 조회·전송은 작성자와 요청자만 가능하다. 게시물이 종료되면 방 상태를 따로 바꾸지 않고 게시물 상태를 확인해 새 메시지만 차단한다.

### 8.7 입양 탐색과 찜·넘김

```text
공공 보호동물 현재 값
└── 공고 종료 후 보호중 + ACTIVE + 대표 사진 + 선택 EVENT.region_code
    └── 회원이 이미 넘긴 건 제외 (adoption_swipe)
        └── 오래된 공고 종료일 순 카드 조회
            ├── 어느 쪽으로든 넘김 → adoption_swipe
            └── 하트 선택 → adoption_favorite (넘김 행도 함께 생김)
```

로그인한 회원만 조회한다. 넘기면 방향과 무관하게 `adoption_swipe` 행이 하나 생기고, 그 뒤로 그
동물은 후보 목록에 오지 않는다. 찜 추가 때만 현재 후보 자격을 검사하고, 넘김 기록은 검사하지 않는다.

찜을 해제해도 넘김 행은 남으므로 그 동물이 카드로 돌아오지 않는다. 찜 목록과 넘김 목록은 원천
상태를 다시 확인해 `AVAILABLE` 또는 `UNAVAILABLE`을 계산한다. 보호소 공식 연락처는 카드가 아니라
공공 보호동물 상세에서 확인한다.

## 9. 자주 묻는 질문

### 테이블 수가 많은 이유는 무엇인가?

핵심 동물 데이터는 `animal_case` 하나로 통합되어 있다. 나머지는 사용자·공공 출처별 상세, 여러 사진, 최소 채팅, 매칭 실행 이력, 인증 및 수집 상태처럼 서로 생명주기와 쓰기 주체가 다른 데이터를 분리한 것이다. 모든 컬럼을 한 테이블에 넣으면 출처에 따라 사용하지 않는 `NULL` 컬럼이 많아지고 권한과 개인정보 경계가 흐려진다.

### 회원 전화번호는 어떻게 보호하는가?

회원가입에서 전화번호를 인증하고 `member.phone_ciphertext`, `phone_lookup_hash`, `phone_verified_at`을 저장한다. 사용자 게시물 작성자는 계속 닉네임으로만 표시하며 내부 1:1 채팅으로 소통한다. 전화번호는 다른 회원·일반 DTO·로그에 넣지 않는다. MVP에는 운영자 전화번호 조회 화면·API가 없으며, 장애 대응이나 적법한 요청의 예외 접근은 서비스 책임자와 개인정보·보안 책임자의 사전 승인 및 인프라 접근 로그를 요구한다. `shelter.phone`은 회원 정보가 아니라 공공 보호소의 공식 연락처이므로 유지한다.

비로그인 목록은 사용자 게시물과 공공 보호동물을 출처 배지로만 구분한다. 작성자 닉네임·보호소 이름·공식 전화번호는 상세에서, 후보 확인에 필요한 출처별 정보는 로그인 후보 요약에서 제공한다. 보호소 주소, 정확한 주소·건물명·좌표는 목록에 포함하지 않는다.

### 채팅 테이블이 두 개뿐인 이유는 무엇인가?

MVP는 1:1 텍스트 대화만 제공한다. 참여자는 `chat_room`의 작성자·요청자로 고정하고 메시지는 `chat_message`에 저장한다. 읽음 상태, 첨부파일, 알림, 차단·신고는 현재 요구사항이 아니므로 테이블을 추가하지 않는다.

### 사용자 보호동물과 공공 보호동물은 같은 DB에 있는가?

같은 PostgreSQL과 같은 `animal_case` 목록에 저장한다. 다만 출처별 필드는 사용자 건은 `user_post`, 공공 건은 `shelter_animal`에 저장한다. 따라서 하나의 목록 쿼리와 매칭 후보군을 공유하면서도 출처 고유 정보를 분리할 수 있다.

### 같은 동물로 보이는 게시 건은 합치는가?

합치지 않는다. 각 게시 건은 독립된 `animal_case`로 유지하고 유사성은 `match_candidate`로 표현한다. 사용자의 최종 확인 없이 실제 동일 개체라고 단정하지 않는다.

### `status`와 `is_matchable`은 왜 분리했는가?

`status`는 공개 목록에 현재 표시할지를 결정하고 `is_matchable`은 매칭 후보로 사용할지를 결정한다. 과거 공공 구조동물은 현재 목록에서는 닫혀 있어도 과거 실종 사건을 찾는 데 필요할 수 있어 두 상태를 분리한다.

### `match_run` 없이 후보만 저장하면 안 되는가?

실행 정보가 없으면 후보 0건이 정상 결과인지 처리 실패인지 구분할 수 없다. 모델 버전, 실행 상태와 실패 원인을 추적하고 이전 성공 결과를 보존하려면 실행과 후보를 분리해야 한다.

### ERDCloud SQL에 모든 인덱스와 CHECK가 없는 이유는 무엇인가?

[erdcloud-import.sql](erdcloud-import.sql)은 팀 공유용 다이어그램 생성을 위한 가져오기 SQL이다. ERDCloud가 관계를 안정적으로 인식하는 데 필요한 PK, 일반 UK와 FK를 중심으로 작성했다. 대소문자 무시 로그인 ID 인덱스처럼 표현식·부분 인덱스와 상세 CHECK 제약조건은 [erd.md](erd.md)를 기준으로 실제 마이그레이션에 구현한다.

## 10. 구현 전 확인사항

- `animal_case`와 위치, 출처 상세, 사진을 필요한 범위의 한 트랜잭션으로 저장한다.
- `USER` 건에는 `user_post`만, `PUBLIC/SHELTERING`에는 `shelter_animal`, `PUBLIC/LOST`에는
  `lost_report`만 존재하게 서비스에서 검사한다.
- 사용자 `LOST`는 `EVENT`만, 사용자·공공 `SHELTERING`은 `EVENT`·`CURRENT`를 각각 한 행 갖게 서비스에서 검사한다.
- 사용자 P3·P4의 `event_date`가 `Asia/Seoul` 기준 미래가 아닌지 같은 서버 검증기로 검사한다.
- 공공 위치는 암호문 없이 `exact_location_visible=false`이고, 전체 보호소 주소는 `shelter.address`에만 저장되게 검사한다.
- 활성 게시 건의 사진이 1장 이상 10장 이하인지 검사한다.
- 입양 후보 추가 시 `PUBLIC/SHELTERING/ACTIVE`, 공고 종료 후 원천 `보호중`, 대표 사진을 검사하고
  찜 조회 시 이용 가능 여부를 다시 계산한다.
- 넘김 기록은 `PUBLIC/SHELTERING` 여부만 검사한다. 찜과 판정 기준이 다른 점을 테스트로 고정한다.
- 입양 후보 조회가 요청 회원의 `adoption_swipe` 행이 있는 건을 빼는지, 다른 회원에게는 그대로
  보이는지 검사한다.
- 회원 탈퇴 뒤 찜·넘김 접근을 즉시 차단하고 관계형 파기에서 `adoption_favorite`와 `adoption_swipe`가
  제거되는지 검사한다.
- 회원가입은 현재 개인정보 고지 버전 동의와 유효한 불투명 전화번호 인증 증명을 요구하고, 전화번호 AES-GCM 암호문·조회 HMAC·인증 시각·동의 버전·시각을 저장한다.
- 병렬 회원가입, 가입 증명 재사용·선점 중 장애, 동시 OTP 실패 횟수 증가, TTL 경계에서 번호당 활성 계정이 하나만 생성되는지 검사한다.
- 탈퇴 30일 회원 개인정보 파기가 사진 저장소 장애와 무관하게 수행되고, DB 파기 실패로 조회 해시가 남으면 재가입을 거부하며 보호값 삭제 커밋 후에는 재가입을 허용하는지 검사한다.
- 파기 job의 중복 실행·100건 경계, 사용자 생성 데이터·HDFS 삭제 실패 재시도와 30일 목표 초과 경보를 검사한다.
- 모든 일반 DTO와 로그에서 회원 전화번호·조회 해시를 제외한다.
- 비로그인 목록 DTO는 `USER_POST` 또는 `SHELTER` 출처 배지만 포함하고 작성자 닉네임·보호소 이름·공식 전화번호를 제외한다.
- 비로그인 목록 DTO에 보호소 주소, 정확한 주소·건물명·좌표를 포함하지 않는다.
- 목록·지역 검색은 `location_type='EVENT'`를 고정하고 `EVENT.region_code`로 필터링하며 `EVENT.public_location`은 표시만 한다.
- 행정구역 CSV의 버전·checksum이 맞지 않으면 위치 쓰기 readiness가 실패하고, 폐지 코드는 신규 쓰기에서 거부하되 기존 표시값은 유지되는지 검사한다.
- 로그인 후보 요약도 `EVENT.public_location`만 반환하고 `CURRENT`, 정확한 위치, 좌표와 보호소 주소를 포함하지 않는다.
- 상세는 역할별 공개 위치 객체를 반환한다. 다른 로그인 회원에게는 `ACTIVE` 사용자 게시물에서 해당 역할 행의 공개가 true이고 저장 동의 버전이 현재 버전일 때만 정확한 위치를 포함하고, 비로그인 상세에는 포함하지 않는다. 작성자 관리용 상세는 비공개·`CLOSED` 상태를 포함한 자신의 저장 위치와 공개 상태를 반환한다. 공공 게시물에는 정확한 위치를 포함하지 않고 좌표는 작성자에게도 제외한다. 보호소 전체 주소는 공공 보호동물 상세에서만 반환한다.
- 채팅방은 활성 사용자 게시물에만 생성하고 작성자·요청자만 메시지를 조회·전송하게 한다.
- 종료·삭제 게시물의 기존 채팅은 읽기 전용으로 처리한다.
- 게시물 등록과 메시지 전송은 각각 클라이언트 UUID·request hash로 재시도를 멱등 처리한다.
- 공공데이터는 `desertion_no` 기준으로 중복 생성 없이 upsert한다.
- 매칭 후보는 `SHELTERING`이면서 `is_matchable=true`인 건만 참조한다. USER_POST는 `ACTIVE`만, SHELTER는 소급 검색 대상으로 유지된 `CLOSED`도 상태와 함께 반환한다.
- API 서버는 `match_candidate` 점수를 생성하지 않고 조회만 한다.
- `match_run`은 등록·수정·수집이 아니라 본인 활성 LOST 상세의 버튼 요청에서만 만들고, 처리 중 재요청은 같은 실행을 반환하며 기준 게시물 수정 뒤에는 결과를 `STALE`로 표시한다.
- 성공한 `DAILY_INCREMENTAL` 실행의 집계값만 일일 요약으로 사용하고, FCM 토픽 발송 시각을 `summary_published_at`에 한 번 기록한다. 앱 내 확인 여부는 Android DataStore에만 저장한다.
- 로그아웃·만료·탈퇴한 `auth_session`의 refresh token 재사용을 거부한다.
- 회전된 refresh token 재사용 시 selector로 같은 세션을 찾아 폐기하고, 동일 token 동시
  갱신은 한 건만 성공하며 최초 로그인 + 30일 만료 시각을 연장하지 않는다.
- 로그아웃·탈퇴 직후 기존 JWT도 `sid` 세션 검사에서 거부한다.
- 표현식 인덱스, 부분 인덱스와 CHECK 제약조건은 ERDCloud SQL이 아니라 Flyway `V1__initial_schema.sql`부터 실제 migration에 포함한다. 모든 프로필은 `ddl-auto=validate`를 사용한다.
