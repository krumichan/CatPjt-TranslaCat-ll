ALTER TABLE language_learning_speaking_audio
    ADD COLUMN physical_deleted_at DATETIME(6) NULL,
    ADD COLUMN delete_claim_token VARCHAR(36) NULL,
    ADD COLUMN delete_lease_until DATETIME(6) NULL,
    ADD KEY idx_ll_speaking_audio_delete (physical_deleted_at, delete_lease_until, retention_until);
