package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV3Policy.Segment
import kotlinx.serialization.json.*

/** 부분별 근거에서 관찰을 조립하고, 실제 원문 조각만 교체한다. 의미 판단 자체는 모델과 수용 검토의 책임이다. */
internal object CuratedWritingFeedbackV6Policy {
    const val version = "curated-writing-feedback-v6"
    private val relationKeys = setOf("causality", "chronology", "specificity", "stance")
    private val relationStates = setOf("PRESERVED", "CONFLICT", "NOT_APPLICABLE", "UNCERTAIN")
    private val partStates = setOf("PRESENT", "ABSENT", "CONFLICT", "UNCERTAIN")
    private val hangul = Regex("[\\u1100-\\u11ff\\u3130-\\u318f\\ua960-\\ua97f\\uac00-\\ud7ff\\uffa0-\\uffdc]")
    private val japanese = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff]")

    data class Context(val writingType: WritingType, val public: JsonObject, val originLanguage: String, val learningLanguage: String) {
        init {
            // 설명은 저장된 언어쌍만 사용한다. 현재 UI 설정으로 과거 결과를 바꾸지 않는다.
            require(originLanguage == "ko" && learningLanguage == "ja") { "WRITING_LANGUAGE_UNSUPPORTED" }
        }
        fun previous() = CuratedWritingFeedbackV5Policy.Context(writingType, public, originLanguage, learningLanguage)
    }

    fun segments(answer: String): List<Segment> = CuratedWritingFeedbackV3Policy.segments(answer)
    fun requirements(checklist: JsonArray): List<Pair<String, String>> = CuratedWritingFeedbackV3Policy.requirements(checklist)
    fun taskFacts(context: Context): List<Pair<String, String>> = CuratedWritingFeedbackV5Policy.taskFacts(context.previous())
    fun prompt(context: Context, reference: JsonObject, answer: String, requirements: List<Pair<String, String>>, segments: List<Segment>): String =
        CuratedWritingFeedbackV5Policy.prompt(context.previous(), reference, answer, requirements, segments)

    val instructions = """
        Give useful formative writing feedback only: no score, band, ability profile or weakness tag.
        Follow task.originLanguage and task.learningLanguage. learnerAnswer alone establishes personal facts,
        opinions and choices. FREE has no reference examples; other reference examples are not requirements.
        Start with evidence, not a whole-requirement verdict. For each requirement, split its exact text into
        minimal independently required parts: separate each fact, reason, request, condition and opinion.
        requirementQuote strings in order must concatenate to the complete requirement text, including spaces
        and connectors. Do not omit a conjunct or merge independently required facts to hide missing content.
        Each part has status PRESENT, ABSENT, CONFLICT or UNCERTAIN and exact learner evidence (segmentId, quote).
        PRESENT means its complete meaning is expressed. ABSENT means needed content is not expressed, with
        empty evidence. A missing personal reason or undecided choice is ABSENT, not a conflicting quotation.
        CONFLICT needs actual expressed contradictory evidence; UNCERTAIN needs genuinely ambiguous evidence.
        Reference each applicable T ID from explicitTaskFacts in taskFactIds. Cover every supplied T fact in
        at least one appropriate part, checking its complete content, not just shared numbers or nearby words.
        Distinguish original plans, current announcements and changes. Check what causes what and concessions,
        stated specificity, actual beliefs and commitments. Quote the smallest sufficient actual learner span.
        relationChecks summarizes these evidence judgments: causality, chronology, specificity, stance use
        PRESERVED, CONFLICT, NOT_APPLICABLE or UNCERTAIN. A CONFLICT/UNCERTAIN relation must have a matching
        part status and vice versa. The server derives overall status and missing-content explanations.
        Independently find actual spelling, inflection and syntax errors even when content is PRESENT.
        Necessary LANGUAGE_FORM requires an actual error, formReason SPELLING/INFLECTION/SYNTAX and empty
        sourceFactId. Optional punctuation, readability, numeral notation and valid duration modifiers are
        not errors. TASK_FACT_CONFLICT requires a contradictory part linked to the supplied T fact and
        formReason NONE. Do not fill missing personal facts or change preferences to satisfy the assignment.
        Every correction or optional alternative supplies ONE local patch: original is a nonempty exact
        fragment occurring once in its segment; replacement changes only that fragment. The server assembles
        the full segment. Keep unaffected text outside the patch. A patch may delete text but the resulting
        sentence must remain meaningful. Separate independent errors with nonoverlapping patches.
        Explain specifically in Japanese why a necessary repair helps. Korean explanations are supplied by
        the server from the issue, fact and actual patch; do not output originText anywhere.
        Optional alternatives must be useful. EXPRESSION_ONLY requires a MET requirementId whose actual evidence
        contains that segment, no necessary correction on that segment, and all applicable relations PRESERVED.
        Compare against the ORIGINAL learner text, not an imagined corrected answer. Do not add a personal
        choice, reason, experience, circumstance, quantity, date, agency or certainty. A small patch is not
        proof of equivalent meaning. HYPOTHETICAL_EXAMPLE is a clearly conditional teaching example tied to
        a requirement, not the learner's fact or a necessary addition. Preserve useful normal optional improvements;
        do not manufacture them or call pure spelling/number notation variants superior.
        learningText is a concise, complete Japanese explanation of the specific change, not the replacement
        itself. Uncertainties explain a genuinely ambiguous part, never disguise a clear error or absence.
        Keep quotations and explanations concise; do not repeat full answers. Evaluate every requirement once.
    """.trimIndent()

    fun schema(context: Context, requirements: List<Pair<String, String>>, segments: List<Segment>): JsonObject {
        // 기존에 동작한 object/enum/array만 쓴다. Provider pattern 실험을 반복하지 않는다.
        fun text(values: List<String>? = null) = buildJsonObject {
            put("type", "string"); values?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
        }
        fun obj(fields: Map<String, JsonElement>) = buildJsonObject {
            put("type", "object"); put("properties", JsonObject(fields)); put("additionalProperties", false)
            put("required", JsonArray(fields.keys.map(::JsonPrimitive)))
        }
        fun list(item: JsonElement) = buildJsonObject { put("type", "array"); put("items", item) }
        val ids = requirements.map { it.first }
        val segmentIds = segments.map { it.id }
        val factIds = taskFacts(context).map { it.first }
        val relations = obj(relationKeys.associateWith { text(relationStates.toList()) })
        val patch = obj(mapOf("original" to text(), "replacement" to text()))
        val part = obj(mapOf(
            "requirementQuote" to text(), "status" to text(partStates.toList()),
            "evidence" to list(obj(mapOf("segmentId" to text(segmentIds), "quote" to text()))),
            // 빈 enum은 지원되지 않는다. 사실이 없는 FREE의 ID 배열은 서버가 반드시 빈 배열로 검사한다.
            "taskFactIds" to list(text(if (factIds.isEmpty()) null else factIds)),
        ))
        return obj(mapOf(
            "observations" to list(obj(mapOf("requirementId" to text(ids), "requirementParts" to list(part), "relationChecks" to relations))),
            "necessaryCorrections" to list(obj(mapOf(
                "requirementId" to text(ids), "segmentId" to text(segmentIds), "patch" to patch,
                "issueType" to text(listOf("LANGUAGE_FORM", "TASK_FACT_CONFLICT")),
                "sourceFactId" to text(listOf("") + factIds),
                "formReason" to text(listOf("SPELLING", "INFLECTION", "SYNTAX", "NONE")), "learningText" to text(),
            ))),
            "optionalAlternatives" to list(obj(mapOf(
                "requirementId" to text(ids), "suggestionKind" to text(listOf("EXPRESSION_ONLY", "HYPOTHETICAL_EXAMPLE")),
                "segmentId" to text(segmentIds), "patch" to patch, "relationChecks" to relations, "learningText" to text(),
            ))),
            "uncertainties" to list(obj(mapOf("requirementId" to text(ids), "learningText" to text()))),
        ))
    }

    fun validate(output: JsonElement, context: Context, requirements: List<Pair<String, String>>, segments: List<Segment>): JsonObject {
        try {
            val root = output.jsonObject
            require(root.keys == setOf("observations", "necessaryCorrections", "optionalAlternatives", "uncertainties"))
            val known = segments.associateBy(Segment::id)
            val facts = taskFacts(context).toMap()
            val texts = requirements.toMap()
            val referencedFacts = mutableSetOf<String>()
            val originals = root.getValue("observations").jsonArray.map { it.jsonObject }

            // 전체 요구의 문자 coverage와 부분별 실제 근거를 먼저 검사한 뒤 요약 상태를 계산한다.
            val observations = originals.map { row ->
                require(row.keys == setOf("requirementId", "requirementParts", "relationChecks"))
                val id = row.string("requirementId")
                val parts = row.getValue("requirementParts").jsonArray.map { it.jsonObject }
                require(parts.isNotEmpty())
                require(parts.joinToString("") { it.string("requirementQuote") } == texts.getValue(id))
                parts.forEach { part ->
                    require(part.keys == setOf("requirementQuote", "status", "evidence", "taskFactIds"))
                    require(part.string("requirementQuote").isNotBlank() && part.string("status") in partStates)
                    val evidence = part.getValue("evidence").jsonArray
                    require((part.string("status") == "ABSENT") == evidence.isEmpty())
                    require(evidence.distinct().size == evidence.size)
                    evidence.forEach { raw ->
                        val span = raw.jsonObject
                        require(span.keys == setOf("segmentId", "quote"))
                        require(span.string("quote").isNotBlank() && known.getValue(span.string("segmentId")).text.contains(span.string("quote")))
                    }
                    val ids = part.getValue("taskFactIds").jsonArray.map { it.jsonPrimitive.content }
                    require(ids.distinct().size == ids.size && ids.all { it in facts })
                    referencedFacts += ids
                }
                val states = parts.map { it.string("status") }
                val relations = relations(row)
                require(("CONFLICT" in states) == relations.values.any { it.jsonPrimitive.content == "CONFLICT" })
                require(("UNCERTAIN" in states) == relations.values.any { it.jsonPrimitive.content == "UNCERTAIN" })
                val status = when {
                    states.any { it in setOf("ABSENT", "CONFLICT") } -> "UNMET"
                    "UNCERTAIN" in states -> "UNCERTAIN"
                    else -> "MET"
                }
                val spans = parts.flatMap { it.getValue("evidence").jsonArray }
                    .map { it.jsonObject.string("segmentId") }.distinct()
                val explanation = observationText(context, id, texts.getValue(id), parts, status)
                buildJsonObject {
                    put("requirementId", id); put("status", status)
                    put("evidenceKind", if (spans.isEmpty()) "MISSING_REQUIREMENT" else "LEARNER_TEXT")
                    put("segmentIds", JsonArray(spans.map(::JsonPrimitive)))
                    put("originText", explanation.first); put("learningText", explanation.second)
                }
            }
            require(referencedFacts == facts.keys)
            val byId = observations.associateBy { it.string("requirementId") }
            val partsById = originals.associate { it.string("requirementId") to it.getValue("requirementParts").jsonArray }

            // 원문 한 위치만 바꾸고 교정 권한과 중복 범위를 확인한다. 사후 삭제로 성공을 만들지 않는다.
            val corrections = root.getValue("necessaryCorrections").jsonArray.map { it.jsonObject }
            val occupied = mutableMapOf<String, MutableList<IntRange>>()
            val assembledCorrections = corrections.map { row ->
                require(row.keys == setOf("requirementId", "segmentId", "patch", "issueType", "sourceFactId", "formReason", "learningText"))
                val segment = known.getValue(row.string("segmentId"))
                val replacement = applyPatch(segment.text, row.getValue("patch").jsonObject)
                val patch = row.getValue("patch").jsonObject
                val start = segment.text.indexOf(patch.string("original"))
                val range = start until start + patch.string("original").length
                val ranges = occupied.getOrPut(segment.id) { mutableListOf() }
                require(ranges.none { it.first <= range.last && range.first <= it.last })
                ranges += range
                require(CuratedWritingFeedbackV3Policy.quantityNotationComparable(segment.text) !=
                    CuratedWritingFeedbackV3Policy.quantityNotationComparable(replacement))
                val id = row.string("requirementId")
                require(partsById.getValue(id).any { raw ->
                    raw.jsonObject.getValue("evidence").jsonArray.any {
                        val span = it.jsonObject
                        span.string("segmentId") == segment.id && span.string("quote").contains(patch.string("original"))
                    }
                })
                when (row.string("issueType")) {
                    "LANGUAGE_FORM" -> {
                        require(row.string("sourceFactId").isEmpty())
                        require(row.string("formReason") in setOf("SPELLING", "INFLECTION", "SYNTAX"))
                    }
                    "TASK_FACT_CONFLICT" -> {
                        val fact = row.string("sourceFactId")
                        require(fact in facts && row.string("formReason") == "NONE")
                        require(partsById.getValue(id).any { raw ->
                            val part = raw.jsonObject
                            part.string("status") == "CONFLICT" && part.getValue("taskFactIds").jsonArray.any { it.jsonPrimitive.content == fact } &&
                                part.getValue("evidence").jsonArray.any {
                                    it.jsonObject.string("segmentId") == segment.id &&
                                        it.jsonObject.string("quote").contains(patch.string("original"))
                                }
                        })
                    }
                    else -> error("WRITING_FEEDBACK_ISSUE_INVALID")
                }
                learningLanguage(row, replacement)
                JsonObject(row - setOf("patch", "issueType", "sourceFactId", "formReason") + mapOf(
                    "replacement" to JsonPrimitive(replacement), "originText" to JsonPrimitive(correctionText(row, facts)),
                ))
            }
            val alternatives = root.getValue("optionalAlternatives").jsonArray.map { it.jsonObject }
            val assembledAlternatives = alternatives.map { row ->
                require(row.keys == setOf("requirementId", "suggestionKind", "segmentId", "patch", "relationChecks", "learningText"))
                val observation = byId.getValue(row.string("requirementId"))
                val segment = known.getValue(row.string("segmentId"))
                val replacement = applyPatch(segment.text, row.getValue("patch").jsonObject)
                val states = relations(row).values.map { it.jsonPrimitive.content }
                if (row.string("suggestionKind") == "EXPRESSION_ONLY") {
                    require(observation.string("status") == "MET")
                    require(observation.getValue("segmentIds").jsonArray.any { it.jsonPrimitive.content == segment.id })
                    require(segment.id !in occupied && states.none { it == "CONFLICT" || it == "UNCERTAIN" })
                }
                learningLanguage(row, replacement)
                val patch = row.getValue("patch").jsonObject
                val label = if (row.string("suggestionKind") == "HYPOTHETICAL_EXAMPLE")
                    "실제 사실이나 필수 교정이 아닌 가상 예시입니다." else "충족한 요구에 대한 선택적 표현 제안입니다."
                JsonObject(row - setOf("requirementId", "patch", "relationChecks") + mapOf(
                    "replacement" to JsonPrimitive(replacement),
                    "originText" to JsonPrimitive("$label ${patchText(patch)} 일본어 설명에서 바꾸는 이유를 확인해 주세요."),
                ))
            }
            val uncertainties = root.getValue("uncertainties").jsonArray.map { raw ->
                val row = raw.jsonObject
                require(row.keys == setOf("requirementId", "learningText"))
                val parts = partsById.getValue(row.string("requirementId")).map { it.jsonObject }
                require(parts.any { it.string("status") == "UNCERTAIN" })
                learningLanguage(row)
                JsonObject(row + ("originText" to JsonPrimitive("의미가 불확실한 부분: " +
                    parts.filter { it.string("status") == "UNCERTAIN" }.joinToString(" / ") { it.string("requirementQuote") })))
            }

            // 기존 원문 복원·수량 근거·언어·요구 완전성 검사를 유지하고 새 근거를 같은 배열 순서로 보존한다.
            val checked = CuratedWritingFeedbackV3Policy.validate(buildJsonObject {
                put("observations", JsonArray(observations)); put("necessaryCorrections", JsonArray(assembledCorrections))
                put("optionalAlternatives", JsonArray(assembledAlternatives)); put("uncertainties", JsonArray(uncertainties))
            }, requirements, segments)
            return JsonObject(checked + mapOf(
                "policyVersion" to JsonPrimitive(version),
                "observations" to JsonArray(checked.getValue("observations").jsonArray.mapIndexed { index, raw ->
                    JsonObject(raw.jsonObject + originals[index].filterKeys { it in setOf("requirementParts", "relationChecks") })
                }),
                "necessaryCorrections" to JsonArray(checked.getValue("necessaryCorrections").jsonArray.mapIndexed { index, raw ->
                    JsonObject(raw.jsonObject + corrections[index].filterKeys { it in setOf("patch", "issueType", "sourceFactId", "formReason") })
                }),
                "optionalAlternatives" to JsonArray(checked.getValue("optionalAlternatives").jsonArray.mapIndexed { index, raw ->
                    JsonObject(raw.jsonObject + alternatives[index].filterKeys { it in setOf("patch", "requirementId", "relationChecks") })
                }),
            ))
        } catch (_: Exception) {
            throw IllegalArgumentException("WRITING_FEEDBACK_SCHEMA_INVALID")
        }
    }

    private fun applyPatch(original: String, patch: JsonObject): String {
        require(patch.keys == setOf("original", "replacement"))
        val from = patch.string("original")
        val to = patch.string("replacement")
        require(from.isNotBlank() && from != to && !hangul.containsMatchIn(to))
        val index = original.indexOf(from)
        require(index >= 0 && original.indexOf(from, index + 1) < 0)
        return original.replaceRange(index, index + from.length, to).also { require(it.isNotBlank() && it.length <= 4000) }
    }

    private fun relations(row: JsonObject): JsonObject = row.getValue("relationChecks").jsonObject.also {
        require(it.keys == relationKeys && it.values.all { state -> state.jsonPrimitive.content in relationStates })
    }

    private fun learningLanguage(row: JsonObject, replacement: String? = null) {
        val text = row.string("learningText")
        require(text.isNotBlank() && text.length <= 4000 && !hangul.containsMatchIn(text) && japanese.findAll(text).count() >= 3)
        require(replacement == null || text.trim() != replacement.trim())
    }

    private fun patchText(patch: JsonObject): String = if (patch.string("replacement").isEmpty())
        "「${patch.string("original")}」 부분을 삭제하는 제안입니다." else
        "「${patch.string("original")}」 부분을 「${patch.string("replacement")}」로 바꾸는 제안입니다."

    private fun correctionText(row: JsonObject, facts: Map<String, String>): String {
        val issue = if (row.string("issueType") == "TASK_FACT_CONFLICT")
            "과제에 명시된 사실과 충돌하는 부분입니다. 근거: ${facts.getValue(row.string("sourceFactId"))}"
        else when (row.string("formReason")) {
            "SPELLING" -> "철자 오류로 판단한 부분입니다."
            "INFLECTION" -> "활용 오류로 판단한 부분입니다."
            else -> "문장 구조 오류로 판단한 부분입니다."
        }
        return "$issue ${patchText(row.getValue("patch").jsonObject)} 일본어 설명에서 구체적인 이유를 확인해 주세요."
    }

    private fun observationText(context: Context, id: String, requirement: String, parts: List<JsonObject>, status: String): Pair<String, String> {
        val missing = parts.filter { it.string("status") == "ABSENT" }.joinToString(" / ") { it.string("requirementQuote") }
        val conflicts = parts.filter { it.string("status") == "CONFLICT" }.joinToString(" / ") { it.string("requirementQuote") }
        val supplement = when (context.writingType) {
            WritingType.TRANSLATION -> "번역 원문에 따라 빠진 내용을 보충해 주세요."
            WritingType.GUIDED -> "제공된 사실과 요구에 따라 빠진 내용을 보충해 주세요."
            WritingType.FREE -> if (taskFacts(context).isEmpty()) "자신의 실제 내용으로 보충해 주세요."
                else "명시된 사실은 과제에 맞게, 개인 선택은 자신의 실제 내용으로 보충해 주세요."
        }
        val korean = buildList {
            if (missing.isNotEmpty()) add("보충할 부분: $missing $supplement")
            if (conflicts.isNotEmpty()) add("실제 표현과 충돌한다고 판단한 부분: $conflicts")
            if (isEmpty()) add(if (status == "MET") "충족한 것으로 판단한 요구 사항: $requirement"
                else "대응이 불확실한 요구 사항: $requirement 해당 의미를 명확히 해 주세요.")
        }.joinToString(" ")
        val learning = when {
            missing.isNotEmpty() && conflicts.isNotEmpty() -> "要件 $id には不足している部分と表現が食い違う部分があります。部分別の根拠を確認してください。"
            missing.isNotEmpty() -> if (context.writingType == WritingType.FREE)
                "要件 $id に必要な内容がまだ示されていません。提示された事実は課題に沿って、個人の選択はご自身の実際の内容で補ってください。"
                else "要件 $id の不足部分を、課題の原文や提示された事実に沿って補ってください。"
            status == "MET" -> "引用した各部分は要件 $id を満たすと判断しました。"
            status == "UNCERTAIN" -> "要件 $id と対応する意味が不確かです。意図を明確にしてください。"
            else -> "要件 $id と実際の表現が食い違うと判断しました。部分別の根拠を確認してください。"
        }
        return korean to learning
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
