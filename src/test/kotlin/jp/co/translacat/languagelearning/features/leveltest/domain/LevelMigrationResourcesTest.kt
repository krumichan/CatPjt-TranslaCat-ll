package jp.co.translacat.languagelearning.features.leveltest.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 실제 SQL 실행은 MySQL 통합 테스트에서 검증한다. 여기서는 승인된 스키마 범위를 검사한다. */
class LevelMigrationResourcesTest {
    @Test
    fun `V007은 레벨 테스트와 기준점 수신 준비용 아홉 테이블만 추가한다`() {
        val sql = checkNotNull(javaClass.getResourceAsStream("/db/migration/V007__create_level_test.sql"))
            .bufferedReader().use { it.readText() }
            .lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
        val tables = Regex("CREATE TABLE ([a-z_]+)").findAll(sql).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "language_learning_level_test_session", "language_learning_level_test_item",
                "language_learning_level_test_response", "language_learning_level_test_evaluation",
                "language_learning_level_test_question_pool", "language_learning_level_test_question_candidate",
                "language_learning_level_test_baseline", "language_learning_level_test_audio",
                "language_learning_level_test_maintenance",
            ),
            tables,
        )
        assertFalse(Regex("(?im)^\\s*(DROP|ALTER|TRUNCATE|DELETE|UPDATE)\\b").containsMatchIn(sql))
        assertFalse(sql.contains("REFERENCES user", ignoreCase = true))
        assertFalse(sql.contains("translacat."))
        assertTrue(sql.contains("REFERENCES language_learning_learner"))
        assertEquals(1, Regex("INSERT INTO").findAll(sql).count())
    }
}
