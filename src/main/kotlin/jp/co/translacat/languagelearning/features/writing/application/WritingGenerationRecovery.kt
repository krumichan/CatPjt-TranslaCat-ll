package jp.co.translacat.languagelearning.features.writing.application

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** 저장된 요청과 만료 lease를 사용해 재기동 뒤 중단된 생성만 재개한다. */
internal class WritingGenerationRecovery(
    private val state: WritingGenerationState,
    private val worker: WritingGenerationWorker,
) {
    suspend fun runOnce(limit: Int = 20): List<WritingGenerationWorker.Result> {
        // 만료된 세트 식별자만 조회하고 실제 claim은 각 worker의 짧은 트랜잭션에 맡긴다.
        val jobs = state.recoverable(limit)
        val results = mutableListOf<WritingGenerationWorker.Result>()

        // 생성 요청은 세트에 고정된 snapshot을 재사용한다. 현재 설정이나 원문은 덮어쓰지 않는다.
        for (job in jobs) {
            val set = state.find(job.userId, job.setId) ?: continue
            val request = Json.parseToJsonElement(set.snapshotJson).jsonObject
            results += worker.process(job.userId, job.setId, request)
        }
        return results
    }
}
