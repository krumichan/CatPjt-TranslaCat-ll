-- 키워드 카탈로그/선택만 LL로 이전한다. 숙련도·프로필·활동 기록은 아직 Core 소유다.
-- 기존 V001~V004와 Core 테이블/데이터는 변경하지 않는다. 실제 카탈로그 데이터는 자동 복제하지 않는다.

CREATE TABLE language_learning_system_keyword (
    id BIGINT NOT NULL AUTO_INCREMENT,
    text VARCHAR(200) NOT NULL,
    normalized_text VARCHAR(200) NOT NULL,
    keyword_type VARCHAR(30) NOT NULL,
    canonical_key VARCHAR(200) NULL,
    parent_keyword_id BIGINT NULL,
    sort_order INT NOT NULL,
    active BOOLEAN NOT NULL,
    created_by VARCHAR(50) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(50) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_system_keyword_normalized_type UNIQUE (normalized_text, keyword_type),
    CONSTRAINT fk_ll_system_keyword_parent FOREIGN KEY (parent_keyword_id) REFERENCES language_learning_system_keyword (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_system_keyword_locale (
    id BIGINT NOT NULL AUTO_INCREMENT,
    system_keyword_id BIGINT NOT NULL,
    locale VARCHAR(20) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_system_keyword_locale UNIQUE (system_keyword_id, locale),
    CONSTRAINT fk_ll_keyword_locale_system FOREIGN KEY (system_keyword_id) REFERENCES language_learning_system_keyword (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_custom_keyword (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    text VARCHAR(200) NOT NULL,
    normalized_text VARCHAR(200) NOT NULL,
    keyword_type VARCHAR(30) NOT NULL,
    canonical_key VARCHAR(200) NULL,
    parent_system_keyword_id BIGINT NULL,
    active BOOLEAN NOT NULL,
    available_from DATE NOT NULL,
    pending_text VARCHAR(200) NULL,
    pending_normalized_text VARCHAR(200) NULL,
    pending_keyword_type VARCHAR(30) NULL,
    pending_canonical_key VARCHAR(200) NULL,
    pending_parent_system_keyword_id BIGINT NULL,
    pending_parent_changed BOOLEAN NOT NULL,
    pending_active BOOLEAN NULL,
    pending_effective_date DATE NULL,
    created_by VARCHAR(50) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(50) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_custom_keyword_user_normalized_type UNIQUE (user_id, normalized_text, keyword_type),
    INDEX idx_ll_custom_keyword_user_active (user_id, active),
    CONSTRAINT fk_ll_custom_keyword_learner FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ll_custom_keyword_parent FOREIGN KEY (parent_system_keyword_id) REFERENCES language_learning_system_keyword (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ll_custom_keyword_pending_parent FOREIGN KEY (pending_parent_system_keyword_id) REFERENCES language_learning_system_keyword (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_user_system_keyword (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    system_keyword_id BIGINT NOT NULL,
    active BOOLEAN NOT NULL,
    available_from DATE NOT NULL,
    pending_active BOOLEAN NULL,
    pending_effective_date DATE NULL,
    created_by VARCHAR(50) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_by VARCHAR(50) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ll_user_system_keyword UNIQUE (user_id, system_keyword_id),
    CONSTRAINT fk_ll_user_keyword_learner FOREIGN KEY (user_id) REFERENCES language_learning_learner (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ll_user_keyword_system FOREIGN KEY (system_keyword_id) REFERENCES language_learning_system_keyword (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 空のカタログでも階層検証と重複判定を直列化するための固定ロック行。
CREATE TABLE language_learning_keyword_catalog_lock (
    id INT NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO language_learning_keyword_catalog_lock (id) VALUES (1);
