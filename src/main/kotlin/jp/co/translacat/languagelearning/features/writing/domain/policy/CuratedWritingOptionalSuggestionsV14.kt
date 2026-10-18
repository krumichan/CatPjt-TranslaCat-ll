package jp.co.translacat.languagelearning.features.writing.domain.policy

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** 엄격하게 검증된 필수 결과에 영향 없이, 별도 생성·독립 검사를 통과한 표현 제안만 제공한다. */
internal object CuratedWritingOptionalSuggestionsV14 {
    enum class Status { SUCCEEDED, FAILED, NOT_RUN_LIMIT, NOT_APPLICABLE }
    data class Result(val status: Status, val rows: JsonArray, val failureCode: String?)
    data class Target(val id: String, val requirementId: String, val segmentId: String, val original: String)
    data class TargetPlan(
        val plan: CuratedWritingFeedbackV13Policy.Plan,
        val verified: JsonObject,
        val coreResult: JsonObject,
        val sourceRevisionHash: String,
        val coreHash: String,
        val targets: List<Target>,
        val coreSegments: Map<String, String>,
    )
    private val checks = listOf("actorPreserved", "actionPreserved", "targetPreserved", "timePreserved",
        "negationPreserved", "conditionPreserved", "certaintyPreserved", "quantitiesPreserved",
        "noNewProposition", "grammarValid", "useful", "explanationAccurate")
    private val hangul = Regex("[\\u1100-\\u11ff\\u3130-\\u318f\\ua960-\\ua97f\\uac00-\\ud7ff\\uffa0-\\uffdc]")
    private const val neutralExplanation = "元の表現も使えます。意味を保ち、文を短くする別の言い方です。"
    private val punctuationOrSpace = Regex("[\\p{P}\\p{Z}\\s]")
    // 제한된 관계 표현은 왼쪽의 실제 문자 anchor와 묶어 그대로 보존한다. 일본어 전체 의미 분석기는 아니다.
    private val spatialPhrase = Regex("[\\p{L}\\p{N}々ー]+?(?:の(?:近く|付近|そば|周辺|内側|外側)|付近|沿い|に沿って)")

    fun planTargets(
        plan: CuratedWritingFeedbackV13Policy.Plan,
        verified: JsonObject,
        coreResult: JsonObject,
    ): TargetPlan {
        // 필수 coverage와 원래 의미 상태를 다시 검증한다. 단순 SUCCEEDED 표시만으로 optional을 시작하지 않는다.
        require(coreResult.getValue("optionalAlternatives").jsonArray.isEmpty())
        val strict = CuratedWritingFeedbackV14Policy.validateCoreResult(plan, verified, coreResult)
        listOf("observations", "necessaryCorrections", "uncertainties", "resultPolicy").forEach { field ->
            require(coreResult.getValue(field) == strict.getValue(field)) { "WRITING_OPTIONAL_CORE_CHANGED" }
        }
        val corrected = strict.getValue("necessaryCorrections").jsonArray.map { it.jsonObject }
        val correctedIds = corrected.map { it.getValue("segmentId").jsonPrimitive.content }.toSet()
        val segments = plan.segments.associate { segment ->
            val changes = corrected.filter { it.getValue("segmentId").jsonPrimitive.content == segment.id }
                .map { row ->
                    val patch = row.getValue("patch").jsonObject
                    Triple(segment.text.indexOf(patch.getValue("original").jsonPrimitive.content),
                        patch.getValue("original").jsonPrimitive.content,
                        patch.getValue("replacement").jsonPrimitive.content)
                }.sortedByDescending { it.first }
            var text = segment.text
            changes.forEach { (start, original, replacement) ->
                require(start >= 0)
                text = text.replaceRange(start, start + original.length, replacement)
            }
            segment.id to text
        }

        // 누락 내용을 채우는 교정은 optional 대상이 아니다. 이미 충족한 근거의 미수정 문장만 선택한다.
        val picked = mutableListOf<Target>()
        strict.getValue("observations").jsonArray.map { it.jsonObject }
            .filter { it.getValue("status").jsonPrimitive.content == "MET" }
            .forEach { observation ->
                observation.getValue("segmentIds").jsonArray.map { it.jsonPrimitive.content }.forEach { segmentId ->
                    val segment = plan.segments.single { it.id == segmentId }
                    if (segmentId !in correctedIds && picked.none { it.segmentId == segmentId } && picked.size < 2) {
                        picked += Target("OPT" + (picked.size + 1), observation.getValue("requirementId").jsonPrimitive.content,
                            segmentId, segment.text)
                    }
                }
            }
        val revision = CuratedWritingEvidenceV14.sourceRevisionHash(plan)
        val coreHash = digest(buildJsonObject { put("sourceRevisionHash", revision); put("core", strict) }.toString())
        return TargetPlan(plan, verified, strict, revision, coreHash, picked, segments)
    }

    fun proposalPrompt(targets: TargetPlan): String = basePrompt(targets).toString()

    val proposalInstructions = """
        Propose at most one genuinely useful EXPRESSION_ONLY alternative for each supplied fixed target.
        You may return proposals=[] when no useful rewording is needed. Each replacement replaces the
        ENTIRE original target segment and must preserve all actual learner meaning. Never fill an ABSENT
        requirement, invent an event, action request, reason, route, companion, date, amount or channel.
        Do not change actor, recipient, agency, object, time, negation, condition, certainty or factual
        specificity. A new provider request is not a rewording of the learner's own pickup plan. Task facts
        are assignment authority, not permission to claim the learner already expressed missing content.
        Only propose a STRICTLY SHORTER wording measured by Unicode code points, with a real wording
        change rather than deleting punctuation or spaces. Corrections and frozen core are read-only.
        Return only targetId and replacement, never teaching claims, learningText, original text, task
        authorities, scores or self-assessed PRESERVED flags. The server supplies a neutral explanation
        only after independent meaning checks pass. Echo sourceRevisionHash and coreHash exactly.
    """.trimIndent()

    fun proposalSchema(targets: TargetPlan): JsonObject {
        val branches = targets.targets.map { target ->
            obj(linkedMapOf("targetId" to text(listOf(target.id)), "replacement" to prose()))
        }
        return obj(linkedMapOf(
            "sourceRevisionHash" to text(listOf(targets.sourceRevisionHash)),
            "coreHash" to text(listOf(targets.coreHash)),
            "proposals" to arrayOfBranches(branches, targets.targets.size),
        ))
    }

    fun validateProposals(output: JsonElement, targets: TargetPlan): JsonObject {
        val root = output.jsonObject
        require(root.keys == setOf("sourceRevisionHash", "coreHash", "proposals"))
        require(root.string("sourceRevisionHash") == targets.sourceRevisionHash && root.string("coreHash") == targets.coreHash)
        val rows = root.getValue("proposals").jsonArray
        require(rows.size <= targets.targets.size)
        val targetIds = mutableSetOf<String>()
        rows.forEach { raw ->
            val row = raw.jsonObject
            require(row.keys == setOf("targetId", "replacement"))
            val target = requireNotNull(targets.targets.singleOrNull { it.id == row.string("targetId") }) {
                "WRITING_OPTIONAL_TARGET_UNKNOWN"
            }
            require(targetIds.add(target.id))
            require(row.string("replacement").isNotBlank() && row.string("replacement").length <= 4000)
            require(!hangul.containsMatchIn(row.string("replacement")))
        }
        // 같은 길이의 말 바꾸기나 문장부호 삭제는 이 기능의 이점이 아니다. checker 호출 전 제외한다.
        val eligible = rows.filter { raw ->
            val row = raw.jsonObject
            val target = requireNotNull(targets.targets.singleOrNull { it.id == row.string("targetId") })
            val replacement = row.string("replacement")
            replacement.codePointCount(0, replacement.length) < target.original.codePointCount(0, target.original.length) &&
                punctuationOrSpace.replace(replacement, "") != punctuationOrSpace.replace(target.original, "")
        }
        // 짧다는 이유로 위치·경로 관계를 바꾸지 않는다. 같은 관계 개수라도 anchor가 바뀌면 별개 표현이다.
        eligible.forEach { raw ->
            val row = raw.jsonObject
            val target = requireNotNull(targets.targets.singleOrNull { it.id == row.string("targetId") })
            require(spatialPhrases(target.original) == spatialPhrases(row.string("replacement"))) {
                "OPTIONAL_SPATIAL_RELATION_CHANGED"
            }
        }
        return JsonObject(root + ("proposals" to JsonArray(eligible)))
    }

    private fun spatialPhrases(text: String): Map<String, Int> =
        spatialPhrase.findAll(text).map { it.value }.groupingBy { it }.eachCount()

    fun verificationPrompt(targets: TargetPlan, proposals: JsonObject): String {
        val checked = validateProposals(proposals, targets)
        return JsonObject(basePrompt(targets) + mapOf(
            "proposalHash" to JsonPrimitive(digest(checked.toString())),
            "combinedPatchedContext" to JsonPrimitive(combinedContext(targets, checked)),
            "proposals" to JsonArray(checked.getValue("proposals").jsonArray.map { raw ->
                val row = raw.jsonObject
                val target = targets.targets.single { it.id == row.string("targetId") }
                buildJsonObject {
                    put("targetId", target.id)
                    put("requirementId", target.requirementId)
                    put("segmentId", target.segmentId)
                    put("original", target.original)
                    put("replacement", row.getValue("replacement"))
                    put("learningText", neutralExplanation)
                    put("fullPatchedContext", patchedContext(targets, target, row.string("replacement")))
                }
            }),
        )).toString()
    }

    val verificationInstructions = """
        Independently compare each proposed expression change with the ORIGINAL learner evidence and
        each fullPatchedContext AND combinedPatchedContext applying ALL proposals together. All checks
        must pass in both contexts. The generator has no authority to judge its own correctness. Check actor,
        recipient/agency and action, object, time, negation, conditions, certainty, quantity/specificity,
        no new proposition, whole-sentence grammar, actual usefulness and the SERVER neutral explanation
        separately. Only a shorter wording that retains all original meaning is useful here; do not
        approve a more general statement that drops a companion, condition or other meaningful detail.
        Return all supplied targetIds exactly once, with a boolean for EVERY check and a short reason.
        Set false for any missing/contradictory or ambiguous preservation. Adding a missing sunset,
        provider hold request, return-home event or email delivery channel is a NEW proposition even when
        a public task requests it. It cannot pass as EXPRESSION_ONLY. Natural paraphrases without such
        changes may pass. Read the entire patched sentence and surrounding context: changing a fragment
        must not leave duplicate particles, contradictory endings, broken conditions or a shifted actor.
        Do not change fixed task evidence, core corrections, statuses or learner original. Echo all hashes.
    """.trimIndent()

    fun verificationSchema(targets: TargetPlan, proposals: JsonObject): JsonObject {
        val checked = validateProposals(proposals, targets)
        val ids = checked.getValue("proposals").jsonArray.map { it.jsonObject.string("targetId") }
        val fields = linkedMapOf<String, JsonElement>("targetId" to if (ids.isEmpty()) prose() else text(ids))
        checks.forEach { fields[it] = buildJsonObject { put("type", "boolean") } }
        fields["reason"] = prose()
        return obj(linkedMapOf(
            "sourceRevisionHash" to text(listOf(targets.sourceRevisionHash)),
            "coreHash" to text(listOf(targets.coreHash)),
            "proposalHash" to text(listOf(digest(checked.toString()))),
            "decisions" to buildJsonObject {
                put("type", "array")
                put("minItems", ids.size)
                put("maxItems", ids.size)
                put("items", obj(fields))
            },
        ))
    }

    fun validateVerifiedSuggestions(
        output: JsonElement,
        targets: TargetPlan,
        proposals: JsonObject,
    ): Result {
        return try {
            // 독립 checker의 전수 대조를 확인한다. 단 하나의 실패도 사용자에게 오류 제안으로 노출하지 않는다.
            val checked = validateProposals(proposals, targets)
            val root = output.jsonObject
            require(root.keys == setOf("sourceRevisionHash", "coreHash", "proposalHash", "decisions"))
            require(root.string("sourceRevisionHash") == targets.sourceRevisionHash && root.string("coreHash") == targets.coreHash)
            require(root.string("proposalHash") == digest(checked.toString()))
            val proposed = checked.getValue("proposals").jsonArray.map { it.jsonObject }
            val decisions = root.getValue("decisions").jsonArray.map { it.jsonObject }
            require(decisions.map { it.string("targetId") }.toSet() == proposed.map { it.string("targetId") }.toSet())
            require(decisions.size == proposed.size && decisions.map { it.string("targetId") }.distinct().size == decisions.size)
            val passed = decisions.all { row ->
                require(row.keys == checks.toSet() + setOf("targetId", "reason"))
                require(row.string("reason").isNotBlank() && row.string("reason").length <= 1200)
                checks.map { name ->
                    val value = row.getValue(name).jsonPrimitive
                    require(!value.isString)
                    value.boolean
                }.all { it }
            }
            if (!passed) return Result(Status.FAILED, JsonArray(emptyList()), "OPTIONAL_SEMANTIC_REJECTED")
            val rows = JsonArray(proposed.map { row ->
                val target = targets.targets.single { it.id == row.string("targetId") }
                val observation = targets.coreResult.getValue("observations").jsonArray
                    .single { it.jsonObject.string("requirementId") == target.requirementId }.jsonObject
                buildJsonObject {
                    put("requirementId", target.requirementId)
                    put("suggestionKind", "EXPRESSION_ONLY")
                    put("segmentId", target.segmentId)
                    put("patch", buildJsonObject { put("original", target.original); put("replacement", row.getValue("replacement")) })
                    put("relationChecks", observation.getValue("relationChecks"))
                    put("learningText", neutralExplanation)
                }
            })

            // 기존 전체 검증과 필수 coverage를 다시 실행한다. optional은 필수 결과를 수정할 수 없다.
            val final = CuratedWritingFeedbackV14Policy.withOptionalResult(targets.plan, targets.verified,
                CuratedWritingFeedbackV14Policy.planRepairs(targets.plan, targets.verified), targets.coreResult,
                rows, Status.SUCCEEDED.name, null)
            listOf("observations", "necessaryCorrections", "uncertainties").forEach { field ->
                require(final.getValue(field) == targets.coreResult.getValue(field))
            }
            Result(Status.SUCCEEDED, final.getValue("optionalAlternatives").jsonArray, null)
        } catch (_: Exception) {
            Result(Status.FAILED, JsonArray(emptyList()), "OPTIONAL_VALIDATION_FAILED")
        }
    }

    private fun basePrompt(targets: TargetPlan): JsonObject = buildJsonObject {
        put("sourceRevisionHash", targets.sourceRevisionHash)
        put("coreHash", targets.coreHash)
        put("task", targets.plan.context.public)
        put("taskMeaningContract", targets.plan.contract.promptJson())
        put("frozenEvidence", targets.verified)
        put("strictCoreResult", targets.coreResult)
        put("originalLearnerContext", targets.plan.segments.joinToString("") { it.text })
        put("coreContext", targets.plan.segments.joinToString("") { targets.coreSegments.getValue(it.id) })
        put("targets", JsonArray(targets.targets.map { target -> buildJsonObject {
            put("targetId", target.id); put("requirementId", target.requirementId)
            put("segmentId", target.segmentId); put("original", target.original)
        } }))
    }

    private fun patchedContext(targets: TargetPlan, target: Target, replacement: String): String =
        targets.plan.segments.joinToString("") { segment ->
            if (segment.id == target.segmentId) replacement else targets.coreSegments.getValue(segment.id)
        }

    private fun combinedContext(targets: TargetPlan, checked: JsonObject): String {
        // 개별 제안은 맞아 보여도 함께 적용하면 모순될 수 있으므로 같은 독립 checker에 전체 조합을 제공한다.
        val replacements = checked.getValue("proposals").jsonArray.associate { raw ->
            val row = raw.jsonObject
            val target = requireNotNull(targets.targets.singleOrNull { it.id == row.string("targetId") })
            target.segmentId to row.string("replacement")
        }
        return targets.plan.segments.joinToString("") { replacements[it.id] ?: targets.coreSegments.getValue(it.id) }
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun text(values: List<String>) = buildJsonObject {
        put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive)))
    }
    private fun prose() = buildJsonObject { put("type", "string"); put("pattern", "\\S") }
    private fun arrayOfBranches(branches: List<JsonObject>, max: Int) = buildJsonObject {
        put("type", "array"); put("maxItems", max)
        put("items", if (branches.isEmpty()) obj(mapOf("targetId" to prose(), "replacement" to prose()))
            else buildJsonObject { put("anyOf", JsonArray(branches)) })
    }
    private fun obj(fields: Map<String, JsonElement>) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        put("properties", JsonObject(fields)); put("required", JsonArray(fields.keys.map(::JsonPrimitive)))
    }
}

