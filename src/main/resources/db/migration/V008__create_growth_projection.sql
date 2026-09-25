-- V008: 공통 성장 데이터의 소유권을 LL로 이전한다. Core DB/테이블에는 접근하지 않는다.
-- 기존 V001~V007은 변경하지 않는다. 사용자 계정 FK 대신 LL learner만 참조한다.

CREATE TABLE language_learning_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    profile_version VARCHAR(30) NOT NULL,
    state VARCHAR(30) NOT NULL,
    base_level_score DOUBLE NULL,
    calibration_started_date DATE NULL,
    calibration_completed_date DATE NULL,
    meaning_score DOUBLE NULL,
    grammar_score DOUBLE NULL,
    vocabulary_score DOUBLE NULL,
    naturalness_score DOUBLE NULL,
    expression_score DOUBLE NULL,
    review_performance DOUBLE NULL,
    normal_performance DOUBLE NULL,
    challenge_performance DOUBLE NULL,
    evaluation_count INT NOT NULL,
    confidence DOUBLE NOT NULL,
    trend VARCHAR(30) NOT NULL,
    additional_signals_json TEXT NOT NULL,
    baseline_completion_id VARCHAR(36) NULL,
    baseline_completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_g_profile UNIQUE (user_id),
    CONSTRAINT fk_ll_g_profile FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_keyword_mastery (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    canonical_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    score DOUBLE NOT NULL,
    evaluation_count INT NOT NULL,
    last_selected_date DATE NULL,
    selected_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_g_keyword_mastery UNIQUE (user_id, canonical_key),
    CONSTRAINT fk_ll_g_keyword_mastery FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_ll_g_mastery_score (user_id, score, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_profile_signal (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    signal_type VARCHAR(40) NOT NULL,
    signal_key VARCHAR(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    occurrence_count INT NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_g_profile_signal UNIQUE (user_id, signal_type, signal_key),
    CONSTRAINT fk_ll_g_profile_signal FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_ll_g_signal_count (user_id, signal_type, occurrence_count, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_profile_evidence (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    source VARCHAR(30) NOT NULL,
    metric_type VARCHAR(40) NULL,
    pattern_key VARCHAR(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    direction VARCHAR(30) NOT NULL,
    evidence_count INT NOT NULL,
    weighted_evidence DOUBLE NOT NULL,
    average_confidence DOUBLE NOT NULL,
    recommended_focus VARCHAR(1000) NULL,
    last_seen_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_g_profile_evidence UNIQUE (user_id, source, pattern_key, direction),
    CONSTRAINT fk_ll_g_profile_evidence FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_activity (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    source VARCHAR(30) NOT NULL,
    reference_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    learning_date DATE NOT NULL,
    title VARCHAR(300) NOT NULL,
    duration_seconds BIGINT NOT NULL,
    status VARCHAR(40) NOT NULL,
    overall_score DOUBLE NULL,
    evaluation_confidence DOUBLE NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    metadata_json TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_g_activity UNIQUE (user_id, source, reference_id),
    CONSTRAINT fk_ll_g_activity FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_ll_g_activity_date (user_id, source, learning_date, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_metric_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    metric_type VARCHAR(40) NOT NULL,
    state VARCHAR(30) NOT NULL,
    score DOUBLE NULL,
    confidence DOUBLE NULL,
    not_evaluable_reason VARCHAR(1000) NULL,
    created_by VARCHAR(100) NOT NULL,
    updated_by VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_g_metric_history UNIQUE (activity_id, metric_type),
    CONSTRAINT fk_ll_g_metric_history FOREIGN KEY (activity_id) REFERENCES language_learning_activity (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_growth_stream (
    source_instance_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    last_sequence BIGINT NOT NULL,
    PRIMARY KEY (source_instance_id, user_id),
    CONSTRAINT fk_ll_g_stream_learner FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_growth_receipt (
    event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_instance_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    stream_sequence BIGINT NOT NULL,
    envelope_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at VARCHAR(40) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT uk_ll_g_receipt_sequence UNIQUE (source_instance_id, user_id, stream_sequence),
    CONSTRAINT fk_ll_g_receipt_stream FOREIGN KEY (source_instance_id, user_id) REFERENCES language_learning_growth_stream (source_instance_id, user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_growth_operation (
    source_instance_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    operation_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (source_instance_id, user_id, operation_key),
    CONSTRAINT fk_ll_g_operation_stream FOREIGN KEY (source_instance_id, user_id) REFERENCES language_learning_growth_stream (source_instance_id, user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 완료한 V007 레벨 테스트를 다시 치르지 않아도 된다. Core 이력을 복사하지 않는다.
INSERT INTO language_learning_profile (
    user_id, profile_version, state, base_level_score, calibration_started_date,
    calibration_completed_date, meaning_score, grammar_score, vocabulary_score, naturalness_score,
    expression_score, review_performance, normal_performance, challenge_performance,
    evaluation_count, confidence, trend, additional_signals_json, baseline_completion_id,
    baseline_completed_at, created_at, updated_at, created_by, updated_by
)
SELECT user_id, 'PROFILE', 'CALIBRATING', base_level_score, completed_date,
    NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, 'stable', '{}',
    completion_id, completed_at, completed_at, completed_at, 'MIGRATION_V008', 'MIGRATION_V008'
FROM language_learning_level_test_baseline;

INSERT INTO language_learning_activity (
    user_id, source, reference_id, learning_date, title, duration_seconds, status,
    overall_score, evaluation_confidence, started_at, completed_at, metadata_json,
    created_at, updated_at, created_by, updated_by
)
SELECT user_id, 'LEVEL_TEST', CONCAT('LL_LEVEL_TEST:', completion_id), completed_date,
    'Language Level Test', GREATEST(0, TIMESTAMPDIFF(SECOND, started_at, completed_at)),
    'COMPLETED', NULL, NULL, started_at, completed_at, '{}',
    completed_at, completed_at, 'MIGRATION_V008', 'MIGRATION_V008'
FROM language_learning_level_test_baseline;
