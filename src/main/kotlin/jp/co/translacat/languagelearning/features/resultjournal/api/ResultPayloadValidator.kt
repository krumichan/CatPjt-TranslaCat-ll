package jp.co.translacat.languagelearning.features.resultjournal.api

import jp.co.translacat.languagelearning.features.resultjournal.domain.model.IncomingLearningResult
import jp.co.translacat.languagelearning.features.resultjournal.domain.model.ResultKind
import kotlinx.serialization.json.*

/** 보관 계약만 검증한다. AI 의미 판정이나 Profile 점수를 다시 계산하지 않는다. */
internal object ResultPayloadValidator {
    fun validate(event: IncomingLearningResult) {
        event.validate()
        val payload = Json.parseToJsonElement(event.payloadJson).jsonObject
        require(payload["resultKind"]?.jsonPrimitive?.content == "SCORED_EVALUATION") {
            "FREE 코칭은 공식 평가 원장에 넣을 수 없습니다."
        }
        val response = payload["response"] as? JsonObject ?: errorInput()
        if (event.kind == ResultKind.WRITING_SCORED) {
            require(payload["answerId"]?.jsonPrimitive?.longOrNull?.toString() == event.referenceId)
            val scores = response["scores"] as? JsonObject ?: errorInput()
            for (name in listOf("overall", "meaning", "grammar", "vocabulary", "naturalness", "expression")) {
                val value = scores[name]?.jsonPrimitive?.doubleOrNull ?: errorInput()
                require(value.isFinite() && value in 0.0..100.0) { "Writing 점수 범위를 확인해 주세요." }
            }
        } else {
            require(payload["sessionId"]?.jsonPrimitive?.longOrNull?.toString() == event.referenceId)
            val formal = payload["formal"]?.jsonPrimitive?.booleanOrNull ?: errorInput()
            require(formal == (event.kind == ResultKind.SPEAKING_SCORED))
            val weight = payload["activityWeight"]?.jsonPrimitive?.doubleOrNull ?: errorInput()
            require(weight.isFinite() && weight in 0.0..1.0)
            if (formal) {
                require(response["status"]?.jsonPrimitive?.content?.equals("EVALUATED", ignoreCase = true) == true)
                val score = response["overallScore"]?.jsonPrimitive?.doubleOrNull ?: errorInput()
                require(score.isFinite() && score in 0.0..100.0)
            } else {
                // 원본 response의 진단값은 보존하지만 집계 가능한 공식 평가로 표시하지 않는다.
                require(weight == 0.0)
            }
        }
    }
    private fun errorInput(): Nothing = throw IllegalArgumentException("결과 본문의 필수 필드를 확인해 주세요.")
}
