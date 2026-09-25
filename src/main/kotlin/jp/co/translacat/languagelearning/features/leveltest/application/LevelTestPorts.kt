package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import java.time.LocalDateTime

internal data class LevelTestContext(
    val originLanguage: String?, val learningLanguage: String?, val timezone: String,
    val aiEvaluationEnabled: Boolean, val poolTarget: Int, val poolReplenishmentEnabled: Boolean,
    val profilePolicyVersion: String, val modelConfigVersion: String, val automaticRetryLimit: Int,
)

internal interface LevelTestContextProvider {
    suspend fun current(userId: Long): LevelTestContext
    suspend fun activeLanguagePairs(): List<Pair<String, String>>
    suspend fun poolPolicy(): Pair<Boolean, Int>
}

internal data class LevelGenerationContext(
    val session: LevelSession,
    val number: Int,
    val band: Int,
    val requestKey: String,
    val previous: List<Pair<LevelItem, LevelEvaluationData>>,
    val currentItems: List<LevelItem>,
    val recentItems: List<LevelItem>,
    val scenarios: List<String>,
    val nowUtc: LocalDateTime,
)

internal data class LevelAudioUpload(
    val uploadUrl: String, val objectKey: String, val contentType: String = "audio/wav",
)

internal interface LevelTestAi {
    suspend fun generate(context: LevelGenerationContext, upload: LevelAudioUpload?): LevelQuestionData
    suspend fun evaluate(
        session: LevelSession, item: LevelItem, response: LevelSubmission, audio: ByteArray?,
    ): LevelEvaluationData

    suspend fun synthesize(
        session: LevelSession, item: LevelItem, text: String, policy: LevelTestContext,
    ): LevelAudioBytes
}

/** 실제 바이트의 보관은 DB 상태와 분리한다. 로컬 저장소는 전용 영속 볼륨을 사용한다. */
internal interface LevelTestAudioStore {
    suspend fun store(key: String, bytes: ByteArray)
    suspend fun load(key: String): ByteArray
    suspend fun delete(key: String)
    suspend fun exists(key: String): Boolean
}
