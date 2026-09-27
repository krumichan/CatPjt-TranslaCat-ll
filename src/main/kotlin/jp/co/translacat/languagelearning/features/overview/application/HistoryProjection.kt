package jp.co.translacat.languagelearning.features.overview.application

import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import kotlinx.serialization.json.*
import java.time.Duration
import java.time.LocalDate

internal object HistoryProjection {
    val sources = listOf("WRITING", "SPEAKING", "LISTENING", "READING", "VOCABULARY", "LEVEL_TEST")
    fun days(raw: String?): Int = raw?.trim()?.lowercase()?.takeIf { it.endsWith("d") }
        ?.dropLast(1)?.toIntOrNull()?.coerceIn(1, 365) ?: 30

    fun history(values: List<JsonObject>, status: String?): JsonArray = JsonArray(
        values.filter {
            status.isNullOrBlank() || status.equals(it.text("completionStatus"), true) || status.equals(
                it.text("evaluationStatus"), true,
            )
        }
            .sortedWith(
                compareByDescending<JsonObject> { it.date("learningDate") }.thenBy {
                    sources.indexOf(
                        it.text("source"),
                    )
                },
            ),
    )

    fun practice(set: JsonObject): JsonObject {
        // 미완료 활동의 소요 시간은 0으로 두고 완료 활동만 실제 경과 시간을 사용한다.
        val source = checkNotNull(set.text("domain"))
        val duration = if (set.text("completedAt") == null) 0 else Duration.between(
            set.time("startedAt"), set.time("completedAt"),
        ).seconds.coerceAtLeast(0)

        return buildJsonObject {
            put("activityId", "$source:${set.text("setId")}"); put("source", source); put(
            "learningDate", set.getValue("learningDate"),
        )
            put("title", "${if (source == "READING") "Reading" else "Vocabulary"} · ${set.text("mode")}")
            put("topic", set.getValue("mode")); put("durationSeconds", duration); put(
            "overallScore", set.getValue("officialScore"),
        )
            put("completionStatus", set.getValue("status")); put("evaluationStatus", set.getValue("status"))
        }
    }

    fun streak(dates: Set<LocalDate>, today: LocalDate): JsonObject {
        // 오늘이 비어 있어도 어제까지 이어진 학습을 현재 연속 일수로 표시한다.
        var cursor = if (today in dates) today else today.minusDays(1)
        var current = 0
        while (cursor in dates) {
            current++
            cursor = cursor.minusDays(1)
        }

        // 전체 완료일을 날짜 순서로 읽어 가장 긴 연속 구간을 계산한다.
        var longest = 0
        var running = 0
        var previous: LocalDate? = null
        for (date in dates.sorted()) {
            running = if (previous?.plusDays(1) == date) running + 1 else 1
            longest = maxOf(longest, running)
            previous = date
        }

        return buildJsonObject {
            put("current", current); put("longest", longest)
            put("lastStudyDate", dates.maxOrNull()?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    fun activity(value: String): Pair<String, Long> {
        // 외부 복합 ID의 출처·숫자 형식 오류는 기존 이력 조회 오류로 유지한다.
        val parts = value.split(':', limit = 2)
        if (parts.size != 2) notFound()
        val source = parts[0].uppercase().takeIf { it in sources } ?: notFound()
        return source to (parts[1].toLongOrNull() ?: notFound())
    }

    fun notFound(): Nothing =
        throw LearningBusinessException("LANGUAGE_LEARNING_DAILY_SET_NOT_FOUND", "학습 이력을 찾을 수 없습니다.")
}
