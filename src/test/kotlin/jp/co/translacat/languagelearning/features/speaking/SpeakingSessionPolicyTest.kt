package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.support.SettingsFixtures
import java.time.LocalDateTime
import kotlin.test.*

class SpeakingSessionPolicyTest {
    @Test
    fun `Core 완료 집계와 Python 모델 전 집계는 원래 차이를 보존한다`() {
        // 준비: 비어 있는 인식 결과와 업로드 대기 발화는 Core 시간·분모에서 다르게 취급된다.
        val turns = List(5) { SpeakingCompletionTurn("합성 발화", 12.0, false, false) } +
            SpeakingCompletionTurn(null, 30.0, false, false) +
            SpeakingCompletionTurn("업로드 전", 20.0, false, true)

        // 실행
        val free = SpeakingPolicy.completionEligibility(SpeakingPracticeMode.FREE, turns)
        val readAloud = SpeakingPolicy.completionEligibility(SpeakingPracticeMode.READ_ALOUD, turns)

        // 검증
        assertEquals(5, free.validUserTurns)
        assertEquals(60.0, free.validUserSpeechSeconds)
        assertEquals(.83, free.validSttTurnRatio)
        assertTrue(free.eligibleBeforeAi)
        assertEquals(listOf("VALID_USER_TURNS"), readAloud.missingRequirements)
        assertEquals(10, readAloud.requiredUserTurns)
    }

    @Test
    fun `topic 단일 공급원과 keyword AI 선행 조건을 유지한다`() {
        // 준비
        val admin = SettingsFixtures.admin()
        val request = SpeakingCreateRequest(
            customTopic = "합성 주제", practiceMode = SpeakingPracticeMode.FREE,
            conversationStartMode = ConversationStartMode.USER_FIRST, correctionMode = CorrectionMode.CONVERSATION,
            targetMinutes = admin.minDailySpeakingGoalMinutes, idempotencyKey = "synthetic",
        )

        // 실행
        SpeakingSessionPolicy.validateCreate(request, admin)
        val conflict =
            assertFailsWith<SpeakingFailure> { SpeakingSessionPolicy.validateCreate(request.copy(topicId = 1), admin) }
        val keyword = assertFailsWith<SpeakingFailure> {
            SpeakingSessionPolicy.validateCreate(request.copy(customTopic = null, keywordBasedTopic = true), admin)
        }

        // 검증
        assertEquals("LANGUAGE_LEARNING_SETTING_INVALID", conflict.code)
        assertEquals("LANGUAGE_LEARNING_SETTING_INVALID", keyword.code)
        assertEquals(400, keyword.status)
    }

    @Test
    fun `따라읽기 기본 반복과 최대 횟수를 일반 대화 종료 조건과 구분한다`() {
        // 준비
        val snapshot = SpeakingSessionPolicySnapshot.from(SettingsFixtures.admin())

        // 실행
        val readAloud =
            SpeakingSessionPolicy.shouldComplete(true, SpeakingPracticeMode.READ_ALOUD, false, 15, 15, 0, snapshot)
        val free = SpeakingSessionPolicy.shouldComplete(true, SpeakingPracticeMode.FREE, false, 15, 15, 0, snapshot)

        // 검증
        assertEquals(15, SpeakingSessionPolicy.maxTurns(SpeakingPracticeMode.READ_ALOUD, 4))
        assertEquals(2, SpeakingSessionPolicy.READ_ALOUD_REQUIRED_ATTEMPTS_PER_ITEM)
        assertFalse(readAloud)
        assertTrue(free)
        assertEquals(
            ConversationStartMode.AI_FIRST,
            SpeakingSessionPolicy.resolveStart(ConversationStartMode.TOPIC_RECOMMENDED, null),
        )
        assertFailsWith<SpeakingFailure> {
            SpeakingSessionPolicy.requireResolvedStart(SpeakingPracticeMode.GUIDED, ConversationStartMode.USER_FIRST)
        }
    }

    @Test
    fun `재개 시간의 경계와 일일 발화시간 한도의 등호를 보존한다`() {
        // 준비
        val admin = SettingsFixtures.admin()
        val snapshot = SpeakingSessionPolicySnapshot.from(admin)
        val now = LocalDateTime.of(2026, 9, 26, 12, 0)
        val boundary = now.minusHours(snapshot.activeSessionResumeHours.toLong())

        // 실행
        val resumable = SpeakingSessionPolicy.resumable(true, boundary, now, snapshot)
        val expired = SpeakingSessionPolicy.resumable(true, boundary.minusNanos(1), now, snapshot)
        SpeakingSessionPolicy.requireTurnAllowed(snapshot.dailySpeakingHardLimitSeconds - 1, 1, snapshot)

        // 검증
        assertTrue(resumable)
        assertFalse(expired)
        assertFailsWith<SpeakingFailure> {
            SpeakingSessionPolicy.requireTurnAllowed(snapshot.dailySpeakingHardLimitSeconds, 1, snapshot)
        }
        assertFailsWith<SpeakingFailure> {
            SpeakingSessionPolicy.requireRemainingLimit(List(admin.dailySpeakingSessionLimit) { 0L }, admin)
        }
    }
}
