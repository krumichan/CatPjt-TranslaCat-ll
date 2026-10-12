-- v1/v2 결과와 hash를 보존하고, 실제 형태 오류와 선택 표현의 수량 근거를 구분한 v3를 추가한다.
ALTER TABLE language_learning_curated_writing_feedback
    DROP CHECK ck_ll_cwf_policy,
    ADD CONSTRAINT ck_ll_cwf_policy CHECK (
        policy_version IN ('curated-writing-feedback-v1', 'curated-writing-feedback-v2', 'curated-writing-feedback-v3')
    );
