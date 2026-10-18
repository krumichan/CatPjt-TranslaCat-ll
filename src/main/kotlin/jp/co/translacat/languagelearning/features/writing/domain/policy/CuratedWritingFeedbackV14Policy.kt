package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*

/** 서버 수리 작업을 먼저 고정하고, 검증된 핵심 결과와 선택 제안을 별도로 다룬다. */
internal object CuratedWritingFeedbackV14Policy {
    const val version = "curated-writing-feedback-v14"
    const val repairPlanVersion = CuratedWritingRepairPlan.version

    data class CoreProgress(
        val repairPlanHash: String,
        val completedResult: JsonObject?,
        val frozenOutput: JsonObject,
        val unresolvedTaskIds: List<String>,
        val issues: List<String>,
    )

    fun prepare(context: CuratedWritingFeedbackPolicy.Context, requirements: List<Pair<String, String>>,
                segments: List<CuratedWritingFeedbackV3Policy.Segment>) =
        CuratedWritingFeedbackV13Policy.prepare(context, requirements, segments)

    fun extractionPrompt(plan: CuratedWritingFeedbackV13Policy.Plan, answer: String) = CuratedWritingFeedbackV13Policy.extractionPrompt(plan, answer)
    val extractionInstructions = CuratedWritingFeedbackV13Policy.extractionInstructions
    fun extractionSchema(plan: CuratedWritingFeedbackV13Policy.Plan) = CuratedWritingFeedbackV13Policy.extractionSchema(plan)
    fun validateExtraction(output: JsonElement, plan: CuratedWritingFeedbackV13Policy.Plan) = CuratedWritingFeedbackV13Policy.validateExtraction(output, plan)

    fun planRepairs(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject): CuratedWritingRepairPlan.Plan =
        CuratedWritingRepairPlan.create(plan, verified)

    val coreInstructions = """
        Repair ONLY the supplied fixed server repair tasks. Return the exact repairPlanHash and one value
        for EACH supplied R ID, with fullPatchedSegment and a concise Japanese learningText. The server owns the
        original, source range, requirement coverage, issue type and T/P authority. Never return those
        fields, another task, another source, or an optional alternative. Return the COMPLETE originalSegment
        after replacing ONLY its fixed start..end span. Copy every character outside that range exactly,
        including punctuation and endings. Check the full resulting Japanese sentence and its connections.
        A task may cover several requirements or equivalent T/P authorities: it is ONE physical correction,
        not one correction per coverage entry. Do not invent missing learner events. Explain alignment
        with the supplied assignment, not proof of the learner's real life. All explanations must be in
        Japanese, including quoted text; refer to Korean task material by authority ID or Japanese meaning.
        There are no scores, new observations, proficiency judgments, or additional repair attempts.
    """.trimIndent()

    fun coreSchema(repairs: CuratedWritingRepairPlan.Plan): JsonObject = schema(repairs, repairs.modelTaskIds)

    fun corePrompt(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                   repairs: CuratedWritingRepairPlan.Plan): String {
        CuratedWritingRepairPlan.requireBound(plan, verified, repairs)
        return prompt(plan, verified, repairs, repairs.modelTaskIds)
    }

    fun serverCoreIfComplete(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                             repairs: CuratedWritingRepairPlan.Plan): JsonObject? {
        CuratedWritingRepairPlan.requireBound(plan, verified, repairs)
        val values = deterministicValues(repairs)
        require(repairs.tasks.filter { it.deterministicReplacement != null }.all { task ->
            validTask(plan, verified, task, values.getValue(task.id))
        }) { "WRITING_FEEDBACK_DETERMINISTIC_REPAIR_INVALID" }
        if (repairs.modelTaskIds.isNotEmpty()) return null

        // 필수 수리가 없거나 알려진 값의 결정적 교정만 있으면 모델을 호출하지 않고 전체 검증한다.
        return assembleValues(plan, verified, repairs, values)
    }

    fun assembleCore(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                     repairs: CuratedWritingRepairPlan.Plan, output: JsonElement): JsonObject {
        CuratedWritingRepairPlan.requireBound(plan, verified, repairs)
        val values = deterministicValues(repairs) + parse(output, repairs, repairs.modelTaskIds)
        return assembleValues(plan, verified, repairs, values)
    }

    fun salvageCore(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                    repairs: CuratedWritingRepairPlan.Plan, output: JsonElement): CoreProgress {
        CuratedWritingRepairPlan.requireBound(plan, verified, repairs)
        val proposed = parse(output, repairs, repairs.modelTaskIds)
        val frozen = deterministicValues(repairs).toMutableMap()
        val issues = mutableListOf<String>()

        // 작업별 독립 검증에 실패한 replacement만 남긴다. 정상 작업의 문자열은 그대로 보존한다.
        repairs.tasks.forEach { task ->
            val value = proposed[task.id] ?: frozen[task.id] ?: return@forEach
            if (validTask(plan, verified, task, value)) frozen[task.id] = value
            else {
                frozen.remove(task.id)
                issues += "${task.id}: replacement failed original source/authority/form/fact checks"
            }
        }
        val unresolved = repairs.tasks.filter { it.id !in frozen }.map { it.id }
        require(unresolved.none { repairs.task(it).deterministicReplacement != null }) {
            "WRITING_FEEDBACK_DETERMINISTIC_REPAIR_INVALID"
        }
        val complete = if (unresolved.isEmpty()) {
            // 정상 작업만으로 기존 전체 검사와 모든 coverage가 통과하면 복구 호출 자체가 필요 없다.
            assembleValues(plan, verified, repairs, frozen)
        } else null
        return CoreProgress(repairs.hash, complete, output(repairs, frozen), unresolved, issues)
    }

    fun repairRemainingSchema(repairs: CuratedWritingRepairPlan.Plan, progress: CoreProgress): JsonObject {
        requireProgress(repairs, progress)
        require(progress.unresolvedTaskIds.isNotEmpty()) { "WRITING_FEEDBACK_CORE_ALREADY_COMPLETE" }
        return schema(repairs, progress.unresolvedTaskIds)
    }

    fun repairRemainingPrompt(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                              repairs: CuratedWritingRepairPlan.Plan, progress: CoreProgress): String {
        CuratedWritingRepairPlan.requireBound(plan, verified, repairs)
        requireProgress(repairs, progress)
        require(progress.unresolvedTaskIds.isNotEmpty()) { "WRITING_FEEDBACK_CORE_ALREADY_COMPLETE" }
        val body = Json.parseToJsonElement(prompt(plan, verified, repairs, progress.unresolvedTaskIds)).jsonObject
        return JsonObject(body + mapOf("frozenRepairs" to progress.frozenOutput.getValue("repairs"),
            "issues" to JsonArray(progress.issues.map(::JsonPrimitive)))).toString()
    }

    val repairRemainingInstructions = coreInstructions + "\n\n" + """
        This is the single allowed core repair. Return ONLY the unresolved R IDs supplied in repairTasks.
        frozenRepairs are already valid and cover their listed requirements. Do not repeat, replace or
        expand a frozen repair, even under an equivalent T/P authority or a second requirement ID.
    """.trimIndent()

    fun assembleRepairedCore(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                             repairs: CuratedWritingRepairPlan.Plan, progress: CoreProgress,
                             output: JsonElement): JsonObject {
        CuratedWritingRepairPlan.requireBound(plan, verified, repairs)
        requireProgress(repairs, progress)
        val frozen = progress.frozenOutput.getValue("repairs").jsonObject
        val additions = parse(output, repairs, progress.unresolvedTaskIds)
        val merged = frozen + additions
        require(merged.keys == repairs.tasks.map { it.id }.toSet()) { "WRITING_FEEDBACK_CORE_REPAIR_INCOMPLETE" }
        return assembleValues(plan, verified, repairs, merged)
    }

    fun withOptionalResult(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                           repairs: CuratedWritingRepairPlan.Plan, core: JsonObject, rows: JsonArray,
                           status: String, code: String?): JsonObject {
        CuratedWritingRepairPlan.requireBound(plan, verified, repairs)
        require(core.string("repairPlanHash") == repairs.hash && core.string("policyVersion") == version)
        require(status in setOf("SUCCEEDED", "FAILED", "NOT_RUN_LIMIT", "NOT_APPLICABLE"))
        val needsCode = status == "FAILED" || status == "NOT_RUN_LIMIT"
        require(if (needsCode) code != null && Regex("[A-Z][A-Z0-9_]{0,79}").matches(code) else code == null) {
            "WRITING_FEEDBACK_OPTIONAL_STATUS_INVALID"
        }
        require(status == "SUCCEEDED" || rows.isEmpty()) { "WRITING_FEEDBACK_OPTIONAL_STATUS_INVALID" }

        // 선택 단계도 동일한 불변 핵심과 범위 proof를 검사한다. 합성 범위를 옛 인용으로 위장하지 않는다.
        validateCoreResult(plan, verified, core)
        val content = rawContent(core, rows)
        val checked = validateContent(plan, verified, repairs, content)
        val decorated = decorate(checked, repairs, status, code)
        require(decorated.getValue("observations") == core.getValue("observations") &&
            decorated.getValue("necessaryCorrections") == core.getValue("necessaryCorrections"))
        return decorated
    }

    fun validateCoreResult(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                           coreResult: JsonObject): JsonObject {
        val repairs = planRepairs(plan, verified)
        CuratedWritingRepairPlan.requireBound(plan, verified, repairs)
        require(coreResult.string("repairPlanHash") == repairs.hash &&
            coreResult.string("repairPlanVersion") == repairPlanVersion && coreResult.string("policyVersion") == version) {
            "WRITING_FEEDBACK_REPAIR_REVISION_MISMATCH"
        }
        val status = coreResult.string("optionalSuggestionsStatus")
        val code = coreResult.getValue("optionalSuggestionsFailureCode").jsonPrimitive.contentOrNull
        require(status in setOf("SUCCEEDED", "FAILED", "NOT_RUN_LIMIT", "NOT_APPLICABLE"))
        require(if (status in setOf("FAILED", "NOT_RUN_LIMIT"))
            code != null && Regex("[A-Z][A-Z0-9_]{0,79}").matches(code) else code == null)
        val optional = coreResult.getValue("optionalAlternatives").jsonArray
        require(status == "SUCCEEDED" || optional.isEmpty())

        // 저장 DTO의 추가 설명·원문 복원 결과까지 재조립해 외부에서 바꾼 핵심을 받아들이지 않는다.
        val checked = validateContent(plan, verified, repairs, rawContent(coreResult, optional))
        require(decorate(checked, repairs, status, code) == coreResult) { "WRITING_FEEDBACK_CORE_RESULT_CHANGED" }
        return coreResult
    }

    private fun rawContent(core: JsonObject, optional: JsonArray): JsonObject = buildJsonObject {
        put("necessaryCorrections", JsonArray(core.getValue("necessaryCorrections").jsonArray.map { raw ->
            JsonObject(raw.jsonObject.filterKeys { it in setOf("requirementId", "segmentId", "patch", "issueType",
                "sourceFactId", "sourceAuthorityId", "formReason", "learningText") })
        }))
        put("optionalAlternatives", JsonArray(optional.map { raw ->
            JsonObject(raw.jsonObject.filterKeys { it in setOf("requirementId", "suggestionKind", "segmentId",
                "patch", "relationChecks", "learningText") })
        }))
        put("uncertainties", JsonArray(core.getValue("uncertainties").jsonArray.map { raw ->
            JsonObject(raw.jsonObject.filterKeys { it in setOf("requirementId", "learningText") })
        }))
    }

    private fun assembleValues(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                               repairs: CuratedWritingRepairPlan.Plan, values: Map<String, JsonElement>): JsonObject {
        require(values.keys == repairs.tasks.map { it.id }.toSet()) { "WRITING_FEEDBACK_CORE_REPAIR_INCOMPLETE" }
        val corrections = repairs.tasks.map { task ->
            val value = values.getValue(task.id).jsonObject
            require(validTask(plan, verified, task, value)) { "WRITING_FEEDBACK_CORE_TASK_INVALID:${task.id}" }
            row(task, value)
        }
        val content = buildJsonObject {
            put("necessaryCorrections", JsonArray(corrections))
            put("optionalAlternatives", JsonArray(emptyList()))
            put("uncertainties", uncertaintyRows(plan, verified))
        }
        return decorate(validateContent(plan, verified, repairs, content), repairs,
            "NOT_RUN_LIMIT", "OPTIONAL_NOT_RUN_LIMIT")
    }

    private fun validTask(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                          task: CuratedWritingRepairPlan.Task, value: JsonElement): Boolean = try {
        val current = row(task, value.jsonObject)
        val content = buildJsonObject {
            put("necessaryCorrections", JsonArray(listOf(current)))
            put("optionalAlternatives", JsonArray(emptyList()))
            put("uncertainties", uncertaintyRows(plan, verified))
        }
        CuratedWritingFeedbackV11Policy.validateWithRepairRanges(
            JsonObject(CuratedWritingFeedbackV13Policy.frozenDecisions(plan, verified) + content),
            plan.context, plan.requirements, plan.segments, listOfNotNull(task.rangeProof))
        val grammar = task.kind != "LANGUAGE_FORM" || CuratedWritingLanguageFormEvidence.issues(
            JsonArray(listOf(current)), task.grammarFindings, plan.segments).isEmpty()
        grammar && CuratedWritingV14FactPatchAudit.valid(current, plan, verified)
    } catch (_: Exception) { false }

    private fun validateContent(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                                repairs: CuratedWritingRepairPlan.Plan, content: JsonObject): JsonObject {
        CuratedWritingRepairPlan.requireBound(plan, verified, repairs)
        require(content.keys == setOf("necessaryCorrections", "optionalAlternatives", "uncertainties"))
        val corrections = content.getValue("necessaryCorrections").jsonArray

        // 공개 행도 서버 작업과 일대일이어야 한다. 권한·범위·coverage를 모델이나 후속 단계가 추가하지 못한다.
        require(corrections.size == repairs.tasks.size) { "WRITING_FEEDBACK_CORE_TASK_SET_INVALID" }
        repairs.tasks.forEach { task ->
            val matches = corrections.filter { raw ->
                val row = raw.jsonObject
                row.string("segmentId") == task.segmentId &&
                    row.getValue("patch").jsonObject.string("original") == task.original
            }
            require(matches.size == 1) { "WRITING_FEEDBACK_CORE_TASK_SET_INVALID" }
            val current = matches.single().jsonObject
            val canonical = buildJsonObject {
                put("replacement", current.getValue("patch").jsonObject.getValue("replacement"))
                put("learningText", current.getValue("learningText"))
            }
            require(row(task, canonical) == current && validTask(plan, verified, task, canonical)) {
                "WRITING_FEEDBACK_CORE_TASK_INVALID:${task.id}"
            }
        }
        val proofs = repairs.tasks.mapNotNull { it.rangeProof }
        if (proofs.isEmpty()) return CuratedWritingFeedbackV13Policy.assemble(content, plan, verified)

        // 연속 절은 실제 개별 인용을 보존한 typed proof 경로만 추가한다. 나머지 구형 전체 검사는 동일하다.
        val checked = CuratedWritingFeedbackV11Policy.validateWithRepairRanges(
            JsonObject(CuratedWritingFeedbackV13Policy.frozenDecisions(plan, verified) + content),
            plan.context, plan.requirements, plan.segments, proofs)
        CuratedWritingLanguageFormEvidence.requireCoverage(corrections,
            verified.getValue(CuratedWritingLanguageFormEvidence.key).jsonArray, plan.segments)
        requireConflictCoverage(plan, verified, corrections, proofs)
        return JsonObject(checked + mapOf(
            "policyVersion" to JsonPrimitive(CuratedWritingFeedbackV13Policy.version),
            "meaningContractId" to JsonPrimitive(plan.contract.id),
            "meaningContractHash" to JsonPrimitive(plan.contract.sha256()),
            "meaningContractVersion" to JsonPrimitive(CuratedWritingMeaningContract.version),
        ))
    }

    private fun requireConflictCoverage(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                                        corrections: JsonArray, proofs: List<CuratedWritingRepairRangeProof>) {
        val decisions = CuratedWritingFeedbackV13Policy.resolvedAtoms(plan, verified)
        plan.contract.atoms.forEach { atom ->
            val decision = decisions.getValue(atom.id)
            if (decision.state != "CONFLICT") return@forEach
            val authorities = atom.taskFactIds.map { "TASK_FACT_CONFLICT" to it } +
                atom.taskConditionIds.map { "TASK_CONDITION_CONFLICT" to it }
            if (authorities.isEmpty()) return@forEach
            require(corrections.any { raw ->
                val row = raw.jsonObject
                val type = row.string("issueType")
                val authority = if (type == "TASK_FACT_CONFLICT") row.string("sourceFactId") else row.string("sourceAuthorityId")
                val patch = row.getValue("patch").jsonObject
                (type to authority) in authorities && (decision.sources.any { id ->
                    val source = plan.sources.single { it.id == id }
                    source.segmentId == row.string("segmentId") && source.text.contains(patch.string("original"))
                } || proofs.any { it.covers(atom.id, row.string("segmentId"), patch, type, authority) })
            }) { "WRITING_FEEDBACK_CONFLICT_CORRECTION_MISSING" }
        }
        plan.contract.facts.forEach { fact ->
            val binding = verified.getValue("facts").jsonObject.getValue(fact.key).jsonObject
            if (binding.string("slotRelation") != "MATCHED_EVENT" ||
                fact.normalizedAlternatives.getValue(binding.string("valueToken")) == fact.expectedValue) return@forEach
            require(corrections.any { CuratedWritingV14FactPatchAudit.repairsExplicitConflict(it.jsonObject, plan, verified, fact) }) {
                "WRITING_FEEDBACK_CONFLICT_CORRECTION_MISSING"
            }
        }
    }

    private fun row(task: CuratedWritingRepairPlan.Task, value: JsonObject): JsonObject {
        require(value.keys == setOf("replacement", "learningText")) { "WRITING_FEEDBACK_CORE_TASK_INVALID" }
        val replacement = value.string("replacement")
        val explanation = value.string("learningText")
        require(replacement.isNotBlank() && replacement != task.original && explanation.isNotBlank())
        return buildJsonObject {
            put("requirementId", task.primary.requirementId); put("segmentId", task.segmentId)
            put("patch", buildJsonObject { put("original", task.original); put("replacement", replacement) })
            put("issueType", task.primary.issueType)
            put("sourceFactId", if (task.primary.issueType == "TASK_FACT_CONFLICT") task.primary.authorityId else "")
            put("sourceAuthorityId", if (task.primary.issueType == "TASK_CONDITION_CONFLICT") task.primary.authorityId else "")
            put("formReason", task.formReason); put("learningText", explanation)
        }
    }

    private fun deterministicValues(repairs: CuratedWritingRepairPlan.Plan): Map<String, JsonElement> = repairs.tasks
        .filter { it.deterministicReplacement != null }.associate { task ->
            task.id to buildJsonObject {
                put("replacement", checkNotNull(task.deterministicReplacement))
                put("learningText", "課題の ${task.primary.authorityId} に示された値に合わせ、この箇所を「${task.deterministicReplacement}」に直してください。")
            }
        }

    private fun parse(value: JsonElement, repairs: CuratedWritingRepairPlan.Plan, expectedIds: List<String>): Map<String, JsonElement> {
        val root = value.jsonObject
        require(root.keys == setOf("repairPlanHash", "repairs") && root.string("repairPlanHash") == repairs.hash) {
            "WRITING_FEEDBACK_REPAIR_REVISION_MISMATCH"
        }
        val rows = root.getValue("repairs").jsonObject
        require(rows.keys == expectedIds.toSet()) { "WRITING_FEEDBACK_CORE_TASK_SET_INVALID" }
        return rows.mapValues { (id, raw) ->
            val row = raw.jsonObject
            require(row.keys == setOf("fullPatchedSegment", "learningText")) { "WRITING_FEEDBACK_CORE_TASK_INVALID" }
            val task = repairs.task(id)
            val full = row.string("fullPatchedSegment")
            val prefix = task.originalSegment.substring(0, task.start)
            val suffix = task.originalSegment.substring(task.end)

            // 전체 문장을 받되 서버가 발급한 범위 밖의 단 한 문자도 바뀌지 않게 역산한다.
            require(full.length >= prefix.length + suffix.length && full.startsWith(prefix) && full.endsWith(suffix)) {
                "WRITING_FEEDBACK_CORE_OUTSIDE_RANGE_EDIT"
            }
            val end = full.length - suffix.length
            require(boundary(full, prefix.length) && boundary(full, end)) { "WRITING_FEEDBACK_CORE_RANGE_INVALID" }
            buildJsonObject {
                put("replacement", full.substring(prefix.length, end)); put("learningText", row.getValue("learningText"))
            }
        }
    }

    private fun schema(repairs: CuratedWritingRepairPlan.Plan, ids: List<String>): JsonObject {
        require(repairs.blockers.isEmpty()) { "WRITING_FEEDBACK_REPAIR_PLAN_BLOCKED" }
        require(ids.isNotEmpty() && ids.distinct().size == ids.size && ids.all { it in repairs.modelTaskIds }) {
            "WRITING_FEEDBACK_NO_MODEL_REPAIR_TASKS"
        }
        return obj(linkedMapOf("repairPlanHash" to text(listOf(repairs.hash)), "repairs" to obj(ids.associateWith {
            obj(linkedMapOf("fullPatchedSegment" to text(), "learningText" to text()))
        })))
    }

    private fun prompt(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                       repairs: CuratedWritingRepairPlan.Plan, ids: List<String>): String = buildJsonObject {
        put("repairPlanVersion", repairPlanVersion); put("repairPlanHash", repairs.hash)
        put("taskMeaningContract", plan.contract.promptJson())
        put("frozenEvidence", CuratedWritingFeedbackV13Policy.frozenDecisions(plan, verified))
        put("learnerSegments", JsonArray(plan.segments.map { buildJsonObject { put("id", it.id); put("text", it.text) } }))
        put("repairTasks", JsonArray(ids.map { CuratedWritingRepairPlan.taskJson(repairs.task(it)) }))
    }.toString()

    private fun requireProgress(repairs: CuratedWritingRepairPlan.Plan, progress: CoreProgress) {
        require(progress.repairPlanHash == repairs.hash && progress.completedResult == null) { "WRITING_FEEDBACK_REPAIR_REVISION_MISMATCH" }
        require(progress.frozenOutput.keys == setOf("repairPlanHash", "repairs") &&
            progress.frozenOutput.string("repairPlanHash") == repairs.hash) { "WRITING_FEEDBACK_REPAIR_REVISION_MISMATCH" }
        val keys = progress.frozenOutput.getValue("repairs").jsonObject.keys
        require(keys.intersect(progress.unresolvedTaskIds.toSet()).isEmpty() &&
            keys + progress.unresolvedTaskIds.toSet() == repairs.tasks.map { it.id }.toSet()) { "WRITING_FEEDBACK_CORE_TASK_SET_INVALID" }
    }

    private fun output(repairs: CuratedWritingRepairPlan.Plan, values: Map<String, JsonElement>) = buildJsonObject {
        put("repairPlanHash", repairs.hash); put("repairs", JsonObject(values))
    }

    private fun uncertaintyRows(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject): JsonArray {
        val decisions = CuratedWritingFeedbackV13Policy.resolvedAtoms(plan, verified)
        return JsonArray(plan.contract.requirements.filter { requirement -> requirement.atoms.any { decisions.getValue(it.id).state == "AMBIGUOUS" } }
            .map { requirement -> buildJsonObject {
                put("requirementId", requirement.id)
                put("learningText", "表現には要件への対応が異なる二つの読み方があり、意味の確認が必要です。")
            } })
    }

    private fun decorate(result: JsonObject, repairs: CuratedWritingRepairPlan.Plan, status: String, code: String?): JsonObject =
        JsonObject(result + mapOf("policyVersion" to JsonPrimitive(version), "repairPlanVersion" to JsonPrimitive(repairPlanVersion),
            "repairPlanHash" to JsonPrimitive(repairs.hash), "optionalSuggestionsStatus" to JsonPrimitive(status),
            "optionalSuggestionsFailureCode" to (code?.let(::JsonPrimitive) ?: JsonNull)))

    private fun text(values: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string"); values?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
    }
    private fun obj(fields: Map<String, JsonElement>): JsonObject = buildJsonObject {
        put("type", "object"); put("properties", JsonObject(fields)); put("additionalProperties", false)
        put("required", JsonArray(fields.keys.map(::JsonPrimitive)))
    }
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun boundary(text: String, offset: Int): Boolean = offset !in 1 until text.length ||
        !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())
}
