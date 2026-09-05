ALTER TABLE generation_job
    ADD COLUMN heartbeat_at TIMESTAMPTZ,
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    ADD COLUMN next_retry_at TIMESTAMPTZ,
    ADD COLUMN started_at TIMESTAMPTZ,
    ADD COLUMN finished_at TIMESTAMPTZ,
    ADD COLUMN result_idempotency_key VARCHAR(256);

UPDATE chapter
SET status = 'WAITING'
WHERE status = 'PENDING';

UPDATE generation_job
SET status = 'WAITING'
WHERE status = 'PENDING';

ALTER TABLE chapter
    ALTER COLUMN status SET DEFAULT 'WAITING';

ALTER TABLE generation_job
    ALTER COLUMN status SET DEFAULT 'WAITING';

CREATE INDEX idx_generation_job_claim
    ON generation_job (status, next_retry_at, chapter_id, segment_index);

CREATE UNIQUE INDEX uq_book_running_status
    ON book (status)
    WHERE status = 'RUNNING';
