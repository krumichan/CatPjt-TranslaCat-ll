-- 기존 결과와 정책은 보존하고 고정 의미 단위·근거 연결 정책의 신규 저장만 허용한다.
ALTER TABLE language_learning_curated_writing_feedback
    DROP CHECK ck_ll_cwf_policy,
    ADD CONSTRAINT ck_ll_cwf_policy CHECK (
        policy_version IN ('curated-writing-feedback-v1', 'curated-writing-feedback-v2', 'curated-writing-feedback-v3', 'curated-writing-feedback-v4', 'curated-writing-feedback-v5', 'curated-writing-feedback-v6', 'curated-writing-feedback-v7', 'curated-writing-feedback-v8', 'curated-writing-feedback-v9', 'curated-writing-feedback-v10', 'curated-writing-feedback-v11', 'curated-writing-feedback-v12', 'curated-writing-feedback-v13')
    );
