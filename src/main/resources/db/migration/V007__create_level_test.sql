-- Level Test는 LL이 소유한다. 기존 V001~V006과 Core DB는 변경하지 않는다.
-- 학습 자료/오디오 기존 데이터는 이관하지 않는 새 DB 스키마다. UTC DATETIME(6)을 사용한다.

CREATE TABLE language_learning_level_test_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_uid VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    session_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    origin_language VARCHAR(20) NOT NULL,
    learning_language VARCHAR(20) NOT NULL,
    timezone VARCHAR(60) NOT NULL,
    current_question_number INT NOT NULL,
    current_complexity_band INT NOT NULL,
    base_level_score INT NULL,
    proficiency_band VARCHAR(40) NULL,
    domain_scores_json TEXT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    last_activity_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    completed_date DATE NULL,
    idempotency_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    operation_token VARCHAR(36) NULL,
    operation_kind VARCHAR(30) NULL,
    lease_until DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_level_session_uid UNIQUE (session_uid),
    CONSTRAINT uk_ll_level_session_user_key UNIQUE (user_id, idempotency_key),
    INDEX idx_ll_level_session_user_status (user_id, status),
    CONSTRAINT fk_ll_level_session_learner FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_level_test_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    question_number INT NOT NULL,
    question_json LONGTEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    pool_question_id BIGINT NULL,
    model_answer_audio_key VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_level_item_session_number UNIQUE (session_id, question_number),
    CONSTRAINT fk_ll_level_item_session FOREIGN KEY (session_id) REFERENCES language_learning_level_test_session (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_level_test_response (
    id BIGINT NOT NULL AUTO_INCREMENT,
    item_id BIGINT NOT NULL,
    idempotency_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    selected_option_key VARCHAR(100) NULL,
    selected_option_keys_json TEXT NOT NULL,
    text_answer TEXT NULL,
    audio_object_key VARCHAR(100) NULL,
    audio_content_type VARCHAR(100) NULL,
    audio_duration_ms INT NULL,
    audio_retention_until DATETIME(6) NULL,
    submitted_at DATETIME(6) NOT NULL,
    manual_evaluation_retry_count INT NOT NULL,
    submission_revision INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_level_response_item UNIQUE (item_id),
    CONSTRAINT fk_ll_level_response_item FOREIGN KEY (item_id) REFERENCES language_learning_level_test_item (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_level_test_evaluation (
    response_id BIGINT NOT NULL,
    evaluation_json LONGTEXT NOT NULL,
    evaluated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (response_id),
    CONSTRAINT fk_ll_level_evaluation_response FOREIGN KEY (response_id) REFERENCES language_learning_level_test_response (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_level_test_question_pool (
    id BIGINT NOT NULL AUTO_INCREMENT,
    pool_key VARCHAR(64) NOT NULL,
    origin_language VARCHAR(20) NOT NULL,
    learning_language VARCHAR(20) NOT NULL,
    question_json LONGTEXT NOT NULL,
    active BOOLEAN NOT NULL,
    quarantine_reason VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_level_pool_key UNIQUE (pool_key),
    INDEX idx_ll_level_pool_language (origin_language, learning_language, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_level_test_question_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    question_number INT NOT NULL,
    complexity_band INT NOT NULL,
    status VARCHAR(30) NOT NULL,
    claim_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    attempt INT NOT NULL,
    pool_question_id BIGINT NULL,
    failure_code VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_level_candidate_slot UNIQUE (session_id, question_number, complexity_band),
    INDEX idx_ll_level_candidate_status (status, lease_until),
    CONSTRAINT fk_ll_level_candidate_session FOREIGN KEY (session_id) REFERENCES language_learning_level_test_session (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ll_level_candidate_pool FOREIGN KEY (pool_question_id) REFERENCES language_learning_level_test_question_pool (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_level_test_baseline (
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    completion_id VARCHAR(36) NOT NULL,
    session_type VARCHAR(30) NOT NULL,
    base_level_score INT NOT NULL,
    proficiency_band VARCHAR(40) NOT NULL,
    completed_date DATE NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_ll_level_baseline_learner FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ll_level_baseline_session FOREIGN KEY (session_id) REFERENCES language_learning_level_test_session (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_level_test_audio (
    object_key VARCHAR(100) NOT NULL,
    owner_user_id BIGINT NULL,
    purpose VARCHAR(20) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    upload_token_hash VARCHAR(64) NULL,
    upload_until DATETIME(6) NULL,
    checksum_sha256 VARCHAR(64) NULL,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    retention_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (object_key),
    INDEX idx_ll_level_audio_expiry (status, retention_until, upload_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 자동 보충의 중복 실행만 제어한다. 기본 정책은 여전히 비활성이다.
CREATE TABLE language_learning_level_test_maintenance (
    id VARCHAR(30) NOT NULL,
    claim_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO language_learning_level_test_maintenance (id, claim_token, lease_until) VALUES ('POOL', NULL, NULL);
