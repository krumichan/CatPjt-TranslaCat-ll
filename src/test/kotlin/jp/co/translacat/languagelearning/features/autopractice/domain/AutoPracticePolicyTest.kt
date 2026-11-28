package jp.co.translacat.languagelearning.features.autopractice.domain

import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.*

/** 자정에 걸친 배정일과 미학습 기간, 관리자 상한을 실제 달력으로 검증한다. */
class AutoPracticePolicyTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val monday = LocalDate.of(2026, 11, 30)

    @Test
    fun midnightUsesTheNextLearningDayOnlyAfterEligibilityIsKnown() {
        val miss = monday.atTime(21, 0).atZone(zone).toInstant()
        val firstPlacement = monday.minusDays(4)
        assertTrue(AutoPracticePolicy.dueDates(miss, zone, LocalTime.MIDNIGHT, firstPlacement, emptySet()).isEmpty())
        assertEquals(listOf(monday.plusDays(1)), AutoPracticePolicy.dueDates(
            miss.plusSeconds(10), zone, LocalTime.MIDNIGHT, firstPlacement, setOf(monday),
        ))
    }

    @Test
    fun firstDayIsReservedOnceEvenWithoutPriorAnswers() {
        val firstWindow = monday.atTime(21, 0).atZone(zone).toInstant()
        assertEquals(listOf(monday.plusDays(1)), AutoPracticePolicy.dueDates(
            firstWindow, zone, LocalTime.MIDNIGHT, monday, emptySet(),
        ))
        assertFalse(AutoPracticePolicy.shouldPause(monday.plusDays(1), monday, monday, emptySet()))
    }

    @Test
    fun skipsNewWorkAfterOneMissAndPausesAfterTwoCompletedDays() {
        val wednesday = monday.plusDays(2)
        val thursday = monday.plusDays(3)
        val firstPlacement = monday.minusDays(10)
        val answers = setOf(monday)
        assertFalse(AutoPracticePolicy.shouldPause(wednesday, monday, firstPlacement, answers))
        assertTrue(AutoPracticePolicy.dueDates(
            wednesday.atTime(18, 0).atZone(zone).toInstant(), zone, LocalTime.of(18, 0), firstPlacement, answers,
        ).isEmpty())
        assertTrue(AutoPracticePolicy.shouldPause(thursday, monday, firstPlacement, answers))
        assertFalse(AutoPracticePolicy.shouldPause(thursday, monday, firstPlacement, answers + wednesday))
    }

    @Test
    fun writingAggregateAndSubtypeLimitsBothApply() {
        val translation = AutoPracticeArea.WRITING_TRANSLATION
        val guided = AutoPracticeArea.WRITING_GUIDED
        val limits = AutoPracticeArea.entries.associateWith { 5 }
        val input = AutoPracticePlanInput(
            "Practice", "00:00", mapOf(translation to AutoPracticeQuantity(1, 2, 2),
                guided to AutoPracticeQuantity(standard = 3)),
        )
        AutoPracticePolicy.validatePlan(input, limits, 8)
        assertFailsWith<LearningBusinessException> { AutoPracticePolicy.validatePlan(input, limits, 7) }
        assertFailsWith<LearningBusinessException> {
            AutoPracticePolicy.validatePlan(input, limits + (translation to 4), 8)
        }
    }
}
