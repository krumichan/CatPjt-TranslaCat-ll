package jp.co.translacat.languagelearning.features.webaiplan.domain

import jp.co.translacat.languagelearning.features.webaiplan.domain.model.WebAiPlan
import java.time.*

internal object WebAiSchedulePolicy {
    /** 매월 없는 날짜는 건너뛰고, 월말과 지정일이 겹치면 한 번만 실행한다. */
    fun latestOccurrence(plan: WebAiPlan, now: Instant): ZonedDateTime {
        val zone = ZoneId.of(plan.timezone)
        val time = LocalTime.parse(plan.time)
        val today = now.atZone(zone).toLocalDate()
        for (offset in 0L..370L) {
            val date = today.minusDays(offset)
            val selected = when (plan.recurrence) {
                "DAILY" -> true
                "WEEKLY" -> date.dayOfWeek.value % 7 in plan.weekdays
                "MONTHLY" -> date.dayOfMonth in plan.monthDays ||
                    (plan.includeLastDay && date.dayOfMonth == date.lengthOfMonth())
                else -> false
            }
            // DST 공백은 해당 지역의 다음 유효 시각, 중복 시각은 첫 오프셋으로 고정한다.
            val occurrence = date.atTime(time).atZone(zone)
            if (selected && !occurrence.toInstant().isAfter(now)) return occurrence
        }
        WebAiPolicy.invalid("유효한 반복 날짜를 찾을 수 없습니다.")
    }
}
