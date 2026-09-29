CREATE TABLE adoption_swipe (
    member_id bigint NOT NULL,
    animal_case_id bigint NOT NULL,
    swiped_at timestamptz NOT NULL,
    CONSTRAINT pk_adoption_swipe PRIMARY KEY (member_id, animal_case_id),
    CONSTRAINT fk_adoption_swipe_member
        FOREIGN KEY (member_id) REFERENCES member (id) ON DELETE CASCADE,
    CONSTRAINT fk_adoption_swipe_animal_case
        FOREIGN KEY (animal_case_id) REFERENCES animal_case (id) ON DELETE CASCADE
);

CREATE INDEX idx_adoption_swipe_member_latest
    ON adoption_swipe (member_id, swiped_at DESC, animal_case_id DESC);

CREATE INDEX idx_adoption_swipe_animal_case
    ON adoption_swipe (animal_case_id);
