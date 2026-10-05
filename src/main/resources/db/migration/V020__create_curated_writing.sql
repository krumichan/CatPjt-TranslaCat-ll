-- 기존 점수형 Writing 테이블과 분리하여 신규 비점수 세션의 의미를 고정한다.
-- 운영 적용은 별도 배포 절차가 소유하며 이 migration은 additive이다.

CREATE TABLE language_learning_curated_writing_catalog (
    id VARCHAR(80) NOT NULL,
    version INT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    release_id VARCHAR(80) NOT NULL,
    review_status VARCHAR(20) NOT NULL,
    origin_language VARCHAR(16) NOT NULL,
    learning_language VARCHAR(16) NOT NULL,
    writing_type VARCHAR(30) NOT NULL,
    target_band INT NOT NULL,
    semantic_key VARCHAR(100) NOT NULL,
    topic_key VARCHAR(100) NOT NULL,
    content_json JSON NOT NULL,
    approved_by VARCHAR(100) NULL,
    approval_manifest_hash CHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id, version),
    CONSTRAINT ck_ll_cwc_status CHECK (review_status IN ('DRAFT','AUTO_REVIEWED','REVIEW_REQUIRED','APPROVED','RETIRED')),
    CONSTRAINT ck_ll_cwc_type CHECK (writing_type IN ('TRANSLATION','GUIDED','FREE')),
    CONSTRAINT ck_ll_cwc_band CHECK (target_band BETWEEN 1 AND 5),
    INDEX idx_ll_cwc_select (release_id, origin_language, learning_language, writing_type, target_band, review_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_curated_writing_set (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    learning_date DATE NOT NULL,
    writing_type VARCHAR(30) NOT NULL,
    policy_version VARCHAR(50) NOT NULL,
    result_policy VARCHAR(30) NOT NULL,
    release_id VARCHAR(80) NOT NULL,
    origin_language VARCHAR(16) NOT NULL,
    learning_language VARCHAR(16) NOT NULL,
    base_band INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    re_practice BOOLEAN NOT NULL DEFAULT FALSE,
    replacement_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_cws_user_date_type UNIQUE (user_id, learning_date, writing_type),
    CONSTRAINT uk_ll_cws_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_ll_cws_learner FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ll_cws_policy CHECK (policy_version = 'curated-writing-v1' AND result_policy = 'REFERENCE_ONLY'),
    CONSTRAINT ck_ll_cws_status CHECK (status IN ('READY','COMPLETED')),
    CONSTRAINT ck_ll_cws_band CHECK (base_band BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_curated_writing_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    set_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    item_order INT NOT NULL,
    difficulty VARCHAR(30) NOT NULL,
    target_band INT NOT NULL,
    catalog_id VARCHAR(80) NOT NULL,
    catalog_version INT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    semantic_key VARCHAR(100) NOT NULL,
    public_json JSON NOT NULL,
    private_json JSON NOT NULL,
    content_revision CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_cwi_set_order UNIQUE (set_id, item_order),
    CONSTRAINT uk_ll_cwi_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_ll_cwi_set_owner FOREIGN KEY (set_id, user_id) REFERENCES language_learning_curated_writing_set (id, user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ll_cwi_catalog FOREIGN KEY (catalog_id, catalog_version) REFERENCES language_learning_curated_writing_catalog (id, version) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_ll_cwi_order CHECK (item_order BETWEEN 1 AND 5),
    CONSTRAINT ck_ll_cwi_difficulty CHECK (difficulty IN ('REVIEW','NORMAL','CHALLENGE')),
    CONSTRAINT ck_ll_cwi_band CHECK (target_band BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_curated_writing_answer (
    id BIGINT NOT NULL AUTO_INCREMENT,
    item_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    attempt_date DATE NOT NULL,
    answer_text TEXT NOT NULL,
    content_revision CHAR(64) NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_cwa_item_date UNIQUE (item_id, attempt_date),
    CONSTRAINT fk_ll_cwa_item_owner FOREIGN KEY (item_id, user_id) REFERENCES language_learning_curated_writing_item (id, user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_ll_cwa_user (user_id, submitted_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
