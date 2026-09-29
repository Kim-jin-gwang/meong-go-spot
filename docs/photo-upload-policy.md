# 사진 업로드·저장소 정책

## 1. 목적과 범위

이 문서는 사용자 게시물 등록(P3)과 사진 전체 교체(P5)의 입력 검증, 정규화, HDFS 저장,
조회 URL과 수명 주기를 정의하는 단일 상세 기준이다. 공개 API의 요약 계약은
[API 명세](api-spec.md)의 OD-03·P3·P5·P8을 따르며, 서로 다르면 이 문서의 상세 기준에
맞춰 함께 수정한다.

보호센터 공공 이미지의 기존 일일·역사 TAR 적재 방식은 유지한다. 이 정책에서 새로 정하는
개별 파일 저장·교체·삭제 절차는 `USER_POST` 사진에 적용한다.

## 2. 확정 요약

| 항목 | 정책 |
| --- | --- |
| 사진 수 | 게시물당 1장 이상 10장 이하, 요청 순서 유지, 0번이 대표 사진 |
| 입력 형식 | `image/jpeg`, `image/png`만 허용 |
| 입력 용량 | 사진당 10 MiB 이하, multipart 요청 전체 50 MiB 이하 |
| 메타데이터 | P3 JSON `payload` part 64 KiB 이하, `clientRequestId` UUID 필수 |
| 입력 해상도 | EXIF 방향 보정 후 가로·세로 각각 64px 이상 (2026-09-21 QA 결정으로 512px 하한 완화 — 메신저로 받아 줄어든 사진도 등록) |
| 안전 상한 | 한 변 10,000px 이하, 총 40,000,000픽셀 이하, 정지 이미지 1프레임 |
| 저장 형식 | 방향 보정·메타데이터 제거·sRGB 변환 후 JPEG 품질 90, 긴 변 최대 4,096px |
| 사용자 사진 경로 | `/data/user/images/{postId}/{photoId}.jpg` |
| 사용자 사진 복제 | HDFS replication 2 |
| 조회 URL | `/api/v1/photos/{photoId}`. HDFS 경로와 WebHDFS 주소는 외부에 노출하지 않음 |
| 교체 방식 | 새 사진 전체 저장과 DB 교체 성공 후 이전 사진 정리. 부분 교체 없음 |
| 종료·삭제 | 일반 종료는 90일 보존. 회원 탈퇴는 이보다 우선해 30일 이내 삭제하고 실패 시 완료까지 재시도 |
| 품질 안내 | 흐림·가림·동물 식별 어려움은 경고하되 업로드 차단 조건으로 사용하지 않음 |

용량 단위의 MiB는 1,048,576바이트다. 따라서 장당 상한은 10,485,760바이트이고 multipart
요청 전체 상한은 52,428,800바이트다. 요청 전체 상한에는 boundary와 `payload` part를 포함한
HTTP 본문 전체가 포함된다.

## 3. 입력 계약과 검증

### 3.1 허용 형식

- P3·P5의 `photos` part는 각각 `Content-Type: image/jpeg` 또는 `image/png`여야 한다.
- 서버는 part의 Content-Type, 파일명이 아니라 실제 매직 바이트와 이미지 디코더 결과를
  최종 기준으로 사용한다. 선언 MIME와 실제 형식이 다르면 거부한다.
- JPEG·PNG라도 디코딩할 수 없거나 잘렸으면 처리 불가 사진으로 거부한다. PNG 는 IEND 로 끝나야 하고 APNG 는 거부한다.
  **JPEG 는 EOI 뒤에 덧붙은 데이터를 허용한다**(2026-09-23 — 울트라 HDR 게인맵·MPF 두 번째 이미지·모션 포토 영상을 폰 카메라가
  기본으로 붙이므로, 정확히 EOI 에서 끝나야 한다는 규칙이 일반 사용자 사진 대부분을 막았다). 첫 이미지만 디코딩해 다시 인코딩하므로
  덧붙은 데이터는 저장되지 않는다.
- 원래 파일명과 확장자는 저장 키, 응답, 로그에 사용하지 않는다.
- HEIC·HEIF·WebP·AVIF 등 Android가 선택할 수 있지만 서버가 받지 않는 형식은 Android가
  EXIF 방향을 적용한 JPEG로 변환한 뒤 업로드한다. 서버 검증은 변환 여부와 관계없이 동일하다.
- **Android는 올리기 전에 사진을 줄인다**(2026-09-23 — 열 장 25MB 업로드가 타임아웃으로 실패한 QA 뒤). 긴 변이
  2,048px를 넘거나 1.5MB를 넘거나 PNG면 EXIF 방향을 적용해 **긴 변 2,048px JPEG 품질 90**으로 다시 인코딩한다
  (`core/media/UploadPhotoShrinker`). 그보다 작은 JPEG는 그대로 보낸다. 서버 저장 상한(4,096px)보다 작지만 매칭 모델
  입력(수백 px)과 폰 화면에는 충분하고 용량은 약 1/4이다. 서버 검증·정규화는 그대로 적용된다.

### 3.2 용량·해상도

서버는 다음 순서로 값싼 검증부터 수행한다.

1. `photos` part 수가 1~10인지 확인한다.
2. multipart 본문을 streaming으로 읽으며 전체 50 MiB를 초과하면 즉시 중단한다.
3. 각 part가 10 MiB 이하인지 확인한다.
4. 선언 MIME와 매직 바이트가 JPEG·PNG로 일치하는지 확인한다.
5. 제한된 디코더로 정지 이미지 한 프레임을 디코딩하고 방향 보정 후 크기를 검사한다.
6. 가로·세로가 각각 64px 이상, 10,000px 이하이고 총 픽셀이 40,000,000 이하인지 확인한다.

압축된 HTTP request body는 받지 않는다. 10장 중 한 장이라도 실패하면 P3·P5 요청 전체를
실패시키고 DB나 최종 HDFS 경로에 일부 사진만 남기지 않는다.

### 3.3 서버가 최종 기준이다

Android는 사진 수·형식·용량·해상도를 먼저 검사해 즉시 안내하지만 서버가 모든 검증을 다시
수행한다. 클라이언트가 전달한 파일명, MIME, 이미지 크기와 EXIF를 신뢰하지 않는다.

## 4. 저장 전 정규화

검증을 통과한 사용자 사진은 다음 순서로 정규화한 결과만 영구 저장한다.

1. EXIF orientation을 실제 픽셀 방향에 적용한다.
2. EXIF의 GPS·기기·촬영 시각을 포함한 메타데이터를 모두 제거한다.
3. 색 공간을 sRGB로 변환한다. PNG에 alpha가 있으면 흰색 배경에 합성한다.
4. 긴 변이 4,096px를 넘으면 종횡비를 유지해 축소한다. 작은 사진은 확대하지 않는다.
5. JPEG 품질 90의 단일 프레임 `.jpg`로 인코딩한다.
6. 정규화된 바이트의 SHA-256, 바이트 수, 가로·세로를 계산해 사진 메타데이터로 저장한다.

수신한 원본 바이트는 요청 처리용 임시 파일에서만 사용하고 정상 처리 뒤 즉시 제거한다.
AI는 사용자가 보낸 원본이 아니라 HDFS의 정규화 결과를 입력으로 사용한다.

## 5. HDFS 저장 경계와 경로

모든 서비스 이미지 바이너리의 보존 저장소는 HDFS다. 다만 접근 특성에 따라 공공 이미지는
기존 TAR 묶음, 사용자 업로드는 개별 파일을 사용한다.

| 데이터 | HDFS 경로 | 형식·수명 |
| --- | --- | --- |
| 공공 일일 이미지 | `/data/shelter/images/dt=YYYY-MM-DD/images-<ms>.tar` | 기존 일일 수집 계약 유지, 클러스터 기본 복제 2 |
| 공공 역사 이미지 | `/data/shelter/images-backfill/yyyymm=YYYYMM/images-YYYYMM-NNNN.tar` | 기존 백필 계약 유지, 다시 수집 가능한 파생물이므로 복제 1 |
| 사용자 최종 사진 | `/data/user/images/{postId}/{photoId}.jpg` | 정규화 JPEG 개별 파일, 복제 2 |
| 사용자 임시 사진 | `/data/user/images/.staging/{requestId}/{photoId}.jpg` | 최종 반영 전 staging, 1시간 뒤 정리 대상 |

`postId`와 `photoId`는 서버가 할당한 양의 정수이고 staging `requestId`는 요청마다 서버가
생성한 128비트 이상 난수 식별자다. P3의 클라이언트 `clientRequestId`를 HDFS 경로에 쓰지 않는다.
사용자 입력을 경로에 포함하지 않는다. 사용자 사진은 다시 내려받을 수 없는 데이터이므로 공공
역사 백필과 달리 replication 2를 강제한다. replication은 노드 장애 가용성 수단이지 백업이 아니다.

`animal_photo.storage_uri`에는 사용자 사진의 HDFS 절대 경로를 저장한다. 이 값과 NameNode,
DataNode, WebHDFS 주소는 API 응답·오류·로그에 노출하지 않는다. 공공 사진의 HDFS TAR은 DATA
파이프라인의 원본·AI 입력 보존 계약이고, 기존 `PUBLIC_URL`은 화면 표시를 위한 출처 URL로
구분한다.

`PUBLIC_URL`은 DB와 API 응답에 **원본 URL 그대로** 남지만, 앱은 표시할 때 서버 1의 캐시 프록시
`https://api.meonggo.shop/img/…`로 바꿔 받는다(`ApiImageUrlResolver`, 2026-09-14). 원본 서버가 느리고
(TTFB p90 6초) 캐시 헤더를 주지 않기 때문이다. 프록시는 경로 모양이 맞는 공공 사진만 통과시키고 원본을
30일 보관한다 — 설계·측정·이용조건은 [deploy-guide.md](deploy-guide.md) "공공 이미지 캐시 프록시".

## 6. 생성·교체·삭제 수명 주기

PostgreSQL 트랜잭션과 HDFS 작업은 하나의 원자 트랜잭션이 될 수 없으므로, 새 사진을 먼저
불투명 키에 저장하고 DB가 그 키를 공개 가능 상태로 만드는 순서를 사용한다.

### 6.1 P3 게시물 등록

1. 64 KiB 이하 payload의 `clientRequestId` UUID와 메타데이터를 검증하고 모든 사진을
   정규화해 checksum을 계산한다. canonical metadata와 checksum 목록의 `requestHash`를 만든다.
2. 같은 회원·`clientRequestId`의 성공 행이 있으면 같은 hash일 때 기존 게시물을 반환하고,
   다른 hash면 `IDEMPOTENCY-001`로 거부한다.
3. 신규 요청이면 `postId`, 각 `photoId`를 미리 할당한다.
4. 정규화 결과를 같은 HDFS의 staging 경로에 덮어쓰기 없이 저장하고 close까지 성공했는지
   확인한다.
5. staging 파일을 최종 경로로 rename한다. 최종 경로가 이미 있으면 덮어쓰지 않고 실패한다.
6. 회원·`clientRequestId` 유일 제약과 request hash를 포함해 게시물·사진 메타데이터를 한 DB
   트랜잭션으로 저장한다. 경합으로 유일 제약을 만났으면 기존 hash를 비교해 같은 응답 또는 충돌로 처리한다.
7. DB commit 뒤 staging 잔여 파일을 제거한다.

HDFS 저장이나 DB commit이 실패하면 게시물은 생성하지 않는다. 해당 요청이 만든 staging·최종
파일은 즉시 정리를 시도하고, 실패한 정리는 6.4의 비참조 파일 정리가 회수한다. DB에 사진
메타데이터가 없으므로 실패 중 생긴 파일은 P8로 조회할 수 없다.

### 6.2 P5 사진 전체 교체

1. 현재 게시물 소유권·상태·`version`을 먼저 확인한다.
2. 최종 사진 1~10장을 P3와 동일하게 새 `photoId`의 staging·최종 경로에 저장한다.
3. DB 트랜잭션에서 `version`을 다시 비교하고 기존 `animal_photo` 행 전체를 새 행으로 교체한다.
4. commit에 성공한 뒤 이전 HDFS 파일을 삭제한다. 실패하면 기존 DB 행과 기존 사진을 유지하고
   새 파일만 정리한다.

기존 경로를 덮어쓰지 않고 항상 새 `photoId`를 사용하므로 교체 도중 읽는 요청은 완전한 이전
세트 또는 완전한 새 세트 중 하나만 본다. 이전 파일 즉시 삭제가 실패해도 응답 성공을 되돌리지
않고 비참조 파일 정리에서 재시도한다.

### 6.3 종료·최종 삭제

- 사용자 게시물을 종료하면 즉시 `CLOSED`, `is_matchable=false`로 바꾸고 공개 목록·새 후보군과
  비로그인 사진 조회에서 제외한다. 사진은 본인 이력과 함께 90일 보존한다.
- 90일 보존 만료 또는 게시물 최종 삭제 시 DB 공개·참조를 먼저 차단하고 HDFS 사진을 별도
  정리 작업으로 삭제한다.
- 회원 탈퇴는 계정과 게시물 접근을 즉시 차단하고 관계형 개인정보 파기 트랜잭션을 우선한다.
  일반 종료의 90일 보존보다 탈퇴 30일 파기가 우선한다. HDFS 장애가 회원 개인정보 파기를
  지연시키지 않는다. 관계형 파기 트랜잭션은 `USER_UPLOAD` 경로를
  `member_photo_erasure_task`에 먼저 적재한 뒤 사진 메타데이터를 제거한다. 별도 작업이 HDFS
  삭제를 성공할 때까지 재시도하고 탈퇴 후 30일 목표 초과를 경보한다.

### 6.4 비참조 파일 정리

- staging 파일은 HDFS 수정 시각 기준 1시간이 지나면 삭제한다.
- 최종 사용자 사진 중 `animal_photo.storage_uri`에서 참조하지 않는 파일은 생성 24시간 뒤부터
  삭제한다. 진행 중 요청을 지우지 않도록 24시간 유예를 둔다.
- 정리 작업은 최소 하루 한 번 실행하고, 실패한 파일은 다음 실행에서 다시 시도한다.
- 삭제 대상 경로는 반드시 `/data/user/images/` 아래의 검증된 `postId/photoId.jpg` 패턴으로
  제한한다. 공공 TAR 경로를 같은 정리 작업으로 삭제하지 않는다.
- 회원 탈퇴 사진 outbox는 실행당 100건을 짧은 DB lease로 선점한다. 성공 또는 HDFS 404면
  작업 행을 제거하고, 실패하면 경로·예외 원문 없이 `PHOTO-006`만 기록해 1분부터 최대 24시간까지
  지수 백오프로 재시도한다. 프로세스가 HDFS 삭제 뒤 DB 반영 전에 종료돼도 같은 경로 삭제를
  다시 수행할 수 있어야 한다.

## 7. 메타데이터와 조회 URL

사용자 `animal_photo`는 다음 값을 보관한다.

| 값 | 규칙 |
| --- | --- |
| `storage_type` | `USER_UPLOAD` |
| `storage_uri` | `/data/user/images/{postId}/{photoId}.jpg` |
| `content_type` | 정규화 결과인 `image/jpeg` |
| `byte_size` | 정규화 결과의 바이트 수 |
| `width_px`, `height_px` | 방향 보정·축소가 끝난 저장 이미지 크기 |
| `checksum_sha256` | 정규화 결과 바이트의 소문자 16진수 SHA-256, 필수 |
| `sort_order` | 요청 순서 `0..9`, 0번이 대표 사진 |

API의 사용자 사진 `url`은 `/api/v1/photos/{photoId}`를 반환한다. P8은 DB의 사진과 게시물
공개 범위를 먼저 확인한 다음 HDFS 내용을 streaming한다.

- `ACTIVE` 게시물 사진은 목록 표시를 위해 비로그인 조회를 허용한다.
- `CLOSED` 사용자 게시물 사진은 작성자가 인증한 경우에만 조회한다. 비작성자와 비로그인
  요청에는 사진 존재 여부를 구분하지 않는 404를 반환한다.
- `DELETED`, 보존 만료, DB 비참조 사진은 파일이 HDFS에 남아 있어도 같은 404로 응답한다.
- 정상 응답은 `Content-Type: image/jpeg`, `X-Content-Type-Options: nosniff`, checksum 기반
  `ETag`를 포함한다. 공개·인증 상태가 섞이는 것을 막기 위해 `Cache-Control: no-store`를
  사용한다.
- HDFS URI로 redirect하지 않고 API가 바이너리를 중계한다.

## 8. Android 품질 안내

- 사진 선택 화면에서 동물의 얼굴과 몸 전체가 잘 보이고 밝으며 흔들리지 않은 사진을 권장한다.
- 흐림, 가림, 너무 작은 동물 영역, 다중 동물처럼 클라이언트나 AI가 감지할 수 있는 문제는
  사진별 경고와 교체 방법을 표시한다.
- 품질 경고는 사용자가 실제 단서를 등록하지 못하게 만들 수 있으므로 업로드 차단 조건으로
  사용하지 않는다. 3절의 입력 형식·용량·해상도 조건만 차단 조건이다.
- 일시적인 네트워크·HDFS 오류가 나면 Android는 보안상 허용되는 화면 수명 안에서 선택한 사진과
  입력값을 유지하고 사용자가 재시도하거나 사진을 바꿀 수 있게 한다.

## 9. 오류 계약

| 상황 | HTTP | 코드 | 처리 |
| --- | ---: | --- | --- |
| 사진 0장 또는 11장 이상 | 400 | `PHOTO-001` | 요청 전체 거부 |
| 선언 MIME·매직 바이트 불일치 또는 JPEG·PNG 외 형식 | 415 | `PHOTO-002` | 해당 사진 위치 안내 |
| 손상·잘림·디코딩 실패·APNG (JPEG 의 EOI 뒤 덧붙은 데이터는 허용) | 400 | `PHOTO-003` | 해당 사진 위치 안내 |
| 사진당 10 MiB 또는 요청 전체 50 MiB 초과 | 413 | `PHOTO-004` | 읽기를 중단하고 요청 전체 거부 |
| 최소·최대 변 또는 총 픽셀 제한 위반 | 422 | `PHOTO-005` | 필요한 해상도 범위 안내 |
| staging·rename·HDFS 읽기/쓰기 장애 | 503 | `PHOTO-006` | 기존 DB 상태 유지, 재시도 가능 안내 |
| P8 비공개·삭제·비참조·존재하지 않는 사진 | 404 | `PHOTO-007` | 존재 여부를 구분하지 않고 조회 거부 |
| 사진 쓰기 동시 실행 한도 도달 | 503 | `PHOTO-008` | `Retry-After: 1`, 입력 유지 후 재시도 |

오류의 `data.fieldErrors`는 `photos[0]`처럼 요청 순서의 위치만 표시할 수 있다. 파일명, HDFS
경로, 이미지 바이트, EXIF, 내부 예외와 스택은 응답이나 로그에 포함하지 않는다. 프레임워크가
multipart 상한에서 먼저 거부한 경우도 `PHOTO-004`로 변환한다.

## 10. 구현 설정 계약

| 설정 | 값 |
| --- | --- |
| `spring.servlet.multipart.max-file-size` | `10485760B` |
| `spring.servlet.multipart.max-request-size` | `52428800B` |
| `PHOTO_MAX_WIDTH_PX`, `PHOTO_MAX_HEIGHT_PX` | `10000` |
| `PHOTO_MIN_WIDTH_PX`, `PHOTO_MIN_HEIGHT_PX` | `64` |
| `PHOTO_MAX_PIXELS` | `40000000` |
| `PHOTO_STORED_MAX_EDGE_PX` | `4096` |
| `PHOTO_JPEG_QUALITY` | `90` |
| `PHOTO_HDFS_ROOT` | `/data/user/images` |
| `PHOTO_HDFS_REPLICATION` | `2` |
| `PHOTO_STAGING_TTL` | `PT1H` |
| `PHOTO_ORPHAN_GRACE_PERIOD` | `PT24H` |
| `PHOTO_CLEANUP_ENABLED` | 운영 HDFS 정리·회원 사진 outbox worker를 함께 활성화할 때 `true` |
| `MEMBER_PHOTO_ERASURE_POLL_INTERVAL` | `PT1M` |

reverse proxy를 도입하면 요청 본문 상한도 50 MiB로 맞춘다. 애플리케이션과 proxy 중 먼저
거부하는 계층이 달라도 외부 오류 계약은 `PHOTO-004`로 동일해야 한다.

## 11. 필수 검증

- 0장·11장은 `PHOTO-001`, 1장·10장은 사진별 규격을 만족하면 허용한다.
- JPEG·PNG 정상 파일, 거짓 확장자, 거짓 Content-Type, HEIC·WebP, 손상·잘린 파일을 구분한다.
- 사진당 10 MiB와 요청 전체 50 MiB의 바로 아래·같음·초과 경계를 검사한다.
- 방향 보정 후 63px·64px와 10,000px·10,001px, 40,000,000픽셀 경계를 검사한다.
- EXIF 방향과 GPS가 있는 입력이 올바른 방향의 sRGB JPEG가 되고 메타데이터가 제거되는지
  확인한다.
- P3의 HDFS 쓰기·rename·DB commit 각 실패 지점에 게시물이나 사진 일부가 공개되지 않는지
  확인한다.
- 같은 회원·`clientRequestId` P3 재전달이 HDFS·DB 리소스를 중복 생성하지 않고, 같은 키의
  다른 metadata·사진 checksum은 `IDEMPOTENCY-001`인지 확인한다.
- P5의 저장·version 재검사·DB commit 실패에도 기존 사진 전체가 유지되는지 확인한다.
- 교체 성공 뒤 이전 파일 삭제 실패가 요청 성공을 되돌리지 않고 비참조 정리로 회수되는지
  확인한다.
- ACTIVE 비로그인, CLOSED 작성자·타인, DELETED 사진의 P8 접근을 검사한다.
- 응답·로그에 원래 파일명, HDFS/WebHDFS 경로, EXIF와 내부 예외가 없는지 확인한다.

## 12. 근거와 제외 범위

- [OWASP 파일 업로드 지침](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)에
  따라 확장자·Content-Type만 신뢰하지 않고 파일 내용 검사, 서버 생성 파일명, 크기 제한과
  별도 저장을 함께 적용한다.
- [Android 지원 미디어 형식](https://developer.android.com/media/platform/supported-formats)은
  기기·버전별 형식 지원이 다르므로 Android에서 JPEG로 변환하고 서버 입력을 JPEG·PNG로
  좁힌다.
- [HDFS 파일시스템 명세](https://hadoop.apache.org/docs/stable2/hadoop-project-dist/hadoop-common/filesystem/filesystem.html)의
  같은 파일시스템 내 rename을 staging에서 최종 경로로 전환하는 기본 연산으로 사용한다.

MVP에서는 원본 파일 장기 보관, GIF·애니메이션 PNG, HEIC·WebP·AVIF 서버 직접 수신, 클라이언트
직접 HDFS 업로드, 이미지 CDN, 사용자 자르기 편집과 품질 경고에 의한 강제 차단을 제공하지 않는다.
