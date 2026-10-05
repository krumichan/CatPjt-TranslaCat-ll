package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.growth.domain.model.LearningEvidenceFilter
import jp.co.translacat.languagelearning.features.growth.domain.model.LearningEvidenceRecord
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.LocalDate

/** 각 원본을 owner/date로 제한한 단일 목록 쿼리. 원문·음성·모델 응답 전체를 적재하지 않는다. */
internal object ExposedLearningEvidenceQuery {
    fun read(filter: LearningEvidenceFilter): List<LearningEvidenceRecord> {
        require(filter.userId > 0 && filter.limit in 2..51)
        val arguments = mutableListOf<Pair<IColumnType<*>, Any?>>()

        // UNION의 각 분기는 세션/세트당 한 행이다. job 재시도·평가 revision을 활동으로 세지 않는다.
        val branches = definitions.filter { filter.source == null || it.source == filter.source }.map { definition ->
            arguments += LongColumnType() to filter.userId
            arguments += TextColumnType() to filter.from.toString()
            arguments += TextColumnType() to filter.to.toString()
            """
                SELECT s.id, '${definition.source}' AS source, ${definition.date} AS learning_date,
                    ${definition.language} AS learning_language, ${definition.origin} AS origin_language,
                    ${definition.title} AS title, ${definition.kind} AS result_kind,
                    ${definition.policy} AS policy_version, s.status, ${definition.resultStatus} AS result_status
                FROM ${definition.table} s ${definition.join}
                WHERE s.user_id = ? AND ${definition.date} BETWEEN ? AND ? ${definition.condition} ${definition.groupBy}
            """.trimIndent()
        }
        val conditions = mutableListOf("1 = 1")
        fun equal(column: String, value: String?) {
            if (value != null) {
                conditions += "$column = ?"
                arguments += TextColumnType() to value
            }
        }
        equal("learning_language", filter.learningLanguage)
        equal("result_kind", filter.resultKind)
        equal("policy_version", filter.policyVersion)

        // 날짜/출처/ID로 안정된 keyset을 사용한다. OFFSET은 중간 삭제 후 다음 행을 누락시킬 수 있다.
        filter.after?.let { after ->
            conditions += "(learning_date < ? OR (learning_date = ? AND (source > ? OR (source = ? AND id < ?))))"
            arguments += TextColumnType() to after.date.toString()
            arguments += TextColumnType() to after.date.toString()
            arguments += TextColumnType() to after.source
            arguments += TextColumnType() to after.source
            arguments += LongColumnType() to after.id
        }
        val sql = "SELECT * FROM (${branches.joinToString(" UNION ALL ")}) evidence " +
            "WHERE ${conditions.joinToString(" AND ")} " +
            "ORDER BY learning_date DESC, source ASC, id DESC LIMIT ${filter.limit}"

        // 모든 외부 값은 bind parameter다. 컬럼/테이블/정렬은 아래 고정 정의만 사용한다.
        return checkNotNull(TransactionManager.current().exec(sql, arguments) { result ->
            buildList {
                while (result.next()) {
                    fun optional(name: String) = result.getString(name)?.takeUnless { it.isBlank() || it == "null" }
                    add(
                        LearningEvidenceRecord(
                            result.getLong("id"), result.getString("source"),
                            LocalDate.parse(result.getString("learning_date")), optional("learning_language"),
                            optional("origin_language"), result.getString("title"), result.getString("result_kind"),
                            optional("policy_version"), result.getString("status"), optional("result_status"),
                        ),
                    )
                }
            }
        })
    }

    private data class Definition(
        val source: String, val table: String, val date: String = "s.learning_date",
        val language: String, val origin: String, val title: String,
        val kind: String = "'SCORED_EVALUATION'", val policy: String = "NULL",
        val resultStatus: String = "NULL", val join: String = "", val condition: String = "",
        val groupBy: String = "",
    )

    // 손상/누락된 과거 JSON은 현재 설정이나 현재 정책으로 채우지 않는다.
    private fun field(column: String, key: String) =
        "NULLIF(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID($column), $column, '{}'), '$.$key')), 'null')"

    private val speakingKind = field("s.snapshot_json", "resultKind")
    private val definitions = listOf(
        Definition(
            "WRITING", "language_learning_daily_set",
            language = field("s.snapshot_json", "learningLanguage"),
            origin = field("s.snapshot_json", "originLanguage"), title = "CONCAT('Daily Writing · ', s.writing_type)",
            policy = "CASE WHEN COUNT(e.id) > 0 AND COUNT(e.scoring_policy_version) = COUNT(e.id) " +
                "AND COUNT(DISTINCT e.scoring_policy_version) = 1 THEN MIN(e.scoring_policy_version) ELSE NULL END",
            resultStatus = "CASE WHEN COUNT(e.id) = 0 THEN 'NOT_REQUESTED' " +
                "WHEN MAX(e.status = 'PENDING') = 1 THEN 'PENDING' " +
                "WHEN MAX(e.status = 'FAILED') = 1 THEN 'FAILED' " +
                "WHEN MIN(e.status = 'SUCCESS') = 1 THEN 'SUCCESS' ELSE NULL END",
            join = "LEFT JOIN language_learning_daily_item wi ON wi.daily_set_id = s.id AND wi.user_id = s.user_id " +
                "LEFT JOIN language_learning_writing_answer wa ON wa.daily_item_id = wi.id AND wa.user_id = s.user_id AND wa.attempt_date = s.learning_date " +
                "LEFT JOIN language_learning_writing_evaluation e ON e.answer_id = wa.id AND e.user_id = s.user_id AND e.evaluation_context = 'DAILY'",
            groupBy = "GROUP BY s.id",
        ),
        Definition(
            "SPEAKING", "language_learning_speaking_session",
            language = field("s.snapshot_json", "learningLanguage"),
            origin = field("s.snapshot_json", "originLanguage"),
            title = "COALESCE(${field("s.snapshot_json", "topicTitle")}, 'Speaking')",
            kind = "CASE WHEN $speakingKind IN ('SCORED_EVALUATION', 'SESSION_COACHING') THEN $speakingKind ELSE 'UNKNOWN' END",
            policy = field("s.snapshot_json", "resultPolicyVersion"),
            resultStatus = "CASE WHEN $speakingKind = 'SESSION_COACHING' THEN COALESCE(j.status, 'NOT_REQUESTED') " +
                "WHEN $speakingKind = 'SCORED_EVALUATION' THEN s.evaluation_status ELSE NULL END",
            join = "LEFT JOIN language_learning_speaking_evaluation_job j ON j.session_id = s.id AND j.problem_index = 0",
            condition = "AND COALESCE(${field("s.opening_json", "_executionState")}, 'READY') = 'READY'",
        ),
        Definition(
            "LISTENING", "language_learning_listening_session", date = "ls.learning_date",
            language = "ls.learning_language", origin = field("ls.state_json", "originLanguage"),
            title = "CONCAT('Listening · ', ls.learning_mode)",
            join = "INNER JOIN language_learning_listening_set ls ON ls.id = s.set_id AND ls.user_id = s.user_id",
        ),
        *listOf("READING", "VOCABULARY").map { source ->
            Definition(
                source, "language_learning_practice_set", language = field("s.request_json", "learningLanguage"),
                origin = field("s.request_json", "originLanguage"), title = "CONCAT('$source · ', s.mode)",
                condition = "AND s.domain = '$source'",
            )
        }.toTypedArray(),
        Definition(
            "LEVEL_TEST", "language_learning_level_test_session", date = "s.completed_date",
            language = "s.learning_language", origin = "s.origin_language", title = "'Language Level Test'",
            resultStatus = "s.status", condition = "AND s.status = 'COMPLETED'",
        ),
    )
}
