package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV3Policy.Segment
import kotlinx.serialization.json.*
import java.text.Normalizer

/** 요구의 의미 관계와 교정 권한을 분리한다. 원문·요구 설명은 저장된 snapshot으로 서버에서 복원한다. */
internal object CuratedWritingFeedbackV5Policy {
    const val version = "curated-writing-feedback-v5"
    private val relationKeys = setOf("causality", "chronology", "specificity", "stance")
    private val relationStates = setOf("PRESERVED", "CONFLICT", "NOT_APPLICABLE", "UNCERTAIN")
    private val hangul = Regex("[\\u1100-\\u11ff\\u3130-\\u318f\\ua960-\\ua97f\\uac00-\\ud7ff\\uffa0-\\uffdc]")
    private val japanese = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff]")
    private val kana = Regex("[\\u3040-\\u30ff]")
    private val quoted = Regex("「[^」]*」|『[^』]*』|‘[^’]*’|“[^”]*”|\"[^\"]*\"|'[^']*'")

    data class Context(
        val writingType: WritingType,
        val public: JsonObject,
        val originLanguage: String,
        val learningLanguage: String,
    ) {
        init {
            // 현재 큐레이션 catalog가 지원하는 언어쌍만 허용한다. UI의 현재 설정으로 바꾸지 않는다.
            require(originLanguage == "ko" && learningLanguage == "ja") { "WRITING_LANGUAGE_UNSUPPORTED" }
        }
    }

    fun segments(answer: String): List<Segment> = CuratedWritingFeedbackV3Policy.segments(answer)
    fun requirements(checklist: JsonArray): List<Pair<String, String>> = CuratedWritingFeedbackV3Policy.requirements(checklist)

    fun taskFacts(context: Context): List<Pair<String, String>> {
        // 번역 원문과 공개된 제공 사실만 교정의 사실 근거다. 개인 선택을 요구하는 checklist는 사실이 아니다.
        val provided = context.public["providedFacts"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        val facts = if (context.writingType == WritingType.TRANSLATION)
            listOf(context.public.getValue("originText").jsonPrimitive.content) + provided else provided
        return facts.filter(String::isNotBlank).distinct().mapIndexed { index, text -> "T${index + 1}" to text }
    }

    fun prompt(
        context: Context, reference: JsonObject, answer: String,
        requirements: List<Pair<String, String>>, segments: List<Segment>,
    ): String {
        val base = Json.parseToJsonElement(CuratedWritingFeedbackV3Policy.prompt(
            context.writingType, context.public, reference, answer, requirements, segments,
        )).jsonObject
        val task = base.getValue("task").jsonObject
        return JsonObject(base + ("task" to JsonObject(task + mapOf(
            "originLanguage" to JsonPrimitive(context.originLanguage),
            "learningLanguage" to JsonPrimitive(context.learningLanguage),
            "explicitTaskFacts" to JsonArray(taskFacts(context).map { (id, text) ->
                buildJsonObject { put("id", id); put("text", text) }
            }),
        )))).toString()
    }

    val instructions = """
        Give useful formative writing feedback only: no score, band, ability profile or weakness tag.
        Evaluate every task requirement exactly once. Follow task.originLanguage and task.learningLanguage.
        task.publicPromptAndConditions defines the assignment; task.explicitTaskFacts contains only supplied facts.
        learnerAnswer is the sole source of the learner's actual circumstances and choices. Reference examples
        are neither additional requirements nor learner facts. FREE has no reference example input.
        Observations are typed judgments, not paraphrases. The server supplies localized explanations and exact
        learner quotations. Select supplied segment IDs only. For each requirement check these relations:
        causality: preserve what causes what, including the scope of reasons and concessions;
        chronology: distinguish original plans, current announcements and changes, not merely matching numbers;
        specificity: preserve the stated level of detail, without narrowing a general object or circumstance;
        stance: preserve the learner's actual preference, belief and commitment, not a nearby different meaning.
        Use NOT_APPLICABLE only when that relation is irrelevant, PRESERVED when the expressed relation meets
        the requirement, CONFLICT for a clear mismatch, UNCERTAIN for genuinely ambiguous expressed meaning.
        MET requires all applicable relations PRESERVED. A CONFLICT requires UNMET. Without a conflict, an
        UNCERTAIN relation requires UNCERTAIN. Exact quotation alone does not establish meaning equivalence.
        Use MISSING_REQUIREMENT with UNMET, empty segmentIds and all relations NOT_APPLICABLE for absent content
        or an undecided personal choice. Use LEARNER_TEXT for expressed content, including contradictions.
        Never attach a fabricated quote or invented personal detail to a missing requirement.
        Independently inspect every segment for actual spelling, inflection and syntax errors even if the content
        is MET. Necessary corrections need issueType LANGUAGE_FORM or TASK_FACT_CONFLICT:
        LANGUAGE_FORM requires an actual spelling, inflection or syntax error, its formReason, and empty sourceFactId.
        Preserve all valid meaning and facts. Optional commas, punctuation, readability or style are not actual
        language errors. Accept grammatically possible duration modifiers without forcing punctuation.
        TASK_FACT_CONFLICT requires UNMET LEARNER_TEXT, formReason NONE and the exact T ID from explicitTaskFacts.
        Restore only the contradicted supplied fact. A requirement to choose or explain a personal opinion is
        not a supplied fact. In FREE, missing or different personal preference needs the learner's own choice;
        never rewrite that preference to satisfy a question. Missing requirements cannot have necessary corrections.
        Both correction types use a minimal, grammatical full-segment replacement and preserve other valid content.
        Explain the actual error and why that minimal repair helps; do not give only a generic correction label.
        For each optional alternative, compare replacement with its learner segment using the same four
        relationChecks, independently of requirement observations. Preserve cause and scope, temporal certainty,
        specificity and quantities, and who asks, accepts or commits to what. EXPRESSION_ONLY needs all applicable
        relations PRESERVED; CONFLICT or UNCERTAIN cannot be presented as a meaning-preserving alternative.
        HYPOTHETICAL_EXAMPLE is explicitly conditional teaching content, never the learner's fact or an obligation.
        Do not manufacture quantities/dates or claim pure notation variants are superior. Leave optional suggestions
        empty when none helps, while still detecting and correcting genuine errors.
        originText contains Korean explanatory prose; learningText contains Japanese explanatory prose.
        Japanese terms quoted inside Korean explanations must be delimited by 「」, 『』, ‘’, “”, or quotation marks.
        Keep explanatory sentences in their assigned language; one Korean character cannot license a Japanese
        explanation. Put Japanese replacements only in replacement and learner evidence only in segment IDs.
        learningText explains the change in Japanese; it must not merely duplicate replacement.
        Give specific useful explanations for corrections, optional changes and genuine uncertainties, but avoid
        duplicating entire answers or requirement summaries. Uncertainty must not disguise a clear contradiction.
    """.trimIndent()

    fun schema(context: Context, requirements: List<Pair<String, String>>, segments: List<Segment>): JsonObject {
        // 이미 동작하는 strict object/enum 구조만 사용한다. Provider가 거절한 pattern 제약은 넣지 않는다.
        fun text(values: List<String>? = null, description: String? = null) = buildJsonObject {
            put("type", "string")
            values?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
            description?.let { put("description", it) }
        }
        fun obj(fields: Map<String, JsonElement>) = buildJsonObject {
            put("type", "object"); put("properties", JsonObject(fields))
            put("required", JsonArray(fields.keys.map(::JsonPrimitive))); put("additionalProperties", false)
        }
        fun list(item: JsonElement) = buildJsonObject { put("type", "array"); put("items", item) }
        val ids = requirements.map { it.first }
        val segmentIds = segments.map { it.id }
        val prose = mapOf(
            "originText" to text(description = "Korean explanation. Delimit any Japanese quotation; surrounding prose stays Korean."),
            "learningText" to text(description = "Japanese explanation only; no Korean sentences or particles."),
        )
        val observation = obj(mapOf(
            "requirementId" to text(ids), "status" to text(listOf("MET", "UNMET", "UNCERTAIN")),
            "evidenceKind" to text(listOf("LEARNER_TEXT", "MISSING_REQUIREMENT")),
            "segmentIds" to list(text(segmentIds)),
            "relationChecks" to obj(relationKeys.associateWith { text(relationStates.toList()) }),
        ))
        val correction = obj(mapOf(
            "requirementId" to text(ids), "segmentId" to text(segmentIds), "replacement" to text(),
            "issueType" to text(listOf("LANGUAGE_FORM", "TASK_FACT_CONFLICT")),
            "sourceFactId" to text(listOf("") + taskFacts(context).map { it.first }),
            "formReason" to text(listOf("SPELLING", "INFLECTION", "SYNTAX", "NONE")),
        ) + prose)
        val optional = obj(mapOf(
            "suggestionKind" to text(listOf("EXPRESSION_ONLY", "HYPOTHETICAL_EXAMPLE")),
            "segmentId" to text(segmentIds), "replacement" to text(),
            "relationChecks" to obj(relationKeys.associateWith { text(relationStates.toList()) }),
        ) + prose)
        return obj(mapOf(
            "observations" to list(observation), "necessaryCorrections" to list(correction),
            "optionalAlternatives" to list(optional),
            "uncertainties" to list(obj(mapOf("requirementId" to text(ids)) + prose)),
        ))
    }

    fun validate(
        output: JsonElement, context: Context, requirements: List<Pair<String, String>>, segments: List<Segment>,
    ): JsonObject {
        try {
            val root = output.jsonObject
            val requirementText = requirements.toMap()
            val known = segments.associateBy(Segment::id)
            val observationRows = root.getValue("observations").jsonArray.map { it.jsonObject }
            val relationsById = mutableMapOf<String, JsonObject>()
            // 모델의 관계 판단은 저장해 수용 검토에 노출한다. 서버 템플릿으로 잘못된 MET을 숨기지 않는다.
            val observations = observationRows.map { row ->
                require(row.keys == setOf("requirementId", "status", "evidenceKind", "segmentIds", "relationChecks"))
                val id = row.string("requirementId")
                val status = row.string("status")
                val relations = row.getValue("relationChecks").jsonObject
                require(relations.keys == relationKeys)
                val states = relations.values.map { it.jsonPrimitive.content }
                require(states.all { it in relationStates })
                require(status != "MET" || states.none { it == "CONFLICT" || it == "UNCERTAIN" })
                require("CONFLICT" !in states || status == "UNMET")
                require("UNCERTAIN" !in states || "CONFLICT" in states || status == "UNCERTAIN")
                if (row.string("evidenceKind") == "MISSING_REQUIREMENT") require(states.all { it == "NOT_APPLICABLE" })
                relationsById[id] = relations
                val messages = observationText(context, id, requirementText.getValue(id), status, row.string("evidenceKind"), relations)
                JsonObject(row - "relationChecks" + mapOf(
                    "originText" to JsonPrimitive(messages.first), "learningText" to JsonPrimitive(messages.second),
                ))
            }

            // 필수 교정은 실제 형태 오류 또는 명시 사실 충돌의 권한을 확인한다. FREE 개인 의견은 사실 ID가 없다.
            val corrections = root.getValue("necessaryCorrections").jsonArray.map { it.jsonObject }
            val factIds = taskFacts(context).map { it.first }.toSet()
            corrections.forEach { row ->
                require(row.keys == setOf("requirementId", "segmentId", "replacement", "originText", "learningText",
                    "issueType", "sourceFactId", "formReason"))
                val observation = observationRows.single { it.string("requirementId") == row.string("requirementId") }
                val original = known.getValue(row.string("segmentId")).text
                require(lettersAndNumbers(original) != lettersAndNumbers(row.string("replacement")))
                when (row.string("issueType")) {
                    "LANGUAGE_FORM" -> {
                        require(row.string("sourceFactId").isEmpty())
                        require(row.string("formReason") in setOf("SPELLING", "INFLECTION", "SYNTAX"))
                    }
                    "TASK_FACT_CONFLICT" -> {
                        require(row.string("sourceFactId") in factIds && row.string("formReason") == "NONE")
                        require(observation.string("status") == "UNMET" &&
                            observation.getValue("relationChecks").jsonObject.values.any { it.jsonPrimitive.content == "CONFLICT" })
                    }
                    else -> error("WRITING_FEEDBACK_ISSUE_INVALID")
                }
                explanatoryLanguage(row)
            }
            // 선택 개선도 원문과의 관계를 판정해야 한다. 잘못된 대안을 제거해 성공으로 바꾸지 않는다.
            val alternatives = root.getValue("optionalAlternatives").jsonArray.map { it.jsonObject }
            alternatives.forEach { row ->
                require(row.keys == setOf("suggestionKind", "segmentId", "replacement", "originText", "learningText", "relationChecks"))
                val relations = row.getValue("relationChecks").jsonObject
                require(relations.keys == relationKeys)
                val states = relations.values.map { it.jsonPrimitive.content }
                require(states.all { it in relationStates })
                if (row.string("suggestionKind") == "EXPRESSION_ONLY") {
                    require(states.none { it == "CONFLICT" || it == "UNCERTAIN" })
                }
                explanatoryLanguage(row)
                require(row.string("learningText").trim() != row.string("replacement").trim())
            }
            root.getValue("uncertainties").jsonArray.forEach { explanatoryLanguage(it.jsonObject) }

            // v3의 원문 복원·요구 완전성·수량 근거 검사를 동일하게 재사용하며 새 typed 필드만 별도로 보존한다.
            val legacyShape = JsonObject(root + mapOf(
                "observations" to JsonArray(observations),
                "necessaryCorrections" to JsonArray(corrections.map { JsonObject(it - setOf("issueType", "sourceFactId", "formReason")) }),
                "optionalAlternatives" to JsonArray(alternatives.map { JsonObject(it - "relationChecks") }),
            ))
            val checked = CuratedWritingFeedbackV3Policy.validate(legacyShape, requirements, segments)
            return JsonObject(checked + mapOf(
                "policyVersion" to JsonPrimitive(version),
                "observations" to JsonArray(checked.getValue("observations").jsonArray.map { raw ->
                    JsonObject(raw.jsonObject + ("relationChecks" to relationsById.getValue(raw.jsonObject.string("requirementId"))))
                }),
                "necessaryCorrections" to JsonArray(checked.getValue("necessaryCorrections").jsonArray.mapIndexed { index, raw ->
                    JsonObject(raw.jsonObject + corrections[index].filterKeys { it in setOf("issueType", "sourceFactId", "formReason") })
                }),
                "optionalAlternatives" to JsonArray(checked.getValue("optionalAlternatives").jsonArray.mapIndexed { index, raw ->
                    JsonObject(raw.jsonObject + ("relationChecks" to alternatives[index].getValue("relationChecks")))
                }),
            ))
        } catch (_: Exception) {
            throw IllegalArgumentException("WRITING_FEEDBACK_SCHEMA_INVALID")
        }
    }

    private fun observationText(
        context: Context, id: String, requirement: String, status: String, evidence: String, relations: JsonObject,
    ): Pair<String, String> {
        val labels = mapOf("causality" to ("인과 관계" to "因果関係"), "chronology" to ("시간·변경 관계" to "時点・変更の関係"),
            "specificity" to ("사실의 범위" to "事実の範囲"), "stance" to ("의견·의도" to "意見・意図"))
        val conflicts = relations.filterValues { it.jsonPrimitive.content == "CONFLICT" }.keys.map { labels.getValue(it) }
        // 누락된 번역 원문·제공 사실을 학습자의 개인 선택으로 바꾸지 않는다.
        val supplement = when (context.writingType) {
            WritingType.TRANSLATION -> "번역 원문에서 빠진 내용을 확인해 보충해 주세요." to "翻訳元の文で抜けている内容を確認して補ってください。"
            WritingType.GUIDED -> "제공된 사실과 요구에 맞게 빠진 내용을 보충해 주세요." to "提示された事実と要件に沿って、抜けている内容を補ってください。"
            WritingType.FREE -> if (taskFacts(context).isEmpty())
                "자신의 실제 내용으로 덧붙여 주세요." to "ご自身の実際の内容を補ってください。"
            else "주어진 사실은 과제에 맞게, 개인 선택은 자신의 실제 내용으로 보충해 주세요." to
                "提示された事実は課題に沿って、個人の選択はご自身の実際の内容で補ってください。"
        }
        return when {
            evidence == "MISSING_REQUIREMENT" ->
                "보충할 요구 사항: $requirement ${supplement.first}" to
                    "要件 $id に必要な内容がまだ示されていません。${supplement.second}"
            status == "MET" -> "충족한 것으로 판단한 요구 사항: $requirement" to "引用箇所の内容は要件 $id を満たすと判断しました。"
            status == "UNCERTAIN" -> "대응이 불확실한 요구 사항: $requirement 해당 의미가 드러나도록 명확히 해 주세요." to
                "要件 $id との対応が不明確です。意図した意味が伝わるように明確にしてください。"
            conflicts.isNotEmpty() -> "요구 사항과 ${conflicts.joinToString("·") { it.first }}가 맞지 않습니다: $requirement 해당 관계를 확인해 주세요." to
                "要件 $id と${conflicts.joinToString("・") { it.second }}が一致していません。該当する関係を確認してください。"
            else -> "아직 충족하지 않은 요구 사항: $requirement 답안의 내용을 확인해 보충해 주세요." to
                "要件 $id をまだ満たしていません。回答の内容を確認して補ってください。"
        }
    }

    private fun explanatoryLanguage(row: JsonObject) {
        val origin = row.string("originText")
        val learning = row.string("learningText")
        // 인용 안의 일본어는 정상이다. 인용 밖 설명의 각 문장이 한국어를 주언어로 사용하는지 확인한다.
        val explanation = quoted.replace(origin, " ")
        require(!kana.containsMatchIn(explanation))
        val clauses = explanation.split(Regex("[。！？!?\n]|(?<![0-9])\\.(?![0-9])")).filter(String::isNotBlank)
        require(clauses.isNotEmpty())
        clauses.forEach { clause ->
            val koreanCount = hangul.findAll(clause).count()
            val japaneseCount = japanese.findAll(clause).count()
            require(koreanCount >= 3 && koreanCount >= japaneseCount * 2)
        }
        require(!hangul.containsMatchIn(learning) && japanese.findAll(learning).count() >= 3)
    }

    private fun lettersAndNumbers(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .filter { it.isLetterOrDigit() }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
