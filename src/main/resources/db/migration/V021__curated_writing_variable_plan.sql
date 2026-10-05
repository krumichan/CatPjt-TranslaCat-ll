-- 기존 fixed5 행은 기본값과 정책 조건으로 보존하고 opt-in 가변 목표를 별도로 기록한다.
ALTER TABLE language_learning_curated_writing_set
    ADD COLUMN target_item_count INT NOT NULL DEFAULT 5,
    ADD COLUMN plan_revision INT NOT NULL DEFAULT 1,
    ADD COLUMN planned_slots_json JSON NULL,
    ADD COLUMN supply_token VARCHAR(36) NULL,
    ADD COLUMN supply_lease_until DATETIME(6) NULL,
    ADD COLUMN completed_supply_token VARCHAR(36) NULL,
    ADD COLUMN supply_stop_reason VARCHAR(30) NULL,
    DROP CHECK ck_ll_cws_policy,
    DROP CHECK ck_ll_cws_status,
    ADD CONSTRAINT ck_ll_cws_policy CHECK (
        result_policy = 'REFERENCE_ONLY' AND
        ((policy_version = 'curated-writing-v1' AND target_item_count = 5 AND planned_slots_json IS NULL)
         OR (policy_version = 'curated-writing-variable-n-v1' AND target_item_count > 0 AND planned_slots_json IS NOT NULL))),
    ADD CONSTRAINT ck_ll_cws_status CHECK (
        (policy_version = 'curated-writing-v1' AND status IN ('READY','COMPLETED')) OR
        (policy_version = 'curated-writing-variable-n-v1' AND status IN ('READY','PARTIAL','COMPLETED','BLOCKED'))),
    ADD CONSTRAINT ck_ll_cws_revision CHECK (plan_revision > 0),
    ADD CONSTRAINT uk_ll_cws_id_user_policy UNIQUE (id, user_id, policy_version);

ALTER TABLE language_learning_curated_writing_item
    ADD COLUMN item_policy_version VARCHAR(50) NOT NULL DEFAULT 'curated-writing-v1',
    DROP CHECK ck_ll_cwi_order,
    ADD CONSTRAINT ck_ll_cwi_order CHECK (
        (item_policy_version = 'curated-writing-v1' AND item_order BETWEEN 1 AND 5) OR
        (item_policy_version = 'curated-writing-variable-n-v1' AND item_order > 0)),
    ADD CONSTRAINT fk_ll_cwi_set_policy FOREIGN KEY (set_id, user_id, item_policy_version)
        REFERENCES language_learning_curated_writing_set (id, user_id, policy_version)
        ON DELETE RESTRICT ON UPDATE RESTRICT;
