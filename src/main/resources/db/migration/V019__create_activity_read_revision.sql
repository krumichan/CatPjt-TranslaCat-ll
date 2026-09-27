CREATE TABLE language_learning_activity_revision (
    user_id BIGINT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_ll_activity_revision_learner FOREIGN KEY (user_id)
        REFERENCES language_learning_learner (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
