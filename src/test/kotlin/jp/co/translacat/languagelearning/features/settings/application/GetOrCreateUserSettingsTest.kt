package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.learner.domain.model.Learner
import jp.co.translacat.languagelearning.features.learner.domain.repository.LearnerRepository
import jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException
import jp.co.translacat.languagelearning.features.settings.domain.model.GoalPolicy
import jp.co.translacat.languagelearning.features.settings.domain.model.InitialSettingsPolicy
import jp.co.translacat.languagelearning.features.settings.domain.model.NewUserSettings
import jp.co.translacat.languagelearning.features.settings.domain.exception.SettingsPolicyNotInitializedException
import jp.co.translacat.languagelearning.features.settings.domain.repository.SettingsPolicyRepository
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettings
import jp.co.translacat.languagelearning.features.settings.domain.repository.UserSettingsRepository
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GetOrCreateUserSettingsTest {
    @Test
    fun `첫 설정은 현재 정책과 기존 BE의 정적 기본값을 사용한다`() = runBlocking {
        val fake = FakeUnitOfWork()
        val actual = GetOrCreateUserSettings(fake).execute(123)
        assertEquals(listOf("transaction", "learner-lock", "find", "policy", "create"), fake.calls)
        assertEquals(7, actual.dailySentenceCount)
        assertEquals(8, actual.dailySpeakingGoalMinutes)
        assertEquals(9, actual.dailyListeningGoalCount)
        assertEquals("Asia/Tokyo", actual.timezone)
        assertEquals("marin", actual.speakingVoiceId)
        assertEquals("NORMAL", actual.speakingPlaybackSpeed)
        assertEquals("[\"DICTATION\"]", actual.defaultListeningTaskTypesJson)
        assertNull(actual.originLanguage)
        assertNull(actual.learningLanguage)
        assertNull(actual.pendingEffectiveDate)
        assertFalse(actual.configured)
        assertEquals(fake.now, actual.createdAt)
        assertEquals(actual.createdAt, actual.updatedAt)
        assertEquals("123", actual.createdBy)
    }

    @Test
    fun `기존 설정은 정책을 다시 읽거나 재생성하지 않는다`() = runBlocking {
        val fake = FakeUnitOfWork()
        val service = GetOrCreateUserSettings(fake)
        val first = service.execute(123)
        fake.calls.clear()
        fake.missingPolicy = true
        val second = service.execute(123)
        assertSame(first, second)
        assertEquals(listOf("transaction", "learner-lock", "find"), fake.calls)
    }

    @Test
    fun `기존 사용자 값과 과거 날짜의 pending도 이번 조회에서는 그대로 반환한다`() = runBlocking {
        val fake = FakeUnitOfWork()
        val service = GetOrCreateUserSettings(fake)
        fake.existing = service.execute(123).copy(
            originLanguage = "ko",
            learningLanguage = "ja",
            timezone = "Europe/Paris",
            dailySentenceCount = 40,
            pendingOriginLanguage = "ja",
            pendingLearningLanguage = "en",
            pendingTimezone = "UTC",
            pendingDailySentenceCount = 80,
            pendingDailySpeakingGoalMinutes = 90,
            pendingDailyListeningGoalCount = 70,
            pendingEffectiveDate = LocalDate.of(2000, 1, 1),
        )
        val expected = fake.existing
        assertEquals(expected, service.execute(123))
        assertTrue(service.execute(123).configured)
    }

    @Test
    fun `양수가 아닌 식별자는 트랜잭션 이전에 거부한다`() = runBlocking {
        val fake = FakeUnitOfWork()
        for (id in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { GetOrCreateUserSettings(fake).execute(id) }
        }
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `비활성 학습자와 알 수 없는 상태는 설정 조회 전에 거부한다`() = runBlocking {
        for (status in listOf("SUSPENDED", "DELETION_PENDING", "DELETED", "UNKNOWN", "active")) {
            val fake = FakeUnitOfWork().apply { learnerStatus = status }
            assertFailsWith<LearnerUnavailableException> { GetOrCreateUserSettings(fake).execute(123) }
            assertEquals(listOf("transaction", "learner-lock"), fake.calls)
        }
    }

    @Test
    fun `필수 정책이 없으면 임의 기본값으로 생성하지 않는다`() = runBlocking {
        val fake = FakeUnitOfWork().apply { missingPolicy = true }
        assertFailsWith<SettingsPolicyNotInitializedException> { GetOrCreateUserSettings(fake).execute(123) }
        assertNull(fake.existing)
        assertFalse("create" in fake.calls)
    }

    @Test
    fun `사용자 식별자와 감사 주체는 같은 입력을 사용한다`() = runBlocking {
        val value = GetOrCreateUserSettings(FakeUnitOfWork()).execute(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, value.userId)
        assertEquals(Long.MAX_VALUE.toString(), value.createdBy)
    }

    /** 업무 순서만 검증하는 가짜 저장소다. 실제 rollback/동시성은 MySQL 테스트에서 검증한다. */
    private class FakeUnitOfWork : SettingsUnitOfWork {
        val now: LocalDateTime = LocalDateTime.of(2026, 9, 24, 3, 30, 0, 123456000)
        val calls = mutableListOf<String>()
        var learnerStatus = "ACTIVE"
        var missingPolicy = false
        var existing: UserSettings? = null
        private val scope = object : SettingsTransaction {
            override val nowUtc = now
            override val learners = object : LearnerRepository {
                override fun ensureAndLock(userId: Long, nowUtc: LocalDateTime): Learner {
                    calls += "learner-lock"
                    return Learner(userId, learnerStatus, 3, nowUtc, nowUtc)
                }
            }
            override val policies = object : SettingsPolicyRepository {
                override fun loadInitialPolicy(): InitialSettingsPolicy {
                    calls += "policy"
                    if (missingPolicy) throw SettingsPolicyNotInitializedException("test")
                    return InitialSettingsPolicy(GoalPolicy(7, 1, 20), GoalPolicy(8, 3, 20), GoalPolicy(9, 1, 20))
                }
            }
            override val userSettings = object : UserSettingsRepository {
                override fun save(settings: UserSettings): UserSettings = settings.also { existing = it }
                override fun findForUser(userId: Long): UserSettings? {
                    calls += "find"
                    return existing
                }
                override fun create(settings: NewUserSettings): UserSettings {
                    calls += "create"
                    return UserSettings(
                        id = 1, userId = settings.userId, originLanguage = null, learningLanguage = null,
                        timezone = settings.timezone, dailySentenceCount = settings.dailySentenceCount,
                        dailySpeakingGoalMinutes = settings.dailySpeakingGoalMinutes,
                        dailyListeningGoalCount = settings.dailyListeningGoalCount,
                        defaultListeningTaskTypesJson = settings.defaultListeningTaskTypesJson,
                        speakingVoiceId = settings.speakingVoiceId, speakingPlaybackSpeed = settings.speakingPlaybackSpeed,
                        pendingOriginLanguage = null, pendingLearningLanguage = null, pendingTimezone = null,
                        pendingDailySentenceCount = null, pendingDailySpeakingGoalMinutes = null,
                        pendingDailyListeningGoalCount = null, pendingEffectiveDate = null,
                        createdBy = settings.userId.toString(), createdAt = settings.nowUtc,
                        updatedBy = settings.userId.toString(), updatedAt = settings.nowUtc,
                    ).also { existing = it }
                }
            }
        }
        override suspend fun <T> execute(block: SettingsTransaction.() -> T): T {
            calls += "transaction"
            return block(scope)
        }
    }
}
