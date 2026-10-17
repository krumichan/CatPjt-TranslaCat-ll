package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.api.LevelResponseMapper
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import jp.co.translacat.languagelearning.features.leveltest.support.MemoryLevelTest
import jp.co.translacat.languagelearning.features.leveltest.support.TestLevelContext
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LevelLanguageBoundaryTest {
    private val work = MemoryLevelTest()
    private val context = TestLevelContext()
    private val service = LevelSessionService(work, context)

    private fun completed(language: String, score: Int, daysAgo: Long): LevelSession {
        val time = work.now.minusDays(daysAgo)
        val session = work.repo.saveSession(LevelSession(
            uid = "completion-$language-$daysAgo", userId = 123, sessionType = LevelTestSessionType.INITIAL,
            status = LevelTestSessionStatus.COMPLETED, originLanguage = "ko", learningLanguage = language,
            timezone = "Asia/Tokyo", baseLevelScore = score, proficiencyBand = LevelTestRules.band(score),
            startedAt = time.minusMinutes(20), lastActivityAt = time, completedAt = time,
            completedDate = time.toLocalDate(), idempotencyKey = "initial-$language-$daysAgo",
        ))
        work.repo.saveBaseline(LevelBaseline(123, session.id, session.uid, session.sessionType, score,
            LevelTestRules.band(score), time.toLocalDate(), session.startedAt, time))
        return session
    }

    @Test
    fun `일본어 완료 후 영어로 바꾸면 영어 최초 테스트가 필요하고 일본어 점수를 쓰지 않는다`() = runBlocking {
        // 준비
        val japanese = completed("ja", 95, 2)
        context.value = context.value.copy(learningLanguage = "en")

        // 실행: 영어 최초 완료 전에는 일본어 이력으로 재측정도 열리지 않는다.
        val status = service.status(123)
        assertFailsWith<LevelTestException> { service.start(123, LevelTestSessionType.RECHECK, "english-recheck") }
        val english = service.start(123, LevelTestSessionType.INITIAL, "english-initial")

        // 검증
        assertFalse(status.initialCompleted)
        assertNull(status.baseline)
        assertNull(status.active)
        assertEquals("en", english.learningLanguage)
        assertEquals(2, english.currentComplexityBand)
        assertEquals(japanese, work.repo.session(japanese.id))
        assertEquals(japanese.id, work.repo.baseline(123)?.sessionId)
    }

    @Test
    fun `다른 언어가 단일 baseline을 갱신해도 원래 언어의 완료 원본을 복원한다`() = runBlocking {
        // 준비
        val japanese = completed("ja", 95, 4)
        val english = completed("en", 42, 2)

        // 실행: 현재 DB baseline은 영어지만 일본어로 돌아온 사용자는 일본어 기준점을 조회한다.
        val japaneseStatus = service.status(123)
        val japaneseHistory = service.history(123)
        context.value = context.value.copy(learningLanguage = "en")
        val englishStatus = service.status(123)
        val englishHistory = service.history(123)

        // 검증: 현재 레벨만 분리하고 전체 과거 이력과 저장된 baseline 행은 보존한다.
        assertTrue(japaneseStatus.initialCompleted)
        assertEquals(japanese.id, japaneseStatus.baseline?.sessionId)
        assertEquals(95, japaneseStatus.baseline?.score)
        assertEquals(english.id, englishStatus.baseline?.sessionId)
        assertEquals(42, englishStatus.baseline?.score)
        assertEquals(listOf(english.id, japanese.id), japaneseHistory.map { it.id })
        assertEquals(japaneseHistory, englishHistory)
        val englishSummary = LevelResponseMapper.summary(english)
        assertEquals("ko", englishSummary.originLanguage)
        assertEquals("en", englishSummary.learningLanguage)
        assertEquals(japanese, service.detail(123, japanese.id).session)
        assertEquals(english.id, work.repo.baseline(123)?.sessionId)
    }

    @Test
    fun `영어 재측정은 더 최근에 저장된 일본어 점수 대신 영어 점수를 사용한다`() = runBlocking {
        // 준비
        completed("en", 42, 4)
        completed("ja", 95, 2)
        context.value = context.value.copy(learningLanguage = "en")

        // 실행
        val recheck = service.start(123, LevelTestSessionType.RECHECK, "english-recheck")

        // 검증
        assertEquals(2, recheck.currentComplexityBand)
        assertEquals("en", recheck.learningLanguage)
    }

    @Test
    fun `다른 언어의 진행 세션과 멱등 키를 영어 세션으로 재사용하지 않는다`() = runBlocking {
        // 준비
        val japanese = service.start(123, LevelTestSessionType.INITIAL, "shared-key")
        context.value = context.value.copy(learningLanguage = "en")

        // 실행
        assertFailsWith<LevelTestException> { service.start(123, LevelTestSessionType.INITIAL, "shared-key") }
        val english = service.start(123, LevelTestSessionType.INITIAL, "english-key")

        // 검증: 이전 진행 상태는 삭제하거나 취소하지 않고 명시 ID로 계속 조회할 수 있다.
        assertNotEquals(japanese.id, english.id)
        assertEquals(english.id, service.status(123).active?.id)
        assertEquals(japanese, service.session(123, japanese.id))
        context.value = context.value.copy(learningLanguage = "ja")
        assertEquals(japanese.id, service.status(123).active?.id)
    }
}
