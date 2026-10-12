-- 저장된 v1 요청/결과는 유지하고 한국어 콘텐츠 검증 및 1회 국소 수정 정책을 추가한다.
ALTER TABLE language_learning_curated_writing_generation
    DROP CHECK ck_ll_cwg_policy,
    ADD CONSTRAINT ck_ll_cwg_policy CHECK (policy_version IN
        ('curated-writing-generation-v1', 'curated-writing-generation-v2'));
