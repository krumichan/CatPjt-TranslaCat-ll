-- Core의 계정 삭제와 독립적으로 재시작 가능한 LL 정리 기록만 추가한다.
-- 개인 학습 자료는 완료 후 남기지 않으며 learner tombstone으로 늦은 재생성을 막는다.
CREATE TABLE language_learning_account_deletion (
    user_id BIGINT NOT NULL,
    operation_id CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_ll_account_deletion_operation (operation_id),
    CONSTRAINT fk_ll_account_deletion_owner FOREIGN KEY (user_id) REFERENCES language_learning_learner(user_id),
    CONSTRAINT ck_ll_account_deletion_status CHECK (status IN ('PENDING','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE language_learning_account_deletion_object (
    operation_id CHAR(36) NOT NULL,
    store_kind VARCHAR(20) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (operation_id, store_kind, object_key),
    CONSTRAINT fk_ll_account_deletion_object_operation FOREIGN KEY (operation_id)
        REFERENCES language_learning_account_deletion(operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 파일을 DB에 연결하기 전의 쓰기도 기록하여 삭제와 경합한 업로드를 놓치지 않는다.
-- lease 시각은 진단용이다. 종료가 확인되지 않은 쓰기를 시간 경과만으로 완료 처리하지 않는다.
CREATE TABLE language_learning_audio_write (
    store_kind VARCHAR(20) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    user_id BIGINT NOT NULL,
    lease_until DATETIME(6) NULL,
    uncertain_reason VARCHAR(40) NULL,
    PRIMARY KEY (store_kind, object_key),
    KEY idx_ll_audio_write_owner (user_id),
    CONSTRAINT fk_ll_audio_write_owner FOREIGN KEY (user_id) REFERENCES language_learning_learner(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
