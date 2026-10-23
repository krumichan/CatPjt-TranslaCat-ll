package jp.co.translacat.languagelearning.features.accountdeletion.infrastructure

/** learner 잠금을 소유한 삭제 트랜잭션 안에서만 호출한다. 테이블/열은 소스에 고정하고 user는 bind한다. */
internal fun purgeAccountLearningData(user: Long) {
    val sql = AccountDeletionSql

    // 개인 생성 catalog는 자식 FK가 다른 사용자에게도 연결될 수 있다. 타인의 고정 문항 본문은 보존한다.
    val generated = sql.query("SELECT catalog_id FROM language_learning_curated_writing_generation WHERE user_id=?", user) { it.getString(1) }
    for (catalog in generated) {
        if (!catalog.startsWith("gen-")) error("ACCOUNT_DELETION_CATALOG_INVALID")
        sql.update("UPDATE language_learning_curated_writing_catalog SET review_status='RETIRED',content_json='{}',content_hash=SHA2('{}',256)," +
            "topic_key='deleted-owner',semantic_key='deleted-owner',approved_by=NULL,approval_manifest_hash=NULL WHERE id=?", catalog)
    }

    // FK로 소유되는 LevelTest/Practice/Speaking/WebAI 자식은 부모가 남아 있을 때 먼저 지운다.
    val dependent = listOf(
        "DELETE FROM language_learning_level_test_evaluation WHERE response_id IN (SELECT r.id FROM language_learning_level_test_response r JOIN language_learning_level_test_item i ON i.id=r.item_id JOIN language_learning_level_test_session s ON s.id=i.session_id WHERE s.user_id=?)",
        "DELETE FROM language_learning_level_test_response WHERE item_id IN (SELECT i.id FROM language_learning_level_test_item i JOIN language_learning_level_test_session s ON s.id=i.session_id WHERE s.user_id=?)",
        "DELETE FROM language_learning_level_test_question_candidate WHERE session_id IN (SELECT id FROM language_learning_level_test_session WHERE user_id=?)",
        "DELETE FROM language_learning_level_test_item WHERE session_id IN (SELECT id FROM language_learning_level_test_session WHERE user_id=?)",
        "DELETE FROM language_learning_practice_attempt WHERE question_id IN (SELECT q.id FROM language_learning_practice_question q JOIN language_learning_practice_set s ON s.id=q.practice_set_id WHERE s.user_id=?)",
        "DELETE FROM language_learning_practice_metric WHERE practice_set_id IN (SELECT id FROM language_learning_practice_set WHERE user_id=?)",
        "DELETE FROM language_learning_practice_question WHERE practice_set_id IN (SELECT id FROM language_learning_practice_set WHERE user_id=?)",
        "DELETE FROM language_learning_metric_history WHERE activity_id IN (SELECT id FROM language_learning_activity WHERE user_id=?)",
        "DELETE FROM language_learning_speaking_audio WHERE session_id IN (SELECT id FROM language_learning_speaking_session WHERE user_id=?)",
        "DELETE FROM language_learning_speaking_ai_usage WHERE session_id IN (SELECT id FROM language_learning_speaking_session WHERE user_id=?)",
        "DELETE FROM language_learning_speaking_evaluation_job WHERE session_id IN (SELECT id FROM language_learning_speaking_session WHERE user_id=?)",
        "DELETE FROM language_learning_speaking_result WHERE session_id IN (SELECT id FROM language_learning_speaking_session WHERE user_id=?)",
        "DELETE FROM language_learning_stt_error_report WHERE user_id=?",
        "DELETE FROM language_learning_speaking_turn WHERE session_id IN (SELECT id FROM language_learning_speaking_session WHERE user_id=?)",
        "DELETE FROM ll_web_ai_items WHERE run_id IN (SELECT id FROM ll_web_ai_runs WHERE user_id=?)",
        "DELETE FROM language_learning_level_test_audio WHERE owner_user_id=?",
    )
    dependent.forEach { sql.update(it, user) }

    // 모든 직접 소유 테이블을 FK 역순으로 정리한다. 공용 pool/catalog/정책 테이블 전체 삭제는 없다.
    val owned = listOf(
        "language_learning_curated_writing_feedback", "language_learning_curated_writing_replacement",
        "language_learning_curated_writing_answer", "language_learning_curated_writing_item",
        "language_learning_curated_writing_set", "language_learning_curated_writing_generation",
        "language_learning_writing_evaluation", "language_learning_writing_answer",
        "language_learning_daily_item", "language_learning_daily_set",
        "language_learning_listening_audio", "language_learning_listening_job", "language_learning_listening_metric_history",
        "language_learning_listening_recommendation", "language_learning_listening_session", "language_learning_listening_set",
        "language_learning_speaking_session", "language_learning_level_test_baseline", "language_learning_level_test_session",
        "language_learning_practice_set", "language_learning_vocabulary_mastery", "language_learning_generation_fingerprint",
        "language_learning_activity_revision", "language_learning_keyword_mastery", "language_learning_profile_signal",
        "language_learning_profile_evidence", "language_learning_activity", "language_learning_profile",
        "language_learning_growth_operation", "language_learning_growth_receipt", "language_learning_growth_stream",
        "language_learning_result_event", "language_learning_result_stream", "language_learning_custom_keyword",
        "language_learning_user_system_keyword", "language_learning_settings_selection_delivery", "language_learning_user_setting",
        "ll_web_ai_results", "ll_web_ai_runs", "ll_web_ai_plans",
    )
    owned.forEach { table -> sql.update("DELETE FROM $table WHERE user_id=?", user) }
    for (catalog in generated) {
        sql.update("DELETE FROM language_learning_curated_writing_catalog WHERE id=? AND NOT EXISTS " +
            "(SELECT 1 FROM language_learning_curated_writing_item WHERE catalog_id=?)", catalog, catalog)
    }

    // 공용 정책 값과 감사 이력은 유지하되 삭제 계정으로 연결되는 actor 열은 익명화한다.
    sql.update("UPDATE language_learning_admin_setting_audit SET admin_user_id=NULL,created_by=NULL WHERE admin_user_id=?", user)
    for (table in listOf("language_learning_admin_setting", "language_learning_listening_policy_setting")) {
        sql.update("UPDATE $table SET created_by=NULL WHERE created_by=?", user.toString())
        sql.update("UPDATE $table SET updated_by=NULL WHERE updated_by=?", user.toString())
    }
    sql.update("UPDATE language_learning_curated_writing_catalog SET approved_by=NULL WHERE approved_by=?", user.toString())
}
