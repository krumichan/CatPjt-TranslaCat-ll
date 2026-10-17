-- 기존 집계와 원본 이력을 보존하면서 이후 성장 자료를 언어쌍별로 저장한다.
-- 귀속 근거가 없는 기존 mastery/signal/evidence는 빈 scope로 보존하며 새 언어의 근거로 재사용하지 않는다.
ALTER TABLE language_learning_profile
    ADD COLUMN language_scope VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    DROP INDEX uk_ll_g_profile,
    ADD CONSTRAINT uk_ll_g_profile UNIQUE (user_id, language_scope);

ALTER TABLE language_learning_keyword_mastery
    ADD COLUMN language_scope VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    DROP INDEX uk_ll_g_keyword_mastery,
    ADD CONSTRAINT uk_ll_g_keyword_mastery UNIQUE (user_id, language_scope, canonical_key),
    DROP INDEX idx_ll_g_mastery_score,
    ADD INDEX idx_ll_g_mastery_score (user_id, language_scope, score, id);

ALTER TABLE language_learning_profile_signal
    ADD COLUMN language_scope VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    DROP INDEX uk_ll_g_profile_signal,
    ADD CONSTRAINT uk_ll_g_profile_signal UNIQUE (user_id, language_scope, signal_type, signal_key),
    DROP INDEX idx_ll_g_signal_count,
    ADD INDEX idx_ll_g_signal_count (user_id, language_scope, signal_type, occurrence_count, id);

ALTER TABLE language_learning_profile_evidence
    ADD COLUMN language_scope VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    DROP INDEX uk_ll_g_profile_evidence,
    ADD CONSTRAINT uk_ll_g_profile_evidence UNIQUE (user_id, language_scope, source, pattern_key, direction);

ALTER TABLE language_learning_vocabulary_mastery
    ADD COLUMN language_scope VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (user_id, language_scope, canonical_key);

-- 시간대 변경으로 같은 학습 날짜를 다시 만나도 다른 언어의 문항 세트를 재사용하지 않는다.
ALTER TABLE language_learning_daily_set
    ADD COLUMN language_scope VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    DROP INDEX uk_ll_w_set_user_date_type,
    ADD CONSTRAINT uk_ll_w_set_user_date_type UNIQUE (user_id, language_scope, learning_date, writing_type);

ALTER TABLE language_learning_practice_set
    ADD COLUMN language_scope VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT '',
    DROP INDEX uk_ll_practice_user_date_mode,
    ADD CONSTRAINT uk_ll_practice_user_date_mode UNIQUE (user_id, language_scope, learning_date, domain, mode);

UPDATE language_learning_daily_set
SET language_scope = CONCAT(
    CHAR_LENGTH(LOWER(REPLACE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.originLanguage'))), '_', '-'))), ':',
    LOWER(REPLACE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.originLanguage'))), '_', '-')),
    CHAR_LENGTH(LOWER(REPLACE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.learningLanguage'))), '_', '-'))), ':',
    LOWER(REPLACE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(snapshot_json, '$.learningLanguage'))), '_', '-'))
)
WHERE JSON_TYPE(JSON_EXTRACT(CASE WHEN JSON_VALID(snapshot_json) THEN snapshot_json ELSE '{}' END, '$.originLanguage')) = 'STRING'
    AND JSON_TYPE(JSON_EXTRACT(CASE WHEN JSON_VALID(snapshot_json) THEN snapshot_json ELSE '{}' END, '$.learningLanguage')) = 'STRING'
    AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(CASE WHEN JSON_VALID(snapshot_json) THEN snapshot_json ELSE '{}' END, '$.originLanguage')))) BETWEEN 2 AND 20
    AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(CASE WHEN JSON_VALID(snapshot_json) THEN snapshot_json ELSE '{}' END, '$.learningLanguage')))) BETWEEN 2 AND 20;

UPDATE language_learning_practice_set
SET language_scope = CONCAT(
    CHAR_LENGTH(LOWER(REPLACE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(request_json, '$.originLanguage'))), '_', '-'))), ':',
    LOWER(REPLACE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(request_json, '$.originLanguage'))), '_', '-')),
    CHAR_LENGTH(LOWER(REPLACE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(request_json, '$.learningLanguage'))), '_', '-'))), ':',
    LOWER(REPLACE(TRIM(JSON_UNQUOTE(JSON_EXTRACT(request_json, '$.learningLanguage'))), '_', '-'))
)
WHERE JSON_TYPE(JSON_EXTRACT(CASE WHEN JSON_VALID(request_json) THEN request_json ELSE '{}' END, '$.originLanguage')) = 'STRING'
    AND JSON_TYPE(JSON_EXTRACT(CASE WHEN JSON_VALID(request_json) THEN request_json ELSE '{}' END, '$.learningLanguage')) = 'STRING'
    AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(CASE WHEN JSON_VALID(request_json) THEN request_json ELSE '{}' END, '$.originLanguage')))) BETWEEN 2 AND 20
    AND CHAR_LENGTH(TRIM(JSON_UNQUOTE(JSON_EXTRACT(CASE WHEN JSON_VALID(request_json) THEN request_json ELSE '{}' END, '$.learningLanguage')))) BETWEEN 2 AND 20;

-- 과거 사용자 집계는 그대로 남긴다. 완료 원본이 증명하는 기준점만 새 언어 프로필로 복사한다.
-- 기존 누적 점수·태그가 어느 언어에서 생성됐는지는 baseline만으로 증명할 수 없다.
INSERT INTO language_learning_profile (
    user_id, language_scope, profile_version, state, base_level_score, calibration_started_date,
    calibration_completed_date, meaning_score, grammar_score, vocabulary_score, naturalness_score,
    expression_score, review_performance, normal_performance, challenge_performance,
    evaluation_count, confidence, trend, additional_signals_json, baseline_completion_id,
    baseline_completed_at, created_at, updated_at, created_by, updated_by
)
SELECT s.user_id, CONCAT(
        CHAR_LENGTH(LOWER(REPLACE(TRIM(s.origin_language), '_', '-'))), ':',
        LOWER(REPLACE(TRIM(s.origin_language), '_', '-')),
        CHAR_LENGTH(LOWER(REPLACE(TRIM(s.learning_language), '_', '-'))), ':',
        LOWER(REPLACE(TRIM(s.learning_language), '_', '-'))
    ), 'PROFILE', 'CALIBRATING', s.base_level_score, s.completed_date,
    NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
    0, 0, 'stable', '{}', s.session_uid, s.completed_at,
    s.completed_at, s.completed_at, 'MIGRATION_V039', 'MIGRATION_V039'
FROM language_learning_level_test_session s
WHERE s.status = 'COMPLETED' AND s.base_level_score IS NOT NULL
    AND s.completed_date IS NOT NULL AND s.completed_at IS NOT NULL
    AND NOT EXISTS (
        SELECT 1 FROM language_learning_level_test_session newer
        WHERE newer.user_id = s.user_id AND newer.status = 'COMPLETED'
            AND newer.base_level_score IS NOT NULL AND newer.completed_date IS NOT NULL
            AND newer.completed_at IS NOT NULL
            AND LOWER(REPLACE(TRIM(newer.origin_language), '_', '-')) = LOWER(REPLACE(TRIM(s.origin_language), '_', '-'))
            AND LOWER(REPLACE(TRIM(newer.learning_language), '_', '-')) = LOWER(REPLACE(TRIM(s.learning_language), '_', '-'))
            AND (newer.completed_at > s.completed_at OR (newer.completed_at = s.completed_at AND newer.id > s.id))
    );
