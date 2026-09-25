package jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence

import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.table.CustomKeywordsTable
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.table.SystemKeywordLocalesTable
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.table.SystemKeywordSelectionsTable
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.table.SystemKeywordsTable
import org.jetbrains.exposed.v1.core.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeywordTableMappingTest {
    @Test
    fun `네 도메인 테이블의 컬럼 이름과 nullable이 V005와 일치한다`() {
        val sql = checkNotNull(javaClass.getResourceAsStream("/db/migration/V005__create_keyword_catalog.sql"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val tables: List<Table> =
            listOf(SystemKeywordsTable, SystemKeywordLocalesTable, CustomKeywordsTable, SystemKeywordSelectionsTable)
        for (table in tables) {
            val body = sql.substringAfter("CREATE TABLE ${table.tableName} (").substringBefore(") ENGINE=")
            val declared =
                Regex("(?m)^    (\\w+) (?:BIGINT|INT|BOOLEAN|VARCHAR\\(\\d+\\)|DATETIME\\(6\\)|DATE) (NOT NULL|NULL)")
                    .findAll(body).associate { it.groupValues[1] to (it.groupValues[2] == "NULL") }
            assertEquals(declared.keys, table.columns.map { it.name }.toSet(), table.tableName)
            table.columns.forEach {
                assertEquals(
                    declared[it.name], it.columnType.nullable, "${table.tableName}.${it.name}",
                )
            }
        }
        assertTrue(sql.contains("REFERENCES language_learning_learner (user_id)"))
        assertFalse(sql.contains("REFERENCES user"))
        assertFalse(sql.contains("translacat."))
        assertFalse(sql.contains("DROP TABLE"))
    }
}
