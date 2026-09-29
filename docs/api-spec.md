# 멍고반점 MVP·P1 백엔드 API 명세

> 상태: **개발 기준 확정** — OD-04의 모델별 수치 임계값만 DATA·AI 평가 후 확정한다.
> Jira: #26, #28, #92, #157
> 기준일: 2026-09-20
> Base URL: `/api/v1` — 운영 종단은 `https://api.meonggo.shop/api/v1` (2026-09-11 구축)

## 1. 목적과 기준 문서

이 문서는 멍고반점 Android 앱과 Spring Boot 백엔드 사이의 MVP API 계약을 정의한다.
요청·응답 필드, 인증·권한, 검증, 오류, 페이지네이션과 매칭 상태를 구현 전에 합의하는 것이 목적이다.

반영한 기준 문서는 다음과 같다.

1. 제품 범위·인수 조건: product/mvp-user-requirements-spec.md
2. 접근 정책·화면 흐름: product/user-flow.md
3. 시스템 책임·매칭 경계: architecture.md, adr/ADR-002-제품-스택-결정기록.md
4. 데이터 구조·상태 전이: erd.md
5. 공통 응답·오류 처리: ../backend/docs/backend-common-settings.md
6. 인증 토큰·키 운영: auth-token-policy.md
7. 개인정보 암호화·전화 인증·마이그레이션·비동기 작업: backend-security-operations-policy.md
8. 게시물 날짜·정확한 위치 공개: post-date-location-policy.md
9. API 구현 절차: ../backend/docs/backend-api-development-guide.md
10. 네이밍·HTTP 규칙: code-convention.md
11. DATA·AI 경계: data-ai-interface.md
12. 입양 탐색 P1 범위·데이터 정책: product/adoption-discovery-mvp.md

문서 간 충돌이 발생하면 제품 범위와 ADR의 확정 결정을 우선한다.
아직 결정되지 않은 값은 2.3의 추적 항목으로 남긴다.

## 2. 범위와 미결정 사항

### 2.1 MVP 포함

- 휴대전화 번호 인증을 포함한 회원가입, 로그인, 토큰 갱신, 로그아웃
- 현재 비밀번호 재인증을 요구하는 회원 탈퇴와 30일 개인정보 파기
- 비로그인 게시물 목록·상세 (정확한 위치 등 일부 필드는 로그인 필요)
- 잃어버렸어요·보호하고 있어요 사용자 게시물 등록
- 내 게시물 조회, 수정, 사진 교체, 종료
- 잃어버렸어요 게시물의 버튼 기반 분석 실행·상태·후보 조회
- 사용자 보호 게시물과 공공 보호동물의 혼합 목록·후보
- 공공 보호동물 데이터 갱신 상태와 일일 신규 입소 요약
- 다른 회원의 활성 사용자 게시물에서 닉네임 기반 1:1 텍스트 채팅, 읽음 상태와 개인 채팅 푸시

### 2.2 MVP 제외

- 보호하고 있어요 기준 역방향 매칭
- 유사 후보 자동 분석·자동 재분석·후보 푸시 알림
- 입양 추천·게시판, 댓글, 좋아요, 결제. 단, 별도 P1 범위인 공공 보호동물 입양 탐색·찜·넘김은 9장의 AD1~AD6 계약을 따른다.
- 이용약관 이력, 마케팅·제3자 제공 동의
- 관리자용 공공데이터 수집 실행 API
- 채팅 이미지·파일, 그룹 채팅, 메시지 수정·삭제, 차단·신고
- WebSocket·SSE 기반 실시간 채팅

### 2.3 결정 및 추적 항목

| ID | 결정 항목 | 현재 계약·미결 내용 | 상태 | 결정 시점 | 영향 |
| --- | --- | --- | --- | --- | --- |
| OD-01 | 액세스 토큰 형식·TTL | **확정:** access token은 `RS256` 서명 JWT로 15분, refresh token은 32바이트 secret 기반 불투명 토큰으로 로그인 시점부터 30일 고정 유효하다. 정상 갱신마다 refresh secret을 원자적으로 회전하고 selector로 토큰 관계를 유지해 이전 토큰 재사용 시 해당 세션을 폐기한다. Android는 access token을 메모리에, refresh token을 Keystore 키로 암호화해 백업 제외 저장소에 보관한다. 세부 계약은 [인증 토큰 정책](auth-token-policy.md)을 따른다. | Decided | — | A2~A4, 인증 설정, Android 저장 |
| OD-02 | 비밀번호 정책 | **확정:** NFC 정규화 후 8~64자이며 모든 공백·제어·형식 문자를 금지하고 자동 제거하지 않는다. 한글·영문·숫자·일반 특수문자는 허용하되 문자 종류 조합은 강제하지 않고 로컬 취약 비밀번호 목록과 비교한다. Android의 확인 입력은 A1에 보내지 않는다. 서버는 `{argon2id-v1}` Argon2id로만 저장하고 모든 Argon2 요청 경로를 공용 동시 실행 상한으로 보호하며, 계정 5회·IP 20회 실패 시 각각 15분 동안 로그인을 제한한다. 세부 계약은 [비밀번호 정책](password-policy.md)을 따른다. | Decided | — | A1~A2, 인증 설정, Android 입력 |
| OD-03 | 이미지 규격 | **확정:** P3·P5는 JPEG·PNG 1~10장을 multipart로 직접 받는다. 장당 10 MiB·요청 전체 50 MiB, 방향 보정 후 각 변 64~10,000px·총 4천만 픽셀로 제한한다. 서버는 실제 내용을 검증한 뒤 메타데이터를 제거한 sRGB JPEG(긴 변 최대 4,096px, 품질 90)로 정규화해 사용자 사진을 HDFS 개별 파일로 저장한다. 세부 계약은 [사진 업로드·저장소 정책](photo-upload-policy.md)을 따른다. | Decided | — | P3, P5, P8, PHOTO 오류, HDFS |
| OD-04 | Top-K·점수 임계값 | **표시 계약 확정:** MVP K=20. 임계값 통과 후보를 rank 순으로 최대 20건 반환하고 원시 점수·정확한 거리는 공개하지 않는다. 수치 임계값은 활성 v2의 별도 평가 후 모델 버전별 서버 설정으로 관리 | 점수 임계값 평가 대기 | DATA·AI 평가 완료 후 | M1, DATA·AI 설정 |
| OD-05 | 사용자 연락 방식 | **확정:** 회원 전화번호는 가입·중복 제한용으로 인증·보호 저장하고 응답하지 않는다. 사용자 게시물은 닉네임 기반 1:1 텍스트 채팅, 공공 보호동물은 보호센터 공식 연락처를 사용한다. | Decided | — | A0~A1, P2, C1~C4 |
| OD-06 | 공공데이터 지연 판정 | **확정:** Airflow는 매일 22:30 KST에 실행한다. D1은 `BACKFILL`을 제외한 `DAILY_INCREMENTAL`과 첫 일일 실행 전 `INITIAL_FULL`만 판정한다. 대상 최신 실행이 `RUNNING`이면 `RUNNING`, 최신 종료 실행이 실패면 `FAILED`다. 그 외 성공 이력이 없으면 `NEVER_SYNCED`, 마지막 성공 완료가 현재보다 36시간 이전이면 `DELAYED`, 아니면 `SUCCEEDED`다. | Decided | — | D1, 수집 상태 계산 |
| OD-07 | 미래 eventDate | **확정:** 사용자 게시물의 `eventDate`는 서버의 `Asia/Seoul` 기준 오늘까지 허용하고 내일부터는 `400 COMMON-001`로 거부한다. P3·P4가 같은 서버 검증을 사용하며 Android 제한은 선검증이다. `eventTime`은 형식만 검증하고 공공데이터 적재에는 이 사용자 입력 상한을 적용하지 않는다. | Decided | — | P3~P4, Android 날짜 입력 |
| OD-08 | 게시물 공개 동의 정책 버전 | **확정:** 현재 버전은 `exact-location-v1`이다. 공개를 켜거나 정확한 위치를 바꾸면서 공개를 유지할 때 해당 위치 객체에 현재 버전이 필요하다. 위치 역할별 버전·시각을 저장하고 현재 버전 증빙이 있는 역할만 노출한다. 누락·불일치는 `409 POST-007`이다. 작성자는 관리용 값을 확인할 수 있고 좌표는 항상 숨긴다. 세부 계약은 [게시물 날짜·정확한 위치 공개 정책](post-date-location-policy.md)을 따른다. | Decided | — | P2~P4, Android 공개 확인, `animal_case_location` |
| OD-09 | 위치 역할 모델 | **확정:** 사용자 요청·로그인 상세는 사건 위치 `eventLocation`을 사용하고, 사용자 `SHELTERING`은 현재 보호 장소 `currentLocation`도 사용한다. 위치별 공개 동의는 해당 위치의 `exactLocationVisible`로 판정한다. | Decided | — | P2~P4, UR-FND-002 |
| OD-10 | 휴대전화 인증 세부 정책 | **확정:** 최우선 목표는 인증된 동일 휴대전화 번호로 둘 이상의 활성 계정을 만들지 못하게 하는 것이다. 국내 `010` 번호를 E.164로 정규화하고 전용 키 HMAC으로 보호한 6자리 OTP는 3분, `pv1.{selector}.{secret}` 불투명 가입 증명은 10분·1회 유효하다. 재전송은 60초 뒤 허용하고 번호당 1시간 5회·24시간 10회, IP당 1시간 20회, 코드 확인 실패 5회로 제한한다. 공급자 결과가 불확실하면 자동 재발송하지 않는다. 탈퇴 번호는 30일 파기 완료 후 재사용하며 영구 차단하지 않는다. 상세 계약은 [백엔드 보안·운영 정책](backend-security-operations-policy.md)을 따른다. | Decided | — | A0~A1, A5, 보안 설정 |
| OD-11 | 채팅 상태·알림·동기화 | **확정:** 고정 1:1 방의 읽음 위치는 `chat_room`에 작성자·요청자별 마지막 확인 메시지 ID로 저장한다. 새 메시지는 DB commit을 최종 기준으로 삼고 같은 트랜잭션의 Outbox를 통해 상대 회원의 활성 인증 세션에 등록된 기기로 본문 없는 FCM data 알림을 보낸다. 대화 화면은 WebSocket·SSE 대신 `afterMessageId` REST 증분 조회를 사용하며 FCM은 메시지 전달이나 읽음 상태의 근거가 아니다. | Decided | — | C2~C5, N1~N2, Android 채팅·FCM, Outbox |

## 3. 공통 계약

### 3.1 형식

- 모든 서비스 API는 /api/v1 아래에 둔다.
- 일반 요청·응답은 application/json이다.
- 사진 포함 요청은 multipart/form-data이며 장당 10 MiB, HTTP 본문 전체 50 MiB를 넘을 수 없다.
- P3의 JSON `payload` part는 64 KiB를 넘을 수 없다.
- 사용자 사진 바이너리 응답은 image/jpeg다.
- 날짜는 YYYY-MM-DD, 시각은 HH:mm:ss, 타임스탬프는 UTC ISO 8601이다.
- ID는 Android Long 범위의 JSON number다.
- Path의 ID는 1 이상의 정수여야 한다.
- 사용자 표시 문자열은 원문 크기 상한을 먼저 검사한 뒤 NFC 정규화·앞뒤 공백 제거를 적용한다.
  제어·형식 문자는 거부하고 내부 일반 공백은 보존한다.

### 3.2 인증과 접근

    Authorization: Bearer {accessToken}

| 기능 | 비로그인 | 로그인 |
| --- | :---: | :---: |
| 잃어버렸어요·보호하고 있어요 목록 | O | O |
| 활성 사용자 게시물 사진 | O | O |
| 종료 사용자 게시물 사진 | X | 본인만 |
| 게시물 상세 | O | O. 정확한 위치는 로그인만 |
| 매칭 상태·후보 조회 | X | 본인 ACTIVE USER_POST/LOST 작성자만 |
| 게시물 등록 | X | O |
| 내 게시물 | X | O |
| 수정·사진 교체·종료 | X | 본인만 |
| 유사도 분석 실행 | X | 잃어버렸어요 작성자만 |
| 채팅방 생성·목록 | X | 참여자만 |
| 채팅 메시지 조회·전송 | X | 해당 대화방 참여자만 |
| 채팅 읽음 위치 갱신 | X | 해당 대화방 참여자만 |
| 개인 푸시 기기 등록·해제 | X | 본인 기기만 |
| 공공데이터 상태 | X | O |
| 일일 입소 요약 | O | O |
| 입양 후보 목록 | O | O. 인증 시 본인 찜 여부 포함 |
| 입양 찜 목록·추가·해제 | X | 본인만 |
| 회원 탈퇴 | X | 본인만 |

- 회원 전화번호는 가입·인증 요청에만 포함하며, 모든 일반 응답·로그에는 평문과 조회 해시를 포함하지 않는다.
- 회원가입은 현재 `privacy-collection-v1` 개인정보 수집·이용 고지의 필수 동의를 요구한다. 고지는 가입 정보뿐 아니라 기능 사용 시 처리하는 게시물·사진·위치·선택 좌표·채팅과 처리 목적·보유 기간을 포함한다. 탈퇴 즉시 접근을 차단하고 계정 보호값과 사용자 생성 데이터는 30일 이내 파기·익명화한다. 동의 여부·버전·시각은 회원 행에 저장하며 마케팅·제3자 제공 동의는 MVP에 포함하지 않는다. 실제 고지문은 운영 배포 전 별도 승인이 필요하다.
- 비로그인 목록은 `source` 값으로 사용자 게시물과 공공 보호동물을 구분하는 출처 배지만 포함하며, 작성자 닉네임·보호소 이름·공식 전화번호는 포함하지 않는다.
- 목록에는 회원 개인 연락처, 보호소 주소, 정확한 주소·건물명·좌표를 포함하지 않는다. 목록의 `publicLocation`은 항상 `EVENT.public_location`을 뜻하는 시·군·구 및 읍·면·동 수준의 스칼라 요약 필드다.
- 다른 회원의 로그인 상세는 활성 사용자 게시물이고 현재 버전 공개 동의가 있는 정확한 위치만 포함한다. 인증 상태, `status=ACTIVE`, 해당 위치의 `exactLocationVisible=true`, 저장 동의 버전이 현재 버전인 조건을 모두 만족할 때만 그 위치의 `exactLocation`을 포함한다. 작성자는 자신의 관리용 상세에서 공개 여부와 관계없이 저장된 `exactLocation`과 `exactLocationVisible`을 확인할 수 있지만 좌표는 볼 수 없다. 다른 회원에게는 종료·삭제·공공 게시물의 정확한 위치를 포함하지 않는다.
- 채팅 내용은 해당 방의 작성자와 요청자에게만 제공한다.
- 좌표는 매칭에만 사용하고 응답하지 않는다.
- Spring Security 오류도 공통 ApiResponse 형식으로 반환한다.
- 인증 정보가 없거나 검증되지 않으면 `AUTH-002(401)`을 반환한다. 인증은 됐지만 일반 URL·메서드
  권한이 부족하면 `AUTH-007(403)`을 반환하며, 게시물 소유권처럼 도메인 의미가 있는 거부는
  `POST-002` 등 해당 도메인 오류 코드를 유지한다.
- access token은 `typ=at+jwt`, `alg=RS256`, `kid` header와 `iss`, `aud`, `sub`, `sid`,
  `client_id`, `jti`, `iat`, `nbf`, `exp` claim만 사용하는 15분 JWT다. 다른 알고리즘과
  필수 header·claim 검증 실패는 `AUTH-002`로 거부한다. 서명 검증 뒤 모든 인증 요청에서
  `sid`가 가리키는 `auth_session`의 회원 일치·고정 만료·폐기 여부와 활성 회원 상태를
  확인한다. 세션 오류와 비활성 회원은 세션을 폐기하고 `AUTH-003`으로 거부한다. 단,
  A4의 동일 세션 로그아웃 재시도만 폐기 여부와 무관하게 204를 반환한다.
- refresh token은 `v1.{selector}.{secret}` 형식이고 로그인 시점부터 30일 동안 유효하다.
  갱신해도 만료 시각을 연장하지 않으며, 정상 갱신마다 secret을 회전한다.
- 토큰 원문, `Authorization` header와 JWT claim 전체는 서버·프록시·Android 로그에 남기지
  않는다. 발급·저장·검증·키 교체의 상세 기준은 [인증 토큰 정책](auth-token-policy.md)을
  따른다.
- A1·A2 JSON body는 8,192바이트, loginId·password는 NFC 전 각각 256 Unicode 코드
  포인트로 먼저 제한한다. 비밀번호는 NFC 정규화 후 8~64자이며 모든 공백·제어·형식 문자를
  허용하지 않는다. 평문·정규화 값·hash는 응답과 로그에 남기지 않으며 생성·저장·로그인
  제한의 상세 기준은 [비밀번호 정책](password-policy.md)을 따른다.

### 3.3 ApiResponse

성공:

~~~json
{
  "code": "SUCCESS",
  "message": "요청에 성공했습니다.",
  "data": {}
}
~~~

오류:

~~~json
{
  "code": "POST-001",
  "message": "게시물을 찾을 수 없습니다."
}
~~~

검증 오류:

~~~json
{
  "code": "COMMON-001",
  "message": "요청 값이 올바르지 않습니다.",
  "data": {
    "fieldErrors": [
      {
        "field": "eventDate",
        "reason": "발생 날짜는 필수입니다."
      }
    ]
  }
}
~~~

- code와 message는 항상 포함한다.
- 추가 데이터가 없으면 data를 생략한다.
- rejectedValue와 민감정보를 오류 응답에 포함하지 않는다.
- 204 No Content는 본문이 없다.

### 3.4 HTTP 상태

| 상황 | 상태 |
| --- | ---: |
| 조회·수정·상태 변경 | 200 |
| 리소스 생성 | 201 |
| 비동기 실행 접수 | 202 |
| 본문 없는 성공 | 204 |
| 검증·형식 오류 | 400 |
| 인증 실패 | 401 |
| 권한 부족 | 403 |
| 리소스 없음 | 404 |
| 상태·버전·중복 충돌 | 409 |
| 사진 또는 요청 용량 초과 | 413 |
| 지원하지 않는 사진 MIME | 415 |
| 사진 해상도·픽셀 제한 위반 | 422 |
| 예상하지 못한 오류 | 500 |
| 사진 저장소 일시 장애 | 503 |

### 3.5 enum

| 이름 | 값 |
| --- | --- |
| PostType | LOST, SHELTERING |
| PostSource | USER_POST, SHELTER, PUBLIC_LOST |
| PostStatus | ACTIVE, CLOSED, DELETED |
| PostListSort | LATEST, OLDEST |
| Species | DOG, CAT, OTHER |
| Sex | MALE, FEMALE, UNKNOWN |
| CloseReason | RETURNED, TRANSFERRED, OTHER |
| MatchStatus | PENDING, RUNNING, SUCCEEDED, FAILED |
| AnalysisStatus | NOT_REQUESTED, PENDING, RUNNING, SUCCEEDED, FAILED, STALE |
| DataSyncStatus | RUNNING, SUCCEEDED, FAILED, DELAYED, NEVER_SYNCED |
| AdoptionAvailability | AVAILABLE, UNAVAILABLE |

DB source_type USER는 USER_POST, PUBLIC은 case_type에 따라 SHELTER(SHELTERING, 보호소 공고) 또는 PUBLIC_LOST(LOST, 공공 분실 신고)로 변환한다.
공공 분실 신고(PUBLIC_LOST)는 2026-09-15 추가 — 매칭 기준(M2)·후보(M1) 어느 쪽에도 들지 않으며 채팅 대상이 아니다.

### 3.6 게시물 목록 등록순·커서 페이지네이션

- 고정 크기 10건
- P1 `sort=LATEST` 또는 생략: listedAt DESC, 동률이면 postId DESC
- P1 `sort=OLDEST`: listedAt ASC, 동률이면 postId ASC
- P7 내 게시물은 기존 최신순 고정이며 `sort`를 받지 않음
- 다음 요청은 응답 nextCursor를 그대로 전달
- P1 커서는 listedAt과 postId를 인코딩하고 정규화한 필터·조회 범위·sort에 묶인 불투명 문자열
- 서버는 최대 11건을 읽고 10건만 반환해 hasNext 계산
- 전체 개수는 조회하지 않음

~~~json
{
  "items": [],
  "page": {
    "size": 10,
    "hasNext": false
  }
}
~~~

다음 페이지가 있으면 page.nextCursor를 추가한다.

### 3.7 낙관적 잠금

사용자 게시물 변경 요청(P4·P5·P6)은 상세 응답의 version을 포함한다.
현재 version과 다르면 409 POST-004를 반환하고 최신 상세를 다시 조회하게 한다.

## 4. 엔드포인트 요약

| ID | Method | Path | 설명 | 인증 | 상태 |
| --- | --- | --- | --- | :---: | ---: |
| A0-1 | POST | /auth/phone-verifications | 휴대전화 인증 코드 요청 | X | 202 |
| A0-2 | POST | /auth/phone-verifications/confirm | 인증 코드 확인·가입용 증명 발급 | X | 200 |
| A0-3 | GET | /auth/login-ids/availability | 로그인 ID 사용 가능 여부 확인 | X | 200 |
| A1 | POST | /auth/signup | 회원가입 | X | 201 |
| A2 | POST | /auth/login | 로그인 | X | 200 |
| A3 | POST | /auth/tokens/refresh | 토큰 갱신 | 갱신 토큰 | 200 |
| A4 | POST | /auth/logout | 로그아웃 | O | 204 |
| A5 | POST | /members/me/withdrawal | 회원 탈퇴 | 본인 | 204 |
| A6 | GET | /members/me | 내 프로필 | O | 200 |
| A7 | PATCH | /members/me | 닉네임 변경 | 본인 | 200 |
| A8 | PUT | /members/me/password | 비밀번호 변경 | 본인 | 204 |
| A9-1 | POST | /auth/account-recovery/phone-verifications | 계정 찾기 인증 코드 요청 | X | 202 |
| A9-2 | POST | /auth/account-recovery/phone-verifications/confirm | 인증 코드 확인·아이디 확인·복구 증명 발급 | X | 200 |
| A9-3 | POST | /auth/account-recovery/password | 비밀번호 재설정 | X | 204 |
| P1 | GET | /posts | 게시물 목록 | X | 200 |
| P2 | GET | /posts/{postId} | 게시물 상세 | X | 200 |
| P3 | POST | /posts | 사용자 게시물 등록 | O | 201 |
| P4 | PATCH | /posts/{postId} | 메타데이터 수정 | 본인 | 200 |
| P5 | PUT | /posts/{postId}/photos | 사진 전체 교체 | 본인 | 200 |
| P6 | POST | /posts/{postId}/closure | 게시물 종료 | 본인 | 200 |
| P7 | GET | /members/me/posts | 내 게시물 | O | 200 |
| P8 | GET | /photos/{photoId} | 사용자 사진 바이너리 | 상태별 | 200 |
| M1 | GET | /posts/{postId}/candidates | 매칭 상태·후보 | 본인 ACTIVE LOST | 200 |
| M2 | POST | /posts/{postId}/match-runs | 유사도 분석 실행 | 본인 | 202 |
| C1 | POST | /posts/{postId}/chat-room | 채팅방 조회 또는 생성 | O | 200 |
| C2 | GET | /chat-rooms | 내 채팅방 목록 | O | 200 |
| C3 | GET | /chat-rooms/{chatRoomId}/messages | 메시지 목록 | 참여자 | 200 |
| C4 | POST | /chat-rooms/{chatRoomId}/messages | 텍스트 메시지 전송 | 참여자 | 201 |
| C5 | PUT | /chat-rooms/{chatRoomId}/read | 마지막 읽음 위치 갱신 | 참여자 | 200 |
| N1 | PUT | /members/me/push-devices/{installationId} | 개인 푸시 기기 등록·갱신 | 본인 | 204 |
| N2 | DELETE | /members/me/push-devices/{installationId} | 개인 푸시 기기 해제 | 본인 | 204 |
| D1 | GET | /data-sources/shelter-animals/status | 공공데이터 상태 | O | 200 |
| D2 | GET | /data-sources/shelter-animals/daily-summary | 최신 일일 입소 요약 | X | 200 |
| D3 | GET | /data-sources/shelter-animals/insights | 홈 인사이트 카드 5장 | X | 200 |
| AD1 | GET | /adoptions | 입양 후보 카드 목록 | 선택(1.5 호환) | 200 |
| AD2 | GET | /members/me/adoption-favorites | 내 입양 찜 목록 | O | 200 |
| AD3 | PUT | /members/me/adoption-favorites/{postId} | 입양 찜 추가 | O | 204 |
| AD4 | DELETE | /members/me/adoption-favorites/{postId} | 입양 찜 해제 | O | 204 |
| AD5 | PUT | /members/me/adoption-swipes/{postId} | 넘긴 동물 기록 | O | 204 |
| AD6 | GET | /members/me/adoption-swipes | 내가 넘긴 동물 목록 | O | 200 |
| V1 | GET | /app/version | 앱 최신·최소 지원 버전과 스토어 링크 | X | 200 |

## 5. 인증 API

### A0-1. 휴대전화 인증 코드 요청

POST /api/v1/auth/phone-verifications

~~~json
{"phoneNumber": "01012345678", "privacyCollectionAgreed": true, "privacyCollectionPolicyVersion": "privacy-collection-v1"}
~~~

문자 발송 전에 현재 개인정보 수집·이용 고지에 대한 동의 여부(`true`)와 버전을 검증한다. 동의가 없거나 버전이 다르면 SMS 공급자를 호출하거나 인증 시도·재전송 제한 상태를 변경하지 않는다. 동의 여부 누락·`null`·`false`는 `COMMON-001`, 버전 누락·불일치는 `MEMBER-002`다. 이 단계에는 회원 행이 없으므로 영구 동의 기록은 회원가입 성공 시 서버 시각으로 저장한다.

MVP는 국내 `010` 휴대전화 번호만 지원한다. `01012345678` 또는 `+821012345678` 형식으로 받고 내부 비교·인증 키는 E.164 `+821012345678`로 정규화한다. 운영 SMS 공급자는 [SOLAPI](https://solapi.com/developers/api/messages-sms)이며 사전 등록한 발신번호로 인증 문자를 보낸다. SOLAPI 어댑터는 정규화 번호를 하이픈 없는 국내 `010` 형식으로 변환해 전달한다. API 키·Secret·발신번호는 환경 변수 또는 배포 Secret으로 주입한다. 로컬·테스트는 외부 발송 없는 Fake 공급자를 사용하고 운영 프로필에서는 Fake 공급자 기동을 거부한다.

OTP는 CSPRNG로 만든 숫자 6자리이며 발송 접수 성공부터 3분 동안 유효하다. 공급자가 접수 성공을 명확히 반환한 새 코드만 활성화하면서 이전 코드를 폐기한다. 타임아웃처럼 결과가 불확실하면 자동 재발송하지 않고 PHONE-004와 재시도 가능 시각을 반환한다. 재전송은 직전 발송 접수 성공 60초 뒤부터 허용한다. 발송은 전화번호 HMAC 기준 최근 1시간 5회·최근 24시간 10회, 요청 IP 기준 최근 1시간 20회로 제한한다. 제한 응답의 `Retry-After` 헤더에는 현재 초과한 제한이 모두 해제될 때까지 남은 초를 제공한다.

인증 도전과 가입 증명 상태는 공유 Redis 인증 캐시에 전화번호 HMAC, 전용 `PHONE_OTP_HMAC_KEY_V1`로 계산한 OTP HMAC, 가입 증명 secret의 SHA-256, 실패 횟수, 발급·만료·소비 상태만 TTL과 함께 저장한다. 발급·재전송·실패 횟수·발송 제한·증명 선점은 Lua 스크립트 또는 동등한 원자 연산으로 처리한다. OTP·가입 증명·전화번호 평문과 영구 인증 이력은 PostgreSQL·Redis key·애플리케이션 로그에 저장하지 않는다. SMS 공급자 호출을 위해 정규화 번호를 메모리에서 일시적으로 사용할 수 있으나 요청 종료 뒤 보유하지 않는다. Fake 공급자도 OTP를 로그에 쓰지 않는다.

응답 202:

~~~json
{"code": "SUCCESS", "message": "인증 코드를 전송했습니다."}
~~~

오류: COMMON-001(400), MEMBER-002(409), PHONE-001(429), PHONE-004(503).

### A0-2. 휴대전화 인증 코드 확인

POST /api/v1/auth/phone-verifications/confirm

~~~json
{"phoneNumber": "01012345678", "verificationCode": "123456", "privacyCollectionAgreed": true, "privacyCollectionPolicyVersion": "privacy-collection-v1"}
~~~

인증 코드 확인에도 현재 고지 동의 여부와 버전이 필수다. 동의가 없거나 버전이 다르면 코드를 확인·소비하거나 가입 증명을 발급하지 않는다.

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "휴대전화 인증을 완료했습니다.",
  "data": {"phoneVerificationToken": "pv1.16-byte-selector.32-byte-secret", "expiresAt": "2026-09-01T10:10:00Z"}
}
~~~

코드당 확인 실패는 최대 5회이며 5번째 실패 시 해당 코드를 즉시 폐기한다. 인증에 성공하면 코드를 폐기하고 인증된 E.164 번호에 바인딩된 `pv1.{selector}.{secret}` 가입용 증명을 발급한다. selector는 16바이트, secret은 32바이트 난수의 Base64 URL(no padding) 값이며 전체 길이는 70자다. 가입용 증명은 발급 후 10분 동안 유효하며 회원가입 성공에 한 번만 사용할 수 있다. 원문 인증 코드와 증명은 로그에 남기지 않는다.
오류: COMMON-001(400), MEMBER-002(409), PHONE-002(400), PHONE-003(409).

### A0-3. 로그인 ID 사용 가능 여부 확인

GET /api/v1/auth/login-ids/availability?loginId=Mango206

`loginId`는 A1과 동일하게 NFC 정규화와 `Locale.ROOT` 소문자 변환을 적용한 canonical 값으로
검사한다. 따라서 `Mango206`과 `mango206`은 같은 아이디다. 형식이 올바른 미사용 아이디는 다음과
같이 응답한다.

~~~json
{
  "code": "SUCCESS",
  "message": "사용할 수 있는 아이디입니다.",
  "data": {"available": true}
}
~~~

이미 사용 중인 아이디도 조회 자체는 성공했으므로 HTTP 200으로 응답하고, 사용자가 바로 수정할 수
있는 안내 문구를 제공한다.

~~~json
{
  "code": "SUCCESS",
  "message": "이미 사용 중인 아이디입니다. 다른 아이디를 입력해 주세요.",
  "data": {"available": false}
}
~~~

이 확인은 아이디를 예약하지 않는다. 확인 직후 다른 요청이 먼저 가입할 수 있으므로 A1은 같은
canonical 값으로 중복을 다시 검사하고, 동시 요청은 DB의 `idx_member_login_id_canonical` 유일
인덱스로 최종 차단한다. A1에서 중복이 확인되면 `409 MEMBER-001`을 반환한다.

오류: COMMON-001(400, 형식 오류), COMMON-003(400, `loginId` 누락).

### A1. 회원가입

POST /api/v1/auth/signup

~~~json
{
  "loginId": "mango206",
  "password": "client-input-only",
  "nickname": "망고보호자",
  "phoneNumber": "01012345678",
  "phoneVerificationToken": "pv1.16-byte-selector.32-byte-secret",
  "privacyCollectionAgreed": true,
  "privacyCollectionPolicyVersion": "privacy-collection-v1"
}
~~~

| 필드 | 타입 | 필수 | 검증 |
| --- | --- | :---: | --- |
| loginId | String | O | NFC → `Locale.ROOT` 소문자 → NFC canonical 값 기준 1~50자이고 공백 불가·중복 금지. 저장·응답도 canonical 값 사용 |
| password | String | O | NFC 정규화 후 8~64자. 공백·제어·형식 문자 불가, 취약 비밀번호 차단. 상세 정책 OD-02 |
| nickname | String | O | NFC·앞뒤 공백 제거 후 1~30자. 제어·형식 문자 불가, 중복 허용 |
| phoneNumber | String | O | A0-2에서 인증한 번호와 일치. 응답·로그에 포함 금지 |
| phoneVerificationToken | String | O | 정확히 70자의 유효·미사용·미만료 `pv1` 가입용 증명. 아래 Fake 전용 번호에서는 생략 |
| privacyCollectionAgreed | Boolean | O | 개인정보 수집·이용 동의. Java 요청 DTO는 `@NotNull`, `@AssertTrue`를 함께 적용한다. `true`만 허용하며, 누락·`null`·`false`는 `400 COMMON-001`로 가입 거부 |
| privacyCollectionPolicyVersion | String | O | 현재 값 `privacy-collection-v1`. 누락·불일치는 `409 MEMBER-002` |

`dev/test` 프로필에서 `SMS_PROVIDER=FAKE`인 경우에만 `phoneNumber`가 `011-0000-0000` 또는
`01100000000`이면 A0-1·A0-2와 `phoneVerificationToken`을 생략할 수 있다. 이 번호는 내부 전용
정규값으로 보호 저장하며 기존 번호 중복 정책을 그대로 적용하므로 활성 또는 파기 대기 계정 하나만
허용한다. `prod`는 Fake 공급자로 기동할 수 없고 `SOLAPI`에서는 `011`을 일반 형식 오류로 거부한다.
응답과 로그에는 입력 번호와 우회 여부를 포함하지 않는다.

응답 201:

~~~json
{
  "code": "SUCCESS",
  "message": "회원가입에 성공했습니다.",
  "data": {
    "memberId": 101,
    "loginId": "mango206",
    "nickname": "망고보호자",
    "createdAt": "2026-08-27T05:10:00Z"
  }
}
~~~

오류: COMMON-001(400), MEMBER-001(409), MEMBER-002(409), PHONE-002(400), PHONE-003(409), AUTH-006(503).
`passwordConfirm`은 Android가 NFC 정규화 후 `password`와 같은지 확인하는 화면 전용
입력이다. A1 요청에는 포함하지 않으며, 서버가 최종 비밀번호 생성 규칙을 다시 검증한다.
공백을 포함한 입력은 잘라내거나 치환하지 않고 `COMMON-001`의
`data.fieldErrors.password`로 거부한다. 검증을 통과한 비밀번호는 비밀번호마다 새
16바이트 salt를 사용하는 `{argon2id-v1}` Argon2id(memory 64 MiB, iterations 3,
parallelism 4, hash 32바이트) 인코딩 문자열로만 저장한다. 인코딩 전에 A2의 실제·dummy
비교와 프로필 상향 재인코딩도 함께 쓰는 공용 Argon2 실행권을 얻고, 즉시 얻지 못하면
`AUTH-006`·HTTP 503·`Retry-After: 1`을 반환한다.

회원가입은 현재 `privacy-collection-v1` 개인정보 수집·이용 동의가 `true`일 때만 허용한다. 고지는 가입 정보와 기능 사용 시 처리하는 게시물·사진·위치·선택 좌표·채팅, 처리 목적과 보유 기간을 포함한다. 성공 시 인증된 전화번호를 AES-256-GCM 암호문·조회 HMAC·인증 시각으로 저장하고 개인정보 수집·이용 동의 여부(`true`)·고지 버전·서버 동의 시각도 `member`에 저장한다. 활성 회원은 세 값이 필수이고 탈퇴 30일 파기 시 세 값을 함께 `NULL`로 만든다. 인증된 동일 휴대전화 번호로 둘 이상의 활성 계정을 생성할 수 없으며, 가입 서비스 검사와 데이터베이스의 활성 번호 부분 유일 인덱스를 함께 적용해 동시 요청에서도 이를 보장한다.

서비스는 비밀번호·닉네임·동의 등 저비용 입력 검증과 불투명 가입 증명의 형식·secret hash·번호 binding 검사를
통과시킨 뒤, 증명을 선점하기 전에 공용 Argon2 실행권을 얻는다. 포화면 증명 상태를 바꾸지
않고 `AUTH-006`을 반환한다. 실행권을 얻은 요청만 Redis에서 가입 증명을 요청 ID로
`ISSUED → CLAIMED` 원자 선점한다. 선점 성공 여부나 Argon2id 인코딩 성공·실패·예외와
관계없이 `finally` 경로에서 실행권을 정확히 한 번 반환한다. 선점한 요청은 인코딩 후 회원
생성 트랜잭션을 실행하며, 커밋 후 증명을 `CONSUMED`로 바꾼다. 인코딩 예외나 명확한 롤백이면
같은 요청 ID의 `CLAIMED`만 비교해 `ISSUED`로 되돌리고, Redis 복구 실패·커밋 여부 불명확·
프로세스 중단이면 `CLAIMED` 상태를 만료까지 유지해 재사용을 막는다. 커밋 후 Redis 갱신이
실패해도 활성 번호 부분 유일 인덱스가 두 번째 계정 생성을 차단한다. 장애로 선점 상태가
만료될 때까지 가입을 완료하지 못한 사용자는 번호 인증부터 다시 진행한다.

활성 계정과 동일 번호면 기존 계정 식별 정보는 노출하지 않고 문의 방법만 안내한다. 휴면 상태는 MVP에서 구분하지 않으므로 탈퇴하지 않은 계정은 모두 활성 번호 중복 제한 대상이다. 가입 서비스는 상태나 탈퇴 후 경과 시간과 무관하게 동일 조회 해시가 남은 모든 회원 행의 번호 재사용을 거부한다. A5 탈퇴 30일 뒤 회원 개인정보 파기 트랜잭션이 전화번호 암호문·조회 해시·인증 시각·동의 여부·버전·시각을 `NULL`로 만들고 로그인 ID·닉네임·비밀번호 자격 증명을 복구 불가능한 탈퇴 식별값으로 치환한 후에만 같은 번호를 새 계정에 사용할 수 있다. 이 DB 파기는 사진 저장소 정리와 분리해 외부 저장소 장애로 지연되지 않게 하며, 실패 시 재사용 차단을 유지하고 재시도한다. 과거 가입 이력을 별도로 남겨 영구 차단하지 않는다. 회원 전화번호 변경 API와 자동 계정 복구는 MVP에 포함하지 않는다. 마케팅 동의와 제3자 제공 동의도 MVP에 포함하지 않는다.

### A2. 로그인

POST /api/v1/auth/login

~~~json
{"loginId": "mango206", "password": "client-input-only"}
~~~

응답 200 (발급 시각이 `2026-08-27T05:10:00Z`인 예시):

~~~json
{
  "code": "SUCCESS",
  "message": "로그인에 성공했습니다.",
  "data": {
    "tokenType": "Bearer",
    "accessToken": "access-token-value",
    "refreshToken": "v1.refresh-token-selector.refresh-token-secret",
    "accessTokenExpiresAt": "2026-08-27T05:25:00Z",
    "refreshTokenExpiresAt": "2026-09-26T05:10:00Z",
    "member": {"memberId": 101, "nickname": "망고보호자"}
  }
}
~~~

access token은 15분 유효한 `RS256` JWT다. refresh token은 로그인 시점부터 30일 고정
유효하며 원문은 한 번만 전달한다. selector는 세션 조회용으로, secret의 SHA-256만 DB에
저장한다. 응답에는 `Cache-Control: no-store`와 `Pragma: no-cache`를 포함한다.
로그인 실패 메시지로 아이디 존재 여부를 구분하지 않는다. A1·A2 JSON body는
`Content-Length`나 chunked 전송과 관계없이 8,192바이트까지만 정규화 전에 읽고, loginId와
password는 각각 NFC 전 256 Unicode 코드 포인트까지만 허용한다. 초과 입력은 값 노출 없이
`COMMON-001`로 거부한다. loginId와 password의 누락·`null`도 `COMMON-001`로 거부한다.
loginId는 A1과 같은
`NFC(NFC(loginId).toLowerCase(Locale.ROOT))` canonical 값을 만들어 공백이 없고 1~50자인지
검사한 뒤 그 값으로 정확히 조회한다. password도 A1과 같은 NFC 결과를 검증과 hash 비교에 사용한다.
값이 있지만 아이디가 없거나 비밀번호가 틀렸거나 비밀번호 형식이 허용 범위가 아니면 모두
같은 `AUTH-001`을 반환한다. 아이디 미존재·비밀번호 형식 오류는 현재
`{argon2id-v1}`과 같은 비용의 사전 생성 dummy hash를 정확히 한 번 비교하되 결과는 버린다.
인증 성공은 회원 존재·비밀번호 형식 유효·실제 hash 일치가 모두 참일 때만 성립한다.

공유 Redis 인증 캐시는 `loginIdCanonical`과 검증된 클라이언트 IP를 각각
HMAC-SHA-256으로 보호한 키만 사용한다. 동일 계정의 15분 내 연속 5번째 실패 또는 동일
IP의 10분 내 20번째 실패부터 15분 동안 로그인을 제한하고 `AUTH-004`와 HTTP 429, 남은
초의 `Retry-After` header를 반환한다. 어떤 제한이 발동했는지와 계정 존재 여부는 응답하지
않는다. 제한 전 로그인 성공은 계정 실패 횟수만 제거하며 IP 횟수는 TTL까지 유지한다.

모든 요청 경로의 Argon2 작업은 대기열 없는 같은 공용 실행권을 사용해 동시에 최대 4개만
실행한다. A2는 실행권을 얻기 전과 얻은 직후 Redis 제한을 확인하며, 실행권을 즉시 얻지
못하면 `AUTH-006`과 `Retry-After: 1`을 반환한다. 제한이 활성화될 때 이미 두 번째 검사를
통과한 최대 4개는 완료될 수 있고 이후 요청은 hash 비교 전에 거부한다. direct peer와 신뢰
proxy 판정, IP canonical화, 차단 목록 파일을 포함한 상세 계약은
[비밀번호 정책](password-policy.md)을 따른다. 영구 잠금과 관리자 수동 해제는 MVP에 포함하지
않는다.

오류: COMMON-001(400), AUTH-001(401), AUTH-004(429), AUTH-005(403), AUTH-006(503), COMMON-500(500).

### A3. 토큰 갱신

POST /api/v1/auth/tokens/refresh

~~~json
{"refreshToken": "v1.refresh-token-selector.refresh-token-secret"}
~~~

응답 200 (`2026-08-27T05:20:00Z`에 위 로그인 토큰을 갱신한 예시):

~~~json
{
  "code": "SUCCESS",
  "message": "토큰을 갱신했습니다.",
  "data": {
    "tokenType": "Bearer",
    "accessToken": "new-access-token-value",
    "refreshToken": "v1.same-selector.new-refresh-token-secret",
    "accessTokenExpiresAt": "2026-08-27T05:35:00Z",
    "refreshTokenExpiresAt": "2026-09-26T05:10:00Z"
  }
}
~~~

selector로 세션을 잠금 조회해 저장된 secret 해시·고정 만료·폐기·회원 상태를 확인하고
기존 secret을 원자적으로 회전한다. 동일 토큰의 동시 요청은 하나만 성공한다. selector가
존재하지만 secret 해시가 다르면 회전된 토큰의 재사용 또는 탈취로 보고 해당 세션을
폐기한다. 형식 오류, 알 수 없는 selector, 불일치, 만료·폐기는 모두 `AUTH-003`으로
응답한다.
refreshToken은 `v1.`과 padding 없는 Base64 URL selector 22자·secret 43자로 구성된 정확히
69자 문자열이어야 한다. 응답에는 `Cache-Control: no-store`와 `Pragma: no-cache`를
포함한다.
오류: AUTH-003(401).

### A4. 로그아웃

POST /api/v1/auth/logout

~~~json
{"refreshToken": "v1.refresh-token-selector.refresh-token-secret"}
~~~

정상 응답은 204이며 본문이 없다.
인증 회원과 세션이 일치하면 auth_session.revoked_at을 기록한다.
이미 폐기된 현재 세션도 JWT의 서명·시간과 `sub`·`sid`, 요청 refresh secret의 현재 해시가
모두 같은 세션에 속하면 로그아웃 재시도를 멱등하게 204로 처리한다. 이 예외는 A4에만
적용하며 회전된 이전 refresh token은 `AUTH-003`으로 거부한다.
로그아웃 후 Android는 access token과 refresh token을 즉시 삭제한다. 서버는 access token
denylist를 두지 않지만, 모든 인증 요청에서 JWT의 `sid` 세션을 확인하므로 폐기 직후 같은
JWT도 `AUTH-003`으로 거부한다.
refreshToken은 A3와 같은 69자 형식이어야 한다.
오류: COMMON-001(400), AUTH-002(401), AUTH-003(401).

### A5. 회원 탈퇴

POST /api/v1/members/me/withdrawal

~~~json
{"currentPassword": "client-input-only"}
~~~

현재 비밀번호를 A2와 같은 정규화·Argon2 실행권으로 재인증한다. 성공하면 한 DB 트랜잭션에서
회원을 `WITHDRAWN`으로 바꾸고 모든 `auth_session`을 폐기하며 소유 사용자 게시물을
`DELETED`, `isMatchable=false`로 바꾼다. 정상 응답은 204이고 Android는 모든 로컬 토큰과
계정 상태를 즉시 삭제한다. 30일 뒤 개인정보 파기가 커밋되면 같은 번호로 다시 가입할 수 있으며
영구 차단하지 않는다. 매일 03:30 KST 단일 실행하는 파기 job이 계정 보호값을 먼저 파기하고
사용자 게시물·위치·매칭·채팅과 HDFS 사진을 삭제·익명화한다. 외부 정리 실패가 회원 식별정보
파기와 번호 재사용을 지연시키지는 않지만 완료까지 재시도하고 30일 목표 초과를 경보한다.

구현 범위(2026-09-16): 재인증과 즉시 차단 트랜잭션(`WITHDRAWN`·세션 전체 폐기·소유 게시물 `DELETED`)은
`MemberAccountService`에 있다. 30일 뒤 파기 job의 advisory lock·100건 경계, 계정 보호값 파기,
게시물·위치·사진 메타데이터·매칭·채팅 관계형 데이터 파기와 회원별 실패 재시도는 구현됐다.
사용자 HDFS 사진 경로는 관계형 파기 트랜잭션에서 outbox에 먼저 보존하고, 짧은 DB lease로
선점한 별도 작업이 멱등 삭제한다. 실패 시 경로를 로그에 남기지 않고 최대 24시간 백오프로 재시도한다.
재인증 실패는 A2와 같은 계정 실패 횟수에 쌓여 `AUTH-004`를 유발할 수 있다. 탈퇴한 회원은 이후 비밀번호가
맞아도 A2에서 `AUTH-005`를 받는다.

오류: COMMON-001(400), AUTH-001(401), AUTH-002(401), AUTH-004(429), AUTH-005(403), AUTH-006(503).

### A6. 내 프로필

GET /api/v1/members/me

~~~json
{"code": "SUCCESS", "message": "요청에 성공했습니다.", "data": {"memberId": 101, "nickname": "망고보호자"}}
~~~

A2 응답의 `member`와 같은 두 필드만 돌려준다. 로그인 ID·전화번호·가입 시각은 응답하지 않는다.
앱이 저장된 갱신 토큰만으로 세션을 되살렸을 때(A3에는 회원 정보가 없다) 마이페이지가 닉네임을 채우는 용도다.
오류: AUTH-002(401), AUTH-003(401), AUTH-005(403).

### A7. 닉네임 변경

PATCH /api/v1/members/me

~~~json
{"nickname": "새 닉네임"}
~~~

A1과 같은 규칙으로 NFC 정규화·앞뒤 공백 제거 뒤 1~30자, 제어·형식 문자 없음을 검사한다. 중복은 허용한다.
응답 200은 A6과 같은 `data`를 돌려준다. 다른 기기 세션에는 영향이 없다.
오류: COMMON-001(400, `fieldErrors[].field=nickname`), AUTH-002(401), AUTH-003(401), AUTH-005(403).

### A8. 비밀번호 변경

PUT /api/v1/members/me/password

~~~json
{"currentPassword": "client-input-only", "newPassword": "client-input-only"}
~~~

`currentPassword`는 A5와 같은 방식으로 재인증한다(틀리면 `AUTH-001`, 실패 횟수는 A2 제한에 합산).
`newPassword`는 A1과 같은 정규화·길이·금지 문자·차단 목록·로그인 ID 동일성 검사를 거치고 위반은
`COMMON-001`(`fieldErrors[].field=newPassword`)이다. 새 값이 현재 값과 같으면 `MEMBER-003`이다.
성공하면 한 트랜잭션에서 hash를 교체하고 **요청한 세션을 제외한** 모든 `auth_session`을 폐기한다 —
비밀번호를 바꾼 기기는 재로그인 없이 계속 쓰고, 다른 기기는 `AUTH-003`을 받아 다시 로그인한다.
검증과 hash 교체 사이에 다른 기기가 비밀번호를 바꿨으면 `AUTH-001`로 거부한다. 정상 응답은 204다.

오류: COMMON-001(400), AUTH-001(401), AUTH-002(401), AUTH-003(401), AUTH-004(429), AUTH-005(403),
AUTH-006(503), MEMBER-003(409).

### A9. 계정 찾기 (2026-09-23 추가 — MVP 제외 항목이었으나 QA 요청으로 포함)

가입 때 인증한 휴대전화 번호로 아이디를 확인하고 비밀번호를 재설정한다. 세 API 모두 비로그인 요청이며
A0 과 같은 Redis 인증 캐시·OTP 규칙(6자리, 3분, 재전송 60초, 번호당 1시간 5회·24시간 10회, IP 1시간 20회,
확인 실패 5회)을 따른다. 단 **키 공간을 `recovery:` 접두로 나눠** 가입용 증명(`pv1`)으로 비밀번호를 바꾸거나
복구 증명으로 가입할 수 없다. 개인정보 수집·이용 동의는 다시 받지 않는다(이미 회원이며 이용 목적 "회원 관리·휴대전화 인증"에
포함된다).

#### A9-1. 계정 찾기 인증 코드 요청

POST /api/v1/auth/account-recovery/phone-verifications

~~~json
{"phoneNumber": "01012345678"}
~~~

**가입 여부를 응답으로 드러내지 않는다.** 활성 회원의 번호면 문자를 보내고, 아니면 보내지 않되 응답은 같은 202 다.
발송 제한 카운터는 둘 다 소비한다.

응답 202: `{"code": "SUCCESS", "message": "인증 코드를 전송했습니다."}`

오류: COMMON-001(400), PHONE-001(429), PHONE-004(503).

#### A9-2. 인증 코드 확인 · 아이디 확인

POST /api/v1/auth/account-recovery/phone-verifications/confirm

~~~json
{"phoneNumber": "01012345678", "verificationCode": "123456"}
~~~

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "휴대전화 인증을 완료했습니다.",
  "data": {"loginId": "mango206", "recoveryToken": "pv1.16-byte-selector.32-byte-secret", "expiresAt": "2026-09-23T10:10:00Z"}
}
~~~

번호 소유를 방금 증명했으므로 `loginId` 는 가리지 않는다(아이디 찾기). `recoveryToken` 은 10분 동안 유효한 1회용
복구 증명이며 그 번호의 활성 회원에만 묶인다. 코드가 틀리거나 만료되었거나 그 번호의 활성 회원이 없으면 `PHONE-002`.

오류: COMMON-001(400), PHONE-002(400).

#### A9-3. 비밀번호 재설정

POST /api/v1/auth/account-recovery/password

~~~json
{"loginId": "mango206", "recoveryToken": "pv1.…", "newPassword": "client-input-only"}
~~~

`loginId` 는 A1 과 같은 canonical 값으로 비교하고, 복구 증명이 **그 아이디의 활성 회원 번호**에 묶여 있어야 한다.
아니면 `PHONE-002`(아이디·증명 어느 쪽이 틀렸는지 구분하지 않는다). `newPassword` 는 A1·A8 과 같은 정규화·길이·금지
문자·차단 목록·로그인 ID 동일성 검사를 거치고 위반은 `COMMON-001`(`fieldErrors[].field=newPassword`), 현재 비밀번호와
같으면 `MEMBER-003`. 성공하면 한 트랜잭션에서 hash 를 교체하고 **모든** `auth_session` 을 폐기한 뒤(비밀번호를 잊은 사람이
남길 세션은 없다) 증명을 소비한다. 정상 응답은 204.

오류: COMMON-001(400), PHONE-002(400), MEMBER-003(409), AUTH-006(503).

## 6. 게시물 API

### 6.1 공통 요약 모델

~~~json
{
  "postId": 1001,
  "type": "SHELTERING",
  "source": "SHELTER",
  "name": null,
  "species": "DOG",
  "breedName": "푸들",
  "sex": "UNKNOWN",
  "color": "갈색",
  "eventDate": "2026-08-26",
  "listedAt": "2026-08-26T00:00:00Z",
  "publicLocation": "서울특별시 강남구 역삼동",
  "thumbnailUrl": "https://cdn.example.com/animals/1001/0.jpg"
}
~~~

일반 게시물 목록(P1)은 `source=USER_POST`, `source=SHELTER`, `source=PUBLIC_LOST`를 출처 배지로 사용하며 `author`, `shelter` 객체를 포함하지 않는다. 상세(P2)는 사용자 출처에 `author.nickname`, 공공 출처에 `shelter.name`, `shelter.phone`을 포함한다. 로그인한 작성자의 유사 후보 조회(M1)는 후보 확인에 필요한 같은 출처별 정보를 포함한다. 회원 ID와 개인 연락처는 표시용 정보로 사용하지 않는다.

`shelter.phone`은 원천 데이터에 전화번호가 없으면 `null`이며, P2와 M1에서만 필드 자체를 유지한다.

목록·후보·내 게시물 요약의 `publicLocation`은 항상 `EVENT.public_location`을 뜻하는 스칼라 필드다. `CURRENT.public_location`은 요약에 대체하거나 추가하지 않는다.
목록·후보 요약에는 보호소 주소, 정확한 위치, 좌표와 `featureText` 전체를 포함하지 않는다.

### P1. 게시물 목록

GET /api/v1/posts

| Query | 필수 | 검증·동작 |
| --- | :---: | --- |
| type | O | LOST 또는 SHELTERING |
| species | X | Species enum 기반 필터 |
| sex | X | Sex |
| breedName | X | 최대 100자, 부분 검색 |
| regionCode | X | 지역 범위. **5자리** 시·군·구 코드는 `EVENT.region_code`와 정확히 일치, **2자리** 시·도 코드(예 `11`)는 그 시·도 전체(접두 일치), **없으면 전국**. 두 `type` 모두 같다. 그 외 형식은 `COMMON-001` (2026-09-17: 그 전엔 SHELTERING에 5자리 필수) |
| color | X | 최대 100자, 부분 검색 |
| source | X | SHELTERING에서만 PostSource |
| sort | X | `LATEST` 또는 `OLDEST`. 생략하면 `LATEST`이며 LOST·SHELTERING에 같은 규칙을 적용 |
| cursor | X | 서버 발급 불투명 커서 |

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "게시물 목록을 조회했습니다.",
  "data": {
    "items": [
      {
        "postId": 1001,
        "type": "SHELTERING",
        "source": "SHELTER",
        "species": "DOG",
        "breedName": "푸들",
        "sex": "UNKNOWN",
        "color": "갈색",
        "eventDate": "2026-08-26",
        "listedAt": "2026-08-26T00:00:00Z",
        "publicLocation": "서울특별시 강남구 역삼동",
        "thumbnailUrl": "https://cdn.example.com/animals/1001/0.jpg"
      }
    ],
    "page": {"size": 10, "hasNext": true, "nextCursor": "opaque-cursor"}
  }
}
~~~

ACTIVE만 반환한다. SHELTERING은 USER_POST와 SHELTER를 섞고, LOST는 USER_POST와 PUBLIC_LOST(공공 분실 신고)를 섞는다. `source` 필터는 SHELTERING에서만 허용한다(기존과 같음).
목록 응답은 `source`에 따라 출처 배지만 표시하고 작성자 닉네임·보호소 이름·공식 전화번호를 포함하지 않는다. 이 출처별 상세 정보와 보호소 주소는 상세(P2)에서 반환한다.
빈 결과는 items=[]과 200이다.
품종·색상은 NFC 정규화·양끝 공백 제거 후 대소문자를 구분하지 않는 부분 검색이며 `%`, `_`, 역슬래시는 일반 문자로 취급한다.
정렬은 `LATEST`이면 `(listedAt DESC, postId DESC)`, `OLDEST`이면 `(listedAt ASC, postId ASC)`이다. `listedAt` 계산 규칙은 정렬 방향과 관계없이 동일하다.
다음 페이지는 동일한 필터와 `sort`로 요청한다. 다른 필터·조회 범위·정렬의 커서나 잘못된 커서는 CURSOR-001이다. 알 수 없거나 중복된 `sort`는 COMMON-001이다.
Android의 정렬 변경은 검색어와 선택 지역을 유지한 채 기존 항목과 커서를 버리고 첫 페이지부터 다시 요청한다. 검색·재시도·더 보기에는 현재 선택한 `sort`를 계속 전달한다. 단 `LATEST`는 서버 기본값이므로 앱이 파라미터를 생략한다(`sort`를 모르는 이전 서버와 호환, 2026-09-22).
오류: COMMON-001(400), POST-006(400), CURSOR-001(400).

### P2. 게시물 상세

GET /api/v1/posts/{postId}

사용자 게시물 응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "게시물을 조회했습니다.",
  "data": {
    "postId": 1002,
    "type": "LOST",
    "source": "USER_POST",
    "status": "ACTIVE",
    "version": 3,
    "name": "망고",
    "species": "DOG",
    "breedName": "푸들",
    "sex": "FEMALE",
    "color": "갈색",
    "eventDate": "2026-08-25",
    "eventTime": "19:30:00",
    "featureText": "빨간 목줄을 착용했습니다.",
    "eventLocation": {
      "regionCode": "11680",
      "emdCode": "1168010100",
      "publicLocation": "서울특별시 강남구 역삼동",
      "exactLocation": "역삼역 3번 출구 인근",
      "exactLocationVisible": false
    },
    "photos": [{"photoId": 3001, "url": "/api/v1/photos/3001", "sortOrder": 0}],
    "author": {"memberId": 101, "nickname": "망고보호자"},
    "chat": {"available": false, "reason": "OWN_POST"},
    "owner": true,
    "createdAt": "2026-08-25T11:00:00Z",
    "updatedAt": "2026-08-27T04:00:00Z"
  }
}
~~~

상세 위치 응답 규칙은 다음과 같다.

- 모든 사용자 게시물은 `eventLocation`을 반환한다. `regionCode`는 필수, `emdCode`는 있으면 반환하며 `publicLocation`은 서버가 코드로부터 만든 표시값이다.
- `type=SHELTERING`인 사용자 게시물은 `currentLocation`도 반환한다. `currentLocation.regionCode`는 필수, `emdCode`는 선택이고 `publicLocation`은 `CURRENT` 코드에서 만든 표시값이다. `type=LOST` 응답에는 `currentLocation`이 없다.
- 작성자가 자신의 사용자 게시물을 조회하면 각 위치 객체에 저장된 `exactLocation`과 `exactLocationVisible`을 관리용 필드로 반환한다. 비공개 위치와 `CLOSED` 게시물도 작성자에게 반환할 수 있지만 좌표는 반환하지 않는다.
- 작성자가 아닌 로그인 요청에는 `status=ACTIVE`, 해당 위치의 `exactLocationVisible=true`, 저장 동의 버전이 현재 `exact-location-v1`인 조건을 모두 만족할 때만 그 객체의 `exactLocation`을 추가한다. `exactLocationVisible`과 동의 증빙은 반환하지 않는다. 종료·삭제·공공 게시물에는 정확한 위치를 추가하지 않는다.
- 비로그인 요청도 상세를 조회할 수 있다. 응답은 작성자가 아닌 로그인 요청과 같되, 공개 동의 여부와 관계없이 `exactLocation`을 반환하지 않는다 ([게시물 날짜·정확한 위치 공개 정책](post-date-location-policy.md)).
- 회원 개인 연락처는 어떤 응답에도 포함하지 않는다.

사용자 `SHELTERING` 상세의 위치 부분 예시는 다음과 같다.

~~~json
{
  "eventLocation": {"regionCode": "11680", "emdCode": "1168010100", "publicLocation": "서울특별시 강남구 역삼동"},
  "currentLocation": {
    "regionCode": "11710",
    "publicLocation": "서울특별시 송파구",
    "exactLocation": "임시보호 장소"
  }
}
~~~

사용자 게시물의 `chat` 규칙은 다음과 같다.

- 다른 회원의 `ACTIVE` 게시물: `{"available": true}`
- 본인 게시물: `{"available": false, "reason": "OWN_POST"}`
- 종료된 게시물: `{"available": false, "reason": "POST_NOT_ACTIVE"}`

공공 분실 신고(`source=PUBLIC_LOST`)는 `shelter`·`author`·`chat`·`version`·`owner`·`currentLocation`을 반환하지 않고 다음 `report` 객체를 반환한다.
신고자 연락처는 저장하지 않으므로 응답에도 없다 — `contactNotice` 문구로 동물보호관리시스템(animal.go.kr) 확인을 안내한다.
`portalUrl`은 그 게시판을 **실종일(`searchSDate`/`searchEDate`)·축종(`searchUpKindCd` 417000 개 · 422400 고양이)·성별(`searchSexCd` M/F)·시·도(`searchUprCd`, 게시판 고유 코드 — `PostDetailResponse.PORTAL_SIDO_CODES`)로 좁힌 목록** 링크다. 원천 API(`lossInfoService`)에 건별 식별자가 없어 신고 상세로 직접 갈 수는 없다. 성별 `UNKNOWN`·모르는 시·도 이름은 그 조건을 생략한다 (2026-09-21 QA: 실종일·축종만으로는 같은 날 다른 개가 먼저 보였다).

~~~json
{
  "source": "PUBLIC_LOST",
  "report": {
    "orgName": "대전광역시 유성구",
    "happenPlace": "유성고등학교 골목 사이",
    "rfidCode": "410100129",
    "firstSeenDate": "2026-09-15",
    "lastSeenDate": "2026-09-15",
    "contactNotice": "신고자 연락처는 동물보호관리시스템(animal.go.kr) 분실동물 게시판에서 확인할 수 있습니다.",
    "portalUrl": "https://www.animal.go.kr/front/awtis/loss/lossList.do?menuNo=1000100000&searchSDate=2026-09-15&searchEDate=2026-09-15&searchUpKindCd=417000&searchSexCd=F&searchUprCd=6300000"
  }
}
~~~

공공 보호동물은 채팅 대상이 아니며 `chat` 대신 다음 `shelter` 객체로 보호센터 공식 연락처를 반환한다. 공공 건은 `version`, `author`, `owner`도 반환하지 않는다.

~~~json
{
  "shelter": {
    "name": "강남동물보호센터",
    "phone": "02-0000-0000",
    "address": "서울특별시 강남구",
    "jurisdiction": "서울특별시 강남구",
    "noticeNo": "서울-강남-2026-001",
    "noticeStartDate": "2026-08-26",
    "noticeEndDate": "2026-09-05",
    "processState": "보호중"
  }
}
~~~

`shelter.phone`은 회원 개인정보가 아닌 공공 보호소 대표번호이므로 원천 데이터를 정규화해 반환한다. 공공 보호동물의 `eventLocation`과 `currentLocation`도 위 상세 위치 응답 규칙을 따른다. 보호소 전체 주소는 `shelter.address`에만 두고, 목록에는 싣지 않고 이 상세 응답에서만 반환한다.

오류: AUTH-002(401, 잘못된 선택 인증 정보), POST-001(404).

### P3. 게시물 등록

POST /api/v1/posts

multipart parts: payload(application/json, 필수), photos(binary 1~10장, 필수).

`payload`는 하나만 허용하고 중복 JSON key·알 수 없는 필드·타입 강제 변환은 거부한다.
사진 쓰기 요청은 multipart 파싱 전 프로세스별 동시 실행 수를 검사한다. 처리 중인 요청이
설정 한도에 도달하면 `503 PHOTO-008`, `Retry-After: 1`을 반환한다.

다음 요청 payload는 형식 `example`이다.

~~~json
{
  "clientRequestId": "9cb37af4-5b75-4f72-9d58-257ee88e95f3",
  "type": "LOST",
  "name": "망고",
  "species": "DOG",
  "breedName": "푸들",
  "sex": "FEMALE",
  "color": "갈색",
  "eventDate": "2026-08-25",
  "eventTime": "19:30:00",
  "eventLocation": {
    "regionCode": "11680",
    "emdCode": "1168010100",
    "exactLocation": "역삼역 3번 출구 인근",
    "latitude": 37.5,
    "longitude": 127.036,
    "exactLocationVisible": false
  },
  "featureText": "빨간 목줄을 착용했습니다."
}
~~~

| 필드 | 필수 | 검증 |
| --- | :---: | --- |
| clientRequestId | O | UUID. 회원 범위의 게시물 등록 멱등성 키 |
| type | O | LOST 또는 SHELTERING |
| name | X | 최대 50자 |
| species | O | Species |
| breedName | X | 최대 100자 |
| sex | O | Sex, 모르면 UNKNOWN |
| color | X | 최대 100자 |
| eventDate | O | 날짜. 서버의 `Asia/Seoul` 기준 오늘까지 허용하며 미래 날짜는 COMMON-001 |
| eventTime | X | HH:mm:ss |
| eventLocation | O | 사건 위치 객체. `EVENT`로 저장하며 아래 위치 객체 공통 검증을 적용 |
| currentLocation | 조건부 | 사용자 `SHELTERING`이면 O이며 `CURRENT`로 저장한다. 사용자 `LOST`는 보내면 안 된다. |
| featureText | X | NFC·앞뒤 공백 제거 후 최대 2,000 Unicode 코드 포인트. 제어·형식 문자 불가 |
| photos | O | JPEG·PNG 1~10장, 장당 10 MiB·요청 전체 50 MiB, 방향 보정 후 각 변 64~10,000px·총 4천만 픽셀. 상세 규격 OD-03 |

`eventLocation`과 `currentLocation`의 위치 객체 공통 필드는 다음과 같다. `currentLocation`은 사용자 `SHELTERING` 요청에서만 이 구조를 사용한다.

| 위치 객체 필드 | 필수 | 검증 |
| --- | :---: | --- |
| regionCode | O | 행정안전부 기준 5자리 시·군·구 코드. 서버가 지원하는 코드인지 검사 |
| emdCode | X | 10자리 읍·면·동 코드. 있으면 `regionCode` 하위여야 함 |
| exactLocation | 조건부 | NFC·앞뒤 공백 제거 후 최대 200 코드 포인트, AES-256-GCM 암호화 저장. 공개가 true이면 1자 이상 필수 |
| latitude, longitude | X | 같은 객체에 둘 다 있거나 둘 다 없음, 좌표 범위 검사. 요청·내부 매칭용이며 응답하지 않음 |
| exactLocationVisible | O | 같은 객체의 `exactLocation` 공개 선택. 기본 false이며 true이면 `exactLocation` 필요 |
| disclosurePolicyVersion | 조건부 | 공개가 true이면 현재 `exact-location-v1` 필수. 누락·불일치는 POST-007 |

서버는 `regionCode`·`emdCode`의 기준 데이터로 시·군·구 및 선택한 읍·면·동 수준
`publicLocation` 표시값을 만든다. 클라이언트가 임의 `publicLocation` 문자열을 보내지 않는다.
기준 데이터는 배포에 포함한 버전 고정 CSV와 SHA-256 checksum으로 검증하며, 누락·불일치 시
위치 쓰기 기능의 readiness를 실패시킨다. 폐지 코드는 신규 쓰기에서 거부하고 기존 게시물의
저장된 표시값은 명시적 migration 전까지 유지한다.
`exactLocation`은 암호화 저장하며
`ACTIVE` 사용자 게시물의 로그인 상세에서 같은 역할의 `exactLocationVisible=true`이고 저장
동의 버전이 현재 버전일 때만 반환한다. 목록·검색·후보 요약에는 포함하지 않고 좌표는 모든
응답에서 제외한다.
위치 객체의 `disclosurePolicyVersion`은 Android가 그 역할의 공개 안내를 확인했음을 증빙하며
`exactLocationVisible=false`인 역할에는 보내거나 저장하지 않는다. 상세 날짜·위치 공개 계약은
[게시물 날짜·정확한 위치 공개 정책](post-date-location-policy.md)을 따른다.

첫 사진은 대표 사진이다. 선언 MIME·매직 바이트·디코더 결과를 모두 검증하고 한 장이라도
실패하면 요청 전체를 거부한다. 검증을 통과한 원본은 방향 보정·메타데이터 제거·sRGB 변환 후
긴 변 최대 4,096px, 품질 90의 JPEG로 정규화한다.
animal_case, 역할별 location, user_post, 사진 메타데이터를 한 트랜잭션에서 저장한다.
`clientRequestId`와 canonical payload·정규화 사진 checksum 목록의 SHA-256을 함께 저장한다.
같은 회원의 같은 ID·같은 payload 재요청은 최초 게시물을 201로 재반환하고, 다른 payload면
IDEMPOTENCY-001(409)로 거부한다.
사용자 사진은 `/data/user/images/{postId}/{photoId}.jpg`에 HDFS replication 2로 저장하고
응답에는 HDFS 경로가 아닌 `/api/v1/photos/{photoId}`만 사용한다. 저장 실패 시 DB를 commit하지
않으며 정리 절차는 [사진 업로드·저장소 정책](photo-upload-policy.md)을 따른다.
LOST의 eventDate·eventTime·`eventLocation`은 실종 정보를 뜻하며 `currentLocation`을 보내면 COMMON-001(400)이다.
SHELTERING의 eventDate·eventTime·`eventLocation`은 발견 정보를 뜻하고 `currentLocation`은 현재 보호 장소다. 둘 중 하나라도 없으면 COMMON-001(400)이다.
두 유형의 `eventDate`는 P3 처리 시점의 `Asia/Seoul` 날짜보다 미래이면 COMMON-001(400)이다.

LOST 응답 201:

~~~json
{
  "code": "SUCCESS",
  "message": "잃어버렸어요 게시물을 등록했습니다.",
  "data": {
    "postId": 1002,
    "type": "LOST",
    "source": "USER_POST",
    "status": "ACTIVE",
    "version": 0,
    "createdAt": "2026-08-27T05:20:00Z"
  }
}
~~~

클라이언트 화면 전환 계약:

- Android는 LOST 등록 성공 후 상세로 이동하고, M1에서 `NOT_REQUESTED` 상태와 `유사도 분석하기` 버튼을 표시한다.
- 작성자가 M2를 호출한 뒤에만 M1의 `PENDING`·`RUNNING`을 `recommendedPollAfterMs` 간격으로 다시 조회한다.
- `SUCCEEDED`이면 `candidateCount`에 따라 후보 목록 또는 후보 없음 상태를 표시하고, `FAILED`이면 처리 실패와 다시 분석할 동선을 표시한다.
- 입력을 고친 뒤에도 분석은 자동 실행하지 않는다. 작성자가 M2를 다시 호출해야 새 실행이 생성된다.

SHELTERING은 역방향 실행을 만들지 않는다. Android는 SHELTERING 등록 후 매칭 화면으로 이동하지 않고 등록 게시물이 향후 LOST의 후보군에 포함됨을 안내한다.
오류: AUTH-002(401), COMMON-001(400), IDEMPOTENCY-001(409), PHOTO-001(400), PHOTO-002(415), PHOTO-003(400), PHOTO-004(413), PHOTO-005(422), PHOTO-006(503), PHOTO-008(503), POST-007(409).

### P4. 게시물 메타데이터 수정

PATCH /api/v1/posts/{postId}

다음 요청 payload는 정확한 위치 공개 상태를 `false`에서 `true`로 바꾸는 형식 `example`이다.

~~~json
{
  "version": 3,
  "eventTime": "20:00:00",
  "featureText": "빨간 목줄과 파란 옷을 착용했습니다.",
  "eventLocation": {
    "exactLocation": "역삼역 3번 출구 인근",
    "exactLocationVisible": true,
    "disclosurePolicyVersion": "exact-location-v1"
  }
}
~~~

- version은 필수다.
- P3 메타데이터 중 type을 제외한 필드를 부분 수정한다.
- source, owner, status, createdAt은 변경할 수 없다.
- 필드 생략은 유지, 명시적 null은 선택 필드 삭제다.
- 위치 변경은 P3와 같은 `eventLocation`, `currentLocation` 객체로만 요청한다. 위치 객체가 있으면 그 객체 안의 필드만 부분 수정하며, 평면 `publicLocation`, `exactLocation`, `latitude`, `longitude`, `exactLocationVisible`는 허용하지 않는다.
- 위치 객체의 요청 필드를 저장된 같은 역할 객체에 병합한 뒤, 완성된 각 위치 객체를 P3의 위치 객체 공통 필드 표로 검증한다. 따라서 병합 결과에서 해당 객체의 `exactLocationVisible=true`인데 공백이 아닌 같은 객체의 `exactLocation`이 없거나, 좌표 쌍·범위 또는 `regionCode`·`emdCode` 조건을 만족하지 않으면 COMMON-001(400)이다.
- 병합 후에도 `LOST`는 `EVENT`만, `SHELTERING`은 `EVENT`와 `CURRENT`를 각각 하나씩 유지해야 한다. `LOST`의 `currentLocation` 또는 `SHELTERING`의 누락된 위치 역할은 COMMON-001(400)이다.
- `eventDate`가 요청에 있으면 P3와 같이 서버의 `Asia/Seoul` 기준 오늘까지 허용하고 미래 날짜는 COMMON-001(400)이다.
- 어느 역할이든 병합 전 `exactLocationVisible=false`에서 최종 `true`가 되거나, 공개를 유지한 채 `exactLocation`을 바꾸면 같은 위치 객체의 현재 `disclosurePolicyVersion=exact-location-v1`이 필수다. 누락하거나 현재 값과 다르면 POST-007(409)이다.
- `true -> false`와 정확한 위치를 바꾸지 않는 다른 부분 수정에는 버전이 필수가 아니다. 공개 확인 성공 시 해당 `animal_case_location` 행의 공개 정책 버전과 동의 시각을 갱신하고, `true -> false`에서는 마지막 증빙을 보존한다. 저장 버전이 현재 버전과 다른 위치는 공개 flag가 true여도 다른 회원 응답에서 정확한 위치를 생략한다.
- LOST의 사진·날짜·`eventLocation`·동물 특징 변경은 version을 올린다. 기존 후보는 보존하되 M1이 `STALE`로 표시하며 자동 분석은 실행하지 않는다.
- 위치별 공개 동의만 바뀌면 재매칭하지 않는다.
- 공개 여부·동의 증빙만 변경하면 `version`은 유지하고 `updatedAt`만 갱신한다. 이런 요청끼리는 같은 버전에서 행 잠금 순서대로 명시한 필드를 적용한다. 내용 변경은 `version`을 1 올리며 정규화 후 변경이 없는 요청은 `updatedAt`도 유지한다.

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "게시물을 수정했습니다.",
  "data": {
    "postId": 1002,
    "version": 4,
    "updatedAt": "2026-08-27T05:30:00Z"
  }
}
~~~

오류: AUTH-002(401), COMMON-001(400), POST-001(404), POST-002(403), POST-003(409), POST-004(409), POST-005(403), POST-007(409).

### P5. 게시물 사진 전체 교체

PUT /api/v1/posts/{postId}/photos

multipart parts:

| Part | Content-Type | 필수 | 검증·바인딩 |
| --- | --- | :---: | --- |
| payload | application/json | O | 상세에서 받은 version을 가진 JSON, `@RequestPart`로 바인딩 |
| photos | image/jpeg, image/png | O | 최종 사진 전체 1~10장, OD-03의 용량·해상도·실제 내용 검증, `@RequestPart`로 바인딩 |

payload part:

~~~json
{"version": 5}
~~~

부분 추가·삭제 대신 최종 사진 전체를 요청 순서대로 교체하며 첫 사진이 대표 사진이다.
새 `photoId`의 HDFS 파일 전체를 먼저 저장하고 DB의 version을 다시 비교해 사진 메타데이터를
한 트랜잭션으로 교체한다. 파일 저장이나 DB commit이 실패하면 기존 사진 전체를 유지하고 새
파일만 정리한다. 성공 뒤 이전 파일 삭제에 실패하면 비참조 파일 정리 작업이 재시도한다.
교체 성공은 게시물 유형과 관계없이 version을 1 올린다. LOST는 이전 성공 결과를 보존하되
M1이 이를 `STALE`로 표시하며, 새 분석은 작성자가 M2를 호출할 때만 실행한다.

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "게시물 사진을 교체했습니다.",
  "data": {
    "postId": 1002,
    "version": 6,
    "photos": [
      {
        "photoId": 3101,
        "url": "/api/v1/photos/3101",
        "sortOrder": 0
      }
    ],
    "updatedAt": "2026-08-27T05:40:00Z"
  }
}
~~~

오류: AUTH-002(401), POST-001(404), POST-002(403), POST-003(409), POST-004(409), POST-005(403), PHOTO-001(400), PHOTO-002(415), PHOTO-003(400), PHOTO-004(413), PHOTO-005(422), PHOTO-006(503), PHOTO-008(503).

### P6. 게시물 종료

POST /api/v1/posts/{postId}/closure

~~~json
{
  "version": 6,
  "reason": "RETURNED"
}
~~~

version과 CloseReason은 필수다.

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "게시물을 종료했습니다.",
  "data": {
    "postId": 1002,
    "status": "CLOSED",
    "version": 7,
    "closeReason": "RETURNED",
    "closedAt": "2026-08-27T05:50:00Z"
  }
}
~~~

status=CLOSED, isMatchable=false, closedAt, closeReason을 한 트랜잭션에서 기록한다.
종료 즉시 공개 목록과 새 후보군에서 제외하며 MVP에서는 재활성화하지 않는다.
오류: AUTH-002(401), POST-001(404), POST-002(403), POST-003(409), POST-004(409), POST-005(403).

### P7. 내 게시물

GET /api/v1/members/me/posts

| Query | 필수 | 검증·동작 |
| --- | :---: | --- |
| type | X | LOST 또는 SHELTERING |
| status | X | ACTIVE 또는 CLOSED |
| cursor | X | listedAt, postId 기반 불투명 커서 |

ACTIVE와 CLOSED를 최신순으로 조회한다. 커서는 `listedAt`, `postId` 기반의 공개 목록과 같은
불투명 형식을 사용하지만, P7은 정렬 선택을 지원하지 않으며 `sort` 파라미터를 거부한다.

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "내 게시물을 조회했습니다.",
  "data": {
    "items": [
      {
        "postId": 1002,
        "type": "LOST",
        "source": "USER_POST",
        "status": "ACTIVE",
        "version": 6,
        "name": "망고",
        "species": "DOG",
        "eventDate": "2026-08-25",
        "listedAt": "2026-08-25T11:00:00Z",
        "publicLocation": "서울특별시 강남구",
        "thumbnailUrl": "/api/v1/photos/3101",
        "updatedAt": "2026-08-27T05:40:00Z"
      }
    ],
    "page": {"size": 10, "hasNext": false}
  }
}
~~~

인증 회원이 작성한 USER_POST만 반환한다.
오류: AUTH-002(401), COMMON-001(400), CURSOR-001(400).

### P8. 사용자 사진 조회

GET /api/v1/photos/{photoId}

`USER_UPLOAD` 사진의 HDFS 경로를 직접 공개하지 않고 API가 정규화된 JPEG를 streaming한다.
공공 `PUBLIC_URL` 사진은 기존 출처 URL 계약을 유지하며 P8 대상이 아니다.

- 연결 게시물이 `ACTIVE`이면 목록 표시를 위해 비로그인 조회를 허용한다.
- 연결 게시물이 `CLOSED`이면 작성자가 유효한 access token으로 요청한 경우에만 허용한다.
- `CLOSED` 게시물의 비작성자·비로그인 요청과 `DELETED`, 보존 만료, DB 비참조, 존재하지 않는
  사진은 존재 여부를 구분하지 않는 같은 `PHOTO-007`로 응답한다.
- 성공 응답은 `Content-Type: image/jpeg`, `X-Content-Type-Options: nosniff`, 저장된 checksum 기반
  `ETag`, `Cache-Control: no-store`를 포함한다.
- HDFS URI로 redirect하지 않는다. HDFS 읽기 장애는 `PHOTO-006`으로 응답하고 내부 경로·예외를
  노출하지 않는다.

정상 응답은 200 바이너리이며 JSON envelope가 없다. 오류 응답은 공통 JSON envelope를 사용한다.
오류: AUTH-002(401), PHOTO-006(503), PHOTO-007(404).

## 7. 매칭 API

### 7.1 매칭 경계

- 기준은 USER_POST/LOST뿐이다.
- 후보는 SHELTERING이며 isMatchable=true인 건이다.
- 사용자 보호 게시물과 공공 보호동물이 함께 후보가 된다.
- 사용자 후보는 `ACTIVE`만 허용한다. 공공 후보는 과거 입소분 소급 검색을 위해 `CLOSED`여도 `isMatchable=true`이면 허용한다.
- Spring Boot는 유사도를 계산하지 않고 match_candidate를 읽는다.
- `match_run`은 본인 활성 LOST 상세의 M2 요청에서만 생성한다. 등록·수정·사진 교체·수집·스케줄은 생성하지 않는다.
- SUCCEEDED 0건은 정상 후보 없음이고 FAILED와 다르다.
- 최신 실행이 실패·처리 중이어도 이전 성공 결과를 유지할 수 있다.
- 최신 성공 실행의 queryCaseVersion이 현재 게시물 version보다 작으면 이전 후보를 보존하되 `STALE`로 표시한다.

### M1. 매칭 상태·후보

GET /api/v1/posts/{postId}/candidates

**조회 권한:** 본인 `ACTIVE USER_POST/LOST`의 작성자만 조회 가능하다. 로그인만으로 다른 회원의
분석 상태·후보를 조회할 수 없다. 기준 게시물의 `user_post.member_id`와 인증 회원 ID를 비교하며,
`NOT_REQUESTED`·처리 중·성공·실패·이전 결과·`STALE` 모두 같은 권한 검사를 적용한다.

| 검사 순서 | 거부 조건 | 오류 |
| --- | --- | --- |
| 1 | 인증 정보 없음 | AUTH-002(401) |
| 2 | 기준 게시물 없음 또는 DELETED | POST-001(404) |
| 3 | 기준 게시물이 PUBLIC 출처 또는 SHELTERING 유형 | MATCH-001(409) |
| 4 | 기준 USER_POST/LOST의 작성자가 아닌 회원 | POST-002(403) |
| 5 | 본인 기준 게시물이 CLOSED | POST-003(409) |

검사를 통과한 뒤 실행 이력과 후보를 조회한다. 권한이 없는 요청에는 실행 ID·상태·후보를 반환하지
않는다. 기준 게시물의 ACTIVE 조건과 후보 게시물의 노출 조건은 별개다. 검색 대상으로 유지된
공공 CLOSED 후보는 아래 후보 정책에 따라 본인 ACTIVE LOST 결과에 포함될 수 있다.

Android는 본인 ACTIVE USER_POST/LOST 상세에서만 M1을 호출한다. 화면 복귀·재시도에도 같은
조건을 확인하고, 로그아웃·계정 전환·게시물 종료 또는 M1의 권한·상태 거부 시 폴링을 중단하고
기존 분석 상태·후보 표시를 제거한다. 클라이언트의 화면 제한과 별개로 서버가 매 요청을 검증한다.

처리 중 응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "매칭을 처리하고 있습니다.",
  "data": {
    "postId": 1002,
    "analysisStatus": "RUNNING",
    "latestRun": {
      "matchRunId": 503,
      "status": "RUNNING",
      "startedAt": "2026-08-27T05:40:01Z"
    },
    "recommendedPollAfterMs": 1000,
    "usingPreviousResult": false,
    "candidates": []
  }
}
~~~

후보 응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "유사 후보를 조회했습니다.",
  "data": {
    "postId": 1002,
    "analysisStatus": "SUCCEEDED",
    "latestRun": {
      "matchRunId": 503,
      "status": "SUCCEEDED",
      "candidateCount": 1,
      "completedAt": "2026-08-27T05:40:03Z"
    },
    "resultRunId": 503,
    "usingPreviousResult": false,
    "candidates": [
      {
        "rank": 1,
        "post": {
          "postId": 1001,
          "type": "SHELTERING",
          "source": "SHELTER",
          "status": "CLOSED",
          "name": null,
          "species": "DOG",
          "breedName": "푸들",
          "sex": "UNKNOWN",
          "color": "갈색",
          "eventDate": "2026-08-26",
          "publicLocation": "서울특별시 강남구",
          "thumbnailUrl": "https://public.example.com/animal.jpg",
          "shelter": {
            "name": "강남동물보호센터",
            "phone": "02-0000-0000"
          }
        }
      }
    ]
  }
}
~~~

- 분석을 요청한 적 없으면 `analysisStatus=NOT_REQUESTED`, `latestRun`과 `resultRunId`는 생략하고 candidates=[]를 반환한다.
- 정상 후보 없음은 analysisStatus=SUCCEEDED, latestRun.status=SUCCEEDED, candidateCount=0, candidates=[]이다.
- 최신 실패는 status=FAILED와 사전 합의한 안전한 errorCode를 반환한다.
- 이전 성공 결과가 있으면 resultRunId와 candidates를 유지하고 usingPreviousResult=true다.
- PENDING·RUNNING에서는 서버가 recommendedPollAfterMs로 다음 조회 권장 간격을 제공한다.
- Android는 NOT_REQUESTED를 분석 버튼 상태, PENDING·RUNNING을 처리 중, SUCCEEDED 1건 이상을 후보 목록, SUCCEEDED 0건을 후보 없음, FAILED를 처리 실패 화면 상태로 매핑한다. 이전 성공 결과를 함께 반환하면 최신 실행 상태와 이전 결과 사용 중임을 모두 표시한다.
- 최신 성공 결과가 현재 게시물 version보다 오래되면 analysisStatus=STALE로 반환한다. candidates와 resultRunId는 보존하고 자동 분석하지 않는다.
- STALE과 새 요청의 처리 중·실패 상태가 겹치면 `analysisStatus=STALE`을 우선하고 `latestRun.status`에는 실제 최신 실행 상태를 유지한다. 폴링은 `latestRun.status`와 `recommendedPollAfterMs`를 따른다.
- 최신 요청은 `created_at DESC, id DESC`, 최신 성공은 `completed_at DESC, id DESC`로 선택한다. `usingPreviousResult`는 두 실행 ID가 다를 때 true다.
- FAILED의 안전한 `errorCode`는 `MATCH_TIMEOUT` 또는 `MATCH_FAILED`다. 알 수 없는 worker 오류는 `MATCH_FAILED`로 반환하며 원문을 공개하지 않는다.
- DATA·AI 경로는 모델 버전별 임계값을 적용한 뒤 `total_score DESC, target_case_id ASC`로 정렬해 상위 20건만 저장한다. `candidateCount`와 `candidates` 길이는 0~20이다.
- M1은 `rank`와 후보 요약만 공개한다. 내부 `total_score`, `image_score`, `distance_km`, `time_gap_days`는 정렬·필터링·평가용이며 응답하지 않는다.
- Android는 원시 점수·백분율·정확한 거리 값을 표시하지 않고, 후보가 유사도 순이며 직접 확인이 필요하다고 안내한다.
- USER_POST 후보는 `ACTIVE && isMatchable=true`만 반환한다.
- SHELTER 후보는 `isMatchable=true`이면 `ACTIVE`와 `CLOSED`를 반환한다. `CLOSED`는 현재 보호 중이 아닌 과거 소급 후보임을 `status`와 상세의 `processState`로 알린다.
- 출처와 관계없이 `DELETED` 또는 `isMatchable=false`인 후보는 제외한다.
- 탈퇴 작성자의 USER_POST 후보도 제외한다. `latestRun.candidateCount`는 성공 시 저장한 후보 수이며, 현재 공개 정책으로 걸러진 `candidates` 길이보다 클 수 있다. 조회는 저장된 count·rank·후보를 수정하지 않는다.
- 후보 요약도 USER_POST는 작성자 닉네임을, SHELTER는 보호소 이름·공식 전화번호를 포함한다.
- 수치 임계값은 D4 평가 결과에 따라 모델 버전별 서버 설정으로 관리하며 공개 API 계약에 하드코딩하지 않는다.

오류: AUTH-002(401), POST-001(404), POST-002(403), POST-003(409), MATCH-001(409).

### M2. 유사도 분석 실행

POST /api/v1/posts/{postId}/match-runs

요청 본문은 없다. 이 API는 animal_case를 변경하지 않고 새 match_run만 생성하므로 게시물 version을 검증하거나 증가시키지 않는다.

응답 202:

~~~json
{
  "code": "SUCCESS",
  "message": "유사도 분석을 접수했습니다.",
  "data": {
    "postId": 1002,
    "matchRunId": 505,
    "status": "PENDING",
    "createdAt": "2026-08-27T06:10:00Z"
  }
}
~~~

작성자의 ACTIVE LOST만 분석을 요청할 수 있다.
PENDING/RUNNING 실행이 있으면 새 실행을 만들거나 충돌 오류를 반환하지 않고 기존 실행을 같은 202 형식으로 반환한다.
새 match_run을 만들며 이전 실행·후보를 덮어쓰지 않는다.
M2 접수 응답 p95는 500ms를 목표로 한다. DATA/AI 작업자는 `match_run`을 내구성 있는 큐로
사용해 `FOR UPDATE SKIP LOCKED`로 PENDING을 선점하고, `matchRunId` 단위로 결과 적재를
멱등 처리한다. RUNNING 5분 초과는 `FAILED(MATCH_TIMEOUT)`으로 전환한다. 초기 결과 완료 SLO는
1~3장 p95 10초, 10장 p95 30초이고 hard timeout은 60초다. 상세 계약은
[백엔드 보안·운영 정책](backend-security-operations-policy.md)을 따른다.
오류: AUTH-002(401), POST-001(404), POST-002(403), POST-003(409), MATCH-001(409).

## 8. 채팅 API

MVP 채팅은 활성 사용자 게시물의 작성자와 요청자 간 1:1 텍스트 대화, 참여자별 읽음 위치와 개인
채팅 푸시를 제공한다. 메시지와 읽음 상태의 최종 기준은 PostgreSQL이며 FCM은 새 메시지 알림과
화면 진입만 담당한다. Android는 대화 화면이 열려 있을 때 C3의 `afterMessageId` 증분 조회를
약 3초마다 호출하고 WebSocket·SSE는 사용하지 않는다.

### C1. 채팅방 조회 또는 생성

POST /api/v1/posts/{postId}/chat-room

요청 본문은 없다. 같은 사용자가 같은 게시물에 다시 요청하면 새 방을 만들지 않고 기존 방을 반환한다.

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "채팅방을 준비했습니다.",
  "data": {
    "chatRoomId": 7001,
    "postId": 1002,
    "otherMember": {"memberId": 101, "nickname": "망고보호자"},
    "lastMessageAt": null,
    "createdAt": "2026-08-28T05:10:00Z"
  }
}
~~~

- `source=USER_POST`, `status=ACTIVE`인 다른 회원의 게시물만 허용한다.
- 본인 게시물과 공공 보호동물에는 방을 만들 수 없다.
- `(postId, requesterMemberId)`당 한 방을 보장한다.

오류: AUTH-002(401), POST-001(404), CHAT-001(409), CHAT-002(409), CHAT-004(409).

### C2. 내 채팅방 목록

GET /api/v1/chat-rooms

| Query | 필수 | 검증·동작 |
|---|:---:|---|
| cursor | X | 서버 발급 불투명 커서 |

대화방의 `updatedAt` 최신순으로 10개씩 반환한다. 방 생성과 메시지 전송 시 이 값을 갱신한다.

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "채팅방 목록을 조회했습니다.",
  "data": {
    "items": [
      {
        "chatRoomId": 7001,
        "post": {
          "postId": 1002,
          "type": "LOST",
          "status": "ACTIVE",
          "name": "콩이",
          "species": "DOG",
          "breedName": "말티즈",
          "sex": "MALE",
          "thumbnailUrl": "/api/v1/photos/3101"
        },
        "otherMember": {"memberId": 101, "nickname": "망고보호자"},
        "lastMessage": {
          "messageId": 9001,
          "content": "비슷한 아이를 보호하고 있어요.",
          "createdAt": "2026-08-28T05:12:00Z"
        },
        "unreadCount": 1,
        "hasUnread": true,
        "readOnly": false
      }
    ],
    "page": {"size": 10, "hasNext": false}
  }
}
~~~

`post.name`·`breedName`·`sex`는 2026-09-25에 더했다. 같은 상대와 여러 게시물로 대화하면
목록에서 방이 여러 줄로 보이므로 어느 게시물인지 이 값들로 밝힌다. 이름과 품종은 등록할 때
비워 둘 수 있어 없으면 생략하고, 화면은 있는 것만 이어 붙여 한 줄로 부른다.

작성자 또는 요청자로 참여한 방만 반환한다. 연결 게시물이 `CLOSED` 또는 `DELETED`이면
`readOnly=true`다. `unreadCount`는 상대방이 보낸 메시지 중 현재 회원의 마지막 읽음 메시지
ID보다 큰 건수이고, 메시지가 없거나 모두 읽었으면 0이다. `hasUnread`는 `unreadCount>0`과 같다.
내가 보낸 메시지는 내 안 읽은 개수에 포함하지 않는다.

상대가 탈퇴한 경우 보존 중인 대화는 `readOnly=true`, 해당 닉네임은 `탈퇴한 회원`으로 반환한다.
사진 URL은 P8 접근 조건을 따른다. 종료 사진은 작성자에게만 90일 이내 제공하고 삭제·기간 경과·탈퇴 작성자의 사진은 생략한다.
커서는 회원과 조회 종류에 묶이며 목록은 고정 스냅샷이 아니다. 새 메시지로 커서 앞쪽으로 이동한 방은 첫 페이지 새로고침으로 반영한다.

오류: AUTH-002(401), CURSOR-001(400).

### C3. 메시지 목록

GET /api/v1/chat-rooms/{chatRoomId}/messages

| Query | 필수 | 검증·동작 |
|---|:---:|---|
| cursor | X | 이전 메시지 페이지를 위한 불투명 커서 |
| afterMessageId | X | 화면 갱신을 위한 마지막 수신 메시지 ID. 해당 방 메시지이며 1 이상 |

`cursor`와 `afterMessageId`는 함께 보낼 수 없다. 둘 다 없으면 최신 메시지부터 20개를 반환하고,
`cursor`는 과거 메시지를 같은 역순으로 이어서 조회한다. `afterMessageId`가 있으면 그 메시지보다
새로운 메시지를 오래된 순서부터 최대 20개 반환하며, 남은 메시지가 있으면
`page.nextAfterMessageId`로 이어서 조회한다. `nextCursor`와 `nextAfterMessageId`는 각 조회 방식에서
다음 페이지가 있을 때만 포함한다. Android는 `messageId`로 중복을 제거한다.

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "메시지를 조회했습니다.",
  "data": {
    "chatRoomId": 7001,
    "post": {
      "postId": 1002,
      "type": "LOST",
      "status": "ACTIVE",
      "name": "콩이",
      "species": "DOG",
      "breedName": "말티즈",
      "sex": "MALE",
      "thumbnailUrl": "/api/v1/photos/3101"
    },
    "otherMember": {"memberId": 101, "nickname": "망고보호자"},
    "readOnly": false,
    "myLastReadMessageId": 8980,
    "otherLastReadMessageId": 8975,
    "items": [
      {
        "messageId": 9001,
        "sender": {"memberId": 101, "nickname": "망고보호자"},
        "content": "비슷한 아이를 보호하고 있어요.",
        "createdAt": "2026-08-28T05:12:00Z"
      }
    ],
    "page": {"size": 20, "hasNext": false},
    "pollAfterMs": 3000
  }
}
~~~

`post`와 `otherMember`는 2026-09-25에 더했다. 화면이 머리줄에 상대 이름을, 그 아래에 어느
게시물의 대화인지 함께 보이기 위한 값이다. 전에는 받은 메시지에서 상대 이름을 찾아야 했고
상대가 아직 아무 말도 하지 않은 방에서는 알 수 없었다. `post`의 구성과 사진 공개 조건은 C2와
같다.

작성자와 요청자만 조회할 수 있다. 존재하지 않는 방과 참여자가 아닌 요청은 방 존재 여부를 노출하지 않도록 모두 CHAT-003(404)로 처리한다.

커서는 회원·방·조회 종류에 묶인다. 방 참여 여부를 확인한 뒤 커서와 `afterMessageId`를 검사한다.
`afterMessageId`가 해당 방 메시지가 아니면 CHAT-005(400)로 거부한다. 읽음 위치는 GET의
부수효과로 갱신하지 않고 C5를 사용한다. 내가 보낸 메시지의 `messageId`가
`otherLastReadMessageId` 이하이면 상대가 확인한 것으로 표시할 수 있다. 상대가 탈퇴하면 보존 중인 메시지는 읽기 전용으로 유지하고
발신자 닉네임을 `탈퇴한 회원`으로 표시한다.

오류: AUTH-002(401), COMMON-001(400), CHAT-003(404), CHAT-005(400), CURSOR-001(400).

### C4. 텍스트 메시지 전송

POST /api/v1/chat-rooms/{chatRoomId}/messages

~~~json
{"clientMessageId": "be521d93-8531-4fb1-9a42-55ed3a93d540", "content": "비슷한 아이를 보호하고 있어요."}
~~~

`clientMessageId`는 UUID 필수값이다. `content`는 NFC·앞뒤 공백 제거 후 1자 이상 1000자
이하이며 제어·형식 문자를 허용하지 않는다.

**욕설 마스킹(2026-09-21 QA)**: 저장 직전에 욕설 목록(`backend/src/main/resources/chat/profanity-ko.txt`)에 걸리는 구간을 같은 길이의 `*`로
바꾼다. 비교는 글자만 남긴 소문자 부분 문자열이라 "씨 발"·"씨.발"도 걸리고, 가린 결과가 응답·C3 조회·저장에 그대로 쓰인다(원문은 어디에도
남지 않는다). 멱등 해시(`request_hash`)는 원문으로 계산하므로 같은 원문 재요청은 마스킹과 무관하게 최초 메시지를 돌려준다. 짧아서 일상
낱말과 겹치는 단어("새끼", "씹")는 목록에 두지 않는다. 신고·차단은 범위 외(팀 결정 2026-09-21).

응답 201:

~~~json
{
  "code": "SUCCESS",
  "message": "메시지를 전송했습니다.",
  "data": {
    "messageId": 9001,
    "chatRoomId": 7001,
    "sender": {"memberId": 101, "nickname": "망고보호자"},
    "content": "비슷한 아이를 보호하고 있어요.",
    "createdAt": "2026-08-28T05:12:00Z"
  }
}
~~~

작성자와 요청자만 전송할 수 있다. 연결 게시물이 더 이상 `ACTIVE`가 아니면 기존 메시지는 조회할 수 있지만 새 메시지는 CHAT-004(409)로 거부한다.
같은 발신 회원의 같은 `clientMessageId`와 같은 내용 재요청은 최초 메시지를 201로 재반환한다.
같은 ID에 다른 내용이 오면 IDEMPOTENCY-001(409)로 거부한다.

다른 방에 같은 발신자의 키를 재사용해도 IDEMPOTENCY-001이다. 참여 권한 확인 후 같은 방·내용의
재요청은 게시물 종료/삭제 후에도 최초 메시지를 201로 반환하며 방 시각을 바꾸지 않는다.
상대가 탈퇴한 방은 새 메시지를 거부한다. 원문에도 1000 코드 포인트 상한을 먼저 적용한다.

오류: AUTH-002(401), COMMON-001(400), IDEMPOTENCY-001(409), CHAT-003(404), CHAT-004(409).

새 메시지와 수신자 한 명의 `CHAT_MESSAGE` Outbox 이벤트는 같은 DB 트랜잭션에서 저장한다.
정상 멱등 재요청은 기존 메시지를 반환하고 이벤트를 다시 만들지 않는다. 발송 worker는 수신자의
활성 인증 세션에 등록된 기기에 `type=CHAT_MESSAGE`, `chatRoomId`, `messageId`, `postId`만 담은 FCM data 메시지를
보낸다. 채팅 본문·닉네임·사진·위치는 payload에 포함하지 않는다. 등록 기기가 없거나 푸시가
실패해도 메시지 commit은 유지되며 상대방은 C2·C3으로 저장된 메시지를 확인한다.

### C5. 마지막 읽음 위치 갱신

PUT /api/v1/chat-rooms/{chatRoomId}/read

~~~json
{"lastReadMessageId": 9001}
~~~

`lastReadMessageId`는 필수인 1 이상의 정수이며 해당 방에서 화면에 표시한 마지막 메시지 ID다. 작성자는
`owner_last_read_message_id`, 요청자는 `requester_last_read_message_id`를 갱신한다. 새 값이 현재
값보다 클 때만 저장하며 같거나 작은 재요청은 현재 값을 그대로 반환한다.

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "채팅 읽음 위치를 갱신했습니다.",
  "data": {"chatRoomId": 7001, "lastReadMessageId": 9001}
}
~~~

종료·삭제 게시물의 읽기 전용 방도 보존 메시지를 읽을 수 있으므로 C5를 허용한다. 메시지가 해당
방에 속하지 않거나 존재하지 않으면 방·메시지 존재 여부를 추가로 노출하지 않는 CHAT-005(400)로
거부한다. 비참여자와 없는 방은 C3·C4와 같은 CHAT-003(404)다.

오류: AUTH-002(401), COMMON-001(400), CHAT-003(404), CHAT-005(400).

### N1~N2. 개인 푸시 기기 등록·해제

PUT /api/v1/members/me/push-devices/{installationId}

~~~json
{"platform": "ANDROID", "token": "FCM registration token"}
~~~

`installationId`는 앱 설치가 생성해 보관하는 UUID다. `token`은 공백이 아닌 4096자 이하 FCM
등록 토큰이다. N1은 **현재 `auth_session`의** `push_*` 등록을 갱신하는 멱등 요청이며 204 No
Content를 반환한다. 같은 설치나 토큰이 다른 세션에 있으면 해당 세션의 `push_*` 등록을 비우고
현재 인증 세션으로 원자적으로 재귀속해 이전 회원·세션에게 더 이상 발송하지 않는다. 토큰은 암호화하고 전용 HMAC 조회
값으로 중복을 막으며 원문·암호문·조회값을 로그, 오류, metric label, trace에 남기지 않는다.

성공 응답은 `204 No Content`이며 HTTP 본문과 `data` 필드가 없다. 노션 API 표의 Output Data에는
표기상 `{}`를 사용하되 실제 JSON 응답으로 전송하지 않는다.

DELETE /api/v1/members/me/push-devices/{installationId}

현재 인증 세션의 설치 등록을 비우며 등록이 없거나 다른 세션 소유 설치여도 204 No Content다.
다른 회원·세션 소유 설치는 존재 여부를 노출하지 않고 변경하지 않는다. 로그아웃은 앱의 로컬
인증정보 삭제 전에 N2를 best effort로 호출한다. N2가 실패해도 A4가 현재 세션을 폐기하면 worker
발송 대상에서 즉시 제외된다. 탈퇴와 영구 FCM 오류도 해당 세션의 등록을 비운다. 일일 요약의
`daily-intake-summary` 토픽 구독은 이 개인 기기 API와 별도다.

성공 응답은 `204 No Content`이며 HTTP 본문과 `data` 필드가 없다. 노션 API 표의 Output Data에는
표기상 `{}`를 사용하되 실제 JSON 응답으로 전송하지 않는다.

오류: AUTH-002(401), COMMON-001(400).

## 9. 공공데이터 상태·입양 탐색 API

### D1. 보호동물 데이터 갱신 상태

GET /api/v1/data-sources/shelter-animals/status

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "공공데이터 갱신 상태를 조회했습니다.",
  "data": {
    "sourceSystem": "ANIMAL_PROTECTION_API",
    "status": "SUCCEEDED",
    "lastSuccessfulAt": "2026-08-27T02:00:00Z",
    "lastSourceUpdatedAt": "2026-08-27T01:30:00Z",
    "lastAttemptAt": "2026-08-27T02:00:00Z",
    "fetchedCount": 1200,
    "insertedCount": 37,
    "updatedCount": 84,
    "shelterCount": 12,
    "failedCount": 0
  }
}
~~~

- `BACKFILL`은 D1 판정에서 제외한다. `DAILY_INCREMENTAL`과 첫 일일 실행 전 `INITIAL_FULL`만
  아래 대상 실행으로 본다.
- 대상 최신 실행이 RUNNING이면 status=RUNNING이다.
- 대상 최신 종료 실행이 실패면 status=FAILED와 마지막 성공 시각을 함께 제공한다. 실패를 후보 없음으로
  해석하지 않고 마지막 정상 데이터를 사용한다.
- 성공 이력이 없으면 status=NEVER_SYNCED이고 성공 시각은 생략한다.
- 그 외 마지막 성공한 DAILY_INCREMENTAL(아직 없으면 INITIAL_FULL) 완료가 현재보다 36시간
  이전이면 status=DELAYED, 아니면 SUCCEEDED다. Airflow 기준 실행 시각은 매일 22:30 KST다.
- `lastAttemptAt`은 최신 대상 실행의 `started_at`이다. `lastSuccessfulAt`,
  `lastSourceUpdatedAt`과 수집 집계는 같은 마지막 성공 실행에서 가져오며, 성공 이력이 없으면
  성공·원본 시각을 생략하고 집계는 0이다.

오류: AUTH-002(401).

### D2. 최신 일일 입소 요약

GET /api/v1/data-sources/shelter-animals/daily-summary

인증 없이 앱의 모든 사용자에게 제공한다. 최신 `SUCCEEDED` `DAILY_INCREMENTAL` 실행만 대상이며, 초기 전체 적재·재처리·실패 실행은 제외한다.

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "일일 입소 요약을 조회했습니다.",
  "data": {
    "summary": {
      "ingestionRunId": 9001,
      "summaryDate": "2026-09-01",
      "animalCount": 37,
      "shelterCount": 12,
      "completedAt": "2026-09-01T02:00:00Z"
    }
  }
}
~~~

대상 실행이 아직 없으면 `data: {"summary": null}`과 200을 반환한다. Android는 FCM 토픽
`daily-intake-summary` 푸시를 받지 못했어도 이 API를 호출해 같은 요약을 확인하며, 마지막 확인
`ingestionRunId`는 기기 로컬 DataStore에만 저장한다. 일일 요약의 기기별 발송·읽음 이력은
저장하지 않고 Android가 해당 토픽을 직접 구독한다. N1·N2 개인 기기 등록은 채팅 알림 전용이며
일일 요약 토픽 구독·확인 상태와 섞지 않는다.

`summaryDate`는 실행의 `requested_to_date`다. 과거 성공 실행에 값이 없으면 `completed_at`의
`Asia/Seoul` 날짜를 사용한다. 동일 완료 시각은 더 큰 `ingestionRunId`를 최신으로 본다.

### D3. 홈 인사이트

GET /api/v1/data-sources/shelter-animals/insights?regionCode=11440

인증 없이 제공한다. 홈의 가로 스크롤 카드 5장을 한 응답으로 채운다. `regionCode`는 P1과 같은 지역 범위다 — 5자리 시·군·구,
2자리 시·도 전체, **없으면 전국**(지역 조각도 전국 값으로 채우고 조각의 `regionCode`만 `null`). 형식이 틀리면 `COMMON-001`.

~~~json
{
  "code": "SUCCESS",
  "message": "홈 인사이트를 조회했습니다.",
  "data": {
    "dailyIntake": {"ingestionRunId": 9001, "summaryDate": "2026-09-14", "animalCount": 248, "shelterCount": 99, "completedAt": "2026-09-14T14:12:00Z"},
    "noticeClosing": {"regionCode": "11440", "withinDays": 3, "animalCount": 4},
    "weeklyIntake": {
      "regionCode": "11440", "from": "2026-09-09", "to": "2026-09-15", "total": 12,
      "days": [{"date": "2026-09-09", "dogCount": 1, "catCount": 0}, {"date": "2026-09-10", "dogCount": 2, "catCount": 1}]
    },
    "shelterOutcomes": {
      "windowStart": "2023-07-17", "windowEnd": "2026-07-17", "closedCount": 318442,
      "returnRate": 0.112, "adoptionRate": 0.281, "averageNoticeDays": 10.4, "computedAt": "2026-09-15T15:40:00Z"
    },
    "lostReports": {"yesterday": "2026-09-14", "yesterdayCount": 136, "regionCode": "11440", "regionLast30DaysCount": 3, "regionDogCount": 2, "regionCatCount": 1}
  }
}
~~~

조각별 규칙. "오늘"은 D2와 같은 `dataSourceClock`의 `Asia/Seoul` 날짜다.

| 조각 | 계산 | `null`이 되는 경우 |
| --- | --- | --- |
| `dailyIntake` | D2의 `summary`와 같다 | 대상 실행이 없을 때 |
| `noticeClosing` | 지역 범위 안(`EVENT.region_code`) `ACTIVE` 공공 보호동물 중 `notice_end_date`가 오늘~오늘+3일인 건수 | 없음 (전국이면 전국 값) |
| `weeklyIntake` | 같은 범위 공공 보호동물의 `event_date`(발견일) 오늘-6~오늘 7일 일별 개·고양이 건수. 없는 날은 0 | 없음 |
| `shelterOutcomes` | DATA 배치(`data/collector/shelter_outcomes.py`)가 `dashboard_stat(stat_key='shelter_outcomes', region_code='00000')`에 넣은 payload를 그대로 전달. 최근 3년 종결 건 기준 반환·입양 비율, 평균 공고 기간 | 배치 미실행, 또는 payload를 읽을 수 없을 때(카드만 비우고 200) |
| `lostReports` | `yesterdayCount`는 `lost_report.first_seen_date`가 어제인 전국 신고 수. 지역 값은 범위 안 공공 실종 신고의 `event_date`(실종일) 최근 30일 건수와 축종 분해 | 없음 (`regionCode` 필드만 전국이면 `null`) |

앱은 카드를 10초마다 다음으로 넘기고 사용자가 밀어도 넘어간다. `null` 조각은 "준비 중" 또는 "지역을 선택하면 보여 드려요" 카드로 그린다.

오류: COMMON-001(400).

### AD1. 입양 후보 카드 목록

GET /api/v1/adoptions

공고가 끝난 뒤에도 원천 상태가 `보호중`인 공공 보호동물을 조회한다. 1.6 앱은 로그인 뒤 호출하지만,
1.5 앱과 backend 선배포 호환성을 위해 비로그인 조회도 허용한다. 인증 요청은 해당 회원이 넘긴
동물을 제외하고 현재 찜 상태를 반환한다. 비로그인 요청은 회원별 넘김을 제외할 수 없으며
`favorited=false`를 반환한다. AD2~AD6은 계속 인증이 필요하다(2026-09-28).

| Query | 필수 | 검증·동작 |
| --- | :---: | --- |
| regionCode | X | 지역 범위. P1·D3와 같다 — **5자리** 시·군·구 코드는 `EVENT.region_code`와 정확히 일치, **2자리** 시·도 코드는 그 시·도 전체(접두 일치), **없으면 전국**. 그 외 형식은 `COMMON-001` (2026-09-22: 그 전엔 5자리 필수) |
| species | X | `DOG`, `CAT`. 생략하면 둘 다 조회 |
| sex | X | `MALE`, `FEMALE`. 생략하면 전체. 고르면 원천 성별이 `UNKNOWN`인 건은 제외한다 — 고른 성별이 맞는지 확인할 수 없기 때문이다. 그 외 값은 `COMMON-001` (2026-09-25 추가) |
| cursor | X | 서버 발급 불투명 커서. 같은 필터와 같은 KST 조회일에만 재사용 |

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "입양 후보 목록을 조회했습니다.",
  "data": {
    "asOfDate": "2026-09-17",
    "items": [
      {
        "postId": 1001,
        "species": "DOG",
        "breedName": "믹스견",
        "sex": "FEMALE",
        "color": "갈색",
        "publicLocation": "서울특별시 강북구",
        "thumbnailUrl": "https://cdn.example.com/animals/1001/0.jpg",
        "noticeEndDate": "2026-03-20",
        "daysSinceNoticeEnd": 181,
        "lastSyncedAt": "2026-09-17T01:30:00Z",
        "favorited": false
      }
    ],
    "page": {"size": 10, "hasNext": true, "nextCursor": "opaque-cursor"}
  }
}
~~~

후보는 아래 조건을 모두 만족해야 한다.

- `animal_case.source_type=PUBLIC`, `case_type=SHELTERING`, `status=ACTIVE`
- `shelter_animal.process_state_raw='보호중'`
- `notice_end_date IS NOT NULL`이고 서버 `Asia/Seoul` 기준 조회일보다 이전
- 공개 가능한 대표 사진 존재
- 요청 회원의 `adoption_swipe` 행이 없음 (2026-09-25 추가)

**이미 넘긴 동물은 서버가 뺀다.** 클라이언트가 받아서 걸러 내면 10건을 요청해 몇 건만 남는 페이지가
생기고, `hasNext`와 커서가 화면에 보이는 장수와 어긋난다. 찜을 해제해도 넘김 행은 남으므로 그 동물은
후보로 돌아오지 않는다 (AD5).

원천 상태는 정확한 허용 목록으로 판정한다. 알 수 없는 새 값과 종료 상태는 제외한다. 정렬은
`notice_end_date ASC, animal_case.id ASC`이고 11건을 읽어 10건만 반환한다. 커서는 조회일,
regionCode, species, sex, 마지막 `notice_end_date`와 `postId`를 결합한 불투명 값이다 (2026-09-25:
sex 추가). 날짜가 바뀌거나 필터가 다른 요청에 재사용하면 `CURSOR-001`이다.
`daysSinceNoticeEnd`는 `asOfDate - noticeEndDate`의 달력 날짜 차다.

`favorited`는 요청 회원의 현재 찜 여부다. 보호소 이름·전화번호·주소, `CURRENT` 위치, 정확한 위치,
좌표와 원천 상태 문자열은 포함하지 않는다. 보호소 연락처는 P2 상세에서 확인한다.
`breedName`과 `color`는 원천에 없으면 `null`이며 확인되지 않은 대체값을 만들지 않는다.

오류: AUTH-002(401), AUTH-003(401), AUTH-005(403), COMMON-001(400), CURSOR-001(400).

### AD2. 내 입양 찜 목록

GET /api/v1/members/me/adoption-favorites

| Query | 필수 | 검증·동작 |
| --- | :---: | --- |
| cursor | X | `favoritedAt`, `postId` 기반 서버 발급 불투명 커서 |

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "입양 찜 목록을 조회했습니다.",
  "data": {
    "asOfDate": "2026-09-17",
    "items": [
      {
        "postId": 1001,
        "species": "DOG",
        "breedName": "믹스견",
        "sex": "FEMALE",
        "color": "갈색",
        "publicLocation": "서울특별시 강북구",
        "thumbnailUrl": "https://cdn.example.com/animals/1001/0.jpg",
        "noticeEndDate": "2026-03-20",
        "daysSinceNoticeEnd": 181,
        "lastSyncedAt": "2026-09-17T01:30:00Z",
        "favoritedAt": "2026-09-17T03:00:00Z",
        "availability": "AVAILABLE"
      }
    ],
    "page": {"size": 10, "hasNext": false}
  }
}
~~~

`favoritedAt DESC, postId DESC`로 10건씩 반환한다. 현재 AD1 자격을 만족하면 `AVAILABLE`, 그렇지
않으면 `UNAVAILABLE`이다. 자격을 잃은 항목도 회원이 확인하고 해제할 수 있도록 찜 행을 유지한다.
목록 카드에 보호소 연락처·주소나 정확한 위치를 포함하지 않는다.

오류: AUTH-002(401), AUTH-003(401), AUTH-005(403), CURSOR-001(400).

### AD3. 입양 찜 추가

PUT /api/v1/members/me/adoption-favorites/{postId}

현재 AD1 후보 자격을 만족하는 공공 보호동물만 추가한다. 이미 찜한 항목의 재요청을 포함해 성공은
body 없이 204다. 존재하지 않거나 현재 후보 자격을 만족하지 않는 ID는 리소스 존재 여부를 구분하지
않고 `ADOPTION-001`로 응답한다.

오류: AUTH-002(401), AUTH-003(401), AUTH-005(403), ADOPTION-001(404).

### AD4. 입양 찜 해제

DELETE /api/v1/members/me/adoption-favorites/{postId}

현재 후보 자격과 관계없이 본인의 찜을 삭제한다. 찜이 없거나 반복 요청이어도 body 없이 204를
반환한다. 넘김 기록(AD5)은 건드리지 않으므로 해제한 동물이 AD1 후보로 돌아오지 않는다.

오류: AUTH-002(401), AUTH-003(401), AUTH-005(403).

### AD5. 넘긴 동물 기록

PUT /api/v1/members/me/adoption-swipes/{postId}

카드를 넘길 때마다 한 건 보낸다. 성공은 body 없이 204다. **어느 쪽으로 넘겼는지는 보내지 않고
저장하지도 않는다** — 찜 여부는 AD3·AD4가 정하고, 이 기록은 "이 동물을 이미 봤다"만 뜻한다.
같은 동물을 다시 보내도 최초 `swiped_at`을 바꾸지 않고 204다.

존재하지 않거나 `PUBLIC/SHELTERING` 게시 건이 아닌 ID는 리소스 존재 여부를 구분하지 않고
`ADOPTION-001`로 응답한다. AD3과 달리 **현재 후보 자격은 검사하지 않는다** — 카드를 보고 넘기는
사이에 원천 상태가 바뀔 수 있는데, 그때 기록이 거절되면 그 동물이 다음 조회에 다시 올라온다.

화면은 이 요청의 성패를 기다리지 않는다. 실패하면 그 동물이 한 번 더 올라올 수 있고, 다음 조회가
서버 기준으로 맞춰 준다.

오류: AUTH-002(401), AUTH-003(401), AUTH-005(403), ADOPTION-001(404).

### AD6. 내가 넘긴 동물 목록

GET /api/v1/members/me/adoption-swipes

| Query | 필수 | 검증·동작 |
| --- | :---: | --- |
| cursor | X | `swipedAt`, `postId` 기반 서버 발급 불투명 커서 |

응답 200:

~~~json
{
  "code": "SUCCESS",
  "message": "넘긴 동물 목록을 조회했습니다.",
  "data": {
    "asOfDate": "2026-09-25",
    "items": [
      {
        "postId": 1001,
        "species": "DOG",
        "breedName": "믹스견",
        "sex": "FEMALE",
        "color": "갈색",
        "publicLocation": "서울특별시 강북구",
        "thumbnailUrl": "https://cdn.example.com/animals/1001/0.jpg",
        "noticeEndDate": "2026-03-20",
        "daysSinceNoticeEnd": 189,
        "lastSyncedAt": "2026-09-25T01:30:00Z",
        "swipedAt": "2026-09-25T03:00:00Z",
        "favorited": true,
        "availability": "AVAILABLE"
      }
    ],
    "page": {"size": 10, "hasNext": false, "nextCursor": null}
  }
}
~~~

`swipedAt DESC, postId DESC`로 10건씩 반환한다. `availability`는 AD2와 같은 규칙으로 현재 AD1 자격을
만족하면 `AVAILABLE`, 아니면 `UNAVAILABLE`이다. 자격을 잃은 동물도 행을 유지한다 — 히스토리에서
아이가 말없이 사라지면 사용자가 자기 기록을 믿지 못한다.

`favorited`는 현재 찜 여부다. 화면이 이 목록에서 바로 찜을 해제하므로(AD4) 넘김 기록과 찜 상태를
한 번에 받아야 한다. 목록 카드에 보호소 연락처·주소나 정확한 위치를 포함하지 않는다.

오류: AUTH-002(401), AUTH-003(401), AUTH-005(403), CURSOR-001(400).

### V1. 앱 버전 안내

GET /api/v1/app/version?platform=android

앱이 시작할 때 한 번 부른다. 인증 없음. `platform` 은 생략하면 `android` 이고 다른 값은 COMMON-001(400)이다.

~~~json
{
  "code": "SUCCESS",
  "message": "요청에 성공했습니다.",
  "data": {
    "platform": "android",
    "latestVersionCode": 7,
    "minSupportedVersionCode": 5,
    "storeUrl": "https://m.onestore.co.kr/v2/ko-kr/app/0001009297"
  }
}
~~~

앱의 판단(2026-09-21 결정):

- 내 `versionCode` < `minSupportedVersionCode` → **강제 업데이트**. 닫을 수 없는 안내와 "업데이트" 버튼만 보인다. 서버 API 가 옛 앱과 호환되지 않게 바뀐 배포에서 서버가 이 값을 올린다.
- 내 `versionCode` < `latestVersionCode` → **권고**. "새 버전이 있어요" 안내를 하루 한 번 보이고 "나중에" 로 닫을 수 있다.
- 값이 0 이거나 조회에 실패하면 안내 없이 진입한다 — 업데이트 확인이 앱을 막으면 안 된다.
- "업데이트" 는 원스토어 앱 딥링크(`onestore://common/product/{PID}`)를 먼저 열고, 원스토어가 없으면 `storeUrl` 을 브라우저로 연다.

값의 출처는 `compose.prod.yml` 의 `APP_ANDROID_LATEST_VERSION_CODE`·`APP_ANDROID_MIN_VERSION_CODE`·`APP_ANDROID_STORE_URL` 이며 릴리즈 절차(docs/deploy-guide.md)에서 함께 올린다.

## 10. 오류 코드

### 10.1 공통 오류

../backend/docs/backend-common-settings.md의 코드를 그대로 사용한다.

| 코드 | HTTP | 의미 |
| --- | ---: | --- |
| COMMON-001 | 400 | 요청값 검증 실패 |
| COMMON-002 | 400 | 읽을 수 없는 JSON |
| COMMON-003 | 400 | 필수 파라미터 누락 |
| COMMON-004 | 400 | 파라미터 타입 불일치 |
| COMMON-404 | 404 | 존재하지 않는 API·리소스 |
| COMMON-405 | 405 | 지원하지 않는 메서드 |
| COMMON-406 | 406 | 제공할 수 없는 응답 형식 |
| COMMON-415 | 415 | 지원하지 않는 요청 형식 |
| COMMON-500 | 500 | 예상하지 못한 서버 오류 |

### 10.2 도메인 오류

| 코드 | HTTP | 안전한 메시지 |
| --- | ---: | --- |
| AUTH-001 | 401 | 아이디 또는 비밀번호가 올바르지 않습니다. |
| AUTH-002 | 401 | 인증이 필요합니다. |
| AUTH-003 | 401 | 로그인 세션이 만료되었거나 유효하지 않습니다. |
| AUTH-004 | 429 | 로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요. |
| AUTH-005 | 403 | 사용할 수 없는 계정입니다. |
| AUTH-006 | 503 | 인증 요청이 많습니다. 잠시 후 다시 시도해 주세요. |
| AUTH-007 | 403 | 접근 권한이 없습니다. |
| MEMBER-001 | 409 | 이미 사용 중인 아이디입니다. 다른 아이디를 입력해 주세요. |
| MEMBER-002 | 409 | 최신 개인정보 수집·이용 내용을 확인해 주세요. |
| MEMBER-003 | 409 | 새 비밀번호는 현재 비밀번호와 달라야 합니다. |
| PHONE-001 | 429 | 인증 요청 횟수를 초과했습니다. 잠시 후 다시 시도해 주세요. |
| PHONE-002 | 400 | 휴대전화 인증 정보가 유효하지 않거나 만료되었습니다. |
| PHONE-003 | 409 | 이미 사용 중인 휴대전화 번호입니다. 문의가 필요하면 고객센터로 연락해 주세요. |
| PHONE-004 | 503 | 인증 문자를 전송할 수 없습니다. 잠시 후 다시 시도해 주세요. |
| POST-001 | 404 | 게시물을 찾을 수 없습니다. |
| POST-002 | 403 | 이 게시물에 대한 요청 권한이 없습니다. |
| POST-003 | 409 | 현재 상태에서는 요청한 작업을 수행할 수 없습니다. |
| POST-004 | 409 | 게시물이 변경되었습니다. 최신 내용을 다시 확인해 주세요. |
| POST-005 | 403 | 공공 보호동물 정보는 사용자가 변경할 수 없습니다. |
| POST-006 | 400 | 게시물 유형과 출처 필터 조합이 올바르지 않습니다. |
| POST-007 | 409 | 최신 공개 동의 내용을 확인해 주세요. |
| IDEMPOTENCY-001 | 409 | 같은 요청 식별자가 다른 요청 내용에 사용되었습니다. |
| PHOTO-001 | 400 | 사진은 1장 이상 10장 이하로 등록해야 합니다. |
| PHOTO-002 | 415 | 지원하지 않는 사진 형식입니다. |
| PHOTO-003 | 400 | 처리할 수 없는 사진입니다. |
| PHOTO-004 | 413 | 사진 용량이 허용 범위를 초과했습니다. |
| PHOTO-005 | 422 | 사진 해상도가 허용 범위를 벗어났습니다. |
| PHOTO-006 | 503 | 사진 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요. |
| PHOTO-007 | 404 | 사진을 찾을 수 없습니다. |
| PHOTO-008 | 503 | 사진 처리 요청이 많습니다. 잠시 후 다시 시도해 주세요. |
| CURSOR-001 | 400 | 목록 커서가 유효하지 않습니다. |
| MATCH-001 | 409 | 잃어버렸어요 게시물에서만 후보를 조회할 수 있습니다. |
| CHAT-001 | 409 | 사용자 게시물에서만 채팅을 시작할 수 있습니다. |
| CHAT-002 | 409 | 본인 게시물에는 채팅을 시작할 수 없습니다. |
| CHAT-003 | 404 | 채팅방을 찾을 수 없습니다. |
| CHAT-004 | 409 | 종료된 게시물의 채팅에는 메시지를 보낼 수 없습니다. |
| CHAT-005 | 400 | 채팅 메시지 위치가 유효하지 않습니다. |
| ADOPTION-001 | 404 | 입양 후보를 찾을 수 없습니다. |

match_run.errorCode는 HTTP 오류와 별도다.
내부 예외·경로·스택을 노출하지 않는다.

## 11. 요구사항 추적표

| 요구사항 | API·계약 |
| --- | --- |
| UR-ACC-001 | A1, COMMON-001 |
| UR-ACC-004 | A0-1, A0-2, A1, PHONE-001~004 |
| UR-ACC-002 | A2, A3 |
| UR-ACC-003 | A4 |
| UR-ACC-005 | A5, 회원 개인정보 30일 파기 |
| 마이페이지(닉네임·비밀번호 변경) | A6, A7, A8, MEMBER-003 |
| UR-COM-001 | P1, 3.2 |
| UR-IMG-001 | OD-03, P3, P5, PHOTO-001 |
| UR-IMG-002 | OD-03, P3, P5, P8, PHOTO-002~007 |
| UR-IMG-003 | 사진 정책 §8, 형식·용량·해상도 외 품질 경고는 비차단 |
| UR-OWN-001 | P3 |
| UR-OWN-002 | OD-07, P3의 LOST 날짜·위치 의미 |
| UR-OWN-003 | M2, M1 |
| UR-OWN-004 | M1 본인 ACTIVE USER_POST/LOST 작성자 전용 조회, POST-002(403), OD-04 |
| UR-OWN-005 | M1의 SUCCEEDED + 빈 candidates |
| UR-OWN-006 | M1에서 P2로 이동 |
| UR-FND-001 | P3 |
| UR-FND-002 | OD-07~09, P3·P4의 `eventLocation`·필수 `currentLocation` 역할 계약, P2 상세 응답 |
| UR-FND-003 | SHELTERING 등록에서 역방향 분석 실행 없음 |
| UR-RPT-001 | P7 |
| UR-RPT-002 | P4, P5 |
| UR-RPT-003 | P6 |
| UR-RPT-004 | OD-08~09, P3, P4, P2 정확한 위치 공개 규칙, POST-007 |
| UR-RPT-005 | P2 chat, C1~C4, OD-05 |
| UR-RPT-007 | C2~C5, N1~N2, OD-11, 채팅 Outbox·FCM data 알림 |
| UR-RPT-006 | Should. 별도 유사 게시물 사전 안내 API는 미포함 |
| UR-DAT-001 | P1, M1 |
| UR-DAT-002 | 모든 요약의 source |
| UR-DAT-003 | D1 |
| UQR-004 | M1 rank 의미와 원시 점수 비노출·직접 확인 안내 |
| UQR-005 | MatchStatus, 후보 없음·실패 구분 |
| UQR-006 | Android 폼 보존 + M2 재분석 |
| UQR-007 | 3.2, A1, P1~P4, OD-08~09, C1~C4 |
| UQR-008 | P4·P5·P6·M1·M2 본인 권한 |
| UQR-009 | API 외부 수집 upsert, erd.md desertion_no 유일성 |
| UQR-001 | M2→M1 polling 계약, 접수 p95 500ms·결과 비동기 SLO |
| UQR-002 | API 외부 평가 지표, OD-04와 DATA·AI 계약 |
| UQR-003 | D1과 수집 파이프라인 커버리지 측정 |
| UQR-010 | Android 접근성·호환성 검증 범위 |
| UR-DAT-004, UR-DAT-005 | D1, D2, FCM 토픽·Android DataStore 계약 |
| 입양 탐색 P1 | AD1~AD6, `product/adoption-discovery-mvp.md`, `adoption_favorite`, `adoption_swipe` |

## 12. 구현·검증 기준

### 12.1 백엔드 책임

- Controller는 HTTP, Bean Validation, 상태 코드, 성공 래핑만 담당한다.
- Service는 비즈니스 규칙과 트랜잭션을 담당하고 DTO 또는 BusinessException을 반환한다.
- 도메인 오류는 도메인별 ErrorCode enum에 둔다.
- 목록 DTO와 상세 DTO를 분리한다.
- 사용자 P3·P4의 사건 날짜는 주입 가능한 `Clock`과 명시적인 `Asia/Seoul` 시간대로 검증한다.
- P3·P4는 위치별 공개 `false -> true`와 공개 유지 중 정확한 위치 변경에서 현재 `exact-location-v1`을 검증하며 누락·불일치는 POST-007로 구분한다.
- AuthenticationEntryPoint와 AccessDeniedHandler도 공통 오류 형식을 사용한다.
- P3·C4는 클라이언트 UUID와 request hash로 성공 재시도를 멱등 처리한다.
- M1은 기준 게시물의 출처·유형·소유권·ACTIVE 상태를 매 요청마다 확인한 뒤 실행 이력과 후보를 조회한다.
- M2는 본인 활성 LOST의 버튼 요청만 `match_run`을 만들고, 처리 중 재요청은 기존 실행을 202로 반환한다. 매칭 API는 match_candidate를 조회만 한다.
- 채팅 API는 방 참여자와 연결 게시물 상태를 매 요청마다 검사한다.
- C5 읽음 위치는 해당 방 메시지만 허용하고 참여자별 현재 값보다 뒤로 이동시키지 않는다.
- C4의 신규 메시지와 개인 알림 Outbox는 같은 트랜잭션에 저장하고 정상 멱등 재요청에서는
  이벤트를 중복 생성하지 않는다.
- 개인 FCM은 수신자의 활성 기기에만 본문 없는 data payload를 보내며 실패가 메시지 commit을
  되돌리지 않는다.
- 채팅 메시지 내용, FCM 토큰·암호문·조회값과 인증·위치 정보는 애플리케이션 로그에 남기지 않는다.
- AD1·AD3는 원천 상태를 허용 목록으로 판정하고 공고 종료일·대표 사진을 포함한 후보 자격을 같은
  도메인 정책으로 검사한다. 알 수 없는 상태는 제외한다.
- AD1은 `Asia/Seoul` 조회일을 커서에 묶고, AD2·AD6은 현재 데이터로 이용 가능 여부를 다시 계산한다.
- AD1은 인증 요청이면 해당 회원의 `adoption_swipe` 행이 있는 건을 쿼리에서 제외한다. 비로그인
  호환 요청은 회원별 제외를 적용하지 않는다. 11건을 읽어 `hasNext`를 계산하는 규칙은 제외 뒤
  결과에 적용한다.
- AD3·AD4·AD5는 같은 요청을 멱등 처리한다. 회원 탈퇴 뒤에는 조회를 즉시 차단하고 관계형
  파기에서 찜과 넘김을 함께 삭제한다.
- AD5는 `PUBLIC/SHELTERING` 게시 건인지만 확인하고 현재 후보 자격은 검사하지 않는다. AD3과
  판정 기준이 다르다는 점을 테스트로 고정한다.

### 12.2 필수 테스트

- 비로그인 목록·상세 성공, 비로그인 상세에 `exactLocation` 없음
- 목록 정렬별 10건, hasNext, 같은 listedAt에서 커서 중복·누락 없음
- P1 기본 `LATEST`, 명시한 `OLDEST`, 알 수 없는·중복 sort 거부와 다른 정렬 커서의 CURSOR-001
- SHELTERING 혼합 목록의 출처와 최신순·오래된순
- 입양 후보는 공고 종료 후 `보호중`인 사진 보유 공공 건만 포함하고 종료 전·당일, 알 수 없는 상태,
  종료 상태와 사진 없는 건을 제외
- 입양 후보의 지역·축종·성별 필터, 오래된 공고 종료일 순서, 같은 공고 종료일의 결정적 커서와 KST 자정 경계
- 성별을 고르면 원천 성별이 `UNKNOWN`인 건이 후보에서 빠짐
- 비로그인 AD1 요청 성공과 `favorited=false`, 인증 AD1의 회원별 찜·넘김 반영
- 입양 후보·찜·넘김 카드에서 보호소 연락처·주소, `CURRENT`, 정확한 위치, 좌표와 원천 상태 문자열 제외
- 찜 추가·해제 멱등성, 최신 찜 순서, 후보 자격 상실 뒤 `UNAVAILABLE` 유지와 해제 가능
- 넘김 기록 멱등성과 `swiped_at` 유지, 후보 자격을 잃은 건도 AD5가 받아들임
- 넘긴 동물이 같은 회원의 AD1 응답에서 제외되고 다른 회원에게는 그대로 보임
- 찜 해제 뒤에도 그 동물이 AD1 후보로 돌아오지 않음
- 넘김 목록의 최신 넘김 순서, `favorited` 정확성, 자격 상실 건의 `UNAVAILABLE` 유지
- 회원 탈퇴 즉시 찜·넘김 API 차단, 관계형 파기 시 `adoption_favorite`·`adoption_swipe` 제거
- 회원가입은 개인정보 수집·이용 동의 `true`, 인증된 전화번호와 가입용 증명을 요구하며, 모든 응답·로그에 전화번호 평문·조회 해시 없음
- 가입용 증명은 70자 `pv1.{selector}.{secret}` 불투명 토큰이고 selector·secret hash·번호 HMAC의 binding과 10분 TTL·1회 소비를 검증
- A0-3은 비로그인 요청에서 미사용 ID에 `available=true`, NFC·대소문자 canonical 중복 ID에 `available=false`와 수정 안내를 반환하고 형식 오류·누락을 거부
- A0-3 확인 뒤 동시 가입이 발생해도 A1의 재검사와 `idx_member_login_id_canonical`이 중복 회원 생성을 차단
- OTP는 전용 키 HMAC으로만 Redis에 저장하고 공급자 불확실 응답에서 자동 재발송·이전 코드 조기 폐기가 없음
- 회원가입에서 `privacyCollectionAgreed`가 누락·`null`·`false`면 `400 COMMON-001`과 `data.fieldErrors`로 거부
- 회원가입 성공 시 현재 `privacy-collection-v1`과 `privacy_collection_consented_at`이 요청 처리 시각의 UTC 기준 `TIMESTAMPTZ` 값으로 저장
- 다른 개인정보 고지 버전은 `MEMBER-002`로 거부하고, 실제 고지 데이터 목록이 가입 정보·게시물·사진·위치·선택 좌표·채팅과 목적·보유 기간을 포함
- 회원가입 비밀번호는 NFC 기준 7자·65자, 공백·제어·형식 문자와 취약 목록 전체 일치를 거부하고 8자·64자와 조합 규칙 없는 한글·영문·숫자·일반 특수문자를 허용
- A1·A2 body의 `Content-Length`·chunked 8,193바이트와 loginId·password의 NFC 전 257 코드 포인트를 정규화 전에 거부
- 로그인 ID의 NFC·대소문자 변형은 같은 canonical 값으로 저장·조회하고 하나의 계정 실패 카운터로 합침
- Android는 NFC 기준 비밀번호 확인값이 다르면 A1을 호출하지 않고 A1 JSON에 `passwordConfirm`을 포함하지 않음
- 비밀번호마다 다른 16바이트 salt와 `{argon2id-v1}`·64 MiB·3회·병렬도 4·32바이트 hash 매개변수를 저장하며 원문·정규화 값·hash를 응답·로그에 남기지 않음
- A1·A2 비밀번호에 같은 NFC 전처리를 적용하고 아이디 미존재·비밀번호 불일치·비밀번호 형식 불일치는 같은 `AUTH-001`과 현재 프로필의 dummy hash 1회 경로 사용. dummy 비교 결과는 버리고 실패 횟수 증가
- 계정의 15분 내 5번째 실패와 IP의 10분 내 20번째 실패부터 15분 동안 `AUTH-004`·429·`Retry-After`를 반환하며, 성공 시 계정 횟수만 초기화하고 영구 잠금은 하지 않음
- A1 인코딩·A2 실제/dummy 비교·프로필 상향 재인코딩은 같은 Argon2 공용 실행권으로 동시 최대 4개만 실행하고 A2는 실행권 획득 뒤 제한을 다시 확인하며, 포화 시 `AUTH-006`·`Retry-After: 1` 반환
- A1 실행권 포화 시 가입 증명을 선점하지 않고, 실행권 획득 뒤 증명 선점 실패 시 실행권을 즉시 반환
- A1 증명 선점 성공 여부와 Argon2id 인코딩 성공·실패·예외의 모든 분기에서 공용 실행권을 정확히 한 번 반환하고, DB 트랜잭션 전 명확한 실패는 같은 요청 ID의 증명만 복구
- direct peer와 신뢰 proxy의 단일 forwarded IP만 canonical화해 제한하고 위조 header·IPv4-mapped IPv6 우회 차단
- SecLists 차단 파일의 고정 commit·100,000줄·828,498바이트·SHA-256 검증 실패 시 애플리케이션 시작 거부
- 비로그인 목록의 USER_POST·SHELTER 출처 배지와 작성자 닉네임·보호소 이름·공식 전화번호 미포함
- 비로그인 목록의 시·군·구 및 읍·면·동 수준 `EVENT.publicLocation`, 보호소 주소·정확한 주소·건물명·좌표 미포함
- 상세의 `eventLocation`·SHELTERING `currentLocation` 각각에 대한 정확한 위치 공개 동의 조건부 응답 및 좌표 미반환
- P3·P4의 사용자 `eventDate`는 고정 `Clock`으로 `Asia/Seoul` 어제·오늘을 허용하고 내일을 `COMMON-001`로 거부
- P3·P4는 공개가 true인 각 위치 역할에 현재 `exact-location-v1`을 요구하고 누락·다른 값은 `POST-007`로 거부
- P4의 위치별 `false -> true`와 공개 유지 중 정확한 위치 변경은 현재 버전을 요구하고, `true -> false`는 버전 없이 허용
- 정책 버전 상향 뒤 이전 버전 동의 위치는 공개 flag가 true여도 다른 회원에게 노출하지 않음
- P1의 `regionCode`는 `EVENT.region_code`와 정확히 일치하고 `publicLocation` 문자열 검색에 의존하지 않음
- 행정구역 CSV의 버전·SHA-256 누락·불일치는 위치 쓰기 readiness 실패, 폐지 코드는 신규 등록 거부, 기존 저장 표시값은 유지
- 비로그인·목록·검색·후보·공공 게시물과 다른 회원의 종료·삭제 게시물에는 정확한 위치가 없고, 다른 회원은 활성 사용자 게시물의 동의한 역할만 확인. 작성자 관리용 상세는 비공개·종료 상태의 자신의 저장 위치와 공개 상태를 반환하되 좌표 제외
- 사용자·공공 상세 응답 분기
- 공공 상세에 보호센터 공식 연락처 유지
- 사진 0장·11장, 거짓 MIME·확장자, JPEG·PNG 외 형식, 손상·다중 프레임 거부
- 사진당 10 MiB·요청 전체 50 MiB와 방향 보정 후 각 변 64~10,000px·총 4천만 픽셀 경계 검증
- 사용자 사진의 방향 보정·메타데이터 제거·sRGB JPEG 정규화와 HDFS replication 2 검증
- P3·P5의 HDFS·DB 실패에서 부분 반영이 없고 P5 실패 시 기존 사진 세트 유지
- ACTIVE 사진 비로그인 조회, CLOSED 작성자 전용, DELETED·비참조 사진 404와 HDFS 경로 미노출
- 다른 회원의 수정·사진·종료·분석 실행 403
- version 충돌 409
- 사용자 게시물 종료 즉시 목록·후보에서 제외
- 종료된 USER_POST 후보 제외, `CLOSED && isMatchable=true`인 SHELTER 소급 후보 유지
- LOST 등록·수정·사진 변경만으로 match_run이 생성되지 않고, 본인 버튼 요청 M2에서만 생성
- P3의 같은 회원·같은 `clientRequestId`·같은 payload 재요청은 같은 게시물을 반환하고 다른 payload는 IDEMPOTENCY-001
- SHELTERING 등록 시 역방향 match_run 없음
- M1은 본인 ACTIVE USER_POST/LOST만 200이며 미인증 401, 없는/DELETED 기준 게시물 404, 타인 LOST 403, PUBLIC·SHELTERING 및 본인 CLOSED 기준 게시물 409
- 타인 LOST의 NOT_REQUESTED·PENDING·RUNNING·SUCCEEDED·FAILED·이전 성공 결과·STALE 모두 POST-002로 거부하고 실행 ID·분석 상태·후보가 오류 응답에 없음
- 기준 LOST가 CLOSED인 경우와 후보가 CLOSED인 경우를 구분: 기준 CLOSED는 거부하되 검색 가능한 공공 CLOSED 후보는 유지
- Android는 타인 상세에서 M1을 호출하지 않고 로그아웃·계정 전환·종료·권한 거부 시 폴링 중단 및 기존 결과 표시 제거
- PENDING, RUNNING, SUCCEEDED 0건, SUCCEEDED N건, FAILED 구분
- 처리 중 M2 재요청은 별도 처리 중 오류 없이 같은 실행을 202로 반환하고, worker 중복 전달·5분 watchdog·60초 timeout이 상태를 중복 완료하지 않음
- 최신 실패 시 이전 성공 결과 유지
- 임계값 적용 후 후보 0~20건, rank 오름차순과 동점 `target_case_id` 정렬
- 후보 응답에 내부 원시 점수·백분율·거리·시간차 값 없음
- 로그아웃·만료·회전된 갱신 토큰 재사용 거부와 selector가 같은 재사용 시 세션 폐기
- A5 즉시 세션·공개·매칭 차단, 03:30 단일 파기 job의 100건 경계·중복 실행, 30일 계정 보호값 파기와 사용자 생성 데이터·HDFS 정리 재시도
- 동일 갱신 토큰 동시 요청은 한 건만 성공하고 refresh 만료 시각은 최초 로그인 + 30일로 유지
- JWT의 RS256·kid·필수 claim·issuer·audience·client ID·15분 TTL·30초 clock skew 검증
- 정상 서명의 JWT도 `sid` 세션이 만료·폐기됐거나 `sub` 회원과 다르면 거부하고 로그아웃
  직후 같은 JWT의 보호 API 요청은 `AUTH-003` 반환
- Android access token 메모리 보관, refresh token Keystore 암호화·백업 제외 저장,
  single-flight 갱신과 원 요청 최대 1회 재시도
- 공공데이터 실패와 마지막 성공 시각 동시 제공
- BACKFILL을 제외하고 대상 최신 실행 RUNNING, 최신 종료 실행 FAILED, 성공 이력 없음 NEVER_SYNCED, 마지막 성공 실행 36시간 초과 DELAYED 순으로 판정
- 성공한 DAILY_INCREMENTAL의 신규 동물·보호소 수만 D2에 반환하고 초기 적재·재처리·실패 수집은 제외
- D2는 비로그인 호출 가능하며 FCM 미수신 기기도 같은 ingestionRunId를 앱 내에서 1회 표시
- 같은 게시물·요청자의 채팅방 생성 멱등성
- 본인 게시물·공공 보호동물·종료 게시물의 신규 채팅방 거부
- 채팅 비참여자의 방·메시지 조회·전송 404
- 채팅 메시지 공백·1000자 초과 거부와 20건 커서 조회, 같은 발신자·`clientMessageId` 재시도 멱등성
- `afterMessageId` 증분 조회에서 20건 초과 신규 메시지의 연속 페이지 누락 없음
- 참여자별 읽음 위치 단조 증가, 방별 안 읽음 개수와 상대방 읽음 표시
- 메시지·Outbox 원자 저장, FCM 실패 후 재시도·영구 오류 토큰 정리와 중복 알림 억제
- 종료 게시물의 기존 채팅 조회 성공·메시지 전송 거부
- 내부 메시지와 민감정보 미노출

## 13. 팀 검토 체크리스트

- [ ] OD-02 비밀번호, OD-06 수집 지연, OD-07 날짜, OD-08 공개 안내 버전과 OD-09 역할 기반 위치 계약을 확인하고, D4 임계값 평가 대기의 결정 시점을 확인했다.
- [ ] Android 담당이 요청 필드와 화면 상태 전환을 검토했다.
- [ ] DATA·AI 담당이 매칭 상태, 점수, Top-K 계약을 검토했다.
- [ ] 회원 전화번호 인증·중복 활성 계정 제한·비노출, 정확한 위치 공개 동의와 내부 채팅 방식을 승인했다.
- [ ] 각 엔드포인트의 Method, Path, 인증, 정상 상태를 확인했다.
- [ ] 모든 Must 요구사항이 11장 추적표에 연결되었다.
- [ ] OpenAPI 작성 시 이 문서를 기준으로 동일 계약을 옮긴다.
