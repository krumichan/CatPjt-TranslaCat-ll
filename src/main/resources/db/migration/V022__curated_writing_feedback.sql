-- 가변 N 답안에만 연결되는 비점수 피드백. 기존 평가/metric/profile 테이블은 변경하지 않는다.
-- 답안 ID와 소유자·문항을 한 외래키로 묶어 다른 사용자의 결과가 섞이지 않게 한다.
ALTER TABLE language_learning_curated_writing_answer
    ADD CONSTRAINT uk_ll_cwa_id_item_user UNIQUE (id, item_id, user_id);

CREATE TABLE language_learning_curated_writing_feedback (
    id BIGINT NOT NULL AUTO_INCREMENT,
    answer_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    set_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    policy_version VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    answer_hash CHAR(64) NOT NULL,
    content_revision CHAR(64) NOT NULL,
    source_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    claim_token VARCHAR(36) NULL,
    claim_until DATETIME(6) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    result_json JSON NULL,
    result_hash CHAR(64) NULL,
    failure_code VARCHAR(80) NULL,
    provider VARCHAR(30) NULL,
    model VARCHAR(80) NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_cwf_answer UNIQUE (answer_id),
    CONSTRAINT fk_ll_cwf_answer_owner FOREIGN KEY (answer_id, item_id, user_id)
        REFERENCES language_learning_curated_writing_answer (id, item_id, user_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ll_cwf_set_owner FOREIGN KEY (set_id, user_id)
        REFERENCES language_learning_curated_writing_set (id, user_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ll_cwf_policy CHECK (policy_version = 'curated-writing-feedback-v1'),
    CONSTRAINT ck_ll_cwf_status CHECK (status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED','UNCERTAIN')),
    CONSTRAINT ck_ll_cwf_attempts CHECK (attempt_count BETWEEN 0 AND 2),
    INDEX idx_ll_cwf_owner_set (user_id, set_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
