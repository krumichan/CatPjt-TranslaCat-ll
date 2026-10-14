CREATE TABLE language_learning_curated_writing_replacement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    request_key VARCHAR(80) NOT NULL,
    original_revision CHAR(64) NOT NULL,
    replacement_revision CHAR(64) NOT NULL,
    original_plan_revision INT NOT NULL,
    original_snapshot_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_curated_replacement_request (user_id, request_key),
    CONSTRAINT fk_curated_replacement_item FOREIGN KEY (item_id)
        REFERENCES language_learning_curated_writing_item(id) ON DELETE CASCADE
);
