CREATE TABLE language_learning_speaking_topic (
    id BIGINT NOT NULL AUTO_INCREMENT,
    topic_code VARCHAR(100) NOT NULL,
    version INT NOT NULL,
    payload_json LONGTEXT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ll_speaking_topic_version (topic_code, version)
);

CREATE TABLE language_learning_speaking_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    create_idempotency_key VARCHAR(200) NOT NULL,
    learning_date DATE NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    evaluation_status VARCHAR(40) NOT NULL,
    completed_turns INT NOT NULL,
    total_duration_seconds BIGINT NOT NULL,
    opening_json LONGTEXT NOT NULL,
    session_summary LONGTEXT NULL,
    usage_json LONGTEXT NOT NULL,
    evaluation_version VARCHAR(100) NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    last_activity_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ll_speaking_session_idempotency (user_id, create_idempotency_key),
    KEY idx_ll_speaking_user_date (user_id, learning_date, id),
    KEY idx_ll_speaking_expiry (status, last_activity_at),
    CONSTRAINT fk_ll_speaking_session_learner FOREIGN KEY (user_id) REFERENCES language_learning_learner(user_id)
);

CREATE TABLE language_learning_speaking_turn (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    turn_index INT NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    problem_index INT NULL,
    attempt_index INT NULL,
    recording_revision INT NOT NULL,
    status VARCHAR(40) NOT NULL,
    upload_token VARCHAR(100) NOT NULL,
    upload_expires_at DATETIME(6) NOT NULL,
    content_json LONGTEXT NOT NULL,
    excluded_from_evaluation BOOLEAN NOT NULL,
    failed_stage VARCHAR(40) NULL,
    error_code VARCHAR(100) NULL,
    error_message VARCHAR(1000) NULL,
    manual_retry_count INT NOT NULL,
    completed_at DATETIME(6) NULL,
    execution_token VARCHAR(36) NULL,
    execution_lease_until DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ll_speaking_turn_order (session_id, turn_index),
    UNIQUE KEY uk_ll_speaking_turn_idempotency (session_id, idempotency_key),
    UNIQUE KEY uk_ll_speaking_turn_slot (session_id, problem_index, attempt_index),
    CONSTRAINT fk_ll_speaking_turn_session FOREIGN KEY (session_id) REFERENCES language_learning_speaking_session(id)
);

CREATE TABLE language_learning_speaking_evaluation_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    problem_index INT NOT NULL,
    result_kind VARCHAR(40) NOT NULL,
    result_policy_version VARCHAR(100) NOT NULL,
    source_snapshot_hash VARCHAR(128) NULL,
    request_json LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    claim_token VARCHAR(36) NULL,
    available_at DATETIME(6) NOT NULL,
    manual_retry_count INT NOT NULL,
    recovery_count INT NOT NULL,
    last_error VARCHAR(100) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ll_speaking_job_problem (session_id, problem_index),
    KEY idx_ll_speaking_job_due (status, available_at, id),
    CONSTRAINT fk_ll_speaking_job_session FOREIGN KEY (session_id) REFERENCES language_learning_speaking_session(id)
);

CREATE TABLE language_learning_speaking_result (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    problem_index INT NOT NULL,
    result_kind VARCHAR(40) NOT NULL,
    status VARCHAR(40) NOT NULL,
    response_json LONGTEXT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ll_speaking_result_problem (session_id, problem_index),
    CONSTRAINT fk_ll_speaking_result_session FOREIGN KEY (session_id) REFERENCES language_learning_speaking_session(id)
);

CREATE TABLE language_learning_speaking_audio (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    turn_id BIGINT NULL,
    role VARCHAR(30) NOT NULL,
    recording_revision INT NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_name VARCHAR(300) NULL,
    byte_length BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    retention_until DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ll_speaking_audio_object (object_key),
    KEY idx_ll_speaking_audio_lookup (session_id, turn_id, role, id),
    KEY idx_ll_speaking_audio_retention (retention_until, deleted_at),
    CONSTRAINT fk_ll_speaking_audio_session FOREIGN KEY (session_id) REFERENCES language_learning_speaking_session(id),
    CONSTRAINT fk_ll_speaking_audio_turn FOREIGN KEY (turn_id) REFERENCES language_learning_speaking_turn(id)
);

CREATE TABLE language_learning_speaking_ai_usage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    turn_id BIGINT NULL,
    usage_json LONGTEXT NOT NULL,
    manual_retry_attempt INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ll_speaking_usage_session (session_id, created_at),
    CONSTRAINT fk_ll_speaking_usage_session FOREIGN KEY (session_id) REFERENCES language_learning_speaking_session(id),
    CONSTRAINT fk_ll_speaking_usage_turn FOREIGN KEY (turn_id) REFERENCES language_learning_speaking_turn(id)
);

CREATE TABLE language_learning_stt_error_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    turn_id BIGINT NOT NULL,
    report_reference VARCHAR(80) NOT NULL,
    report_type VARCHAR(40) NOT NULL,
    report_status VARCHAR(40) NOT NULL,
    expected_text VARCHAR(4000) NULL,
    audio_analysis_consent BOOLEAN NOT NULL,
    audio_retention_until DATETIME(6) NULL,
    stt_metadata_json LONGTEXT NOT NULL,
    client_metadata_json LONGTEXT NOT NULL,
    support_requested BOOLEAN NOT NULL,
    support_reference VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ll_stt_report_reference (report_reference),
    KEY idx_ll_stt_report_user (user_id, created_at),
    CONSTRAINT fk_ll_stt_report_session FOREIGN KEY (session_id) REFERENCES language_learning_speaking_session(id),
    CONSTRAINT fk_ll_stt_report_turn FOREIGN KEY (turn_id) REFERENCES language_learning_speaking_turn(id),
    CONSTRAINT fk_ll_stt_report_learner FOREIGN KEY (user_id) REFERENCES language_learning_learner(user_id)
);
