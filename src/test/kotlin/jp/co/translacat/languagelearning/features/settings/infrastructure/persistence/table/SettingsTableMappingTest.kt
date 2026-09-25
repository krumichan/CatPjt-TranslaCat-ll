package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table

import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsTableMappingTest {
    @Test
    fun `네 테이블의 컬럼과 null 허용 여부가 적용된 V001과 일치한다`() {
        val sql =
            checkNotNull(
                javaClass.getResourceAsStream("/db/migration/V001__create_learner_and_settings.sql"),
            ).bufferedReader()
                .use { it.readText() }
        val tables: List<Table> = listOf(LearnersTable, UserSettingsTable, AdminSettingsTable, ListeningPoliciesTable)
        for (table in tables) {
            val body = Regex(
                "CREATE TABLE " + table.tableName + " \\((.*?)\\) ENGINE=", RegexOption.DOT_MATCHES_ALL,
            ).find(sql)?.groupValues?.get(1) ?: error("스키마에 테이블이 없습니다: ${table.tableName}")
            val expected =
                Regex(
                    "(?m)^    (\\w+) (?:BIGINT|INT|DOUBLE|BOOLEAN|VARCHAR\\(\\d+\\)|DATETIME\\(6\\)|DATE) (.*)",
                ).findAll(
                    body,
                ).associate { it.groupValues[1] to !it.groupValues[2].contains("NOT NULL") }
            assertTrue(expected.isNotEmpty())
            assertEquals(expected, table.columns.associate { it.name to it.columnType.nullable }, table.tableName)
        }
    }

    @Test
    fun `개인 설정 기본키와 learner 식별자 매핑은 서로 다른 열이다`() {
        assertEquals("id", UserSettingsTable.primaryKey.columns.single().name)
        assertEquals("user_id", LearnersTable.primaryKey.columns.single().name)
        assertEquals("user_id", UserSettingsTable.userId.name)
    }
}
