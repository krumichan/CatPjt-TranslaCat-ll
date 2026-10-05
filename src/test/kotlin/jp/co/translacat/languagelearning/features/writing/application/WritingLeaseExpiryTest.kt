package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import jp.co.translacat.languagelearning.features.writing.domain.model.*
import jp.co.translacat.languagelearning.features.writing.domain.repository.*
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.*

class WritingLeaseExpiryTest {
    private val now = LocalDateTime.parse("2026-10-03T01:00:00")
    private val token = "11111111-1111-4111-8111-111111111111"

    @Test
    fun `만료 또는 없는 생성 lease는 token이 같아도 게시와 실패 반영을 중단한다`() = runBlocking {
        for (lease in listOf(now.minusNanos(1), now, null)) {
            // 준비: 상태 조회 이외의 저장소 접근은 실패하게 하여 부작용 없는 거절을 검증한다.
            val work = readOnlyWork(set(WritingSetStatus.GENERATING, lease))
            val generation = WritingGenerationState(work)

            // 실행
            val published = generation.publishItem(
                41, 17, WritingGenerationState.GenerationClaim(1, token), item(), "existing-prompt",
            )
            val failed = generation.fail(41, 17, token, "LATE_TIMEOUT")

            // 검증
            assertFalse(published)
            assertFalse(failed)
        }
    }

    @Test
    fun `만료 또는 없는 재생성 lease는 문항과 횟수를 읽거나 수정하기 전에 거절한다`() = runBlocking {
        for (lease in listOf(now.minusNanos(1), now, null)) {
            // 준비
            val regeneration = WritingRegenerationState(readOnlyWork(set(WritingSetStatus.READY, lease)))
            val claim = WritingRegenerationState.Claim(
                41, 17, token, listOf(WritingRegenerationState.Target(5, 1, WritingDifficulty.NORMAL, "revision")),
            )

            // 실행
            val published = regeneration.publish(claim, listOf(item()))

            // 검증
            assertFalse(published)
        }
    }

    private fun set(status: WritingSetStatus, lease: LocalDateTime?) = WritingSet(
        17, 41, LocalDate.parse("2026-10-03"), WritingType.FREE, "synthetic", 1,
        status, "{}", "existing-prompt", 0, token, lease, null,
    )

    private fun item() = NewWritingItem(
        1, WritingDifficulty.NORMAL, "Synthetic task", emptyList(), emptyList(), "Synthetic focus",
    )

    private fun readOnlyWork(set: WritingSet): WritingSetUnitOfWork {
        val repository = Proxy.newProxyInstance(
            WritingSetRepository::class.java.classLoader, arrayOf(WritingSetRepository::class.java),
        ) { _, method, args ->
            check(method.name == "findById") { "Unexpected repository operation: ${method.name}" }
            if (args[0] == set.userId && args[1] == set.id) set else null
        } as WritingSetRepository
        val transaction = object : WritingSetTransaction {
            override val sets = repository
            override val nowUtc = now
            override val items: WritingItemRepository get() = error("Unexpected item access")
            override val answers: WritingAnswerRepository get() = error("Unexpected answer access")
            override val evaluations: WritingEvaluationRepository get() = error("Unexpected evaluation access")
            override val fingerprints: WritingFingerprintRepository get() = error("Unexpected fingerprint access")
            override val growth: GrowthRepository get() = error("Unexpected growth access")
        }
        return object : WritingSetUnitOfWork {
            override suspend fun <T> write(userId: Long, block: WritingSetTransaction.() -> T): T = transaction.block()
            override suspend fun <T> read(block: WritingSetTransaction.() -> T): T = transaction.block()
        }
    }
}
