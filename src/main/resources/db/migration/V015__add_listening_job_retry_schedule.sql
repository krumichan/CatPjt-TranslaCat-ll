ALTER TABLE language_learning_listening_job
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD INDEX idx_listening_job_available (status, available_at, id);
