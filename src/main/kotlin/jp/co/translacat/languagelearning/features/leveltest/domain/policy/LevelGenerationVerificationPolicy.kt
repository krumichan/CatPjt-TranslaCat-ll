package jp.co.translacat.languagelearning.features.leveltest.domain.policy

import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestItemType
import kotlinx.serialization.json.*

/** 기존 독립 선택지 판정·부분 점수·과제 충분성 규칙이다. 생성 루프의 연결 전에도 순수 비교가 가능하다. */
internal object LevelGenerationVerificationPolicy {
    fun selection(itemType: LevelTestItemType, band: Int): String? {
        val uniqueThrough = when (itemType) {
            LevelTestItemType.VOCAB_CONTEXT_CHOICE, LevelTestItemType.GRAMMAR_FORM_CHOICE,
            LevelTestItemType.READING_GIST, LevelTestItemType.READING_DETAIL,
            LevelTestItemType.LISTENING_GIST_CHOICE, LevelTestItemType.LISTENING_DETAIL_CHOICE,
                -> 3

            LevelTestItemType.VOCAB_PARAPHRASE_CHOICE, LevelTestItemType.READING_DISCOURSE_FUNCTION,
            LevelTestItemType.READING_TEXT_INFERENCE,
                -> 2

            else -> return null
        }
        return if (band <= uniqueThrough) "UNIQUE_ANSWER" else "BEST_ANSWER"
    }

    fun passes(selection: String): Int = if (selection == "BEST_ANSWER") 2 else 1

    fun rejection(candidate: JsonObject, verdict: JsonObject, selection: String): String? {
        val type = candidate.getValue("itemType").jsonPrimitive.content
        val keys =
            candidate.getValue("options").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content }.toSet()
        val plausible = verdict.strings("plausibleOptionKeys").distinct()
        val near = verdict.strings("nearEquivalentOptionKeys").distinct()
        val best = verdict.getValue("bestOptionKey").jsonPrimitive.content

        // 존재하지 않는 선택지·자기 자신과 동등한 정답·검증 불가는 기존 순서대로 거절한다.
        if ((plausible + near).any { it !in keys }) return "$type 의미 검증 결과에 존재하지 않는 Option key가 포함되어 있습니다."
        if (best !in keys) return "$type 의미 검증 결과의 bestOptionKey가 유효하지 않습니다."
        if (best in near) return "$type 의미 검증의 nearEquivalentOptionKeys에는 bestOptionKey 자체를 포함할 수 없습니다."
        if (!verdict.getValue("verifiable").jsonPrimitive.boolean) return "$type 문항을 공정한 단일 정답 문제로 독립 검증할 수 없습니다."
        val correct =
            candidate.getValue("internalAnswerKey").jsonObject["correctOptionKey"]?.jsonPrimitive?.contentOrNull
                ?: return "$type 서버 정답 Key가 없습니다."
        val clear = verdict.getValue("bestOptionAdvantage") == JsonPrimitive("CLEAR")

        // 높은 band의 BEST_ANSWER도 명백한 최선 정답 조건을 완화하지 않는다.
        if (selection == "UNIQUE_ANSWER") {
            if (near.isNotEmpty()) return "$type UNIQUE_ANSWER 검증에서 기능적으로 동등한 경쟁 선택지가 발견되었습니다."
            if (plausible != listOf(correct) || best != correct || !clear)
                return "$type UNIQUE_ANSWER 검증에서 복수 정답 가능성 또는 정답 불일치가 발견되었습니다."
        } else {
            if (near.isNotEmpty()) return "$type BEST_ANSWER 검증에서 정답과 기능적으로 거의 동등한 경쟁 선택지가 발견되었습니다."
            if (correct !in plausible || best != correct || !clear)
                return "$type BEST_ANSWER 검증에서 서버 정답이 명백한 최선의 선택지로 확인되지 않았습니다."
        }
        return null
    }

    fun repairable(candidate: JsonObject, verdict: JsonObject, selection: String): Boolean {
        if (!verdict.getValue("verifiable").jsonPrimitive.boolean || verdict.strings("nearEquivalentOptionKeys")
                .isNotEmpty()
        ) return false
        val correct =
            candidate.getValue("internalAnswerKey").jsonObject["correctOptionKey"]?.jsonPrimitive?.contentOrNull
                ?: return false
        val plausible = verdict.strings("plausibleOptionKeys").distinct()
        if (correct !in plausible || verdict["bestOptionKey"] != JsonPrimitive(correct)) return false
        return if (selection == "UNIQUE_ANSWER") plausible.size > 1 else verdict["bestOptionAdvantage"] != JsonPrimitive(
            "CLEAR",
        )
    }

    fun optionScores(candidate: JsonObject, selection: String, verdicts: List<JsonObject>): Map<String, Int> {
        val correct =
            candidate.getValue("internalAnswerKey").jsonObject["correctOptionKey"]?.jsonPrimitive?.contentOrNull
                ?: return emptyMap()
        val keys = candidate.getValue("options").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content }
        if (selection == "UNIQUE_ANSWER" || verdicts.any { it.strings("nearEquivalentOptionKeys").isNotEmpty() })
            return keys.associateWith { if (it == correct) 100 else 0 }

        // 부분 점수는 검증기의 숫자가 아니라 기존 두 번의 독립 plausible 합의에서 결정한다.
        val passes = maxOf(1, verdicts.size)
        return keys.associateWith { key ->
            val count = verdicts.count { key in it.strings("plausibleOptionKeys") }
            when {
                key == correct -> 100; count >= passes -> 30; count > 0 -> 10; else -> 0
            }
        }
    }

    fun taskRejection(candidate: JsonObject, verdict: JsonObject): String? {
        fun flag(key: String) = verdict.getValue(key).jsonPrimitive.boolean
        if (flag("sufficient") && !flag("requiresExternalKnowledge") && !flag("requiresProblemSolving") &&
            flag("providedFactsSufficient") && flag("communicativeGoalsClear") && flag(
                "instructionAndTaskRolesSeparated",
            )
        ) return null
        val reasons = mutableListOf<String>()
        if (flag("requiresExternalKnowledge")) reasons += "외부 지식 필요"
        if (flag("requiresProblemSolving")) reasons += "상황 판단/문제 해결 요구"
        if (!flag("providedFactsSufficient")) reasons += "제공 사실 부족"
        if (!flag("communicativeGoalsClear")) reasons += "의사기능 가이드 불명확"
        if (!flag("instructionAndTaskRolesSeparated")) reasons += "origin/learning 언어 역할 중복"
        val missing = verdict.strings("missingInformation")
        if (missing.isNotEmpty()) reasons += "누락=" + missing.take(3).joinToString(",")
        if (reasons.isEmpty()) reasons += "독립 검증 실패"
        return candidate.getValue("itemType").jsonPrimitive.content + " 가이드 충분성 검증에 실패했습니다: " + reasons.joinToString(
            "; ",
        )
    }

    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
}
