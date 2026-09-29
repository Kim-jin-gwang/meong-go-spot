# 게시물 등록

Jira #83·84의 `POST /api/v1/posts`를 구현한다. 인증된 활성 회원은
`payload(application/json)` 하나와 `photos` 1~10장으로 LOST 또는 SHELTERING을 등록한다.
응답은 201이며 `postId`, `type`, `source=USER_POST`, `status=ACTIVE`, `version=0`,
DB 저장 시각 `createdAt`만 반환한다. 회원 전화번호·정확한 위치·좌표·HDFS 경로는 응답하지 않는다.
등록 데이터는 EVENT 지역으로 조회할 수 있는 ACTIVE USER aggregate이며 자동 `match_run`은 없다.
목록·상세는 [조회 안내](post-query-guide.md), 수정·사진 교체·종료는
[관리 안내](post-management-guide.md)에서 연결한다.

## 입력과 멱등성

`PostMultipartReader`는 JSON part를 최대 64 KiB+1까지 읽고 초과 여부를 검사한 다음 파싱한다.
중복 part/key·알 수 없는 필드·후행 JSON·문자열/숫자/불리언 타입 강제 변환을 허용하지 않는다.
형식은 DTO 생성 전에 명시적으로 검증한다. 바인딩 후 제한으로 큰 JSON을 먼저 할당하지 않도록
일반 `@RequestPart` 자동 역직렬화 대신 이 경계를 사용한다.

문자열은 원문 코드 포인트 상한 → 제어·형식·잘못된 surrogate 거부 → NFC → 앞뒤 공백 제거 순서다.
빈 선택 문자열은 null로 통일하며 `eventDate`는 명시적 Clock의 Asia/Seoul 오늘까지 허용한다.
좌표는 원래 값의 쌍·범위를 검사한 후 DB numeric(9,6)과 동일하게 HALF_UP 반올림한다.
비공개 역할의 동의 버전은 보내지 않으며 공개 역할마다 현재 버전·서버 동의 시각을 저장한다.

서버는 고정 순서·길이 prefix를 가진 canonical metadata와 정규화 사진 checksum 목록으로
SHA-256을 계산한다. 입력 순서·동일 좌표의 소수 자릿수·NFC 변형·앞뒤 공백은 중복 게시물을
만들지 않는다. 사진 순서는 비교 대상이며 서버 표시값·암호화 nonce·동의 시각은 hash에 넣지 않는다.

파일 준비 후 `PhotoWriteService`의 독립 트랜잭션에서 회원을 잠그고 활성 상태·멱등키를 재검사한다.
같은 회원의 같은 키·같은 hash는 최초 등록 응답을 재반환하고 다른 hash는 `IDEMPOTENCY-001`이다.
경합한 요청의 신규 사진은 rollback 후 회수하며 DB unique 제약도 유지한다. 첫 성공 이후 게시물이
수정·종료되어도 등록 재시도는 최초 생성 응답을 반환한다. 현재 상태 조회는 상세 API의 책임이다.

## 처리량과 배포

`PHOTO_UPLOAD_MAX_CONCURRENCY`는 프로세스별 1~4, 기본1이다. 실행권은 multipart 임시 파일
생성 전부터 정규화 결과·파일 저장이 끝날 때까지 유지한다. 대기열 없이 `PHOTO-008(503)`과
`Retry-After: 1`로 포화를 알리고 실패 시에도 실행권을 반환한다. 여러 서버의 합계는 각 서버
설정의 합이다. 클라이언트는 입력과 clientRequestId를 유지하여 재시도한다.

최대 크기의 사진 여러 장과 인증 Argon2 작업이 함께 실행될 수 있으므로 운영 JVM은 충분한 힙을
배정하고 실제 서버에서 최대 요청 부하를 검증해야 한다. 초기 용량 산정은 실행권1·힙2 GiB 이상으로
시작한다. 기존 단일 이미지 메모리 테스트는 전체 요청·동시 인증 부하의 운영 검증을 대신하지 않는다.

`compose.prod.yml`은 위치 키링과 승인 CSV를 호스트 경로에서 컨테이너의 고정 경로로 읽기 전용
mount하며 파일이 없을 때 디렉터리를 자동 생성하지 않는다. CSV 버전·checksum도 전달한다.
아래 필수 위치 설정이 없으면 기동에 실패하므로 이미지 교체 전에 파일을 준비해야 한다.
기존 인증 TLS·Redis·다른 Secret mount, WebHDFS 방화벽·연결 준비도 함께 필요하다.
이 변경은 실제 서버 배포·키 발급·공식 데이터 승인·HDFS 접속 검증을 수행하지 않는다.

## 검증

실제 PostgreSQL·JWT 세션을 사용해 정상 등록, 두 위치 역할, 암호화 저장·공개 동의, 미래 날짜,
JSON 크기·타입, 사진 오류, 동시 동일/충돌 재시도, 파일 회수, 저장 중 탈퇴, 처리량 포화를 검증한다.
실제 HTTP chunked 요청은 50 MiB 경계와 장당 제한을 확인한다. 전체 게이트는 `npm run check`다.

## 위치 기준 데이터

P3 위치 쓰기는 승인된 행정구역 기준 데이터가 준비되어야 사용할 수 있다. 애플리케이션은
아래 설정의 누락, 파일 읽기 실패, 버전·체크섬·형식 불일치 시 기동을 거부한다. 임의 지역명이나
코드 접두사로 표시값을 추정하지 않는다. 실제 승인된 공식 데이터 확보·검수·배포는 배포 전제다.
저장소의 `src/test/resources/location/regions-test.csv`는 가상의 테스트 표시값만 가진 fixture이며
운영 기준 데이터로 사용할 수 없다. 운영 데이터나 암호화 키를 이미지에 포함하지 않는다.

| 설정 | 값 |
| --- | --- |
| `REGION_CODE_DATA_PATH` | 배포한 승인 CSV의 읽기 전용 파일 경로 |
| `REGION_CODE_DATA_VERSION` | 파일 모든 행에 동일하게 기록한 버전, 영숫자·`.`·`_`·`-`, 1~100자 |
| `REGION_CODE_DATA_SHA256` | 파일 원본 바이트의 SHA-256, 64자리 16진수 |
| `LOCATION_DATA_ENCRYPTION_KEYRING_PATH` | 위치 전용 Secret JSON 파일 경로 |

CSV는 최대 16 MiB의 엄격한 UTF-8이다. BOM은 허용하지 않으며 LF 또는 CRLF와 마지막 개행은
허용한다. 헤더는 정확히 다음 순서다.

```csv
version,regionCode,emdCode,publicLocation,active
```

각 행은 위 다섯 열을 쉼표로 분리한다. 따옴표 escape·필드 내 쉼표·다중 행 필드는 지원하지 않는다.
시·군·구는 5자리 `regionCode`, 빈 `emdCode`를 가진 명시적 행이어야 한다. 읍·면·동은 10자리
`emdCode`이며 앞 5자리가 같은 행의 `regionCode`와 일치해야 한다. 모든 읍·면·동에는 대응하는
시·군·구 행이 반드시 있어야 한다. 같은 단계의 코드 중복은 파일 오류다.

`publicLocation`은 공식 데이터에서 검수한 전체 공개 표시값으로 채운다. 앞뒤 공백 없이 1~200
Unicode 코드 포인트이며 제어·형식 문자·큰따옴표를 허용하지 않는다. `active`는 소문자
`true` 또는 `false`만 허용한다. 폐지된 시·군·구와 그 하위 읍·면·동, 폐지된 읍·면·동은 신규
쓰기에서 거부한다. 기존 게시물의 저장된 표시값을 이 로더가 변경하지 않는다.

배포 전에 원본 파일의 SHA-256을 계산하여 배포 설정과 대조한다. 파일을 교체할 때 버전과 체크섬도
함께 변경한다. 체크섬은 지정한 파일과 배포 설정의 일치만 증명하므로 공식 출처·승인 여부는 별도
검수해야 한다. 실제 파일 경로·원본 데이터는 오류 메시지에 포함하지 않는다.

## 위치 암호화 키링

전화번호 키링과 같은 JSON 구조이며 최대 8 KiB다. 아래 값은 형식 설명용 자리표시자로 실제 키가 아니다.

```json
{
  "currentKid": "location-v2",
  "keys": {
    "location-v2": "<현재 32바이트 무작위 키의 표준 Base64>",
    "location-v1": "<직전 32바이트 무작위 키의 표준 Base64>"
  }
}
```

최상위 속성은 `currentKid`, `keys` 두 개만 허용한다. 중복 JSON 속성·후행 JSON·잘못된 Base64는
거부한다. 키 ID는 영숫자·`_`·`-` 1~64자, 키 값은 정확히 32바이트를 padding 있는 표준 Base64로
인코딩한다. 현재 키는 필수이며 직전 키는 선택이다. 키는 최대 두 개이고 같은 값을 재사용할 수 없다.
새 암호화는 현재 키로만 수행하며 복호화는 현재·직전 키를 지원한다. 재암호화와 검증이 끝난 뒤에만
직전 키를 제거한다.

기동 시 두 위치 키 모두 전화번호의 현재·직전 암호화 키, 전화번호 조회·OTP·IP HMAC 키와 비교한다.
`AUTH_LOGIN_ID_HMAC_KEY_V1`도 반드시 제공하며 위치 키와 같으면 기동을 거부한다. 운영 Secret은
저장소·일반 env 파일에 기록하지 않고 배포 Secret file 또는 기존 Secret 주입 경로로 전달한다.
`prod`가 활성화된 경우 다른 프로필이 함께 있어도 CSV와 키링의 `classpath:` 경로를 거부한다.
비운영 프로필에서는 테스트를 위한 classpath 리소스를 사용할 수 있다.

암호화는 정규화된 정확한 위치 UTF-8에 AES-256-GCM을 적용하고 매번 새 96비트 nonce,
128비트 인증 태그, AAD `mgbj:exact-location:v1`을 사용한다. 저장 형식은
`enc:v1:{kid}:{nonce}:{ciphertextAndTag}`이며 nonce와 암호문+태그는 padding 없는 Base64url이다.
입력 정규화·공개 동의 검증·null 처리는 P3 입력 정책과 호출자가 담당한다. 비공개 정확한 위치도
동일한 암호화를 적용한다. 변조·잘못된 키·잘못된 envelope는 원문과 envelope를 포함하지 않는
내부 오류로 처리한다.

관련 기준: [위치 공개 정책](../../docs/post-date-location-policy.md),
[보안·운영 정책](../../docs/backend-security-operations-policy.md).
