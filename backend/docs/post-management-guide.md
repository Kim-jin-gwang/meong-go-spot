# 게시물 수정·사진 교체·종료

P4·P5·P6은 로그인한 작성자의 ACTIVE USER_POST만 변경합니다. 상세에서 읽은 정수 `version`을
보내야 하며, 현재 값과 다르면 POST-004입니다. 공공 정보는 POST-005, 타인 게시물은 POST-002,
종료 게시물은 POST-003, 삭제·없는 게시물은 POST-001입니다.

## 부분 수정

`PATCH /api/v1/posts/{postId}`는 JSON으로 `version`과 수정할 필드만 받습니다. 필드 생략은 유지,
선택 필드의 명시적 `null`은 삭제입니다. `type`, `source`, `owner`, `status`, `createdAt`,
`clientRequestId`, 임의 위치 표시값은 받을 수 없습니다. 일반 JSON과 multipart payload는 각각
64 KiB 이하이며 중복 key·후행 JSON·타입 강제 변환을 허용하지 않습니다.

위치는 역할 객체 안에서 부분 병합합니다. LOST는 EVENT 하나, SHELTERING은 EVENT·CURRENT가
모두 필요합니다. 예를 들어 지역 코드를 바꾸면서 기존 읍면동 코드가 새 지역에 속하지 않으면
요청을 거부하므로 해당 읍면동도 바꾸거나 `null`로 지워야 합니다. 좌표도 병합 결과가 완전한
쌍이어야 합니다. 요청하지 않은 과거 지역 코드·표시값은 보존합니다.

정확한 위치 공개를 켜거나 공개 중인 정확한 위치를 변경할 때는 같은 역할 객체에
`disclosurePolicyVersion=exact-location-v1`을 명시해야 합니다. 공개를 끄면 마지막 동의 증빙은
남기고 공개 flag만 내립니다. 제공한 정책 버전은 항상 현재 값과 일치해야 하며, 공개 중에
현재 버전을 다시 보내면 이전 정책의 동의를 갱신할 수 있습니다. 실패 시 다른 역할 변경도
함께 rollback됩니다. 정확한 위치는 암호화 저장하고 응답·로그에 넣지 않습니다.

정규화 뒤 실제 내용이 바뀌면 version을 1 올리고 `listedAt`은 유지합니다. 공개 동의만 변경한
경우에는 매칭 결과를 불필요하게 STALE로 만들지 않도록 version을 유지하고 `updatedAt`과
해당 역할의 증빙만 갱신합니다. 따라서 동의만 바꾸는 요청끼리는 같은 version에서 잠금 순서대로
적용되며, 각 요청에 명시한 필드만 병합합니다. 완전한 no-op은 version·updatedAt을 유지합니다.

## 사진 전체 교체

`PUT /api/v1/posts/{postId}/photos`는 `payload={"version":현재버전}`과 최종 `photos` 1~10장을
multipart로 받습니다. 사진 규격·용량·실행권은 [사진 정책](../../docs/photo-upload-policy.md)과
기존 등록 API와 같습니다. 첫 장이 대표 사진이고 요청 순서를 저장합니다.

소유권·버전을 먼저 확인하고 새 사진 전체를 검증·정규화한 뒤 새 ID의 파일을 저장합니다.
그 후 DB 트랜잭션에서 회원과 게시물을 순서대로 잠그고 권한·상태·version을 다시 확인합니다.
기존 사진 행 삭제, 새 행 전체 저장, version+1이 함께 commit되어야 성공합니다.

저장 중 종료·탈퇴·다른 수정이 일어나면 재검사에서 거부하고 새 파일만 정리합니다. 파일 저장이나
확인된 DB rollback 실패는 기존 사진을 유지합니다. commit 결과가 불명확하면 신규 파일을
보존하고 비참조 정리에 맡깁니다. 성공 후 이전 파일 삭제 실패도 기존 정리 작업이 재시도합니다.

## 종료와 후속 기능 연결

`POST /api/v1/posts/{postId}/closure`는 JSON `version`, `reason`을 받습니다.
reason은 RETURNED·TRANSFERRED·OTHER입니다. 한 트랜잭션에서 CLOSED, isMatchable=false,
closedAt, user_post.closeReason, updatedAt, version+1을 기록합니다. 재활성화는 없습니다.

종료 즉시 공개 목록과 익명 사진 접근이 차단됩니다. 작성자는 기존 조회 API의 90일 보관 범위에서
사진·상세·내 이력을 확인할 수 있습니다. 기존 매칭 실행·후보·채팅방은 삭제하지 않습니다.
신규 후보 검색은 `is_matchable`을, 채팅의 쓰기 가능 여부는 ACTIVE 상태를 검사해야 합니다.
별도의 매칭·채팅 API 구현은 후속 묶음이며, 현재 상세의 chat은 POST_NOT_ACTIVE를 반환합니다.

내용·사진을 바꿔도 새 match_run을 생성하지 않습니다. 기존 실행의 query_case_version을
보존하므로 후속 M1은 현재 게시물 version과 비교하여 STALE을 표시할 수 있습니다.

## 검증·배포

PostMetadataApiTest와 PostManagementApiTest는 실제 PostgreSQL·JWT와 사진 저장소 fixture로
권한·버전·위치 병합·동의 증빙, 저장 중 상태 경합·보상, 기존 매칭 보존과 종료 접근을 검증합니다.
PhotoMultipartLimitTest는 실제 HTTP PUT의 전체 50 MiB·장당 10 MiB 제한도 확인합니다.
전체 검증은 `npm run check`입니다.

스키마·새 환경변수는 추가하지 않습니다. 기존 공식 지역 CSV/checksum, 위치 키링과 WebHDFS
운영 준비는 그대로 필요합니다. 이 변경은 운영 배포나 Jira 상태 전환을 실행하지 않습니다.
