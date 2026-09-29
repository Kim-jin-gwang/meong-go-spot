# 게시물 공통 도메인·사진 처리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Jira #62·82의 공통 도메인과 재사용 가능한 사진 검증·HDFS 저장 서비스를 제공한다.

**Architecture:** 기존 V1 스키마를 JPA 도메인으로 연결한다. 모든 입력을 검증·정규화한 뒤 새 HDFS 파일을 저장하고 DB 트랜잭션 완료에 따라 새 파일 회수 또는 이전 파일 정리를 수행한다. P8은 DB 접근 권한을 확인한 뒤 바이너리를 중계한다.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL 17, ImageIO, WebHDFS.

**Spec:** `docs/photo-upload-policy.md`, `docs/erd.md`, `docs/api-spec.md`, `docs/post-date-location-policy.md`.

## Global Constraints

- 기준 dev: `63ba279`. 기존 `.vscode/` 변경을 보존한다. MR 대상 dev, rebase·force push 금지.
- 사진 정책의 상세 오류 계약 PHOTO-001~007을 따른다. Jira의 이전 규격 오류 설명보다 확정 OD-03이 우선한다.
- V1은 변경하지 않는다. 공개 동의 증빙은 현재 ERD대로 `animal_case_location`에 저장한다.
- P3 등록 및 P5 HTTP API는 다음 묶음이다. 이번에는 이들이 사용할 저장 경계와 P8을 제공한다.
- 사용자 업로드만 쓰고, 공공 데이터 쓰기와 탈퇴·90일 파기는 별도 묶음이다. 비참조 파일 회수는 이번 저장 서비스의 일부다.
- 민감한 파일명·바이트·메타데이터·내부 경로·예외 원문을 로그나 API에 노출하지 않는다.
- 테스트부터 실행해 RED를 확인한다. 커밋은 부모가 전체 `npm run check` 이후 수행한다.

### Task 1: 사진 검증·정규화

**Files:** `backend/src/main/java/com/meonggo/backend/photo/{exception,image}/`, 대응 test 경로.

**Interfaces:** `PhotoNormalizer.normalize(List<MultipartFile>)` → 요청 순서의 `List<NormalizedPhoto>`.
`NormalizedPhoto`는 `byte[] bytes()`, `int width()`, `int height()`, `String checksum()`을 제공하고 바이트 접근은 방어 복사, toString은 민감값을 숨긴다.
`PhotoErrorCode`는 `INVALID_COUNT`, `UNSUPPORTED_FORMAT`, `INVALID_IMAGE`, `TOO_LARGE`, `INVALID_DIMENSIONS`, `STORAGE_UNAVAILABLE`, `NOT_FOUND`의 순서로 PHOTO-001~007을 구현한다.

- [x] 정상 JPEG·PNG, count 0/11, MIME 위조, 손상·잘림·다중 프레임, 파일 용량, 해상도, EXIF 회전·메타데이터 제거 테스트를 먼저 작성하고 RED 확인.
  ```java
  assertThatThrownBy(() -> normalizer.normalize(List.of()))
      .isInstanceOfSatisfying(BusinessException.class,
          e -> assertThat(e.errorCode()).isEqualTo(PhotoErrorCode.INVALID_COUNT));
  ```
- [x] 장당 10 MiB, 각 변 512~10000, 총 40000000픽셀, 1프레임을 디코딩 전 헤더와 디코딩 결과로 검증한다. 파일명은 참조하지 않는다.
- [x] EXIF 방향 적용, sRGB·흰 alpha 합성, 긴 변 4096 축소, 품질90 JPEG 인코딩, SHA-256 계산을 구현한다. JDK ImageIO만으로 정확히 처리하지 못하는 입력은 근거를 보고하고 필요한 라이브러리는 부모와 조율한다.
- [x] 단위 테스트로 GREEN 확인. 작업 결과·RED/GREEN 근거·제약을 보고 파일에 남긴다.

### Task 2: 게시물 도메인과 저장 트랜잭션

**Files:** `backend/src/main/java/com/meonggo/backend/post/{entity,repository,service}/`, `photo/{entity,repository,storage,service,config}/`, 대응 테스트, application.yml.

**Interfaces:** Task 1의 `NormalizedPhoto`를 받아 서버 할당 ID로 새 파일을 만든다. 저장소는 create-no-overwrite, rename-no-overwrite, open, delete, list를 제공한다. 저장 트랜잭션은 모든 새 파일 저장 성공 후에만 DB 사진 행을 반영한다.

- [x] PostgreSQL 통합 테스트로 USER/LOST EVENT만, SHELTERING EVENT/CURRENT, 사진 1~10, 위치 공개 동의의 암호문 필요 규칙을 먼저 검증한다.
- [x] 기존 스키마에 맞춘 AnimalCase, AnimalCaseLocation, UserPost, AnimalPhoto 매핑과 사용자 aggregate 검증을 구현한다. 공공 도메인은 조회 매핑만 제공한다.
- [x] 실제 HTTP fixture로 WebHDFS create/rename/open/delete 실패와 redirect 경계 테스트를 먼저 작성한다. HDFS 주소는 설정에서만 받으며 데이터 노드 redirect도 설정된 주소만 허용한다.
- [x] HDFS staging→final 저장을 구현한다. Spring 트랜잭션 동기화로 rollback은 신규 파일, commit은 이전 파일을 정리한다. 파일 정리 실패가 DB 성공을 번복하지 않는다.
- [x] 저장·rename·DB 실패에도 기존 DB 사진 전체와 파일이 유지되는지 통합 테스트한다. 비참조 정리는 staging 1시간, final 24시간 유예와 안전 경로 패턴을 적용한다.

### Task 3: 사진 조회·요청 제한·문서·MR

**Files:** `photo/{controller,service,web}/`, `backend/docs/post-photo-guide.md`, `backend/docs/README.md`, 설정 예시, 대응 테스트.

- [x] P8 GET `/api/v1/photos/{photoId}`의 ACTIVE 익명, CLOSED 작성자·타인, DELETED·보존 만료·PUBLIC_URL·비참조 404 테스트를 작성하고 RED 확인.
- [x] 권한 확인 후 streaming, JPEG·nosniff·ETag·no-store 응답을 구현한다. HDFS 오류는 PHOTO-006으로 변환한다.
- [x] P3·P5 요청 본문 전체 50 MiB와 압축 거부, multipart 장당 10 MiB, 프레임워크 상한 오류 PHOTO-004 계약을 테스트·구현한다.
- [x] 문서에 재사용 서비스 경계, 설정·운영 준비, 다음 P3/P5 연동 작업을 기록한다.
- [x] 태스크 및 전체 코드 리뷰, `npm run fix:be`, `npm run check`, pre-mr-readiness, secret scan을 수행한다.
- [ ] 목적별 커밋 계획을 제시하고 커밋·push·dev MR을 생성한다. CI와 AI 리뷰를 확인하고 완료 대상 Jira 번호를 보고한다.
