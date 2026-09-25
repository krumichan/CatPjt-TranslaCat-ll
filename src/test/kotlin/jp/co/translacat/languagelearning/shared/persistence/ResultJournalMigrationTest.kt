package jp.co.translacat.languagelearning.shared.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResultJournalMigrationTest {
    @Test
    fun `V006은 원장 두 테이블만 생성하고 기존 데이터를 수정하지 않는다`() {
        val sql =
            checkNotNull(
                javaClass.getResourceAsStream("/db/migration/V006__create_learning_result_journal.sql"),
            ).bufferedReader()
                .use { it.readText() }
                .lineSequence()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
        assertEquals(
            listOf("language_learning_result_stream", "language_learning_result_event"),
            Regex("CREATE TABLE (\\w+)").findAll(sql).map { it.groupValues[1] }.toList(),
        )
        assertFalse(
            Regex(
                "\\b(DROP|ALTER|TRUNCATE|INSERT)\\b|DELETE\\s+FROM|UPDATE\\s+\\w+\\s+SET", RegexOption.IGNORE_CASE,
            ).containsMatchIn(sql),
        )
        assertTrue(sql.contains("uk_ll_result_event_sequence"))
        assertFalse(sql.contains("translacat."))
    }
}
