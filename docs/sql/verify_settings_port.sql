-- 읽기 전용 확인 SQL. 연결의 선택 DB가 translacat_ll인지 먼저 확인한다.
SELECT DATABASE() AS selected_database;
SELECT installed_rank, version, description, script, success
FROM flyway_schema_history ORDER BY installed_rank;
SHOW TABLES;
SELECT COUNT(*) AS audit_count FROM language_learning_admin_setting_audit;
SELECT id, admin_user_id, created_by, created_at
FROM language_learning_admin_setting_audit ORDER BY id DESC LIMIT 10;
-- 원문 before_json/after_json은 필요한 경우에만 로컬에서 확인한다.
SELECT user_id, origin_language, learning_language, timezone, daily_sentence_count,
       pending_daily_sentence_count, pending_effective_date
FROM language_learning_user_setting ORDER BY user_id LIMIT 20;
