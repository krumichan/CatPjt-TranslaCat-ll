package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingGenerationPolicy
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.serialization.json.*
import java.time.Clock

/** QA 준비 명령만 생성하며 사용자 조회·계획 복원에서는 절대 모델을 부르지 않는다. */
internal class CuratedWritingGenerationService(
    private val store: CuratedWritingGenerationPersistence,
    private val model: ModelExecutionPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun prepare(userId: Long, requestKey: String, type: WritingType, band: Int, topic: String,
                        repairOf: String? = null, repairNotes: String? = null): JsonObject {
        require(requestKey.matches(Regex("[a-zA-Z0-9._-]{1,80}")) && band in 1..5 &&
            topic.matches(Regex("[a-z0-9][a-z0-9-]{2,79}"))) { "WRITING_REQUEST_INVALID" }
        require((repairOf == null) == (repairNotes == null)) { "WRITING_REQUEST_INVALID" }
        if (repairOf != null) {
            require(repairOf != requestKey && repairOf.matches(Regex("[a-zA-Z0-9._-]{1,80}")) &&
                !repairNotes.isNullOrBlank() && repairNotes.length <= 2000) { "WRITING_REQUEST_INVALID" }
        }
        val claim = store.prepare(userId, requestKey, type, band, topic, repairOf, repairNotes)
        if (!claim.execute) return if (claim.status == "GENERATED") store.finish(userId, requestKey) else store.get(userId, requestKey)

        // 네트워크는 DB 트랜잭션 밖에서 한 번만 실행한다. 결과가 불명확하면 같은 키를 자동 재호출하지 않는다.
        val result = try {
            model.execute(ModelExecutionCommand(
                traceId = claim.id,
                instructions = CuratedWritingGenerationPolicy.instructions(claim.input),
                messages = listOf(ModelMessage("user", claim.input.toString())),
                tier = ModelTier.LUNA,
                maxOutputTokens = if ("coreVersion" in claim.input) 512 else 8192,
                deadlineUtc = clock.instant().plusSeconds(180),
                responseSchema = CuratedWritingGenerationPolicy.schema(claim.input),
                schemaName = "curated_writing_content",
                strict = true,
                taskName = "LANGUAGE_LEARNING_CURATED_WRITING_GENERATION",
            ))
        } catch (failure: ModelExecutionFailure) {
            store.fail(userId, requestKey, failure.code, uncertain = failure.code in setOf(
                "MODEL_DEADLINE_EXCEEDED", "MODEL_EXECUTION_TRANSPORT"))
            return store.get(userId, requestKey)
        }
        // 콘텐츠 응답을 먼저 보관한다. 이후 DRAFT 저장 실패는 같은 결과로 복구할 수 있다.
        store.saveOutput(userId, requestKey, result)
        return store.finish(userId, requestKey)
    }

    suspend fun get(userId: Long, requestKey: String): JsonObject = store.get(userId, requestKey)
}
