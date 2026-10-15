CREATE TABLE ll_web_ai_plans (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    payload_json LONGTEXT NOT NULL,
    token_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ll_web_ai_plan_token (token_hash),
    KEY ix_ll_web_ai_plans_user (user_id, deleted, created_at),
    CONSTRAINT fk_ll_web_ai_plans_learner FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id),
    CONSTRAINT ck_ll_web_ai_plan_json CHECK (JSON_VALID(payload_json))
);

CREATE TABLE ll_web_ai_runs (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    plan_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    execution_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ll_web_ai_run_execution (plan_id, execution_key),
    KEY ix_ll_web_ai_runs_user (user_id, created_at),
    CONSTRAINT fk_ll_web_ai_runs_plan FOREIGN KEY (plan_id) REFERENCES ll_web_ai_plans (id),
    CONSTRAINT fk_ll_web_ai_runs_learner FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id),
    CONSTRAINT ck_ll_web_ai_run_json CHECK (JSON_VALID(payload_json))
);

CREATE TABLE ll_web_ai_results (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    submission_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    lease_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    attempts INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ll_web_ai_result_submission (run_id, submission_id),
    KEY ix_ll_web_ai_results_user (user_id, created_at),
    CONSTRAINT fk_ll_web_ai_results_run FOREIGN KEY (run_id) REFERENCES ll_web_ai_runs (id),
    CONSTRAINT fk_ll_web_ai_results_learner FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id),
    CONSTRAINT ck_ll_web_ai_result_json CHECK (JSON_VALID(payload_json))
);

CREATE TABLE ll_web_ai_items (
    run_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    item_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (run_id, item_id),
    KEY ix_ll_web_ai_items_result (result_id),
    CONSTRAINT fk_ll_web_ai_items_run FOREIGN KEY (run_id) REFERENCES ll_web_ai_runs (id),
    CONSTRAINT fk_ll_web_ai_items_result FOREIGN KEY (result_id) REFERENCES ll_web_ai_results (id)
);
