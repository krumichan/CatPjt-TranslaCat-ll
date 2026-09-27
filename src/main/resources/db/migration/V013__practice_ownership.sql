CREATE TABLE language_learning_practice_set (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    learning_date DATE NOT NULL,
    domain VARCHAR(30) NOT NULL,
    mode VARCHAR(40) NOT NULL,
    complexity_band INT NOT NULL,
    question_count INT NOT NULL,
    request_json LONGTEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    generation_status VARCHAR(30) NOT NULL,
    generation_token VARCHAR(36) NULL,
    generation_lease_until DATETIME(6) NULL,
    next_attempt_at DATETIME(6) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    failure_code VARCHAR(100) NULL,
    official_score DOUBLE NULL,
    prompt_version VARCHAR(100) NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ll_practice_user_date_mode (user_id, learning_date, domain, mode),
    KEY idx_ll_practice_recovery (generation_status, next_attempt_at, generation_lease_until),
    CONSTRAINT fk_ll_practice_learner FOREIGN KEY (user_id) REFERENCES language_learning_learner(user_id)
);

CREATE TABLE language_learning_practice_question (
    id BIGINT NOT NULL AUTO_INCREMENT,
    practice_set_id BIGINT NOT NULL,
    order_no INT NOT NULL,
    content_json LONGTEXT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ll_practice_question_order (practice_set_id, order_no),
    CONSTRAINT fk_ll_practice_question_set FOREIGN KEY (practice_set_id) REFERENCES language_learning_practice_set(id)
);

CREATE TABLE language_learning_practice_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    question_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    answer_json LONGTEXT NOT NULL,
    correct BOOLEAN NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ll_practice_attempt_no (question_id, attempt_no),
    CONSTRAINT fk_ll_practice_attempt_question FOREIGN KEY (question_id) REFERENCES language_learning_practice_question(id)
);

CREATE TABLE language_learning_practice_metric (
    practice_set_id BIGINT NOT NULL,
    skill_tag VARCHAR(80) NOT NULL,
    score DOUBLE NOT NULL,
    sample_count INT NOT NULL,
    PRIMARY KEY (practice_set_id, skill_tag),
    CONSTRAINT fk_ll_practice_metric_set FOREIGN KEY (practice_set_id) REFERENCES language_learning_practice_set(id)
);

CREATE TABLE language_learning_vocabulary_mastery (
    user_id BIGINT NOT NULL,
    canonical_key VARCHAR(200) NOT NULL,
    display_expression VARCHAR(300) NOT NULL,
    score DOUBLE NOT NULL,
    evaluation_count INT NOT NULL,
    selected_count INT NOT NULL,
    last_selected_date DATE NULL,
    PRIMARY KEY (user_id, canonical_key),
    CONSTRAINT fk_ll_vocabulary_mastery_learner FOREIGN KEY (user_id) REFERENCES language_learning_learner(user_id)
);
