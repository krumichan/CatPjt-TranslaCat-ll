CREATE TABLE language_learning_listening_record_id (
    id BIGINT NOT NULL AUTO_INCREMENT,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE language_learning_listening_set (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    learning_date DATE NOT NULL,
    learning_language VARCHAR(20) NOT NULL,
    learning_mode VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    revision BIGINT NOT NULL,
    state_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ll_listening_set_user FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id),
    CONSTRAINT uk_ll_listening_set UNIQUE (user_id, learning_date, learning_language, learning_mode)
) ENGINE=InnoDB;

CREATE TABLE language_learning_listening_session (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    set_id BIGINT NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    status VARCHAR(30) NOT NULL,
    revision BIGINT NOT NULL,
    state_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ll_listening_session_user FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id),
    CONSTRAINT fk_ll_listening_session_set FOREIGN KEY (set_id) REFERENCES language_learning_listening_set (id),
    CONSTRAINT uk_ll_listening_session_idempotency UNIQUE (user_id, idempotency_key),
    INDEX ix_ll_listening_session_user (user_id, status)
) ENGINE=InnoDB;

CREATE TABLE language_learning_listening_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    aggregate_id BIGINT NOT NULL,
    job_type VARCHAR(30) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    lease_token VARCHAR(36),
    lease_until DATETIME(6),
    error_code VARCHAR(80),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ll_listening_job_user FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id),
    CONSTRAINT uk_ll_listening_job_idempotency UNIQUE (user_id, idempotency_key),
    INDEX ix_ll_listening_job_recovery (status, lease_until, id)
) ENGINE=InnoDB;

CREATE TABLE language_learning_listening_audio (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    audio_bytes LONGBLOB,
    retention_until DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ll_listening_audio_user FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id),
    INDEX ix_ll_listening_audio_retention (retention_until, deleted_at)
) ENGINE=InnoDB;
