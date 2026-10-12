-- 기존 v1~v7의 결과와 요청 identity는 보존하고 새 원문 단위/명시 조건 계약을 허용한다.
ALTER TABLE language_learning_curated_writing_feedback
    DROP CHECK ck_ll_cwf_policy,
    ADD CONSTRAINT ck_ll_cwf_policy CHECK (
        policy_version IN ('curated-writing-feedback-v1', 'curated-writing-feedback-v2', 'curated-writing-feedback-v3', 'curated-writing-feedback-v4', 'curated-writing-feedback-v5', 'curated-writing-feedback-v6', 'curated-writing-feedback-v7', 'curated-writing-feedback-v8')
    );
