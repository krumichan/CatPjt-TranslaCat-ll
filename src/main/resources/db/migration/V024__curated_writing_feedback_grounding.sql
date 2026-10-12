-- 기존 v1 피드백과 hash는 보존하고, 새 답안에 출처를 분리한 v2 계약을 허용한다.
ALTER TABLE language_learning_curated_writing_feedback
    DROP CHECK ck_ll_cwf_policy,
    ADD CONSTRAINT ck_ll_cwf_policy CHECK (
        policy_version IN ('curated-writing-feedback-v1', 'curated-writing-feedback-v2')
    );
