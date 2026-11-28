ALTER TABLE language_learning_auto_practice_job
    ADD COLUMN requested_date DATE NULL,
    ADD COLUMN rollover_at DATETIME(6) NULL,
    ADD COLUMN available_at DATETIME(6) NULL,
    ADD KEY idx_ll_auto_practice_request (user_id, origin_language, learning_language, requested_date);
