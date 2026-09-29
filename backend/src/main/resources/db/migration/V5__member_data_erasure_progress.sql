ALTER TABLE member
    ADD COLUMN personal_data_erased_at timestamptz,
    ADD COLUMN relational_data_erased_at timestamptz,
    ADD CONSTRAINT ck_member_erasure_progress
        CHECK (
            (
                personal_data_erased_at IS NULL
                OR (
                    status = 'WITHDRAWN'
                    AND deleted_at IS NOT NULL
                    AND personal_data_erased_at >= deleted_at
                )
            )
            AND (
                relational_data_erased_at IS NULL
                OR (
                    personal_data_erased_at IS NOT NULL
                    AND relational_data_erased_at >= personal_data_erased_at
                )
            )
        );

CREATE INDEX idx_member_data_erasure_due
    ON member (deleted_at, id)
    WHERE status = 'WITHDRAWN'
      AND (personal_data_erased_at IS NULL OR relational_data_erased_at IS NULL);
