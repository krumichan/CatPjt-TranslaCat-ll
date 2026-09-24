-- Core Listening outbox의 중복/역순 전달이 최신 설정을 덮어쓰지 않도록 수신 이력을 보관한다.
-- 기존 설정 테이블과 V001~V003은 변경하지 않는다. Core DB와의 FK는 없다.
CREATE TABLE language_learning_settings_selection_delivery (
    user_id BIGINT NOT NULL,
    last_event_id BIGINT NOT NULL,
    base_revision DATETIME(6) NOT NULL,
    applied_revision DATETIME(6) NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_ll_selection_delivery_learner FOREIGN KEY (user_id)
        REFERENCES language_learning_learner (user_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
