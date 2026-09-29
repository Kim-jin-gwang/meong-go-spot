# 게시물 공통 도메인·사진 처리

## 제공 범위

Jira #62·82의 게시물·위치·사진 엔티티와 사진 처리 공통 서비스를 구현한다.
기존 Flyway V1 스키마와 제약을 그대로 사용한다. 공개 동의 버전·시각은 현재 ERD에 따라
위치 역할별 `animal_case_location`에 둔다.

- `PostAggregateService`: 서버 ID 선할당, USER/LOST의 EVENT 하나, USER/SHELTERING의
  EVENT·CURRENT 각각 하나, 요청 순서의 사용자 사진 1~10장과 aggregate ID 일치를 검증해 저장한다.
- `PhotoNormalizer`: JPEG·PNG 실제 내용·크기·프레임 검증, EXIF 방향과 색 공간 반영,
  메타데이터 제거, 흰 배경 alpha 합성, 긴 변 최대 4096px·품질90 JPEG와 SHA-256 생성.
- `PhotoWriteService`: 모든 새 파일 저장 후 전달받은 DB 변경을 독립 트랜잭션으로 실행한다.
  파일 생성·rename은 덮어쓰지 않는다. DB 성공 뒤 이전 비참조 파일을 정리한다.
- `GET /api/v1/photos/{photoId}`: ACTIVE 사용자 사진은 익명, CLOSED는 90일 보존 기간 안의
  인증된 작성자에게만 제공한다. 탈퇴 회원·DELETED·PUBLIC_URL·비참조 사진은 PHOTO-007이다.

P3 등록과 행정구역 사전·정확한 위치 암호화·입력 검증은
[게시물 등록 가이드](post-creation-guide.md)에서 연결한다. P5 교체 HTTP API는
[게시물 관리 가이드](post-management-guide.md)에서 연결한다. 90일 종료 파기·탈퇴 데이터 파기는 후속 작업이다.

## 쓰기 서비스 연결

P3/P5의 호출 서비스는 외부 DB 트랜잭션을 열지 않은 상태에서 다음 순서를 따른다.

1. 세션·회원 상태·소유권·현재 게시물 상태를 검증한다. P3는 metadata·위치·동의·멱등키를,
   P5는 현재 version을 확인한다.
2. multipart 사진 전체를 `PhotoNormalizer.normalize`로 변환한다. P3의 request hash는
   canonical metadata와 정규화 checksum 목록을 사용한다. 멱등 성공 행 조회는 저장 전에 수행한다.
3. `PhotoWriteService.write(postId, normalized, databaseWrite)`를 호출한다. 이 함수는 새
   사진 ID와 HDFS 파일을 준비한 후 `databaseWrite`에 순서가 정해진 `List<AnimalPhoto>`를 전달한다.
4. `databaseWrite`는 같은 트랜잭션 안에서 회원 상태·소유권·version/멱등키를 다시 확인한다.
   P3는 `PostAggregateService.persist`로 전체 aggregate를 저장한다. P5는 게시물 버전을
   검증·변경하고 기존 사진 전체를 새 목록으로 교체한 뒤 flush한다.
5. 전달된 사진 전체가 DB에 정확한 순서로 반영돼야 commit된다. callback이 별도 트랜잭션을
   생성하거나 파일을 쓰면 안 된다. 기존 사진을 변경하지 않은 callback은 실패한다.

파일 저장 실패 또는 확인된 DB rollback에서는 신규 파일을 회수한다. commit 결과를 알 수 없는
통신 장애에서는 DB commit이 뒤늦게 성공할 수 있으므로 신규 최종 파일을 보존하고 비참조 정리에
맡긴다. 이전 파일 삭제 실패는 성공 응답을 되돌리지 않으며 다음 정리 실행에서 재시도한다.

## 오류·전송 제한

상세 값은 [사진 정책](../../docs/photo-upload-policy.md)과 [API 명세](../../docs/api-spec.md)를 따른다.
PHOTO-001~007은 각각 개수400, 형식415, 손상400, 용량413, 해상도422, 저장소503, 비공개/없음404다.
Jira의 오래된 규격 오류 설명에 적힌 PHOTO-003 대신 확정 OD-03의 용량·해상도 구분을 적용한다.

제한된 PNG 디코더는 텍스트 metadata를 펼치지 않고 제거한다. ICC 프로파일의 압축 해제 결과는
최대 1 MiB이며 초과·손상 프로파일은 PHOTO-003이다. 단일 4천만 픽셀 16-bit RGBA 입력의
512 MiB 힙 검증은 동시 요청 수에 대한 보장이 아니다. P3/P5 공개 전에 요청 동시성·JVM 메모리를
실제 서버 용량에 맞춰 제한해야 한다. P3는 `PHOTO_UPLOAD_MAX_CONCURRENCY`(기본1)의 실행권을
multipart 파싱부터 저장 완료까지 유지하며 포화 시 `PHOTO-008(503)`과 `Retry-After: 1`을 반환한다.

Servlet multipart parser는 장당 10 MiB·전체 50 MiB를 streaming 중 검사하며 원본은 임시 파일에
둔다. 전체 크기에는 boundary도 포함한다. P3/P5 요청 필터는 알려진 초과 Content-Length와
Content-Encoding을 파싱 전에 거부한다. P3 JSON payload는 64 KiB까지 읽고 크기 확인 뒤 파싱한다.

P8 정상 응답은 JPEG 바이너리, checksum ETag, `nosniff`, `Cache-Control: no-store`이며
HDFS redirect를 응답하지 않는다. 파일을 열기 전 오류는 JSON PHOTO-006으로 응답한다.
이미 바이너리 전송이 시작된 뒤 장애가 생기면 HTTP 상태를 바꿀 수 없으므로 전송을 종료한다.
클라이언트는 Content-Length 미달 응답을 실패로 처리해야 한다. 원문 저장 경로·예외는 노출하지 않는다.

## 설정과 HDFS 권한

| 설정 | 값·의미 |
| --- | --- |
| `PHOTO_HDFS_NAMENODE_URL` | 내부 NameNode WebHDFS origin (`http(s)://host:port`, 경로 없음) |
| `PHOTO_HDFS_DATANODE_URLS` | redirect를 허용할 모든 DataNode origin, 쉼표 구분 |
| `PHOTO_HDFS_USER` | 사용자 사진 namespace를 관리하는 HDFS 서비스 계정 |
| `PHOTO_CLEANUP_ENABLED` | HDFS 설정·접속 검증 후 `true` |
| `PHOTO_CLEANUP_CRON` | 기본 `0 0 4 * * *`, Asia/Seoul |

세 접속 설정을 모두 비우면 사진 저장소 작업만 503으로 실패하고 인증 API는 기동할 수 있다.
일부만 채우거나 잘못된 origin을 넣으면 기동을 거부한다. 실제 HDFS 없이 파일을 성공 저장했다고
응답하는 운영 대체 구현은 없다. 테스트만 메모리 저장소·로컬 HTTP fixture를 사용한다.

연결 2초, 요청·스트림 수명 15초 제한이다. NameNode가 준 redirect도 설정한 origin·경로·작업·사용자와
복제2/덮어쓰기 금지 조건을 확인한다. `/data/user/images` 밖의 경로와 symlink를 허용하지 않는다.
namespace의 디렉터리·파일 생성은 이 서비스 계정만 담당해야 한다. WebHDFS rename에는 목적지
사전 확인과 실제 rename 사이 간격이 있으므로 다른 운영자가 사진 ID 경로에 디렉터리를 만들면 안 된다.

최종 사진을 읽는 AI worker의 HDFS 권한도 함께 준비한다. 서비스 계정과 필요한 worker만 읽도록
상위 디렉터리 ACL·사용자 구성을 검증한다. 공공 TAR 쓰기·삭제 권한을 이 API에 추가하지 않는다.

## 운영 반영 순서

현재 인증 배포의 TLS·Redis·Secret mount 준비 사항도 여전히 적용된다.
이 MR은 서버 방화벽 변경이나 운영 배포를 실행하지 않는다.

1. [클러스터 네트워크 정책](../../docs/bigdata-cluster.md)에 따라 compose 서브넷을 고정한다.
   `compose.prod.yml`의 default network는 `172.18.0.0/16`이다. 기존 네트워크 재생성이 필요한
   변경이므로 배포 시 DB volume을 보존하고 서비스 중단 구간을 확보한다.
2. 서버1 `/usr/local/sbin/docker-user-rules.sh`의 기존 서버2 DROP 추가 코드 **뒤에** 다음 예외를
   넣는다. `-I ... 1`에 의해 실행 결과 ACCEPT가 DROP 앞에 온다. `<server2-private-ip>`는
   클러스터 문서의 서버2 내부 IP로 바꾼다. WebHDFS에 필요한 두 포트만 허용한다.

   ```bash
   iptables -C DOCKER-USER -s 172.18.0.0/16 -d <server2-private-ip> -p tcp -m multiport --dports 9870,9864 -j ACCEPT || \
     iptables -I DOCKER-USER 1 -s 172.18.0.0/16 -d <server2-private-ip> -p tcp -m multiport --dports 9870,9864 -j ACCEPT
   ```

3. 기존 멱등 스크립트를 적용하고 `iptables -L DOCKER-USER -n --line-numbers`로 순서를 확인한다.
   backend 컨테이너에서 NameNode와 모든 DataNode의 이름 해석·연결, 복제2의 쓰기·rename·읽기·삭제를 검증한다.
4. 운영 Secret/env에 접속 origin·서비스 계정을 넣고 정리를 활성화한다. 매일 정리 성공과 실패
   개수를 확인한다. staging은 수정 1시간, 최종 비참조 파일은 24시간 뒤부터 정리한다.

HDFS RPC 포트는 이 WebHDFS 어댑터에 필요하지 않다. 실제 클러스터 통신 검증은 운영 준비 단계에서
수행해야 하며 로컬 HTTP fixture 통과를 클러스터 접속 성공으로 해석하면 안 된다.

## 검증

사진 정규화의 실제 픽셀·방향·metadata·손상 검증, PostgreSQL aggregate·rollback/unknown commit,
사진 공개 권한, 실제 HTTP chunked multipart 경계, WebHDFS redirect와 실패, 비참조 정리를 검증한다.
전체 게이트는 저장소 루트의 `npm run check`다.
