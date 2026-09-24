package jp.co.translacat.languagelearning.shared.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationResourcesTest {
    private fun resource(path: String): String = checkNotNull(javaClass.getResourceAsStream(path)) {
        "Missing resource: $path"
    }.bufferedReader().use { it.readText() }

    private fun sqlWithoutComments(path: String): String = resource(path)
        .lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")

    @Test
    fun `the schema creates exactly the four approved LL tables`() {
        val sql = sqlWithoutComments("/db/migration/V001__create_learner_and_settings.sql")
        val tables = Regex("CREATE TABLE (\\w+)").findAll(sql).map { it.groupValues[1] }.toSet()
        assertEquals(setOf(
            "language_learning_learner",
            "language_learning_user_setting",
            "language_learning_admin_setting",
            "language_learning_listening_policy_setting",
        ), tables)
        assertTrue(sql.contains("REFERENCES language_learning_learner (user_id)"))
        assertTrue(sql.contains("ON DELETE RESTRICT"))
        assertFalse(sql.contains("REFERENCES user", ignoreCase = true))
        assertFalse(sql.contains("translacat."))
    }

    @Test
    fun `all mapped BE settings columns are retained including pending and audit fields`() {
        val sql = resource("/db/migration/V001__create_learner_and_settings.sql")
        val baseline = Json.parseToJsonElement(resource("/db/be-settings-baseline.json")).jsonObject
        baseline.forEach { (table, entry) ->
            val body = sql.substringAfter("CREATE TABLE $table (").substringBefore(") ENGINE=")
            entry.jsonObject.getValue("columns").jsonArray.forEach { column ->
                val name = column.jsonObject.getValue("column").jsonPrimitive.content
                assertTrue(body.lineSequence().any { it.trimStart().startsWith("$name ") }, "$table.$name")
            }
            listOf("created_by", "created_at", "updated_by", "updated_at").forEach { audit ->
                assertTrue(body.contains("$audit "), "$table.$audit")
            }
        }
    }

    @Test
    fun `seed contains only singleton settings without overwriting user data`() {
        val sql = sqlWithoutComments("/db/migration/V002__seed_default_settings.sql")
        assertEquals(2, Regex("INSERT INTO").findAll(sql).count())
        assertFalse(Regex("\\b(UPDATE|DELETE|DROP|TRUNCATE|REPLACE)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(sql))
        assertFalse(sql.contains("language_learning_user_setting"))
        assertFalse(sql.contains("language_learning_learner"))
        assertTrue(sql.contains("'listening-profile'"))
        assertTrue(sql.contains("'listening-model-config'"))
        assertFalse(sql.contains("'listening-profile-v1'"))
    }
}
