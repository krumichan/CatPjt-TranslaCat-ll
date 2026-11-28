package jp.co.translacat.languagelearning.features.autopractice.domain

import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 서비스 모델과 무관한 학습일·예약·수량 판정을 공유한다. */
internal object AutoPracticePolicy {
    val preparationLead: Duration = Duration.ofHours(3)
    const val maxAreaLimit = 100
    const val defaultAreaLimit = 5
    private val timePattern = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")

    /** 0은 해당 유형 자동 생성 금지이며 모든 유형의 관리자 설정이 필요하다. */
    fun validateLimits(limits: Map<AutoPracticeArea, Int>) {
        if (limits.keys != AutoPracticeArea.entries.toSet() ||
            limits.any { (area, value) -> value !in 0..maximumSupported(area) }
        ) {
            invalid("세부 유형별 일일 최대 문항 수를 모두 확인해 주세요.")
        }
    }

    /** 기존 Reading 생성 엔진의 두 지문·다섯 문항 계약을 보존한다. */
    fun maximumSupported(area: AutoPracticeArea): Int =
        if (area.name.startsWith("READING_")) 5 else maxAreaLimit

    /** 사용자가 지정한 난이도 구성을 보존하고 허용 수량을 넘긴 플랜은 저장하지 않는다. */
    fun validatePlan(input: AutoPracticePlanInput, limits: Map<AutoPracticeArea, Int>, writingLimit: Int) {
        if (input.name.trim().length !in 1..100 || !timePattern.matches(input.studyTime)) {
            invalid("플랜 이름과 학습 예정 시각을 확인해 주세요.")
        }
        if (input.areas.isEmpty() || input.areas.values.none { it.total > 0 }) {
            invalid("자동 생성할 유형과 문항 수를 선택해 주세요.")
        }
        if (input.areas.values.any { it.easy < 0 || it.standard < 0 || it.hard < 0 } ||
            !withinLimits(input.areas, limits, writingLimit)
        ) {
            invalid("관리자 일일 최대 문항 수를 초과했습니다.")
        }
    }

    /** 플랜별 한도는 세부 유형과 기존 라이팅 전체 한도를 동시에 적용한다. */
    fun withinLimits(
        areas: Map<AutoPracticeArea, AutoPracticeQuantity>,
        limits: Map<AutoPracticeArea, Int>,
        writingLimit: Int,
    ): Boolean = areas.all { (area, quantity) -> quantity.total in 0..limits.getValue(area).toLong() } &&
        areas.filterKeys { it.isWriting }.values.sumOf { it.total } <= writingLimit

    /** 자정을 넘는 준비도 예약 시각이 속한 현지 학습일의 회차다. */
    fun preparationStartsOn(learningDate: LocalDate, time: LocalTime, zone: ZoneId): Instant =
        learningDate.atTime(time).atZone(zone).toInstant().minus(preparationLead)

    /** 深夜の翌日準備と同じ日付に始める手動回だけを翌日へ繰り越せる。 */
    fun rolloverAt(today: LocalDate, time: LocalTime, zone: ZoneId): Instant? =
        preparationStartsOn(today.plusDays(1), time, zone).takeIf { it.atZone(zone).toLocalDate() == today }

    /** 生成を開始できることと、すでに生成された問題を公開できることを分ける。 */
    fun isVisible(job: AutoPracticeJob, now: Instant): Boolean =
        job.availableAt?.let { now >= Instant.parse(it) } != false &&
            job.rolloverAt?.let { now < Instant.parse(it) } != false

    /** 전날 분량은 그대로 두고, 오늘 또는 준비 시간이 열린 내일 분량만 배정한다. */
    fun dueDates(
        now: Instant,
        zone: ZoneId,
        time: LocalTime,
        placementDate: LocalDate?,
        answeredDates: Set<LocalDate>,
    ): List<LocalDate> {
        val today = now.atZone(zone).toLocalDate()
        if (placementDate == null) return emptyList()
        return listOf(today, today.plusDays(1)).filter { date ->
            date.isAfter(placementDate) &&
                !now.isBefore(preparationStartsOn(date, time, zone)) &&
                (date == placementDate.plusDays(1) || date.minusDays(1) in answeredDates)
        }
    }

    /** 첫 분량을 받을 기회를 보존하고, 두 학습일이 모두 종료된 뒤에만 자동으로 끈다. */
    fun shouldPause(
        today: LocalDate,
        activatedDate: LocalDate,
        placementDate: LocalDate?,
        answeredDates: Set<LocalDate>,
    ): Boolean {
        if (placementDate == null) return false
        val observedFrom = maxOf(activatedDate, placementDate.plusDays(1))
        return !today.minusDays(2).isBefore(observedFrom) &&
            today.minusDays(1) !in answeredDates && today.minusDays(2) !in answeredDates
    }

    private fun invalid(message: String): Nothing =
        throw LearningBusinessException("LANGUAGE_LEARNING_SETTING_INVALID", message)
}
