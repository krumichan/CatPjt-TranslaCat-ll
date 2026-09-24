-- 수신 원장은 평가 사실의 전달 검증용이다. 기존 Profile/Activity/Metric/Mastery를 대체하지 않는다.
-- V001~V005 및 기존 데이터는 변경하지 않는다. source UUID는 환경별로 고정한다.
CREATE TABLE language_learning_result_stream (
    source_instance_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    last_sequence BIGINT NOT NULL,
    PRIMARY KEY (source_instance_id, user_id),
    CONSTRAINT fk_ll_result_stream_learner FOREIGN KEY (user_id)
        REFERENCES language_learning_learner (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE language_learning_result_event (
    event_id VARCHAR(36) NOT NULL,
    schema_version INT NOT NULL,
    source_instance_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    stream_sequence BIGINT NOT NULL,
    kind VARCHAR(40) NOT NULL,
    reference_id VARCHAR(100) NOT NULL,
    occurred_at VARCHAR(40) NOT NULL,
    payload_json MEDIUMTEXT NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    aggregation_eligible BOOLEAN NOT NULL,
    received_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT uk_ll_result_event_sequence UNIQUE (source_instance_id, user_id, stream_sequence),
    CONSTRAINT fk_ll_result_event_stream FOREIGN KEY (source_instance_id, user_id)
        REFERENCES language_learning_result_stream (source_instance_id, user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
