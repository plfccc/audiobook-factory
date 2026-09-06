ALTER TABLE book
    ADD COLUMN active_scope_id VARCHAR(128);

ALTER TABLE generation_job
    ADD COLUMN run_id VARCHAR(128),
    ADD COLUMN batch_id VARCHAR(128),
    ADD COLUMN scope_id VARCHAR(128);

CREATE INDEX idx_generation_job_scope_claim
    ON generation_job (scope_id, status, next_retry_at, chapter_id, segment_index);
