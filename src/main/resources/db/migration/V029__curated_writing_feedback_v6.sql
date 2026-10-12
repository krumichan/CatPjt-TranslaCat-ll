-- 기존 결과를 보존하고 부분별 근거와 국소 교정 계약을 새 버전으로 저장한다.
ALTER TABLE language_learning_curated_writing_feedback
    DROP CHECK ck_ll_cwf_policy,
    ADD CONSTRAINT ck_ll_cwf_policy CHECK (
        policy_version IN ('curated-writing-feedback-v1', 'curated-writing-feedback-v2', 'curated-writing-feedback-v3', 'curated-writing-feedback-v4', 'curated-writing-feedback-v5', 'curated-writing-feedback-v6')
    );
