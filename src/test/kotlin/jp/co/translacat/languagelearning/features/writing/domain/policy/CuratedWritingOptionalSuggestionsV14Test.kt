package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingOptionalSuggestionsV14Test {
    @Test
    fun usefulParaphraseRequiresIndependentCheckerAndKeepsStrictCore() {
        // 준비: 실제 뜻을 보존하는 가게 요청의 표현 대안이다.
        val targets = targets()
        val target = targets.targets.first()
        val proposals = proposal(targets, target.original.replace("いただけますか", "もらえますか"))
        val coreBefore = targets.coreResult.toString()

        // 실행: 생성자의 자기 보존 주장은 없고 독립 checker 전항목을 거친다.
        val verified = CuratedWritingOptionalSuggestionsV14.validateVerifiedSuggestions(checker(targets, proposals), targets, proposals)

        // 검증: 정상 optional이 실제로 남으며 필수 결과와 근거는 바뀌지 않는다.
        assertEquals(CuratedWritingOptionalSuggestionsV14.Status.SUCCEEDED, verified.status)
        assertEquals(1, verified.rows.size)
        assertTrue(verified.rows.single().jsonObject.getValue("replacement").jsonPrimitive.content.contains("もらえますか"))
        assertEquals("EXPRESSION_ONLY", verified.rows.single().jsonObject.getValue("suggestionKind").jsonPrimitive.content)
        assertEquals("元の表現も使えます。意味を保ち、文を短くする別の言い方です。",
            verified.rows.single().jsonObject.getValue("learningText").jsonPrimitive.content)
        assertEquals(coreBefore, targets.coreResult.toString())
        assertNull(verified.failureCode)
        val withoutChecker = CuratedWritingOptionalSuggestionsV14.validateVerifiedSuggestions(proposals, targets, proposals)
        assertEquals(CuratedWritingOptionalSuggestionsV14.Status.FAILED, withoutChecker.status)
        assertTrue(withoutChecker.rows.isEmpty())
    }

    @Test
    fun everyPreservationFailureRejectsOptionalWithoutDamagingCore() {
        // 준비: checker는 행위·시간·조건·확실성 및 문법/실용성을 각각 판정한다.
        val targets = targets()
        val target = targets.targets.first()
        val proposals = proposal(targets, target.original.replace("いただけますか", "もらえますか"))
        val coreBefore = targets.coreResult.toString()

        // 실행·검증: 어느 한 항목의 부정도 성공으로 바뀌지 않고 오류 row는 표시되지 않는다.
        checks.forEach { failedCheck ->
            val result = CuratedWritingOptionalSuggestionsV14.validateVerifiedSuggestions(checker(targets, proposals, failedCheck), targets, proposals)
            assertEquals(CuratedWritingOptionalSuggestionsV14.Status.FAILED, result.status, failedCheck)
            assertTrue(result.rows.isEmpty(), failedCheck)
            assertEquals("OPTIONAL_SEMANTIC_REJECTED", result.failureCode)
            assertEquals(coreBefore, targets.coreResult.toString())
        }
    }

    @Test
    fun addingAnUnexpressedActionOrFactCannotPassAsExpressionOnly() {
        // 준비: G06·FT02에서 관찰한 부정 유형과 같이 새 사건/전달수단을 추가한 제안이다.
        val targets = targets()
        val invented = listOf(
            "植木鉢を家に持って帰りました。",
            "メールで結果を受け取ります。",
            "日曜日の午後に必ず受け取ります。",
        )

        // 실행·검증: 독립 검사에서 새 명제가 발견되면 core는 보존하고 optional만 실패한다.
        invented.forEach { replacement ->
            val proposal = proposal(targets, replacement)
            val result = CuratedWritingOptionalSuggestionsV14.validateVerifiedSuggestions(checker(targets, proposal, "noNewProposition"), targets, proposal)
            assertEquals(CuratedWritingOptionalSuggestionsV14.Status.FAILED, result.status)
            assertTrue(result.rows.isEmpty())
            assertTrue(targets.coreResult.getValue("necessaryCorrections").jsonArray.isEmpty())
        }
    }

    @Test
    fun missingRequirementIsNotAnOptionalTarget() {
        // 준비: 자기 수령 계획만 있고 가게 요청은 없는 FREE 사례다.
        val plan = request("文具店の青い手帳について書きます。月曜日の研修で使うため日曜日の午後までに必要です。正午に受け取りに行きます。")
        val absent = CuratedWritingFeedbackV13TestPayload.edge("ABSENT", emptyList())
        val verified = CuratedWritingFeedbackV13TestPayload.verified(plan)
            .changeAtom("C1.A1", absent).changeAtom("C3.A1", absent)
        val core = core(plan, verified)

        // 실행: C1/C3 부재는 그대로 두고 충족한 C2만 표현 제안 대상으로 삼는다.
        val targets = CuratedWritingOptionalSuggestionsV14.planTargets(plan, verified, core)

        // 검증: 누락 요청에 대한 강제 보충 patch를 만들 대상 자체가 없다.
        assertTrue(targets.targets.isNotEmpty())
        assertTrue(targets.targets.all { it.requirementId == "C2" })
        val proposal = proposal(targets, "取り置いてください。")
        val rejected = CuratedWritingOptionalSuggestionsV14.validateVerifiedSuggestions(checker(targets, proposal, "actionPreserved"), targets, proposal)
        assertEquals(CuratedWritingOptionalSuggestionsV14.Status.FAILED, rejected.status)
        assertEquals(listOf("UNMET", "MET", "UNMET"), targets.coreResult.getValue("observations").jsonArray
            .map { it.jsonObject.getValue("status").jsonPrimitive.content })
    }

    @Test
    fun generatorCannotChooseUnknownTargetChangeHashesOrSelfApprove() {
        // 준비: 원문/필수 hash와 대상은 서버가 고정한다.
        val targets = targets()
        val valid = proposal(targets, targets.targets.first().original.replace("いただけますか", "もらえますか"))
        val row = valid.getValue("proposals").jsonArray.single().jsonObject
        val invalid = listOf(
            JsonObject(valid + ("sourceRevisionHash" to JsonPrimitive("stale"))),
            JsonObject(valid + ("coreHash" to JsonPrimitive("another-core"))),
            withRows(valid, listOf(JsonObject(row + ("targetId" to JsonPrimitive("C4"))))),
            withRows(valid, listOf(JsonObject(row + ("actionPreserved" to JsonPrimitive(true))))),
            withRows(valid, listOf(JsonObject(row + ("learningText" to JsonPrimitive("元の表現は不自然です。"))))),
            withRows(valid, listOf(row, row)),
        )

        // 실행·검증: 무권한 target·중복·생성자의 자기 보존 선언은 모두 거절한다.
        invalid.forEach {
            assertFailsWith<IllegalArgumentException> { CuratedWritingOptionalSuggestionsV14.validateProposals(it, targets) }
        }
        val approved = checker(targets, valid)
        val forgedChecker = JsonObject(approved + ("proposalHash" to JsonPrimitive("changed-replacement")))
        assertEquals(CuratedWritingOptionalSuggestionsV14.Status.FAILED,
            CuratedWritingOptionalSuggestionsV14.validateVerifiedSuggestions(forgedChecker, targets, valid).status)
        val missing = JsonObject(approved + ("decisions" to JsonArray(emptyList())))
        assertEquals(CuratedWritingOptionalSuggestionsV14.Status.FAILED,
            CuratedWritingOptionalSuggestionsV14.validateVerifiedSuggestions(missing, targets, valid).status)
    }

    @Test
    fun missingNecessaryGrammarCorrectionCannotBeHiddenByOptionalStatus() {
        // 준비: 공개 뜻은 있으나 실제 LF finding을 수리하지 않은 core를 위조한다.
        val plan = request("明日の午後まで商品を取り置きしていただけますか。仕事で使いまる。")
        val originalVerified = CuratedWritingFeedbackV13TestPayload.verified(plan)
        val wrongCore = core(plan, originalVerified)
        val verified = withGrammar(originalVerified)

        // 실행·검증: optional 계획 단계부터 기존 LF coverage를 그대로 요구한다.
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingOptionalSuggestionsV14.planTargets(plan, verified, wrongCore)
        }
    }

    @Test
    fun checkerReadsFullContextWithValidatedNecessaryCorrectionFrozen() {
        // 준비: 필수 문법 교정은 이미 검증되었으며 optional은 다른 문장에만 적용한다.
        val plan = request("明日の午後まで商品を取り置きしていただけますか。仕事で使いまる。")
        val verified = withGrammar(CuratedWritingFeedbackV13TestPayload.verified(plan))
        val core = core(plan, verified, mapOf("R1" to "ます"))
        val targets = CuratedWritingOptionalSuggestionsV14.planTargets(plan, verified, core)
        val proposals = proposal(targets, targets.targets.first().original.replace("いただけますか", "もらえますか"))

        // 실행: 독립 checker는 잘린 patch만이 아니라 전체 문맥을 받는다.
        val prompt = Json.parseToJsonElement(CuratedWritingOptionalSuggestionsV14.verificationPrompt(targets, proposals)).jsonObject
        val result = CuratedWritingOptionalSuggestionsV14.validateVerifiedSuggestions(checker(targets, proposals), targets, proposals)

        // 검증: 문법 교정 문장을 optional이 덮어쓰지 않으며 정상 표현 제안은 살아 있다.
        assertTrue(targets.targets.none { it.segmentId == "S2" })
        assertEquals("仕事で使います。", targets.coreSegments.getValue("S2"))
        assertTrue(prompt.getValue("proposals").jsonArray.single().jsonObject.getValue("fullPatchedContext").jsonPrimitive.content.endsWith("仕事で使います。"))
        assertEquals(CuratedWritingOptionalSuggestionsV14.Status.SUCCEEDED, result.status)
        assertEquals(1, result.rows.size)
        assertEquals(1, targets.coreResult.getValue("necessaryCorrections").jsonArray.size)
    }

    @Test
    fun emptyTargetsAndEmptySuggestionsAreExplicitAndSchemaHasNoEmptyEnum() {
        // 준비: 과제 내용이 모두 누락되면 선택 제안 대상이 없다.
        val plan = request("よろしくお願いします。")
        val base = CuratedWritingFeedbackV13TestPayload.verified(plan)
        val atoms = base.getValue("atoms").jsonObject.mapValues { (_, raw) ->
            val row = raw.jsonObject
            if (row.containsKey("alternatives")) buildJsonObject {
                put("alternatives", JsonObject(row.getValue("alternatives").jsonObject.mapValues {
                    CuratedWritingFeedbackV13TestPayload.edge("ABSENT", emptyList())
                }))
            } else CuratedWritingFeedbackV13TestPayload.edge("ABSENT", emptyList())
        }
        val verified = JsonObject(base + ("atoms" to JsonObject(atoms)))
        val core = core(plan, verified)

        // 실행: 빈 제안도 별도 의미 판정을 바꾸지 않고 정상적으로 표현한다.
        val targets = CuratedWritingOptionalSuggestionsV14.planTargets(plan, verified, core)
        val proposals = buildJsonObject {
            put("sourceRevisionHash", targets.sourceRevisionHash); put("coreHash", targets.coreHash)
            put("proposals", JsonArray(emptyList()))
        }

        // 검증: Worker가 NOT_APPLICABLE 또는 빈 SUCCEEDED로 처리할 수 있고 실패를 숨기는 row는 없다.
        assertTrue(targets.targets.isEmpty())
        assertFalse(CuratedWritingOptionalSuggestionsV14.proposalSchema(targets).toString().contains("\"enum\":[]"))
        assertFalse(CuratedWritingOptionalSuggestionsV14.verificationSchema(targets, proposals).toString().contains("\"enum\":[]"))
        assertEquals(CuratedWritingOptionalSuggestionsV14.Status.SUCCEEDED,
            CuratedWritingOptionalSuggestionsV14.validateVerifiedSuggestions(checker(targets, proposals), targets, proposals).status)
    }

    @Test
    fun onlyActualCodePointShorteningSurvivesBeforeIndependentChecking() {
        // 준비: UTF-16 길이가 같아도 보조 평면 문자는 한 code point다.
        val base = targets()
        val target = base.targets.first()
        val counted = base.copy(targets = listOf(target.copy(original = "ABCD")))
        val unicode = proposal(counted, "😀AB")

        // 실행: 검사할 가치가 있는 실제 단축만 남기며 문장부호 삭제는 제외한다.
        assertEquals(1, CuratedWritingOptionalSuggestionsV14.validateProposals(unicode, counted)
            .getValue("proposals").jsonArray.size)
        listOf("ABCD", "WXYZ", "ABCDE").forEach { replacement ->
            assertTrue(CuratedWritingOptionalSuggestionsV14.validateProposals(proposal(counted, replacement), counted)
                .getValue("proposals").jsonArray.isEmpty())
        }
        val punctuated = base.copy(targets = listOf(target.copy(original = "長い文、です。")))
        val punctuationOnly = proposal(punctuated, "長い文です。")
        val filtered = CuratedWritingOptionalSuggestionsV14.validateProposals(punctuationOnly, punctuated)

        // 검증: 이점 없는 행을 checker가 자기 승인해 되살릴 수 없다. 빈 결과는 명시적인 성공이다.
        assertTrue(filtered.getValue("proposals").jsonArray.isEmpty())
        assertEquals(filtered, CuratedWritingOptionalSuggestionsV14.validateProposals(filtered, punctuated))
        assertEquals(CuratedWritingOptionalSuggestionsV14.Status.SUCCEEDED,
            CuratedWritingOptionalSuggestionsV14.validateVerifiedSuggestions(checker(punctuated, punctuationOnly), punctuated, punctuationOnly).status)
    }

    @Test
    fun checkerReceivesBothIndividualAndCombinedContextsBoundToStableProposalHash() {
        // 준비: 서로 다른 두 문장에 의미를 보존하는 단축 표현을 제안한다.
        val plan = request("明日の午後まで商品を取り置きしていただけますか。月曜日の仕事の後に使うため必要です。")
        val verified = CuratedWritingFeedbackV13TestPayload.verified(plan)
        val targets = CuratedWritingOptionalSuggestionsV14.planTargets(plan, verified, core(plan, verified))
        assertEquals(2, targets.targets.size)
        val replacements = targets.targets.associate { target -> target.id to target.original
            .replace("いただけますか", "もらえますか").replace("仕事の後に", "仕事後に") }
        val proposals = buildJsonObject {
            put("sourceRevisionHash", targets.sourceRevisionHash); put("coreHash", targets.coreHash)
            put("proposals", JsonArray(targets.targets.map { target -> buildJsonObject {
                put("targetId", target.id); put("replacement", replacements.getValue(target.id))
            } }))
        }

        // 실행: 같은 정규화 입력은 동일한 hash와 전체 조합 문맥을 만든다.
        val checked = CuratedWritingOptionalSuggestionsV14.validateProposals(proposals, targets)
        val prompt = Json.parseToJsonElement(CuratedWritingOptionalSuggestionsV14.verificationPrompt(targets, proposals)).jsonObject
        val normalizedPrompt = Json.parseToJsonElement(CuratedWritingOptionalSuggestionsV14.verificationPrompt(targets, checked)).jsonObject

        // 검증: 개별 문맥만 통과하는 경우를 방지할 수 있도록 두 변경이 함께 들어간 원문 순서를 보존한다.
        assertEquals(targets.targets.joinToString("") { replacements.getValue(it.id) },
            prompt.getValue("combinedPatchedContext").jsonPrimitive.content)
        assertEquals(prompt.getValue("proposalHash"), normalizedPrompt.getValue("proposalHash"))
        assertEquals(targets.coreResult, prompt.getValue("strictCoreResult"))
        val individual = prompt.getValue("proposals").jsonArray.map { it.jsonObject.getValue("fullPatchedContext").jsonPrimitive.content }
        assertTrue(individual.any { it.contains("いただけますか") })
        assertTrue(individual.any { it.contains("仕事の後に") })
        assertTrue(CuratedWritingOptionalSuggestionsV14.verificationInstructions.contains("combinedPatchedContext"))
        assertEquals(CuratedWritingOptionalSuggestionsV14.Status.SUCCEEDED,
            CuratedWritingOptionalSuggestionsV14.validateVerifiedSuggestions(checker(targets, proposals), targets, proposals).status)
    }

    @Test
    fun spatialRelationAndAnchorMustRemainTogetherEvenWhenOperatorCountsAreUnchanged() {
        // 준비: 더 짧아도 위치·경로·안팎의 관계를 다른 사실로 바꾸는 제안이다.
        val base = targets()
        val cases = listOf(
            "先週末、友達と海の近くを自転車で走りました。" to "先週末、友達と海沿いを自転車で走りました。",
            "先週末、友達と海沿いを自転車でゆっくり走りました。" to "友達と海の近くを自転車で走りました。",
            "先週末、友達と自転車でゆっくり走りました。" to "友達と川沿いを自転車で走りました。",
            "先週末、友達と海の近くを自転車で走りました。" to "友達と自転車で走りました。",
            "先週末、海の近くと川沿いをゆっくり歩きました。" to "海沿いと川の近くを歩きました。",
            "先週末、海の近くをゆっくり歩きました。" to "川の近くを歩きました。",
            "海の近くと海の近くをゆっくり歩きました。" to "海の近くを歩きました。",
            "公園の内側をゆっくり歩きました。" to "公園の外側を歩きました。",
        )

        // 실행 / 검증: 동일 NEAR/ALONG 개수의 anchor 교환도 거절하고 원문 관계를 복구하지 않는다.
        cases.forEach { (original, replacement) ->
            assertTrue(replacement.codePointCount(0, replacement.length) < original.codePointCount(0, original.length))
            val bound = base.copy(targets = listOf(base.targets.first().copy(original = original)))
            val failure = assertFailsWith<IllegalArgumentException> {
                CuratedWritingOptionalSuggestionsV14.validateProposals(proposal(bound, replacement), bound)
            }
            assertEquals("OPTIONAL_SPATIAL_RELATION_CHANGED", failure.message)
        }
    }

    @Test
    fun unchangedSpatialPhraseAllowsUnrelatedUsefulShorteningThroughAllExistingChecks() {
        // 준비: 실제 위치 구절은 그대로 두고 다른 요청 표현만 짧게 바꾼다.
        val plan = request("海の近くの店舗で、明日の午後まで商品を取り置きしていただけますか。仕事で使うためです。")
        val verified = CuratedWritingFeedbackV13TestPayload.verified(plan)
        val targets = CuratedWritingOptionalSuggestionsV14.planTargets(plan, verified, core(plan, verified))
        val proposals = proposal(targets, targets.targets.first().original.replace("いただけますか", "もらえますか"))
        val coreBefore = targets.coreResult.toString()

        // 실행: 새 eligibility 이후에도 12항목 독립 checker와 기존 전체 strict guard를 거친다.
        val checked = CuratedWritingOptionalSuggestionsV14.validateProposals(proposals, targets)
        val result = CuratedWritingOptionalSuggestionsV14.validateVerifiedSuggestions(checker(targets, checked), targets, checked)

        // 검증: 유효한 선택 기능을 제거하지 않고 동일 anchor/관계와 검증된 필수 결과를 보존한다.
        assertEquals(CuratedWritingOptionalSuggestionsV14.Status.SUCCEEDED, result.status)
        assertEquals(1, result.rows.size)
        assertTrue(result.rows.single().jsonObject.getValue("replacement").jsonPrimitive.content.contains("海の近く"))
        assertEquals(coreBefore, targets.coreResult.toString())
    }

    @Test
    fun foodSobaAndTemporalChikakuAreNotInventedSpatialRelations() {
        // 준비: 명시적인 공간 관계 문형이 아닌 동음어·시각 근사 표현이다.
        val base = targets()
        val controls = listOf(
            "昼にそばを食べることができます。" to "昼にそばを食べられます。",
            "5時近くに店へ行くことができます。" to "5時近くに店へ行けます。",
        )

        // 실행 / 검증: 제한된 공간 parser가 이 표현을 공간 관계라고 단정하지 않는다.
        controls.forEach { (original, replacement) ->
            val bound = base.copy(targets = listOf(base.targets.first().copy(original = original)))
            assertEquals(1, CuratedWritingOptionalSuggestionsV14.validateProposals(proposal(bound, replacement), bound)
                .getValue("proposals").jsonArray.size)
        }
    }

    @Test
    fun checkerSelfApprovalCannotExposeChangedSpatialMeaningOrDamageCore() {
        // 준비: 현재 근처 위치를 해안 경로로 바꾸는 제안을 checker가 모두 true라고 주장한다.
        val plan = request("海の近くの店舗で、明日の午後まで商品を取り置きしていただけますか。仕事で使うためです。")
        val verified = CuratedWritingFeedbackV13TestPayload.verified(plan)
        val targets = CuratedWritingOptionalSuggestionsV14.planTargets(plan, verified, core(plan, verified))
        val proposals = proposal(targets, targets.targets.first().original.replace("海の近く", "海沿い"))
        val coreBefore = targets.coreResult.toString()
        val proposalHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(proposals.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val allTrue = buildJsonObject {
            put("sourceRevisionHash", targets.sourceRevisionHash); put("coreHash", targets.coreHash)
            put("proposalHash", proposalHash)
            put("decisions", JsonArray(listOf(buildJsonObject {
                put("targetId", targets.targets.first().id); checks.forEach { put(it, true) }
                put("reason", "The checker incorrectly claims all original meaning is preserved.")
            })))
        }

        // 실행 / 검증: 서버 관계 대조 실패는 잘못된 row를 내보내지 않고 core를 그대로 남긴다.
        val result = CuratedWritingOptionalSuggestionsV14.validateVerifiedSuggestions(allTrue, targets, proposals)
        assertEquals(CuratedWritingOptionalSuggestionsV14.Status.FAILED, result.status)
        assertTrue(result.rows.isEmpty())
        assertEquals(coreBefore, targets.coreResult.toString())
    }

    private fun request(answer: String) = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-free-b3-01", answer)
    private fun targets(): CuratedWritingOptionalSuggestionsV14.TargetPlan {
        val plan = request("明日の午後まで商品を取り置きしていただけますか。仕事で使うためです。")
        val verified = CuratedWritingFeedbackV13TestPayload.verified(plan)
        val core = core(plan, verified)
        return CuratedWritingOptionalSuggestionsV14.planTargets(plan, verified, core)
    }

    private fun proposal(targets: CuratedWritingOptionalSuggestionsV14.TargetPlan, replacement: String) = buildJsonObject {
        put("sourceRevisionHash", targets.sourceRevisionHash); put("coreHash", targets.coreHash)
        put("proposals", JsonArray(listOf(buildJsonObject {
            put("targetId", targets.targets.first().id); put("replacement", replacement)
        })))
    }

    private fun checker(targets: CuratedWritingOptionalSuggestionsV14.TargetPlan, proposals: JsonObject, failedCheck: String? = null): JsonObject {
        val schema = CuratedWritingOptionalSuggestionsV14.verificationSchema(targets, proposals).getValue("properties").jsonObject
        return buildJsonObject {
            put("sourceRevisionHash", targets.sourceRevisionHash); put("coreHash", targets.coreHash)
            put("proposalHash", schema.getValue("proposalHash").jsonObject.getValue("enum").jsonArray.single())
            put("decisions", JsonArray(CuratedWritingOptionalSuggestionsV14.validateProposals(proposals, targets)
                .getValue("proposals").jsonArray.map { raw -> buildJsonObject {
                put("targetId", raw.jsonObject.getValue("targetId"))
                checks.forEach { put(it, it != failedCheck) }
                put("reason", if (failedCheck == null) "Original facts and request are preserved." else "The proposed change does not preserve the original.")
            } }))
        }
    }

    private fun core(plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                     replacements: Map<String, String> = emptyMap()): JsonObject {
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        return CuratedWritingFeedbackV14Policy.serverCoreIfComplete(plan, verified, repairs)
            ?: CuratedWritingFeedbackV14Policy.assembleCore(plan, verified, repairs,
                CuratedWritingFeedbackV14TestPayload.output(repairs, replacements))
    }

    private fun withGrammar(base: JsonObject) = JsonObject(base + (CuratedWritingLanguageFormEvidence.key to JsonArray(listOf(buildJsonObject {
        put("segmentId", "S2"); put("quote", "使いまる"); put("errorCore", "まる"); put("formReason", "INFLECTION")
    }))))
    private fun withRows(base: JsonObject, rows: List<JsonObject>) = JsonObject(base + ("proposals" to JsonArray(rows)))
    private val checks = listOf("actorPreserved", "actionPreserved", "targetPreserved", "timePreserved",
        "negationPreserved", "conditionPreserved", "certaintyPreserved", "quantitiesPreserved",
        "noNewProposition", "grammarValid", "useful", "explanationAccurate")
}

private fun JsonObject.changeAtom(id: String, value: JsonObject) =
    JsonObject(this + ("atoms" to JsonObject(getValue("atoms").jsonObject + (id to value))))

