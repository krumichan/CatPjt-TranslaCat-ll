package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*

/** 필수 수리의 전체 적용 문맥은 선택 제안과 별도로 검사하며 실패를 선택 제안 실패로 숨기지 않는다. */
internal object CuratedWritingCoreVerificationV14 {
    data class CheckPlan(
        val plan: CuratedWritingFeedbackV13Policy.Plan,
        val verified: JsonObject,
        val repairs: CuratedWritingRepairPlan.Plan,
        val core: JsonObject,
        val sourceRevisionHash: String,
        val coreHash: String,
        val patchedSegments: Map<String, String>,
    )

    private val taskChecks = listOf("targetRepaired", "grammarValid", "unaffectedMeaningPreserved",
        "noUnlicensedProposition", "explanationAccurate")
    private val contextChecks = listOf("grammarValid", "unaffectedFactsPreserved", "conditionsPreserved", "noUnlicensedProposition")

    fun prepare(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                repairs: CuratedWritingRepairPlan.Plan, core: JsonObject): CheckPlan {
        // 원문·고정 수리 계획·전체 정적 검사를 먼저 확인한다. SUCCEEDED라는 표시만 신뢰하지 않는다.
        CuratedWritingRepairPlan.requireBound(plan, verified, repairs)
        val checked = CuratedWritingFeedbackV14Policy.validateCoreResult(plan, verified, core)
        val corrections = checked.getValue("necessaryCorrections").jsonArray.map { it.jsonObject }
        require(corrections.isNotEmpty()) { "WRITING_FEEDBACK_CORE_VERIFICATION_NOT_APPLICABLE" }
        require(checked.getValue("optionalAlternatives").jsonArray.isEmpty()) { "WRITING_FEEDBACK_CORE_VERIFICATION_INVALID" }
        require(checked.getValue("repairPlanHash").jsonPrimitive.content == repairs.hash)
        val patched = plan.segments.associate { segment ->
            val changes = corrections.filter { it.getValue("segmentId").jsonPrimitive.content == segment.id }.map { row ->
                val patch = row.getValue("patch").jsonObject
                val original = patch.getValue("original").jsonPrimitive.content
                val start = segment.text.indexOf(original)
                require(start >= 0 && segment.text.lastIndexOf(original) == start)
                Triple(start, original, patch.getValue("replacement").jsonPrimitive.content)
            }.sortedByDescending { it.first }
            var text = segment.text
            changes.forEach { (start, original, replacement) -> text = text.replaceRange(start, start + original.length, replacement) }
            segment.id to text
        }
        val revision = CuratedWritingEvidenceV14.sourceRevisionHash(plan)
        val hash = CuratedWritingRepairPlan.digest(buildJsonObject {
            put("sourceRevisionHash", revision); put("repairPlanHash", repairs.hash); put("core", checked)
        }.toString())
        return CheckPlan(plan, verified, repairs, checked, revision, hash, patched)
    }

    val instructions = """
        Independently verify the supplied mandatory repairs, not the original answer's fulfilment grade.
        Read ORIGINAL learner text, fixed server repair tasks/authorities, all applied patches, every full
        patched segment and the entire fullPatchedContext. Check every fixed R ID exactly once.
        targetRepaired means the specified task target is actually fixed in the FULL resulting sentence.
        grammarValid must be false for a broken ending, duplicate suffix/particle or other grammar error
        remaining in that result, even if the local replacement by itself looks correct. For example,
        changing a selected fragment must not leave an extra original inflection character outside it.
        An authority-licensed fact correction may intentionally change the original conflicting claim:
        changing a pot left at the library into taking it home is allowed when that is the fixed R target.
        noUnlicensedProposition forbids additions OUTSIDE those fixed repair targets, such as inventing a
        new companion, route, time, cause or action. It does not forbid the assigned correction itself.
        Preserve all unaffected meaning, facts, negation, conditions and certainty. Check the Japanese
        learningText against the ACTUAL whole result; do not accept a claim of a correct past form when
        the displayed result remains malformed. The originalObservations describe ORIGINAL text and
        must remain UNMET/CONFLICT where originally appropriate; that is not proof a repair failed.
        For wholeContext check grammar and preservation after ALL mandatory patches together. A failure
        invalidates the mandatory core before optional suggestions; never treat it as optional advice.
        Return every boolean explicitly and a short nonblank reason. Echo all three hashes exactly.
        No scores, new requirements, replacements, or revisions to the frozen evidence are allowed.
    """.trimIndent()

    fun prompt(checkPlan: CheckPlan): String = buildJsonObject {
        put("sourceRevisionHash", checkPlan.sourceRevisionHash)
        put("repairPlanHash", checkPlan.repairs.hash); put("coreHash", checkPlan.coreHash)
        put("task", checkPlan.plan.context.public); put("taskMeaningContract", checkPlan.plan.contract.promptJson())
        put("strictCoreResult", checkPlan.core)
        put("frozenEvidence", checkPlan.verified); put("originalObservations", checkPlan.core.getValue("observations"))
        put("originalLearnerContext", checkPlan.plan.segments.joinToString("") { it.text })
        put("fullPatchedContext", checkPlan.plan.segments.joinToString("") { checkPlan.patchedSegments.getValue(it.id) })
        put("learnerSegments", JsonArray(checkPlan.plan.segments.map { segment -> buildJsonObject {
            put("segmentId", segment.id); put("original", segment.text)
            put("fullPatchedSegment", checkPlan.patchedSegments.getValue(segment.id))
        } }))
        put("necessaryCorrections", checkPlan.core.getValue("necessaryCorrections"))
        put("repairTasks", JsonArray(checkPlan.repairs.tasks.map { task -> JsonObject(
            CuratedWritingRepairPlan.taskJson(task) + ("fullPatchedSegment" to JsonPrimitive(checkPlan.patchedSegments.getValue(task.segmentId))))
        }))
    }.toString()

    fun schema(checkPlan: CheckPlan): JsonObject = obj(linkedMapOf(
        "sourceRevisionHash" to text(listOf(checkPlan.sourceRevisionHash)),
        "repairPlanHash" to text(listOf(checkPlan.repairs.hash)), "coreHash" to text(listOf(checkPlan.coreHash)),
        "tasks" to obj(checkPlan.repairs.tasks.associate { it.id to checks(taskChecks) }),
        "wholeContext" to checks(contextChecks),
    ))

    fun validate(output: JsonElement, checkPlan: CheckPlan): JsonObject {
        try {
            // 검사 결과도 현재 서버 문맥에 묶인다. 누락된 R이나 잘못된 boolean을 성공으로 보정하지 않는다.
            val current = prepare(checkPlan.plan, checkPlan.verified, checkPlan.repairs, checkPlan.core)
            require(current.sourceRevisionHash == checkPlan.sourceRevisionHash && current.coreHash == checkPlan.coreHash &&
                current.patchedSegments == checkPlan.patchedSegments)
            val root = output.jsonObject
            require(root.keys == setOf("sourceRevisionHash", "repairPlanHash", "coreHash", "tasks", "wholeContext"))
            require(root.getValue("sourceRevisionHash").jsonPrimitive.content == current.sourceRevisionHash &&
                root.getValue("repairPlanHash").jsonPrimitive.content == current.repairs.hash &&
                root.getValue("coreHash").jsonPrimitive.content == current.coreHash)
            val tasks = root.getValue("tasks").jsonObject
            require(tasks.keys == current.repairs.tasks.map { it.id }.toSet())
            val decisions = tasks.values.map { passed(it.jsonObject, taskChecks) } + passed(root.getValue("wholeContext").jsonObject, contextChecks)
            require(decisions.all { it }) { "WRITING_FEEDBACK_CORE_VERIFICATION_REJECTED" }
            return root
        } catch (failure: Exception) {
            if (failure is IllegalArgumentException && failure.message == "WRITING_FEEDBACK_CORE_VERIFICATION_REJECTED") throw failure
            throw IllegalArgumentException("WRITING_FEEDBACK_CORE_VERIFICATION_INVALID", failure)
        }
    }

    private fun passed(row: JsonObject, names: List<String>): Boolean {
        require(row.keys == names.toSet() + "reason")
        val reason = row.getValue("reason").jsonPrimitive.content
        require(reason.isNotBlank() && reason.length <= 1200)
        return names.map { name ->
            val value = row.getValue(name).jsonPrimitive
            require(!value.isString)
            value.boolean
        }.all { it }
    }

    private fun checks(names: List<String>): JsonObject = obj(linkedMapOf<String, JsonElement>().apply {
        names.forEach { put(it, buildJsonObject { put("type", "boolean") }) }
        put("reason", text())
    })
    private fun text(values: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string"); put("pattern", "\\S")
        values?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
    }
    private fun obj(fields: Map<String, JsonElement>): JsonObject = buildJsonObject {
        put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(fields))
        put("required", JsonArray(fields.keys.map(::JsonPrimitive)))
    }
}
