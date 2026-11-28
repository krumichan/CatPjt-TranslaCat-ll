CREATE TABLE language_learning_auto_practice_plan (
    id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    origin_language VARCHAR(20) NOT NULL,
    learning_language VARCHAR(20) NOT NULL,
    active_slot TINYINT NULL,
    activated_at DATETIME(6) NULL,
    next_check_at DATETIME(6) NULL,
    content_json TEXT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ll_auto_practice_active (user_id, active_slot),
    KEY idx_ll_auto_practice_clock (active_slot, next_check_at, id),
    CONSTRAINT fk_ll_auto_practice_plan_learner FOREIGN KEY (user_id) REFERENCES language_learning_learner(user_id)
);

CREATE TABLE language_learning_auto_practice_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    plan_id CHAR(36) NOT NULL,
    plan_revision BIGINT NOT NULL,
    origin_language VARCHAR(20) NOT NULL,
    learning_language VARCHAR(20) NOT NULL,
    learning_date DATE NOT NULL,
    area VARCHAR(40) NOT NULL,
    quantity_json VARCHAR(256) NOT NULL,
    state VARCHAR(20) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_token CHAR(36) NULL,
    attempts INT NOT NULL DEFAULT 0,
    resource_id VARCHAR(80) NULL,
    reason VARCHAR(80) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ll_auto_practice_assignment (user_id, origin_language, learning_language, learning_date, area),
    KEY idx_ll_auto_practice_work (state, next_attempt_at, id),
    CONSTRAINT fk_ll_auto_practice_job_learner FOREIGN KEY (user_id) REFERENCES language_learning_learner(user_id)
);

CREATE TABLE language_learning_auto_practice_limit (
    area VARCHAR(40) NOT NULL,
    daily_max INT NOT NULL,
    PRIMARY KEY (area)
);
