package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException
import jp.co.translacat.languagelearning.features.settings.domain.model.GoalPolicy
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.support.MemorySettingsUnitOfWork
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.*
import jp.co.translacat.languagelearning.support.SettingsFixtures as F

class UserSettingsLifecycleTest {
    @Test
    fun `설명 언어 PATCH의 예약값은 저장되고 다음날 GET에서 활성값으로 승격된다`() = runBlocking {
        // 준비
        val work = MemorySettingsUnitOfWork()
        work.rows[123] = F.configured()

        // 실행
        val scheduled = UpdateUserSettings(work).execute(123, UserSettingsChange(feedbackLanguage = "en"))
        val sameDay = GetUserSettings(work).execute(123)
        work.now = F.now.plusDays(1)
        val nextDay = GetUserSettings(work).execute(123)

        // 검증
        assertEquals("ko", scheduled.settings.feedbackLanguage)
        assertEquals("en", scheduled.settings.pendingFeedbackLanguage)
        assertEquals(scheduled.settings, sameDay.settings)
        assertEquals("en", nextDay.settings.feedbackLanguage)
        assertNull(nextDay.settings.pendingFeedbackLanguage)
        assertNull(nextDay.settings.pendingEffectiveDate)
        assertEquals(nextDay.settings, work.rows[123])
    }

    @Test
    fun `기존 미지정 설명 언어의 원문 언어 기본값을 GET이 저장한다`() = runBlocking {
        // 준비
        val work = MemorySettingsUnitOfWork()
        work.rows[123] = F.configured().copy(feedbackLanguage = null)

        // 실행
        val result = GetUserSettings(work).execute(123)
        val repeated = GetUserSettings(work).execute(123)

        // 검증
        assertEquals("ko", result.settings.feedbackLanguage)
        assertEquals(result.settings, work.rows[123])
        assertEquals(result.settings, repeated.settings)
        assertEquals(1, work.saves)
    }

    @Test
    fun `처음 조회와 반복 조회는 같은 설정을 반환한다`() = runBlocking {
        val work = MemorySettingsUnitOfWork();
        val get = GetUserSettings(work)
        val first = get.execute(123);
        val second = get.execute(123)
        assertEquals(first, second); assertEquals(1, work.rows.size); assertEquals(0, work.saves)
    }

    @Test
    fun `최초 PATCH도 learner와 설정 생성부터 원자적으로 처리한다`() = runBlocking {
        val work = MemorySettingsUnitOfWork()
        val result =
            UpdateUserSettings(work).execute(123, UserSettingsChange(originLanguage = "ko", learningLanguage = "ja"))
        assertTrue(result.settings.configured); assertEquals(setOf(123L), work.learners); assertEquals(
        1, work.rows.size,
    )
    }

    @Test
    fun `첫 PATCH 검증 실패는 빈 learner와 설정을 남기지 않는다`() = runBlocking {
        val work = MemorySettingsUnitOfWork()
        assertFailsWith<LearningBusinessException> {
            UpdateUserSettings(work).execute(
                123, UserSettingsChange(originLanguage = "ko"),
            )
        }
        assertTrue(work.rows.isEmpty()); assertTrue(work.learners.isEmpty())
    }

    @Test
    fun `요청 실패는 그 앞에서 수행한 pending 승격도 롤백한다`() = runBlocking {
        val work = MemorySettingsUnitOfWork()
        val before = F.configured().copy(pendingDailySentenceCount = 8, pendingEffectiveDate = LocalDate.of(2000, 1, 1))
        work.rows[123] = before
        assertFailsWith<LearningBusinessException> {
            UpdateUserSettings(work).execute(
                123, UserSettingsChange(dailySentenceCount = 999),
            )
        }
        assertEquals(before, work.rows[123])
    }

    @Test
    fun `GET이 승격과 clamp를 저장하고 그 결과와 같은 정책을 반환한다`() = runBlocking {
        val work = MemorySettingsUnitOfWork()
        work.rows[123] =
            F.configured().copy(pendingDailySentenceCount = 99, pendingEffectiveDate = LocalDate.of(2000, 1, 1))
        val result = GetUserSettings(work).execute(123)
        assertEquals(20, result.settings.dailySentenceCount); assertNull(result.settings.pendingEffectiveDate)
        assertEquals(result.settings, work.rows[123]); assertEquals(work.policy, result.policy)
        assertEquals("123", result.settings.updatedBy)
    }

    @Test
    fun `정책 기본값 변화는 신규 사용자에게만 적용한다`() = runBlocking {
        val work = MemorySettingsUnitOfWork();
        val get = GetUserSettings(work)
        get.execute(123); work.policy = work.policy.copy(writing = GoalPolicy(7, 1, 20))
        assertEquals(5, get.execute(123).settings.dailySentenceCount)
        assertEquals(7, get.execute(456).settings.dailySentenceCount)
    }

    @Test
    fun `비활성 학습자는 현재 설정 조회와 변경이 모두 거부된다`() = runBlocking {
        val work = MemorySettingsUnitOfWork().apply { learnerStatus = "DELETION_PENDING" }
        assertFailsWith<LearnerUnavailableException> { GetUserSettings(work).execute(123) }
        assertFailsWith<LearnerUnavailableException> { UpdateUserSettings(work).execute(123, UserSettingsChange()) }
        assertTrue(work.rows.isEmpty())
    }

    @Test
    fun `저장 실패는 부분 변경을 남기지 않는다`() = runBlocking {
        val work = MemorySettingsUnitOfWork(); work.rows[123] = F.configured(); work.failSave = true
        assertFailsWith<IllegalStateException> {
            UpdateUserSettings(work).execute(
                123, UserSettingsChange(dailySentenceCount = 8),
            )
        }
        assertEquals(F.configured(), work.rows[123])
    }
}
