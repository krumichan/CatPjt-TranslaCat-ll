-- 과거 코칭과 의미 계약은 보존하고 서버 고정 수리 계약의 신규 결과만 허용한다.
ALTER TABLE language_learning_curated_writing_feedback
    DROP CHECK ck_ll_cwf_policy,
    ADD CONSTRAINT ck_ll_cwf_policy CHECK (
        policy_version IN ('curated-writing-feedback-v1', 'curated-writing-feedback-v2', 'curated-writing-feedback-v3', 'curated-writing-feedback-v4', 'curated-writing-feedback-v5', 'curated-writing-feedback-v6', 'curated-writing-feedback-v7', 'curated-writing-feedback-v8', 'curated-writing-feedback-v9', 'curated-writing-feedback-v10', 'curated-writing-feedback-v11', 'curated-writing-feedback-v12', 'curated-writing-feedback-v13', 'curated-writing-feedback-v14')
    );
