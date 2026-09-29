-- 홈 인사이트 카드(D3)용 배치 집계 저장소.
--
-- 홈의 카드 5장 중 지역 조각(공고 마감·주간 입소·지역 실종)은 요청 시 SQL 로 바로 계산할 수 있지만,
-- "보호소에 들어온 동물은 어떻게 되나"(최근 3년 종결 건의 반환·입양 비율, 평균 공고 기간)는 HDFS 백필
-- 163만 건을 훑어야 한다. 그 결과를 DATA 배치(data/collector/shelter_outcomes.py)가 이 표에 넣고
-- 백엔드는 읽기만 한다. payload 를 jsonb 로 두는 이유: 카드가 늘거나 필드가 바뀔 때마다 마이그레이션을
-- 만들지 않기 위해서다 — 키·지역이 행의 정체이고 내용은 배치와 백엔드 DTO 가 함께 정한다.
--
-- region_code '00000' 은 전국이다. 지역별 통계(P1)가 생기면 같은 stat_key 에 시·군·구 코드 행이 추가된다.

CREATE TABLE dashboard_stat (
    stat_key varchar(50) NOT NULL,
    region_code char(5) NOT NULL DEFAULT '00000',
    payload jsonb NOT NULL,
    computed_at timestamptz NOT NULL,
    CONSTRAINT pk_dashboard_stat PRIMARY KEY (stat_key, region_code),
    CONSTRAINT ck_dashboard_stat_region_code CHECK (region_code ~ '^[0-9]{5}$')
);

-- 적재기 역할이 있으면 쓰기 권한을 준다 (V3 와 같은 방식 — setup-loader-db.sh 재실행과 동치).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shelter_loader') THEN
        GRANT SELECT, INSERT, UPDATE ON dashboard_stat TO shelter_loader;
    END IF;
END
$$;
