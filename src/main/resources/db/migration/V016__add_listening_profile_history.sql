CREATE TABLE language_learning_listening_metric_history (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    learning_language VARCHAR(20) NOT NULL,
    metric VARCHAR(40) NOT NULL,
    reference_evaluation_id VARCHAR(160) NOT NULL,
    state_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ll_listening_metric_user FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id),
    CONSTRAINT uk_ll_listening_metric_evaluation UNIQUE (user_id, reference_evaluation_id, metric),
    INDEX ix_ll_listening_metric_profile (user_id, learning_language, metric, created_at)
) ENGINE=InnoDB;

CREATE TABLE language_learning_listening_recommendation (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    learning_language VARCHAR(20) NOT NULL,
    metric VARCHAR(40) NOT NULL,
    calculation_version VARCHAR(100) NOT NULL,
    state_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ll_listening_recommendation_user FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id),
    CONSTRAINT uk_ll_listening_recommendation_version UNIQUE (user_id, learning_language, metric, calculation_version)
) ENGINE=InnoDB;
