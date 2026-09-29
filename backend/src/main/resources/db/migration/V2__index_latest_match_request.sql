-- M1 polls the latest request regardless of status; success/active partial indexes do not cover it.
CREATE INDEX idx_match_run_latest_request
    ON match_run (query_case_id, created_at DESC, id DESC);
