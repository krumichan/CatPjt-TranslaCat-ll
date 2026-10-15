package jp.co.translacat.languagelearning.features.webaiplan.domain

import jp.co.translacat.languagelearning.features.webaiplan.domain.model.*
import java.security.MessageDigest
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

internal object WebAiPolicy {
    val areaCodes = setOf(
        "WRITING_TRANSLATION", "WRITING_GUIDED", "WRITING_FREE",
        "READING_COMPREHENSION", "READING_STRUCTURE", "READING_CONTEXT_INFERENCE",
        "SPEAKING_READ_ALOUD", "SPEAKING_GUIDED", "SPEAKING_FREE",
        "LISTENING_DICTATION", "LISTENING_COMPREHENSION", "LISTENING_SUMMARY",
    )
    private val keyPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}")

    fun id(value: String): String {
        if (runCatching { UUID.fromString(value).toString() }.getOrNull() != value) invalid("식별자를 확인해 주세요.")
        return value
    }

    fun executionKey(value: String): String {
        if (!keyPattern.matches(value)) invalid("실행·제출 식별자는 1~128자의 영문, 숫자 및 ._:@+-로 지정해 주세요.")
        return value
    }

    fun plan(change: WebAiPlanChange) {
        if (change.name.isBlank() || change.name.length > 120 || change.focus.length > 10_000) {
            invalid("계획 이름과 학습 목표의 길이를 확인해 주세요.")
        }
        if (change.timezone.length > 60 || runCatching { ZoneId.of(change.timezone) }.isFailure ||
            !Regex("\\d{2}:\\d{2}").matches(change.time) || runCatching { LocalTime.parse(change.time) }.isFailure
        ) invalid("실행 시각과 시간대를 확인해 주세요.")
        if (change.weekdays.distinct().size != change.weekdays.size || change.weekdays.any { it !in 0..6 } ||
            change.monthDays.distinct().size != change.monthDays.size || change.monthDays.any { it !in 1..31 }
        ) invalid("반복 날짜를 확인해 주세요.")
        when (change.recurrence) {
            "DAILY" -> Unit
            "WEEKLY" -> if (change.weekdays.isEmpty()) invalid("요일을 선택해 주세요.")
            "MONTHLY" -> if (change.monthDays.isEmpty() && !change.includeLastDay) invalid("월별 일자를 선택해 주세요.")
            else -> invalid("반복 방식을 확인해 주세요.")
        }

        // 문항 수에 상품 상한을 두지 않는다. JSON/브라우저가 정확히 표현할 수 있는 정수만 허용한다.
        if (change.areas.isEmpty() || change.areas.size > areaCodes.size ||
            change.areas.map { it.code }.distinct().size != change.areas.size ||
            change.areas.any { it.code !in areaCodes || listOf(it.easy, it.standard, it.hard).any { n -> n !in 0..9_007_199_254_740_991L } } ||
            change.areas.all { it.easy == 0L && it.standard == 0L && it.hard == 0L }
        ) invalid("세부 학습 분야와 난이도별 문제 수를 확인해 주세요.")
        if (change.keywordMode !in setOf("DEFAULT", "CUSTOM") || change.keywords.size > 500 ||
            change.keywords.map { it.key }.distinct().size != change.keywords.size
        ) invalid("계획별 키워드를 확인해 주세요.")
    }

    fun submission(value: WebAiResultSubmission, run: WebAiRun) {
        id(value.runId)
        executionKey(value.submissionId)
        if (value.items.isEmpty() || value.items.size > 10) invalid("한 번에 1~10문항씩 제출해 주세요. 나머지는 이어서 제출할 수 있습니다.")
        if (value.originalFeedbackMarkdown.length > 250_000 || value.items.map { it.itemId }.distinct().size != value.items.size) {
            invalid("피드백 크기 또는 중복 문항을 확인해 주세요.")
        }
        value.items.forEach { item ->
            // 실행 snapshot에 할당된 세부 유형·난이도·순번만 수락한다. 부분 제출은 허용한다.
            val parts = item.itemId.split(':')
            val index = parts.getOrNull(2)?.toLongOrNull()
            val area = run.areas.singleOrNull { it.code == item.areaCode }
            if (parts.size != 3 || parts[0] != item.areaCode || parts[1] != item.difficulty ||
                index == null || index < 1 || area == null || index > area.count(item.difficulty) ||
                parts[2] != index.toString()
            ) invalid("문항 식별자가 실행 계획의 분야·난이도·문제 수와 일치하지 않습니다.")
            if (item.promptText.isBlank() || item.answerText.isBlank() || item.promptText.length > 20_000 ||
                item.answerText.length > 20_000 || (item.passageText?.length ?: 0) > 40_000 ||
                (item.sourceText?.length ?: 0) > 20_000 || item.externalFeedbackMarkdown.length > 100_000 ||
                item.requiredIntents.size > 30 || item.requiredIntents.any { it.length > 2_000 } ||
                item.externalScore?.let { !it.isFinite() || it !in 0.0..100.0 } == true
            ) invalid("문제·답안·피드백의 내용과 크기를 확인해 주세요.")
            if (item.keywordKeys.distinct().size != item.keywordKeys.size ||
                item.keywordKeys.any { key -> run.keywords.none { it.key == key } }
            ) invalid("실행 시 선택된 키워드만 제출할 수 있습니다.")
        }
    }

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun invalid(message: String): Nothing = throw WebAiException("WEB_AI_INVALID", message)
    fun missing(): Nothing = throw WebAiException("WEB_AI_NOT_FOUND", "학습 계획 또는 결과를 찾을 수 없습니다.", 404)
    fun conflict(message: String): Nothing = throw WebAiException("WEB_AI_CONFLICT", message, 409)
}
