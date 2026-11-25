package jp.co.translacat.languagelearning.features.leveltest.domain.policy

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import java.math.BigDecimal
import java.math.RoundingMode

/** 최초 직접 선택의 허용 구간과 기존 정수 점수 구간의 중앙값을 관리한다. */
internal object LevelSelfSelectionPolicy {
    data class Selection(val band: Int, val score: Int)

    /** B1~B3만 허용하며 정수 구간의 중앙값을 HALF_UP으로 반올림한다. */
    fun selection(value: String?): Selection {
        val band = when (value) {
            "B1" -> 1
            "B2" -> 2
            "B3" -> 3
            else -> throw LevelTestException("INVALID_REQUEST", 400, "직접 선택은 B1, B2, B3 중에서만 가능합니다.")
        }
        val range = when (band) {
            1 -> 0..39
            2 -> 40..54
            else -> 55..69
        }
        val midpoint = BigDecimal.valueOf((range.first + range.last).toLong())
            .divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP).intValueExact()
        return Selection(band, midpoint)
    }

    /** 재전송 키는 1~200자로 제한하며 잘못된 입력은 상태 충돌과 구분한다. */
    fun requireKey(value: String?): String = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
        ?: throw LevelTestException("INVALID_REQUEST", 400, "200자 이하의 idempotencyKey가 필요합니다.")
}
