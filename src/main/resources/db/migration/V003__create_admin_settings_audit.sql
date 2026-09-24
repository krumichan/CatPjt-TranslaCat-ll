-- 관리자 설정 변경과 같은 트랜잭션에서 감사 기록을 저장한다.
-- 기존 V001/V002 및 설정 데이터는 변경하지 않는다. Core DB와 외래키를 연결하지 않는다.
CREATE TABLE language_learning_admin_setting_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    admin_user_id BIGINT NULL,
    before_json TEXT NOT NULL,
    after_json TEXT NOT NULL,
    created_by VARCHAR(50) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
