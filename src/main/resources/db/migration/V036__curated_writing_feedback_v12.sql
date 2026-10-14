-- 기존 정책 결과는 그대로 두고 독립 근거 검사·단회 복구 정책의 신규 저장만 허용한다.
ALTER TABLE language_learning_curated_writing_feedback
    DROP CHECK ck_ll_cwf_policy,
    ADD CONSTRAINT ck_ll_cwf_policy CHECK (
        policy_version IN ('curated-writing-feedback-v1', 'curated-writing-feedback-v2', 'curated-writing-feedback-v3', 'curated-writing-feedback-v4', 'curated-writing-feedback-v5', 'curated-writing-feedback-v6', 'curated-writing-feedback-v7', 'curated-writing-feedback-v8', 'curated-writing-feedback-v9', 'curated-writing-feedback-v10', 'curated-writing-feedback-v11', 'curated-writing-feedback-v12')
    );
