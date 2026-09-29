CREATE TABLE adoption_favorite (
    member_id bigint NOT NULL,
    animal_case_id bigint NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_adoption_favorite PRIMARY KEY (member_id, animal_case_id),
    CONSTRAINT fk_adoption_favorite_member
        FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT fk_adoption_favorite_animal_case
        FOREIGN KEY (animal_case_id) REFERENCES animal_case (id) ON DELETE CASCADE
);

CREATE INDEX idx_adoption_favorite_member_latest
    ON adoption_favorite (member_id, created_at DESC, animal_case_id DESC);

CREATE INDEX idx_adoption_favorite_animal_case
    ON adoption_favorite (animal_case_id);

CREATE INDEX idx_shelter_animal_adoption_candidate
    ON shelter_animal (notice_end_date, animal_case_id)
    WHERE process_state_raw = '보호중'
      AND notice_end_date IS NOT NULL;
