-- 과거 저장 정책과 결과를 보존하고 새 공개 의미 기준의 코칭 정책을 허용한다.
ALTER TABLE language_learning_curated_writing_feedback
    DROP CHECK ck_ll_cwf_policy,
    ADD CONSTRAINT ck_ll_cwf_policy CHECK (
        policy_version IN ('curated-writing-feedback-v1', 'curated-writing-feedback-v2', 'curated-writing-feedback-v3', 'curated-writing-feedback-v4', 'curated-writing-feedback-v5', 'curated-writing-feedback-v6', 'curated-writing-feedback-v7', 'curated-writing-feedback-v8', 'curated-writing-feedback-v9', 'curated-writing-feedback-v10')
    );
