-- 동의 증빙은 탈퇴 30일 파기 대상이므로 전체 행에 NOT NULL을 걸지 않는다.
-- ACTIVE 행은 기존 제약으로 버전·시각이 이미 필수이며, 새 동의 여부도 CHECK로 강제한다.
ALTER TABLE member
    ADD COLUMN privacy_collection_agreed boolean;

-- 이미 보관 중인 버전·시각이 모두 있는 계정만 동의 여부를 이관한다.
-- 파기 완료 행의 NULL을 가입 시각으로 되살리지 않는다.
UPDATE member
SET privacy_collection_agreed = true
WHERE privacy_collection_policy_version IS NOT NULL
  AND privacy_collection_consented_at IS NOT NULL
  AND personal_data_erased_at IS NULL;

-- 파기 완료 표시가 있는 과거 행에 동의 증빙이 남아 있으면 먼저 제거한다.
-- 이 정리를 제약 추가보다 앞에 두어 기존 데이터 때문에 배포가 실패하지 않게 한다.
UPDATE member
SET privacy_collection_policy_version = NULL,
    privacy_collection_consented_at = NULL
WHERE personal_data_erased_at IS NOT NULL
  AND (
      privacy_collection_policy_version IS NOT NULL
      OR privacy_collection_consented_at IS NOT NULL
  );

ALTER TABLE member
    ADD CONSTRAINT ck_member_active_privacy_collection_agreed
        CHECK (status <> 'ACTIVE' OR privacy_collection_agreed IS TRUE),
    ADD CONSTRAINT ck_member_erased_privacy_collection
        CHECK (
            personal_data_erased_at IS NULL
            OR (
                privacy_collection_agreed IS NULL
                AND privacy_collection_policy_version IS NULL
                AND privacy_collection_consented_at IS NULL
            )
        );
