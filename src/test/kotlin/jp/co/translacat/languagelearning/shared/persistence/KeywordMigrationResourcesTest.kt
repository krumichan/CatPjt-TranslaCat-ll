package jp.co.translacat.languagelearning.shared.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 현재 V005의 승인된 범위 검사다. 범용 SQL 파서나 실제 MySQL 실행 검증은 아니다. */
class KeywordMigrationResourcesTest {
    private fun sql(): String =
        checkNotNull(javaClass.getResourceAsStream("/db/migration/V005__create_keyword_catalog.sql")) {
            "V005 키워드 migration 리소스가 없습니다."
        }.bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")

    @Test
    fun `V005는 네 키워드 테이블과 잠금 테이블만 만든다`() {
        val names = Regex("CREATE TABLE (\\w+)").findAll(sql()).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "language_learning_system_keyword", "language_learning_system_keyword_locale",
                "language_learning_custom_keyword", "language_learning_user_system_keyword",
                "language_learning_keyword_catalog_lock",
            ), names
        )
        assertFalse(sql().contains("keyword_mastery"))
        assertFalse(sql().contains("learning_profile"))
        assertFalse(sql().contains("learning_activity"))
    }

    @Test
    fun `카탈로그 예제 데이터를 몰래 넣지 않고 잠금 기준 행만 초기화한다`() {
        val inserts = Regex("INSERT INTO (\\w+)").findAll(sql()).map { it.groupValues[1] }.toList()
        assertEquals(listOf("language_learning_keyword_catalog_lock"), inserts)
        assertTrue(sql().contains("INSERT INTO language_learning_keyword_catalog_lock (id) VALUES (1)"))
    }

    @Test
    fun `기존 데이터 변경문과 Core 외래키는 없고 LL 내부 RESTRICT는 유지한다`() {
        val sql = sql()
        // ON DELETE/UPDATE RESTRICT는 DML이 아니므로 DELETE 단어 전체를 금지하지 않는다.
        assertFalse(
            Regex(
                "\\b(DROP|ALTER|TRUNCATE|REPLACE)\\b|\\bDELETE\\s+FROM\\b|\\bUPDATE\\s+\\w+\\s+SET\\b",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(sql)
        )
        assertFalse(sql.contains("translacat."))
        assertFalse(sql.contains("REFERENCES user"))
        assertTrue(sql.contains("REFERENCES language_learning_learner (user_id)"))
        assertTrue(sql.contains("ON DELETE RESTRICT ON UPDATE RESTRICT"))
    }
}
