-- 멍고반점 MVP ERDCloud import DDL
--
-- 목적: 팀 공유용 ERD를 ERDCloud에 생성하기 위한 가져오기 전용 SQL이다.
-- 기준: docs/erd.md
-- 대상: PostgreSQL 17 논리 스키마
--
-- ERDCloud 가져오기 권장 설정
--   1. Import에서 PostgreSQL 계열 DDL로 이 파일 전체를 붙여 넣는다.
--   2. Display에서 Logical/Physical, Type, Null, Default, Comment를 표시한다.
--   3. 코멘트는 "한글 논리명 | PK/FK/UK; 핵심 설명·허용값" 형식으로 작성했다.
--   4. SQL 표준에는 논리명 전용 메타데이터가 없으므로 ERDCloud 버전에 따라
--      코멘트의 "|" 왼쪽 값을 논리명 칸에 한 번 옮겨야 할 수 있다.
--
-- 이 파일은 운영 DB 마이그레이션이 아니다.
-- ERDCloud에서 테이블과 관계를 안정적으로 인식하도록 PK, UK, FK와 기본값을 담았다.
-- 부분 인덱스, 표현식 인덱스, CHECK 제약조건의 계약은 docs/erd.md를 따른다.
-- 실제 적용 DDL은 backend/src/main/resources/db/migration/ 아래 버전별 migration에 있다.
-- adoption_favorite의 실제 migration은 후속 구현 이슈 #162에서 추가한다.
--
-- 가져온 뒤 아래 관계의 카디널리티를 ERDCloud 화면에서 확인한다.
--   animal_case - animal_case_location : 1 : N
--   animal_case - user_post            : 1 : 0..1
--   animal_case - shelter_animal       : 1 : 0..1
--   animal_case - lost_report          : 1 : 0..1
--   animal_case - adoption_favorite    : 1 : N
--   member      - adoption_favorite    : 1 : N
--   animal_case - chat_room            : 1 : N
--   chat_room   - chat_message         : 1 : N
--   member      - chat_notification_outbox : 1 : N
--   chat_message - chat_notification_outbox : 1 : 0..1

-- ============================================================
-- 1. 회원·인증
-- ============================================================

CREATE TABLE member (
    id BIGINT NOT NULL,
    login_id VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(30) NOT NULL,
    phone_ciphertext TEXT,
    phone_lookup_hash CHAR(64),
    phone_verified_at TIMESTAMPTZ,
    privacy_collection_policy_version VARCHAR(50),
    privacy_collection_consented_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    personal_data_erased_at TIMESTAMPTZ,
    relational_data_erased_at TIMESTAMPTZ,
    CONSTRAINT pk_member PRIMARY KEY (id),
    CONSTRAINT uk_member_login_id_canonical UNIQUE (login_id)
);

COMMENT ON TABLE member IS '회원 | 인증 계정과 화면 표시용 닉네임';
COMMENT ON COLUMN member.id IS '회원 ID | PK; 회원 식별자';
COMMENT ON COLUMN member.login_id IS '로그인 ID | NFC·Locale.ROOT 소문자 canonical 값; UNIQUE';
COMMENT ON COLUMN member.password_hash IS '비밀번호 해시 | {argon2id-v1} Argon2id 인코딩 문자열; 평문·정규화 값 저장 금지';
COMMENT ON COLUMN member.nickname IS '닉네임 | 사용자 화면 표시명';
COMMENT ON COLUMN member.phone_ciphertext IS '전화번호 암호문 | 인증된 회원 전화번호, 외부 응답 금지';
COMMENT ON COLUMN member.phone_lookup_hash IS '전화번호 조회 해시 | 활성 회원 중복 확인용 HMAC-SHA-256 등';
COMMENT ON COLUMN member.phone_verified_at IS '전화번호 인증 일시 | 소유 인증 완료 시각';
COMMENT ON COLUMN member.privacy_collection_policy_version IS '개인정보 고지 버전 | 현재 privacy-collection-v1';
COMMENT ON COLUMN member.privacy_collection_consented_at IS '개인정보 수집·이용 동의 일시 | 가입 시 필수 동의 1개를 true로 제출한 시각';
COMMENT ON COLUMN member.status IS '회원 상태 | ACTIVE / WITHDRAWN';
COMMENT ON COLUMN member.created_at IS '생성 일시 | 회원 생성 시각';
COMMENT ON COLUMN member.updated_at IS '수정 일시 | 회원 정보 최종 수정 시각';
COMMENT ON COLUMN member.deleted_at IS '삭제 일시 | 탈퇴 후 최종 삭제 추적 시각';
COMMENT ON COLUMN member.personal_data_erased_at IS '개인정보 파기 완료 일시 | 전화번호·동의·계정 자격 증명 파기 커밋';
COMMENT ON COLUMN member.relational_data_erased_at IS '관계형 데이터 파기 완료 일시 | 게시물·위치·매칭·채팅 정리 커밋';

CREATE TABLE auth_session (
    id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    refresh_token_selector CHAR(22) NOT NULL,
    refresh_token_hash CHAR(64) NOT NULL,
    push_installation_id UUID,
    push_platform VARCHAR(20),
    push_token_ciphertext TEXT,
    push_token_lookup_hash CHAR(64),
    push_last_seen_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    CONSTRAINT pk_auth_session PRIMARY KEY (id),
    CONSTRAINT uk_auth_session_refresh_token_selector UNIQUE (refresh_token_selector),
    CONSTRAINT uk_auth_session_refresh_token_hash UNIQUE (refresh_token_hash),
    CONSTRAINT uk_auth_session_push_installation UNIQUE (push_installation_id),
    CONSTRAINT uk_auth_session_push_token_lookup_hash UNIQUE (push_token_lookup_hash)
);

COMMENT ON TABLE auth_session IS '인증 세션 | 갱신 토큰 회전과 로그아웃 상태';
COMMENT ON COLUMN auth_session.id IS '인증 세션 ID | PK; 인증 세션 식별자';
COMMENT ON COLUMN auth_session.member_id IS '회원 ID | FK -> member.id; 세션 소유 회원';
COMMENT ON COLUMN auth_session.refresh_token_selector IS '갱신 토큰 selector | UK; 회전 전후 세션 조회용 16바이트 난수의 Base64 URL 값';
COMMENT ON COLUMN auth_session.refresh_token_hash IS '갱신 토큰 secret 해시 | UK; 32바이트 secret 원문 대신 저장하는 SHA-256';
COMMENT ON COLUMN auth_session.push_installation_id IS '푸시 설치 ID | UK; N1 앱 설치 UUID, 계정 전환 시 현재 세션으로 재귀속';
COMMENT ON COLUMN auth_session.push_platform IS '푸시 플랫폼 | 등록 시 ANDROID; 미등록 세션은 NULL';
COMMENT ON COLUMN auth_session.push_token_ciphertext IS 'FCM 토큰 암호문 | AES-256-GCM; 응답·로그 노출 금지';
COMMENT ON COLUMN auth_session.push_token_lookup_hash IS 'FCM 토큰 조회값 | 전용 HMAC-SHA-256; UK';
COMMENT ON COLUMN auth_session.push_last_seen_at IS '푸시 마지막 확인 일시 | 성공한 N1 등록·갱신 시각';
COMMENT ON COLUMN auth_session.expires_at IS '만료 일시 | 로그인 시점부터 30일 고정; 회전 시 연장 금지';
COMMENT ON COLUMN auth_session.revoked_at IS '폐기 일시 | 로그아웃·강제 만료 시각';
COMMENT ON COLUMN auth_session.created_at IS '생성 일시 | 세션 생성 시각';
COMMENT ON COLUMN auth_session.last_used_at IS '마지막 사용 일시 | 마지막 정상 갱신 시각';

-- ============================================================
-- 2. 동물 게시 건
-- ============================================================

CREATE TABLE animal_case (
    id BIGINT NOT NULL,
    case_type VARCHAR(20) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    is_matchable BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    listed_at TIMESTAMPTZ NOT NULL,
    name VARCHAR(50),
    species VARCHAR(20) NOT NULL,
    breed_name VARCHAR(100),
    sex VARCHAR(20) NOT NULL,
    color VARCHAR(100),
    event_date DATE NOT NULL,
    event_time TIME,
    feature_text TEXT,
    closed_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_animal_case PRIMARY KEY (id)
);

COMMENT ON TABLE animal_case IS '동물 게시 건 | 사용자·공공 동물 데이터의 목록·매칭 공통 본체';
COMMENT ON COLUMN animal_case.id IS '동물 게시 건 ID | PK; 신고·보호 관찰 건 식별자';
COMMENT ON COLUMN animal_case.case_type IS '게시 건 유형 | LOST / SHELTERING';
COMMENT ON COLUMN animal_case.source_type IS '출처 유형 | USER / PUBLIC';
COMMENT ON COLUMN animal_case.status IS '게시 건 상태 | ACTIVE / CLOSED / DELETED';
COMMENT ON COLUMN animal_case.is_matchable IS '매칭 가능 여부 | 현재·과거 후보군 포함 여부';
COMMENT ON COLUMN animal_case.version IS '수정 버전 | 낙관적 잠금 버전';
COMMENT ON COLUMN animal_case.listed_at IS '목록 정렬 시각 | 사용자=등록, 공공=공고 시작일·사건·적재 시각 우선순위';
COMMENT ON COLUMN animal_case.name IS '동물 이름 | 사용자 입력 동물 이름';
COMMENT ON COLUMN animal_case.species IS '동물 종 | DOG / CAT / OTHER';
COMMENT ON COLUMN animal_case.breed_name IS '품종명 | 사용자 입력 또는 공공데이터 정규화 품종';
COMMENT ON COLUMN animal_case.sex IS '성별 | MALE / FEMALE / UNKNOWN';
COMMENT ON COLUMN animal_case.color IS '색상 | 동물 색상 설명';
COMMENT ON COLUMN animal_case.event_date IS '사건 날짜 | 실종일 또는 발견·접수일; 사용자 입력은 Asia/Seoul 기준 오늘 이하';
COMMENT ON COLUMN animal_case.event_time IS '사건 시각 | 사용자 입력 선택 시각, 공공데이터는 NULL';
COMMENT ON COLUMN animal_case.feature_text IS '특징 설명 | 외형·특이사항';
COMMENT ON COLUMN animal_case.closed_at IS '종료 일시 | CLOSED 전환 시각';
COMMENT ON COLUMN animal_case.deleted_at IS '삭제 일시 | 논리 삭제 시각';
COMMENT ON COLUMN animal_case.created_at IS '생성 일시 | 게시 건 생성 시각';
COMMENT ON COLUMN animal_case.updated_at IS '수정 일시 | 게시 건 최종 수정 시각';

CREATE TABLE animal_case_location (
    animal_case_id BIGINT NOT NULL,
    location_type VARCHAR(20) NOT NULL,
    region_code CHAR(5) NOT NULL,
    emd_code CHAR(10),
    public_location VARCHAR(255) NOT NULL,
    exact_location_ciphertext TEXT,
    exact_location_visible BOOLEAN NOT NULL DEFAULT FALSE,
    disclosure_policy_version VARCHAR(20),
    disclosure_consented_at TIMESTAMPTZ,
    latitude NUMERIC(9, 6),
    longitude NUMERIC(9, 6),
    CONSTRAINT pk_animal_case_location
        PRIMARY KEY (animal_case_id, location_type)
);

COMMENT ON TABLE animal_case_location IS '동물 게시 위치 | EVENT·CURRENT 역할별 공개·정확 위치';
COMMENT ON COLUMN animal_case_location.animal_case_id IS '동물 게시 건 ID | PK, FK -> animal_case.id; 복합 키의 게시 건';
COMMENT ON COLUMN animal_case_location.location_type IS '위치 유형 | PK; EVENT / CURRENT, 복합 키의 역할 구분';
COMMENT ON COLUMN animal_case_location.region_code IS '시군구 코드 | 5자리; 목록 지역 필터는 EVENT 값 사용';
COMMENT ON COLUMN animal_case_location.emd_code IS '읍면동 코드 | 선택 10자리; region_code 하위';
COMMENT ON COLUMN animal_case_location.public_location IS '공개 지역 | 코드에서 만든 표시 전용 문자열';
COMMENT ON COLUMN animal_case_location.exact_location_ciphertext IS '정확한 위치 암호문 | AES-256-GCM envelope';
COMMENT ON COLUMN animal_case_location.exact_location_visible IS '정확한 위치 공개 여부 | 위치별 선택, 기본 비공개; 다른 회원은 활성 사용자 게시물 상세에만 적용';
COMMENT ON COLUMN animal_case_location.disclosure_policy_version IS '공개 정책 버전 | 해당 위치 역할의 마지막 공개 동의; 현재 exact-location-v1';
COMMENT ON COLUMN animal_case_location.disclosure_consented_at IS '공개 동의 일시 | 해당 역할 안내를 확인한 서버 처리 시각';
COMMENT ON COLUMN animal_case_location.latitude IS '위도 | 내부 매칭용 -90..90';
COMMENT ON COLUMN animal_case_location.longitude IS '경도 | 내부 매칭용 -180..180';

CREATE TABLE user_post (
    animal_case_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    client_request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    close_reason VARCHAR(30),
    CONSTRAINT pk_user_post PRIMARY KEY (animal_case_id),
    CONSTRAINT uk_user_post_member_client_request UNIQUE (member_id, client_request_id)
);

COMMENT ON TABLE user_post IS '사용자 게시물 | 작성자 소유권과 등록 멱등성';
COMMENT ON COLUMN user_post.animal_case_id IS '동물 게시 건 ID | PK, FK -> animal_case.id; 사용자 게시물 본체';
COMMENT ON COLUMN user_post.member_id IS '회원 ID | FK -> member.id; 게시물 작성 회원';
COMMENT ON COLUMN user_post.client_request_id IS '클라이언트 요청 ID | UK(member_id, client_request_id); P3 멱등성 UUID';
COMMENT ON COLUMN user_post.request_hash IS '요청 해시 | canonical metadata와 사진 checksum 목록 SHA-256';
COMMENT ON COLUMN user_post.close_reason IS '종료 사유 | RETURNED / TRANSFERRED / OTHER';

CREATE TABLE animal_photo (
    id BIGINT NOT NULL,
    animal_case_id BIGINT NOT NULL,
    storage_type VARCHAR(20) NOT NULL,
    storage_uri TEXT NOT NULL,
    content_type VARCHAR(50),
    byte_size BIGINT,
    width_px INTEGER,
    height_px INTEGER,
    sort_order SMALLINT NOT NULL,
    checksum_sha256 CHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_animal_photo PRIMARY KEY (id),
    CONSTRAINT uk_animal_photo_case_sort_order
        UNIQUE (animal_case_id, sort_order)
);

COMMENT ON TABLE animal_photo IS '동물 사진 | 게시 건별 사용자 업로드·공공 URL 사진';
COMMENT ON COLUMN animal_photo.id IS '동물 사진 ID | PK; 사진 메타데이터 식별자';
COMMENT ON COLUMN animal_photo.animal_case_id IS '동물 게시 건 ID | FK -> animal_case.id; UK(animal_case_id, sort_order); 사진이 속한 게시 건';
COMMENT ON COLUMN animal_photo.storage_type IS '저장 유형 | USER_UPLOAD / PUBLIC_URL';
COMMENT ON COLUMN animal_photo.storage_uri IS '저장 위치 | 사용자 HDFS 절대 경로 또는 공공 이미지 출처 URL; 사용자 경로 외부 응답 금지';
COMMENT ON COLUMN animal_photo.content_type IS '콘텐츠 형식 | 사용자 정규화 사진은 image/jpeg 필수; 공공 URL은 NULL 가능';
COMMENT ON COLUMN animal_photo.byte_size IS '파일 바이트 수 | 사용자 정규화 결과는 양수 필수; 공공 URL은 NULL 가능';
COMMENT ON COLUMN animal_photo.width_px IS '저장 가로 크기 | 사용자 정규화 결과 1..4096px; 공공 URL은 NULL 가능';
COMMENT ON COLUMN animal_photo.height_px IS '저장 세로 크기 | 사용자 정규화 결과 1..4096px; 공공 URL은 NULL 가능';
COMMENT ON COLUMN animal_photo.sort_order IS '정렬 순서 | UK(animal_case_id, sort_order); 0..9, 0번은 대표 사진';
COMMENT ON COLUMN animal_photo.checksum_sha256 IS '파일 체크섬 | 사용자 정규화 결과의 SHA-256은 필수; 공공 URL은 NULL 가능';
COMMENT ON COLUMN animal_photo.created_at IS '생성 일시 | 사진 메타데이터 생성 시각';

CREATE TABLE member_photo_erasure_task (
    id BIGINT NOT NULL,
    storage_uri TEXT NOT NULL,
    deadline_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    last_error_code VARCHAR(30),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_member_photo_erasure_task PRIMARY KEY (id),
    CONSTRAINT uk_member_photo_erasure_task_storage_uri UNIQUE (storage_uri)
);

COMMENT ON TABLE member_photo_erasure_task IS '회원 사진 파기 작업 | 관계형 파기 뒤 HDFS 사용자 사진 삭제 재시도 outbox';
COMMENT ON COLUMN member_photo_erasure_task.id IS '작업 ID | PK; 내부 재시도 식별자';
COMMENT ON COLUMN member_photo_erasure_task.storage_uri IS 'HDFS 저장 경로 | UK; 사용자 최종 사진 경로만 허용, 로그·응답 노출 금지';
COMMENT ON COLUMN member_photo_erasure_task.deadline_at IS '파기 목표 일시 | 회원 탈퇴 시각 + 30일';
COMMENT ON COLUMN member_photo_erasure_task.attempt_count IS '시도 횟수 | 선점 때 증가하는 0 이상 값';
COMMENT ON COLUMN member_photo_erasure_task.next_attempt_at IS '다음 시도 일시 | 지수 백오프 뒤 재시도 가능 시각';
COMMENT ON COLUMN member_photo_erasure_task.lease_until IS '선점 만료 일시 | 다중 인스턴스 중복 실행 방지용 30분 lease';
COMMENT ON COLUMN member_photo_erasure_task.last_error_code IS '마지막 오류 코드 | PHOTO-006만 저장, 원문·스택 금지';
COMMENT ON COLUMN member_photo_erasure_task.created_at IS '생성 일시 | 관계형 파기 트랜잭션의 outbox 적재 시각';
COMMENT ON COLUMN member_photo_erasure_task.updated_at IS '수정 일시 | 마지막 선점·실패 상태 갱신 시각';

-- ============================================================
-- 3. 내부 채팅
-- ============================================================

CREATE TABLE chat_room (
    id BIGINT NOT NULL,
    animal_case_id BIGINT NOT NULL,
    owner_member_id BIGINT NOT NULL,
    requester_member_id BIGINT NOT NULL,
    owner_last_read_message_id BIGINT,
    requester_last_read_message_id BIGINT,
    last_message_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_chat_room PRIMARY KEY (id),
    CONSTRAINT uk_chat_room_case_requester
        UNIQUE (animal_case_id, requester_member_id)
);

COMMENT ON TABLE chat_room IS '채팅방 | 사용자 게시물 작성자와 요청자의 1:1 대화방';
COMMENT ON COLUMN chat_room.id IS '채팅방 ID | PK; 대화방 식별자';
COMMENT ON COLUMN chat_room.animal_case_id IS '동물 게시 건 ID | FK -> animal_case.id; UK(animal_case_id, requester_member_id); 대화 주제인 사용자 게시 건';
COMMENT ON COLUMN chat_room.owner_member_id IS '작성 회원 ID | FK -> member.id; 게시물 작성자';
COMMENT ON COLUMN chat_room.requester_member_id IS '요청 회원 ID | FK -> member.id; UK(animal_case_id, requester_member_id); 대화를 시작한 회원';
COMMENT ON COLUMN chat_room.owner_last_read_message_id IS '작성자 마지막 읽음 메시지 ID | 같은 방 메시지, 단조 증가; 순환 FK 없이 서비스 검증';
COMMENT ON COLUMN chat_room.requester_last_read_message_id IS '요청자 마지막 읽음 메시지 ID | 같은 방 메시지, 단조 증가; 순환 FK 없이 서비스 검증';
COMMENT ON COLUMN chat_room.last_message_at IS '마지막 메시지 일시 | 메시지가 없으면 NULL';
COMMENT ON COLUMN chat_room.created_at IS '생성 일시 | 채팅방 생성 시각';
COMMENT ON COLUMN chat_room.updated_at IS '수정 일시 | 채팅방 최종 갱신 시각';

CREATE TABLE chat_message (
    id BIGINT NOT NULL,
    chat_room_id BIGINT NOT NULL,
    sender_member_id BIGINT NOT NULL,
    client_message_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_chat_message PRIMARY KEY (id),
    CONSTRAINT uk_chat_message_sender_client_message UNIQUE (sender_member_id, client_message_id)
);

COMMENT ON TABLE chat_message IS '채팅 메시지 | 1:1 채팅방의 텍스트 메시지';
COMMENT ON COLUMN chat_message.id IS '채팅 메시지 ID | PK; 메시지 식별자';
COMMENT ON COLUMN chat_message.chat_room_id IS '채팅방 ID | FK -> chat_room.id; 메시지가 속한 대화방';
COMMENT ON COLUMN chat_message.sender_member_id IS '발신 회원 ID | FK -> member.id; 메시지 발신자';
COMMENT ON COLUMN chat_message.client_message_id IS '클라이언트 메시지 ID | UK(sender_member_id, client_message_id); C4 멱등성 UUID';
COMMENT ON COLUMN chat_message.request_hash IS '요청 해시 | 정규화된 메시지 내용 SHA-256';
COMMENT ON COLUMN chat_message.content IS '메시지 내용 | 공백 제외 1..1000자 텍스트';
COMMENT ON COLUMN chat_message.created_at IS '생성 일시 | 메시지 전송 시각';

CREATE TABLE chat_notification_outbox (
    id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    recipient_member_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until TIMESTAMPTZ,
    last_error_code VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT pk_chat_notification_outbox PRIMARY KEY (id),
    CONSTRAINT uk_chat_notification_outbox_message UNIQUE (message_id)
);

COMMENT ON TABLE chat_notification_outbox IS '채팅 알림 Outbox | 메시지와 원자 저장하는 수신자 개인 FCM 발송 이벤트';
COMMENT ON COLUMN chat_notification_outbox.id IS '채팅 알림 이벤트 ID | PK';
COMMENT ON COLUMN chat_notification_outbox.message_id IS '채팅 메시지 ID | FK -> chat_message.id; UK';
COMMENT ON COLUMN chat_notification_outbox.recipient_member_id IS '수신 회원 ID | FK -> member.id';
COMMENT ON COLUMN chat_notification_outbox.status IS '발송 상태 | PENDING, PROCESSING, SENT, SKIPPED';
COMMENT ON COLUMN chat_notification_outbox.attempt_count IS '발송 시도 횟수 | 기본 0';
COMMENT ON COLUMN chat_notification_outbox.next_attempt_at IS '다음 재시도 가능 일시';
COMMENT ON COLUMN chat_notification_outbox.lease_until IS 'worker 선점 만료 일시';
COMMENT ON COLUMN chat_notification_outbox.last_error_code IS '마지막 비민감 오류 분류 코드';
COMMENT ON COLUMN chat_notification_outbox.created_at IS '생성 일시';
COMMENT ON COLUMN chat_notification_outbox.completed_at IS 'SENT 또는 SKIPPED 종료 일시';

-- ============================================================
-- 4. 공공데이터
-- ============================================================

CREATE TABLE shelter (
    id BIGINT NOT NULL,
    care_reg_no VARCHAR(50) NOT NULL,
    name VARCHAR(150) NOT NULL,
    phone VARCHAR(50),
    address TEXT,
    jurisdiction VARCHAR(150),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_shelter PRIMARY KEY (id),
    CONSTRAINT uk_shelter_care_reg_no UNIQUE (care_reg_no)
);

COMMENT ON TABLE shelter IS '보호소 | 공공데이터 기반 보호소 정보';
COMMENT ON COLUMN shelter.id IS '보호소 ID | PK; 보호소 식별자';
COMMENT ON COLUMN shelter.care_reg_no IS '보호소 등록번호 | UK; 공공 API careRegNo, 유일 upsert 키';
COMMENT ON COLUMN shelter.name IS '보호소 이름 | 공공 보호소 명칭';
COMMENT ON COLUMN shelter.phone IS '보호소 전화번호 | 공공 공식 연락처';
COMMENT ON COLUMN shelter.address IS '보호소 주소 | 전체 careAddr, 로그인 상세에서만 노출';
COMMENT ON COLUMN shelter.jurisdiction IS '관할기관 | 공공 API orgNm';
COMMENT ON COLUMN shelter.created_at IS '생성 일시 | 보호소 생성 시각';
COMMENT ON COLUMN shelter.updated_at IS '수정 일시 | 보호소 최종 수정 시각';

CREATE TABLE ingestion_run (
    id BIGINT NOT NULL,
    source_system VARCHAR(50) NOT NULL,
    run_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    requested_from_date DATE,
    requested_to_date DATE,
    fetched_count INTEGER NOT NULL DEFAULT 0,
    inserted_count INTEGER NOT NULL DEFAULT 0,
    updated_count INTEGER NOT NULL DEFAULT 0,
    shelter_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    last_source_updated_at TIMESTAMPTZ,
    error_summary TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    summary_published_at TIMESTAMPTZ,
    CONSTRAINT pk_ingestion_run PRIMARY KEY (id)
);

COMMENT ON TABLE ingestion_run IS '공공데이터 수집 실행 | 수집 성공·실패와 처리 건수 이력';
COMMENT ON COLUMN ingestion_run.id IS '수집 실행 ID | PK; 공공데이터 수집 실행 식별자';
COMMENT ON COLUMN ingestion_run.source_system IS '원천 시스템 | 공공데이터 제공 시스템 식별자';
COMMENT ON COLUMN ingestion_run.run_type IS '수집 유형 | INITIAL_FULL / DAILY_INCREMENTAL / BACKFILL';
COMMENT ON COLUMN ingestion_run.status IS '수집 상태 | RUNNING / SUCCEEDED / FAILED';
COMMENT ON COLUMN ingestion_run.requested_from_date IS '요청 시작일 | 공공 API 조회 범위 시작일';
COMMENT ON COLUMN ingestion_run.requested_to_date IS '요청 종료일 | 공공 API 조회 범위 종료일';
COMMENT ON COLUMN ingestion_run.fetched_count IS '수신 건수 | 원천에서 받은 전체 건수';
COMMENT ON COLUMN ingestion_run.inserted_count IS '신규 건수 | 새로 생성한 건수';
COMMENT ON COLUMN ingestion_run.updated_count IS '갱신 건수 | 기존 데이터를 갱신한 건수';
COMMENT ON COLUMN ingestion_run.shelter_count IS '요약 대상 보호소 수 | 이번 실행에서 신규 동물이 입소한 서로 다른 보호소 집계';
COMMENT ON COLUMN ingestion_run.failed_count IS '실패 건수 | 처리에 실패한 건수';
COMMENT ON COLUMN ingestion_run.last_source_updated_at IS '최신 원본 수정 일시 | 실행에서 확인한 최신 updTm';
COMMENT ON COLUMN ingestion_run.error_summary IS '오류 요약 | 민감정보를 제외한 실패 요약';
COMMENT ON COLUMN ingestion_run.started_at IS '시작 일시 | 수집 실행 시작 시각';
COMMENT ON COLUMN ingestion_run.completed_at IS '완료 일시 | 성공·실패 종료 시각';
COMMENT ON COLUMN ingestion_run.summary_published_at IS '일일 요약 발송 시각 | 성공한 DAILY_INCREMENTAL 실행을 FCM 토픽에 발송한 시각';

CREATE TABLE shelter_animal (
    animal_case_id BIGINT NOT NULL,
    desertion_no VARCHAR(50) NOT NULL,
    shelter_id BIGINT NOT NULL,
    ingestion_run_id BIGINT,
    notice_no VARCHAR(100),
    notice_start_date DATE,
    notice_end_date DATE,
    process_state_raw VARCHAR(100),
    end_reason_raw VARCHAR(255),
    age_text VARCHAR(100),
    weight_text VARCHAR(100),
    neuter_status VARCHAR(20) NOT NULL,
    source_updated_at TIMESTAMPTZ,
    last_synced_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_shelter_animal PRIMARY KEY (animal_case_id),
    CONSTRAINT uk_shelter_animal_desertion_no UNIQUE (desertion_no)
);

COMMENT ON TABLE shelter_animal IS '공공 보호동물 | 구조번호·공고·보호소·동기화 상세';
COMMENT ON COLUMN shelter_animal.animal_case_id IS '동물 게시 건 ID | PK, FK -> animal_case.id; 공공 보호동물 본체';
COMMENT ON COLUMN shelter_animal.desertion_no IS '구조번호 | UK; 공공 API desertionNo, 유일 upsert 키';
COMMENT ON COLUMN shelter_animal.shelter_id IS '보호소 ID | FK -> shelter.id; 현재 보호소';
COMMENT ON COLUMN shelter_animal.ingestion_run_id IS '수집 실행 ID | FK -> ingestion_run.id; 마지막으로 갱신한 수집 실행';
COMMENT ON COLUMN shelter_animal.notice_no IS '공고번호 | 공공 API noticeNo';
COMMENT ON COLUMN shelter_animal.notice_start_date IS '공고 시작일 | 공공 API noticeSdt';
COMMENT ON COLUMN shelter_animal.notice_end_date IS '공고 종료일 | 공공 API noticeEdt';
COMMENT ON COLUMN shelter_animal.process_state_raw IS '원본 처리 상태 | 공공 API processState 원문';
COMMENT ON COLUMN shelter_animal.end_reason_raw IS '원본 종료 사유 | 공공 API endReason 원문';
COMMENT ON COLUMN shelter_animal.age_text IS '나이 원문 | 단위·추정 표현을 포함한 공공 API 값';
COMMENT ON COLUMN shelter_animal.weight_text IS '체중 원문 | 단위를 포함할 수 있는 공공 API 값';
COMMENT ON COLUMN shelter_animal.neuter_status IS '중성화 상태 | YES / NO / UNKNOWN';
COMMENT ON COLUMN shelter_animal.source_updated_at IS '원본 수정 일시 | 공공 API updTm 정규화 값';
COMMENT ON COLUMN shelter_animal.last_synced_at IS '마지막 동기화 일시 | 마지막 정상 동기화 시각';

CREATE TABLE lost_report (
    animal_case_id BIGINT NOT NULL,
    lost_key CHAR(64) NOT NULL,
    rfid_code VARCHAR(50),
    org_name VARCHAR(150),
    happen_place VARCHAR(500),
    ingestion_run_id BIGINT,
    first_seen_date DATE NOT NULL,
    last_seen_date DATE NOT NULL,
    last_synced_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_lost_report PRIMARY KEY (animal_case_id),
    CONSTRAINT uk_lost_report_lost_key UNIQUE (lost_key)
);

COMMENT ON TABLE lost_report IS '공공 분실 신고 | 신고자 연락처·상세 주소를 제외한 출처 상세';
COMMENT ON COLUMN lost_report.animal_case_id IS '동물 게시 건 ID | PK, FK -> animal_case.id; PUBLIC / LOST 본체';
COMMENT ON COLUMN lost_report.lost_key IS '분실 신고 멱등 키 | UK; 원천 조합의 SHA-256';
COMMENT ON COLUMN lost_report.ingestion_run_id IS '수집 실행 ID | FK -> ingestion_run.id; 마지막으로 확인한 실행';

CREATE TABLE dashboard_stat (
    stat_key VARCHAR(50) NOT NULL,
    region_code CHAR(5) NOT NULL DEFAULT '00000',
    payload JSONB NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_dashboard_stat PRIMARY KEY (stat_key, region_code)
);

COMMENT ON TABLE dashboard_stat IS '홈 인사이트 통계 | DATA 배치가 미리 계산한 카드 데이터';
COMMENT ON COLUMN dashboard_stat.stat_key IS '통계 키 | PK 일부; shelter_outcomes 등';
COMMENT ON COLUMN dashboard_stat.region_code IS '지역 코드 | PK 일부; 전국은 00000';

CREATE TABLE adoption_favorite (
    member_id BIGINT NOT NULL,
    animal_case_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_adoption_favorite PRIMARY KEY (member_id, animal_case_id)
);

COMMENT ON TABLE adoption_favorite IS '입양 탐색 찜 | 회원이 명시적으로 선택한 공공 보호동물';
COMMENT ON COLUMN adoption_favorite.member_id IS '회원 ID | PK 일부, FK -> member.id';
COMMENT ON COLUMN adoption_favorite.animal_case_id IS '동물 게시 건 ID | PK 일부, FK -> animal_case.id; PUBLIC / SHELTERING';
COMMENT ON COLUMN adoption_favorite.created_at IS '최초 찜 시각 | 같은 PUT 재요청에서는 유지';

-- ============================================================
-- 5. AI 매칭
-- ============================================================

CREATE TABLE match_run (
    id BIGINT NOT NULL,
    query_case_id BIGINT NOT NULL,
    query_case_version BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    model_id VARCHAR(100) NOT NULL,
    model_version VARCHAR(50) NOT NULL,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(100),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_match_run PRIMARY KEY (id)
);

COMMENT ON TABLE match_run IS '매칭 실행 | LOST 게시 건 기준의 DATA/AI·MapReduce 후보 계산 실행';
COMMENT ON COLUMN match_run.id IS '매칭 실행 ID | PK; 매칭 실행 식별자';
COMMENT ON COLUMN match_run.query_case_id IS '기준 게시 건 ID | FK -> animal_case.id; USER / LOST 게시 건';
COMMENT ON COLUMN match_run.query_case_version IS '기준 게시 건 버전 | 요청 시점 animal_case.version, 결과 STALE 판정용';
COMMENT ON COLUMN match_run.status IS '실행 상태 | PENDING / RUNNING / SUCCEEDED / FAILED';
COMMENT ON COLUMN match_run.model_id IS '모델 ID | 임베딩 모델 식별자';
COMMENT ON COLUMN match_run.model_version IS '모델 버전 | 전처리를 포함한 모델 버전';
COMMENT ON COLUMN match_run.candidate_count IS '후보 건수 | 성공 실행에서 저장한 0..20건';
COMMENT ON COLUMN match_run.error_code IS '오류 코드 | 민감정보·스택을 제외한 실패 분류';
COMMENT ON COLUMN match_run.started_at IS '시작 일시 | 매칭 실행 시작 시각';
COMMENT ON COLUMN match_run.completed_at IS '완료 일시 | 성공·실패 종료 시각';
COMMENT ON COLUMN match_run.created_at IS '생성 일시 | 매칭 실행 생성 시각';

CREATE TABLE match_candidate (
    id BIGINT NOT NULL,
    match_run_id BIGINT NOT NULL,
    target_case_id BIGINT NOT NULL,
    rank INTEGER NOT NULL,
    total_score DOUBLE PRECISION NOT NULL,
    image_score DOUBLE PRECISION,
    distance_km DOUBLE PRECISION,
    time_gap_days INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_match_candidate PRIMARY KEY (id),
    CONSTRAINT uk_match_candidate_run_target
        UNIQUE (match_run_id, target_case_id),
    CONSTRAINT uk_match_candidate_run_rank UNIQUE (match_run_id, rank)
);

COMMENT ON TABLE match_candidate IS '매칭 후보 | 매칭 실행별 SHELTERING 후보와 내부 평가값';
COMMENT ON COLUMN match_candidate.id IS '매칭 후보 ID | PK; 후보 결과 식별자';
COMMENT ON COLUMN match_candidate.match_run_id IS '매칭 실행 ID | FK -> match_run.id; UK(match_run_id, target_case_id), UK(match_run_id, rank); 후보를 생성한 실행';
COMMENT ON COLUMN match_candidate.target_case_id IS '후보 게시 건 ID | FK -> animal_case.id; UK(match_run_id, target_case_id); SHELTERING 대상 게시 건';
COMMENT ON COLUMN match_candidate.rank IS '후보 순위 | UK(match_run_id, rank); 실행 내 1..20';
COMMENT ON COLUMN match_candidate.total_score IS '최종 점수 | 내부 정렬·임계값용 0..1 값';
COMMENT ON COLUMN match_candidate.image_score IS '이미지 점수 | 내부 평가용 0..1 유사도';
COMMENT ON COLUMN match_candidate.distance_km IS '거리 | 내부 필터링·평가용 km';
COMMENT ON COLUMN match_candidate.time_gap_days IS '날짜 차이 | 실종일과 발견일 차이 일수';
COMMENT ON COLUMN match_candidate.created_at IS '생성 일시 | 후보 적재 시각';

-- ============================================================
-- 6. 외래키 관계
-- ============================================================

ALTER TABLE auth_session
    ADD CONSTRAINT fk_auth_session_member
    FOREIGN KEY (member_id) REFERENCES member (id)
    ON DELETE CASCADE;

ALTER TABLE animal_case_location
    ADD CONSTRAINT fk_animal_case_location_animal_case
    FOREIGN KEY (animal_case_id) REFERENCES animal_case (id)
    ON DELETE CASCADE;

ALTER TABLE user_post
    ADD CONSTRAINT fk_user_post_animal_case
    FOREIGN KEY (animal_case_id) REFERENCES animal_case (id)
    ON DELETE CASCADE;

ALTER TABLE user_post
    ADD CONSTRAINT fk_user_post_member
    FOREIGN KEY (member_id) REFERENCES member (id)
    ON DELETE RESTRICT;

ALTER TABLE animal_photo
    ADD CONSTRAINT fk_animal_photo_animal_case
    FOREIGN KEY (animal_case_id) REFERENCES animal_case (id)
    ON DELETE CASCADE;

ALTER TABLE chat_room
    ADD CONSTRAINT fk_chat_room_animal_case
    FOREIGN KEY (animal_case_id) REFERENCES animal_case (id)
    ON DELETE CASCADE;

ALTER TABLE chat_room
    ADD CONSTRAINT fk_chat_room_owner_member
    FOREIGN KEY (owner_member_id) REFERENCES member (id)
    ON DELETE CASCADE;

ALTER TABLE chat_room
    ADD CONSTRAINT fk_chat_room_requester_member
    FOREIGN KEY (requester_member_id) REFERENCES member (id)
    ON DELETE CASCADE;

ALTER TABLE chat_message
    ADD CONSTRAINT fk_chat_message_chat_room
    FOREIGN KEY (chat_room_id) REFERENCES chat_room (id)
    ON DELETE CASCADE;

ALTER TABLE chat_message
    ADD CONSTRAINT fk_chat_message_sender_member
    FOREIGN KEY (sender_member_id) REFERENCES member (id)
    ON DELETE CASCADE;

ALTER TABLE chat_notification_outbox
    ADD CONSTRAINT fk_chat_notification_outbox_message
    FOREIGN KEY (message_id) REFERENCES chat_message (id)
    ON DELETE CASCADE;

ALTER TABLE chat_notification_outbox
    ADD CONSTRAINT fk_chat_notification_outbox_recipient
    FOREIGN KEY (recipient_member_id) REFERENCES member (id)
    ON DELETE CASCADE;

ALTER TABLE shelter_animal
    ADD CONSTRAINT fk_shelter_animal_animal_case
    FOREIGN KEY (animal_case_id) REFERENCES animal_case (id)
    ON DELETE CASCADE;

ALTER TABLE shelter_animal
    ADD CONSTRAINT fk_shelter_animal_shelter
    FOREIGN KEY (shelter_id) REFERENCES shelter (id)
    ON DELETE RESTRICT;

ALTER TABLE shelter_animal
    ADD CONSTRAINT fk_shelter_animal_ingestion_run
    FOREIGN KEY (ingestion_run_id) REFERENCES ingestion_run (id)
    ON DELETE SET NULL;

ALTER TABLE lost_report
    ADD CONSTRAINT fk_lost_report_animal_case
    FOREIGN KEY (animal_case_id) REFERENCES animal_case (id)
    ON DELETE CASCADE;

ALTER TABLE lost_report
    ADD CONSTRAINT fk_lost_report_ingestion_run
    FOREIGN KEY (ingestion_run_id) REFERENCES ingestion_run (id)
    ON DELETE SET NULL;

ALTER TABLE adoption_favorite
    ADD CONSTRAINT fk_adoption_favorite_member
    FOREIGN KEY (member_id) REFERENCES member (id)
    ON DELETE CASCADE;

ALTER TABLE adoption_favorite
    ADD CONSTRAINT fk_adoption_favorite_animal_case
    FOREIGN KEY (animal_case_id) REFERENCES animal_case (id)
    ON DELETE CASCADE;

ALTER TABLE match_run
    ADD CONSTRAINT fk_match_run_animal_case
    FOREIGN KEY (query_case_id) REFERENCES animal_case (id)
    ON DELETE CASCADE;

ALTER TABLE match_candidate
    ADD CONSTRAINT fk_match_candidate_match_run
    FOREIGN KEY (match_run_id) REFERENCES match_run (id)
    ON DELETE CASCADE;

ALTER TABLE match_candidate
    ADD CONSTRAINT fk_match_candidate_animal_case
    FOREIGN KEY (target_case_id) REFERENCES animal_case (id)
    ON DELETE CASCADE;
