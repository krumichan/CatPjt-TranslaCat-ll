package jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence

import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningFacts
import jp.co.translacat.languagelearning.features.speaking.infrastructure.persistence.table.SpeakingSessions
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.WritingSetsTable
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

internal class ExposedKeywordLearningFacts(private val transactions: JdbcTransactionRunner) : KeywordLearningFacts {
    override suspend fun hasStartedLearning(userId: Long): Boolean = transactions.read {
        require(userId > 0)

        // 원본은 Writing 세트와 Speaking 세션만 확인한다. 다른 학습의 완료 여부로 범위를 넓히지 않는다.
        if (!WritingSetsTable.selectAll().where { WritingSetsTable.userId eq userId }.limit(1).empty()) {
            return@read true
        }

        // 생성 중·실패한 opening intent는 원본에서 rollback된 세션에 해당하므로 시작으로 세지 않는다.
        SpeakingSessions.select(SpeakingSessions.opening).where { SpeakingSessions.userId eq userId }.any { row ->
            val opening = Json.parseToJsonElement(row[SpeakingSessions.opening]).jsonObject
            (opening["_executionState"]?.jsonPrimitive?.contentOrNull ?: "READY") == "READY"
        }
    }
}
