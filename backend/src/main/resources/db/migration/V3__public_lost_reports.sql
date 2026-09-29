-- 공공 분실동물 신고를 animal_case 에 PUBLIC / LOST 로 담기 위한 변경 (S15P21B206-145).
--
-- 지금까지 PUBLIC 은 SHELTERING 만 허용했다 (docs/erd.md 불변식 1·2). 분실동물 API(lossInfoService)를
-- '찾고 있어요' 목록에 넣으려면 PUBLIC / LOST 조합이 필요하다. 출처별 상세는 shelter_animal 이 아니라
-- 새 1:1 테이블 lost_report 에 둔다 — 보호소가 없고, 고유 ID·상태·갱신 시각이 없는 원천이라 열이 다르다.
--
-- 신고자 이름·전화번호는 저장하지 않는다 (2026-09-15 결정). 비회원 제3자의 개인정보를 우리 DB 에 둘 이유가 없고,
-- 상세 화면은 관할 기관명과 "동물보호관리시스템에서 확인" 안내만 보여준다. 도로명 상세 주소도 저장하지 않는다 —
-- 위치는 animal_case_location(EVENT) 의 시·군·구 공개 지역만이다.

ALTER TABLE animal_case
    DROP CONSTRAINT ck_animal_case_source_type_pair,
    ADD CONSTRAINT ck_animal_case_source_type_pair
        CHECK (
            (source_type = 'USER')
            OR (source_type = 'PUBLIC' AND case_type IN ('SHELTERING', 'LOST'))
        );

CREATE TABLE lost_report (
    animal_case_id bigint NOT NULL,
    -- 원천에 고유 ID 가 없어 만든 멱등 키: sha256(happenDt + 원문 happenAddr + popfile) — 적재기와 HDFS 스냅샷이 같은 규칙
    lost_key char(64) NOT NULL,
    -- 마이크로칩 번호(29% 만 있음). 있으면 개체 식별에 쓸 수 있다
    rfid_code varchar(50),
    -- 관할 기관명 (원천 orgNm). 신고자 연락처 대신 상세에 보여 주는 유일한 출처 정보
    org_name varchar(150),
    -- 자유 지명 (원천 happenPlace, "○○고등학교 골목"). 상세 주소·건물명은 저장하지 않는다
    happen_place varchar(500),
    ingestion_run_id bigint,
    -- 매일 전량 스냅샷에서 처음/마지막으로 본 날짜. 원천에 상태가 없어 목록에서 사라지면 CLOSED 로 내린다
    first_seen_date date NOT NULL,
    last_seen_date date NOT NULL,
    last_synced_at timestamptz NOT NULL,
    CONSTRAINT pk_lost_report PRIMARY KEY (animal_case_id),
    CONSTRAINT uk_lost_report_lost_key UNIQUE (lost_key),
    CONSTRAINT ck_lost_report_lost_key CHECK (lost_key ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_lost_report_seen_dates CHECK (last_seen_date >= first_seen_date),
    CONSTRAINT fk_lost_report_animal_case
        FOREIGN KEY (animal_case_id) REFERENCES animal_case (id) ON DELETE CASCADE,
    CONSTRAINT fk_lost_report_ingestion_run
        FOREIGN KEY (ingestion_run_id) REFERENCES ingestion_run (id) ON DELETE SET NULL
);

COMMENT ON TABLE lost_report IS '공공 분실동물 신고(lossInfoService) 출처 상세. 신고자 연락처·상세 주소는 저장하지 않는다.';

-- 적재기 역할이 있는 환경(운영 서버 1)에서만 권한을 준다. 개발·CI DB 에는 역할이 없다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shelter_loader') THEN
        GRANT SELECT, INSERT, UPDATE ON lost_report TO shelter_loader;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'match_engine') THEN
        GRANT SELECT ON lost_report TO match_engine;
    END IF;
END
$$;
