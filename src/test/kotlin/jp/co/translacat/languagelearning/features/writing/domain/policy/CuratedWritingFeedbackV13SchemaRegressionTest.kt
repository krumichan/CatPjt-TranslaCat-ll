package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import java.security.MessageDigest
import kotlin.test.*

class CuratedWritingFeedbackV13SchemaRegressionTest {
    private val resource = "/contracts/writing-feedback-v13-actual-synthetic-evidence.json"
    private val bytes by lazy { checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() } }
    private val fixture by lazy { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
    private val cases by lazy { fixture.getValue("cases").jsonArray.map { it.jsonObject } }
    private val authorityBytes by lazy {
        checkNotNull(javaClass.getResourceAsStream("/contracts/writing-feedback-v13-actual-synthetic-authority.json")).use { it.readBytes() }
    }
    private val authorityFixture by lazy { Json.parseToJsonElement(authorityBytes.toString(Charsets.UTF_8)).jsonObject }
    private val authorityCase by lazy { authorityFixture.getValue("case").jsonObject }
    private val missingAuthorityBytes by lazy {
        checkNotNull(javaClass.getResourceAsStream("/contracts/writing-feedback-v13-actual-synthetic-missing-authority.json")).use { it.readBytes() }
    }
    private val missingAuthorityFixture by lazy { Json.parseToJsonElement(missingAuthorityBytes.toString(Charsets.UTF_8)).jsonObject }
    private val missingAuthorityCase by lazy { missingAuthorityFixture.getValue("case").jsonObject }

    @Test
    fun `실제 합성 과제 응답 원본과 답안 fingerprint를 보존한다`() {
        // 준비: 실제 OpenAI output_text만 고정했다. 비밀정보와 encrypted reasoning은 자원에 포함하지 않는다.
        // 원본 세 응답의 ledger ID·response SHA는 fixture provenance에 있고 payload는 바꾸지 않았다.
        // Git checkout의 문서 개행만 정규화한다. JSON 안의 실제 답안·output_text와 Provider 원본 hash는 바꾸지 않는다.
        val document = bytes.toString(Charsets.UTF_8).replace("\r\n", "\n")
        assertEquals("f5fa2ed618a8b84a8d52db3f16bd5ca6182ec15b9550c833e83441dcc281a7a5", sha(document.toByteArray(Charsets.UTF_8)))

        // 실행·검증: 원본 문자열과 synthetic answer의 hash가 그대로인지 확인한다.
        assertEquals(listOf("G04", "FT03", "G05"), cases.map { it.string("id") })
        cases.forEach { row ->
            assertEquals(row.string("answerSha256"), sha(row.string("answer").toByteArray(Charsets.UTF_8)))
            val provenance = row.getValue("provenance").jsonObject
            assertEquals("gpt-5-mini-2025-08-07", provenance.string("model"))
            assertEquals(provenance.string("outputTextSha256"), sha(row.string("outputText").toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun `변경하지 않은 실제 실패 세 응답은 기존 엄격 검증기가 계속 거절한다`() {
        // 준비: 빈 ABSENT 설명, 원문에 없는 동의 token, SUPPORT와 false 관계를 그대로 읽는다.
        val raw = cases.associate { it.string("id") to output(it) }
        assertEquals("", raw.getValue("G04").getValue("atoms").jsonObject.getValue("C4.A2").jsonObject.string("semanticReading"))
        assertEquals("来週月曜日", raw.getValue("FT03").getValue("facts").jsonObject.getValue("result_day").jsonObject.string("valueToken"))
        assertFalse(raw.getValue("G05").getValue("atoms").jsonObject.getValue("C3.A1").jsonObject.getValue("conditionMatches").jsonPrimitive.boolean)

        // 실행·검증: 실패를 정규화하거나 판정을 바꾸어 성공시키지 않는다.
        cases.forEach { row ->
            val failure = assertFailsWith<IllegalArgumentException>(row.string("id")) {
                CuratedWritingFeedbackV13Policy.validateVerification(output(row), plan(row))
            }
            assertEquals("WRITING_FEEDBACK_MEANING_EVIDENCE_INVALID", failure.message)
        }
    }

    @Test
    fun `새 Provider schema가 원본의 세 가지 표현 모순을 호출 전에 제한한다`() {
        // 준비: payload는 실제 응답이며 schema 해석 helper는 구조 제약만 검사한다.
        cases.forEach { row ->
            val schema = CuratedWritingFeedbackV13Policy.verificationSchema(plan(row))

            // 실행·검증: 원본은 schema에도 맞지 않지만 최소한의 올바른 표현은 맞는다.
            assertFalse(matches(schema, output(row)), row.string("id"))
            assertTrue(matches(schema, represented(row)), row.string("id"))
        }
    }

    @Test
    fun `schema는 nonblank와 실제 source별 literal 및 SUPPORT ABSENT 관계를 명시한다`() {
        // 준비: 실제로 없었던 다음주 동의표현을 고를 수 없는 FT03 schema를 사용한다.
        val row = cases.single { it.string("id") == "FT03" }
        val properties = CuratedWritingFeedbackV13Policy.verificationSchema(plan(row)).getValue("properties").jsonObject
        val edge = properties.getValue("atoms").jsonObject.getValue("properties").jsonObject.getValue("C1.A1").jsonObject
        val support = branch(edge, "SUPPORT")
        val absent = branch(edge, "ABSENT")
        val supportFields = support.getValue("properties").jsonObject
        val absentFields = absent.getValue("properties").jsonObject

        // 실행·검증: 설명·부울·근거 수 제약이 Provider 응답 구조에 포함된다.
        assertTrue(Regex(supportFields.getValue("semanticReading").jsonObject.string("pattern")).containsMatchIn("explicit meaning"))
        assertFalse(Regex(supportFields.getValue("semanticReading").jsonObject.string("pattern")).containsMatchIn(" \n\t"))
        for (field in listOf("actorMatches", "actionMatches", "targetMatches", "conditionMatches")) {
            assertEquals(JsonArray(listOf(JsonPrimitive(true))), supportFields.getValue(field).jsonObject.getValue("enum"))
        }
        assertEquals(1, supportFields.getValue("sourceIds").jsonObject.getValue("minItems").jsonPrimitive.int)
        assertEquals(0, absentFields.getValue("sourceIds").jsonObject.getValue("maxItems").jsonPrimitive.int)

        // 원문 source에 없는 literal은 어느 fact branch에도 존재하지 않는다.
        val resultDay = properties.getValue("facts").jsonObject.getValue("properties").jsonObject.getValue("result_day")
        assertFalse(resultDay.toString().contains("来週月曜日"))
        assertTrue(resultDay.toString().contains("IMPLICIT_CONTEXT"))
        val literalResultDay = resultDay.jsonObject.getValue("anyOf").jsonArray.single { raw ->
            val fields = raw.jsonObject.getValue("properties").jsonObject
            fields.getValue("slotRelation").jsonObject.getValue("enum").jsonArray ==
                JsonArray(listOf(JsonPrimitive("MATCHED_EVENT"))) &&
                fields.getValue("sourceId").jsonObject.getValue("enum").jsonArray ==
                JsonArray(listOf(JsonPrimitive("S1.Q2")))
        }.jsonObject.getValue("properties").jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("翌週月曜"))), literalResultDay.getValue("valueToken").jsonObject.getValue("enum"))

        // 사전은 특정 답안 치환이 아니라 다음 주의 일반적인 두 표기·조사·요일 형태를 제공한다.
        val dictionary = plan(row).contract.facts.single { it.key == "result_day" }.normalizedAlternatives
        assertEquals(setOf("来週月曜", "来週月曜日", "来週の月曜", "来週の月曜日",
            "翌週月曜", "翌週月曜日", "翌週の月曜", "翌週の月曜日"), dictionary.keys)
        assertEquals(setOf("NEXT_MONDAY"), dictionary.values.toSet())
    }

    @Test
    fun `최소 표현 정정은 의미 상태와 실제 source를 바꾸지 않고 전체 저장 계약을 통과한다`() {
        // 준비: 이 copy는 schema 계약 검사 전용이며 실제 Provider 원본이나 저장 결과를 고치지 않는다.
        cases.forEach { row ->
            val plan = plan(row)
            val raw = output(row)
            val represented = represented(row)
            assertEquals(meaningSignature(raw), meaningSignature(represented))

            // 실행: 의미와 실제 원문 연결을 고정한 채 정합한 표현을 검사한다.
            val verified = CuratedWritingFeedbackV13Policy.validateVerification(represented, plan)
            val content = if (row.string("id") == "G05") {
                CuratedWritingFeedbackV13TestPayload.content(listOf(
                    CuratedWritingFeedbackV13TestPayload.correction("C4", "S3", "図書館に置いたままです", "家に持ち帰りました", "T4"),
                ))
            } else CuratedWritingFeedbackV13TestPayload.content()
            val result = CuratedWritingFeedbackV13Policy.assemble(content, plan, verified)
            val statuses = result.getValue("observations").jsonArray.associate { entry ->
                entry.jsonObject.string("requirementId") to entry.jsonObject.string("status")
            }

            // 검증: G04 귀가 누락과 G05 귀가 충돌은 UNMET로 남고 정상 번역은 MET다.
            if (row.string("id") == "FT03") assertTrue(statuses.values.all { it == "MET" })
            else {
                assertEquals("UNMET", statuses.getValue("C4"))
                val parts = result.getValue("observations").jsonArray.single {
                    it.jsonObject.string("requirementId") == "C4"
                }.jsonObject.getValue("requirementParts").jsonArray
                assertEquals(listOf("PRESENT", if (row.string("id") == "G04") "ABSENT" else "CONFLICT"),
                    parts.map { it.jsonObject.string("status") })
            }
            assertEquals(if (row.string("id") == "G05") 1 else 0, result.getValue("necessaryCorrections").jsonArray.size)
        }
    }

    @Test
    fun `정상 표현도 미지 근거나 평가자 지시로 바꾸면 통과하지 않는다`() {
        // 준비: 정상 표현의 G04 참석 근거를 훼손한다.
        val row = cases.single { it.string("id") == "G04" }
        val plan = plan(row)
        val valid = represented(row)
        val atoms = valid.getValue("atoms").jsonObject
        val attended = atoms.getValue("C4.A1").jsonObject
        val malformed = listOf(
            JsonObject(attended + ("sourceIds" to JsonArray(listOf(JsonPrimitive("S999.Q1"))))),
            JsonObject(attended + mapOf("recipient" to JsonPrimitive("EVALUATOR"), "kind" to JsonPrimitive("INSTRUCTION"))),
            JsonObject(attended + ("sourceIds" to JsonArray(emptyList()))),
        )

        // 실행·검증: schema 개선이 기존 근거 검증을 우회하는 통로가 되지 않는다.
        malformed.forEach { edge ->
            val invalid = JsonObject(valid + ("atoms" to JsonObject(atoms + ("C4.A1" to edge))))
            assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.validateVerification(invalid, plan) }
        }
    }

    @Test
    fun `더 긴 상대 주 표현 안의 다음 주 부분 문자열을 사실 token으로 선택하지 못한다`() {
        // 준비: 같은 공개 과제에서 결과 시점만 다른 주로 바꾼 합성 부정 대조다. 실제 fixture는 바꾸지 않는다.
        val original = cases.single { it.string("id") == "FT03" }
        val samples = listOf(
            "再来週月曜" to "来週月曜",
            "再来週の月曜日" to "来週の月曜日",
            "再々来週月曜" to "来週月曜",
            "翌翌週月曜" to "翌週月曜",
            "翌々週月曜" to "翌週月曜",
        )
        samples.forEach { (actual, forged) ->
            val row = JsonObject(original + ("answer" to JsonPrimitive(original.string("answer").replace("翌週月曜", actual))))
            val plan = plan(row)
            val validShape = represented(row)
            val facts = validShape.getValue("facts").jsonObject
            val invalid = JsonObject(validShape + ("facts" to JsonObject(facts + ("result_day" to buildJsonObject {
                put("sourceId", "S1.Q2"); put("valueToken", forged); put("slotRelation", "MATCHED_EVENT")
            }))))

            // 실행·검증: 사전 밖 주 오프셋을 NEXT_MONDAY로 단정하는 literal branch와 검증을 모두 막는다.
            val schema = CuratedWritingFeedbackV13Policy.verificationSchema(plan)
            assertFalse(matches(schema, invalid), actual)
            val resultDay = schema.getValue("properties").jsonObject.getValue("facts").jsonObject
                .getValue("properties").jsonObject.getValue("result_day")
            assertFalse(resultDay.toString().contains("MATCHED_EVENT"), actual)
            assertFailsWith<IllegalArgumentException>(actual) {
                CuratedWritingFeedbackV13Policy.validateVerification(invalid, plan)
            }
        }
    }

    @Test
    fun `이번 주 마감과 다음 주 여덟 정상 요일 표현은 실제 원문 token으로 유지한다`() {
        // 준비: 来週/翌週·조사·요일 축약은 같은 공개 의미의 정상 대조다.
        val original = cases.single { it.string("id") == "FT03" }
        val normalExpressions = listOf("来週月曜", "来週月曜日", "来週の月曜", "来週の月曜日",
            "翌週月曜", "翌週月曜日", "翌週の月曜", "翌週の月曜日")
        normalExpressions.forEach { actual ->
            val row = JsonObject(original + ("answer" to JsonPrimitive(original.string("answer").replace("翌週月曜", actual))))
            val plan = plan(row)
            val validShape = represented(row)
            val facts = validShape.getValue("facts").jsonObject
            val verifiedShape = JsonObject(validShape + ("facts" to JsonObject(facts + ("result_day" to buildJsonObject {
                put("sourceId", "S1.Q2"); put("valueToken", actual); put("slotRelation", "MATCHED_EVENT")
            }))))

            // 실행·검증: 경계 방어가 실제 정상 token과 원래 今週金曜 제출 마감의 연결을 손상하지 않는다.
            assertTrue(matches(CuratedWritingFeedbackV13Policy.verificationSchema(plan), verifiedShape), actual)
            val checked = CuratedWritingFeedbackV13Policy.validateVerification(verifiedShape, plan)
            val result = CuratedWritingFeedbackV13Policy.assemble(CuratedWritingFeedbackV13TestPayload.content(), plan, checked)
            assertTrue(result.getValue("observations").jsonArray.all { it.jsonObject.string("status") == "MET" }, actual)
        }
    }

    @Test
    fun `실제 G05 권한 혼합 compose repair와 검증 근거 원본을 별도 자원에 보존한다`() {
        // 준비: 첫 실제 실패 세 응답 자원은 그대로 두고 두 번째 실행의 다른 실패를 별도로 고정한다.
        val document = authorityBytes.toString(Charsets.UTF_8).replace("\r\n", "\n")
        assertEquals("9a454a46b84a76ff6714952c7410db9e8b26fb1aece1c506263404b8b11b819c", sha(document.toByteArray(Charsets.UTF_8)))

        // 실행·검증: 문서 개행을 제외한 실제 synthetic answer·각 단계 payload의 bytes hash를 그대로 확인한다.
        assertEquals(authorityCase.string("answerSha256"), sha(authorityCase.string("answer").toByteArray(Charsets.UTF_8)))
        for (stage in listOf("evidence", "compose", "repair")) {
            val value = authorityCase.getValue("stages").jsonObject.getValue(stage).jsonObject
            val provenance = value.getValue("provenance").jsonObject
            assertEquals("gpt-5-mini-2025-08-07", provenance.string("model"))
            assertEquals(provenance.string("outputTextSha256"), sha(value.string("outputText").toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun `실제 두 권한 혼합 출력은 엄격 전체 검증과 새 schema에서 계속 거절한다`() {
        // 준비: 일본어 patch가 맞더라도 T4와 P2를 같은 종류의 교정 권한에 섞을 수 없다.
        val plan = plan(authorityCase)
        val verified = CuratedWritingFeedbackV13Policy.validateVerification(authorityOutput("evidence"), plan)
        val compose = authorityOutput("compose")
        val repair = authorityOutput("repair")
        for ((payload, field) in listOf(compose to "necessaryCorrections", repair to "additionalNecessaryCorrections")) {
            val correction = payload.getValue(field).jsonArray.single().jsonObject
            assertEquals("TASK_FACT_CONFLICT", correction.string("issueType"))
            assertEquals("T4", correction.string("sourceFactId"))
            assertEquals("P2", correction.string("sourceAuthorityId"))
        }

        // 실행·검증: 오류를 사후 정리하지 않고 원본 compose와 한 번의 원본 repair를 모두 거절한다.
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.assemble(compose, plan, verified) }
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.assembleLocalRepair(repair, plan, verified, compose) }
        assertFalse(matches(CuratedWritingFeedbackV13Policy.compositionSchema(plan, verified), compose))
        assertFalse(matches(CuratedWritingFeedbackV13Policy.localRepairSchema(plan, verified, compose), repair))
    }

    @Test
    fun `test 전용 빈 조건 권한 copy는 같은 patch와 고정 의미를 바꾸지 않고 저장 계약을 통과한다`() {
        // 준비: 원본은 고치지 않고 test 안에서 잘못 섞은 P 권한 필드 하나만 비운 copy를 만든다.
        val plan = plan(authorityCase)
        val evidence = authorityOutput("evidence")
        val verified = CuratedWritingFeedbackV13Policy.validateVerification(evidence, plan)
        val rawCompose = authorityOutput("compose")
        val rawRepair = authorityOutput("repair")
        val compose = emptyConditionAuthority(rawCompose, "necessaryCorrections")
        val repair = emptyConditionAuthority(rawRepair, "additionalNecessaryCorrections")

        // 실행: frozen evidence는 그대로 두고 서로 다른 두 실제 payload의 최소 표현 정정 copy를 검사한다.
        assertTrue(matches(CuratedWritingFeedbackV13Policy.compositionSchema(plan, verified), compose))
        assertTrue(matches(CuratedWritingFeedbackV13Policy.localRepairSchema(plan, verified, rawCompose), repair))
        val composed = CuratedWritingFeedbackV13Policy.assemble(compose, plan, verified)
        val repaired = CuratedWritingFeedbackV13Policy.assembleLocalRepair(repair, plan, verified, rawCompose)

        // 검증: 원래 학습자 답안은 C4 UNMET이고 참석 PRESENT·귀가 CONFLICT를 그대로 보존한다.
        assertEquals(evidence, verified)
        assertEquals(composed.getValue("observations"), repaired.getValue("observations"))
        val c4 = composed.getValue("observations").jsonArray.single { it.jsonObject.string("requirementId") == "C4" }.jsonObject
        assertEquals("UNMET", c4.string("status"))
        assertEquals(listOf("PRESENT", "CONFLICT"), c4.getValue("requirementParts").jsonArray.map { it.jsonObject.string("status") })
        for ((raw, result, field) in listOf(Triple(rawCompose, composed, "necessaryCorrections"),
            Triple(rawRepair, repaired, "additionalNecessaryCorrections"))) {
            val originalCorrection = raw.getValue(field).jsonArray.single().jsonObject
            val accepted = result.getValue("necessaryCorrections").jsonArray.single().jsonObject
            assertEquals(originalCorrection.getValue("patch"), accepted.getValue("patch"))
            assertEquals("", accepted.string("sourceAuthorityId"))
            assertEquals("完成した植木鉢は家に持ち帰りました。", accepted.string("replacement"))
        }
    }

    @Test
    fun `문법 사실 조건 교정의 schema는 상호 배타적인 권한과 이유를 요구한다`() {
        // 준비: 실제 G05의 고정된 귀가 CONFLICT에는 T4/P2만 있고 문법 오류 finding은 없다.
        val plan = plan(authorityCase)
        val verified = CuratedWritingFeedbackV13Policy.validateVerification(authorityOutput("evidence"), plan)
        val item = CuratedWritingFeedbackV13Policy.compositionSchema(plan, verified).getValue("properties").jsonObject
            .getValue("necessaryCorrections").jsonObject.getValue("items").jsonObject
        assertEquals(2, item.getValue("anyOf").jsonArray.size)
        val base = emptyConditionAuthority(authorityOutput("compose"), "necessaryCorrections")
            .getValue("necessaryCorrections").jsonArray.single().jsonObject
        fun row(issue: String, fact: String, condition: String, reason: String) = JsonObject(base + mapOf(
            "issueType" to JsonPrimitive(issue), "sourceFactId" to JsonPrimitive(fact),
            "sourceAuthorityId" to JsonPrimitive(condition), "formReason" to JsonPrimitive(reason),
        ))

        // 실행·검증: 실제 충돌의 두 권한만 허용하며 없는 문법 finding과 혼합·빈 권한·다른 namespace를 거절한다.
        assertFalse(matches(item, row("LANGUAGE_FORM", "", "", "INFLECTION")))
        assertTrue(matches(item, row("TASK_FACT_CONFLICT", "T4", "", "NONE")))
        assertTrue(matches(item, row("TASK_CONDITION_CONFLICT", "", "P2", "NONE")))
        val invalid = listOf(
            row("LANGUAGE_FORM", "T4", "", "INFLECTION"),
            row("LANGUAGE_FORM", "", "P2", "SPELLING"),
            row("LANGUAGE_FORM", "", "", "NONE"),
            row("TASK_FACT_CONFLICT", "T4", "P2", "NONE"),
            row("TASK_FACT_CONFLICT", "", "", "NONE"),
            row("TASK_FACT_CONFLICT", "P2", "", "NONE"),
            row("TASK_FACT_CONFLICT", "T4", "", "INFLECTION"),
            row("TASK_CONDITION_CONFLICT", "T4", "P2", "NONE"),
            row("TASK_CONDITION_CONFLICT", "", "", "NONE"),
            row("TASK_CONDITION_CONFLICT", "", "T4", "NONE"),
            row("TASK_CONDITION_CONFLICT", "", "P2", "SYNTAX"),
            row("TASK_FACT_CONFLICT", "T1", "", "NONE"),
            row("TASK_CONDITION_CONFLICT", "", "P1", "NONE"),
            JsonObject(base + ("requirementId" to JsonPrimitive("C2"))),
            JsonObject(base + ("segmentId" to JsonPrimitive("S2"))),
        )
        invalid.forEach { assertFalse(matches(item, it), it.toString()) }
    }

    @Test
    fun `실제 문법 finding이 있는 원문 위치와 정확한 이유에서만 LF schema를 허용한다`() {
        // 준비: 정상 문체가 아니라 실제 잘못된 활용과 독립 finding이 있는 답안이다.
        val plan = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-free-b3-01",
            "明日の午後に商品を取り置きしていただけるますか。仕事が終わってから伺いたいためです。")
        val finding = buildJsonObject {
            put("segmentId", "S1"); put("quote", "取り置きしていただけるますか")
            put("errorCore", "いただけるます"); put("formReason", "INFLECTION")
        }
        val verified = JsonObject(CuratedWritingFeedbackV13TestPayload.verified(plan) +
            (CuratedWritingLanguageFormEvidence.key to JsonArray(listOf(finding))))
        val correction = CuratedWritingFeedbackV13TestPayload.correction("C1", "S1", "いただけるます", "いただけます", "",
            "LANGUAGE_FORM", "INFLECTION")
        val content = CuratedWritingFeedbackV13TestPayload.content(listOf(correction))
        val schema = CuratedWritingFeedbackV13Policy.compositionSchema(plan, verified)

        // 실행·검증: finding에 맞는 실제 교정은 통과하고 다른 위치·이유·임의 T/P 권한은 거절한다.
        assertTrue(matches(schema, content))
        val accepted = CuratedWritingFeedbackV13Policy.assemble(content, plan, verified)
        assertEquals(1, accepted.getValue("necessaryCorrections").jsonArray.size)
        val invalidRows = listOf(
            JsonObject(correction + ("segmentId" to JsonPrimitive("S2"))),
            JsonObject(correction + ("formReason" to JsonPrimitive("SPELLING"))),
            JsonObject(correction + ("sourceFactId" to JsonPrimitive("T1"))),
            JsonObject(correction + ("sourceAuthorityId" to JsonPrimitive("P1"))),
        )
        invalidRows.forEach { invalid ->
            assertFalse(matches(schema, CuratedWritingFeedbackV13TestPayload.content(listOf(invalid))))
        }
    }

    @Test
    fun `실제 누락 권한 발명 원본을 보존하고 원래 검증과 frozen schema에서 거절한다`() {
        // 준비: S3의 실제 G04 귀가 누락을 다른 문장에 발명 삽입한 compose/repair 원본이다.
        val document = missingAuthorityBytes.toString(Charsets.UTF_8).replace("\r\n", "\n")
        assertEquals("492a1c77355468b07757df8b632fa1f41ee46445f121b31c3286b9c88974d0be", sha(document.toByteArray(Charsets.UTF_8)))
        assertEquals(missingAuthorityCase.string("answerSha256"), sha(missingAuthorityCase.string("answer").toByteArray(Charsets.UTF_8)))
        for (stage in listOf("evidence", "compose", "repair")) {
            val value = missingAuthorityCase.getValue("stages").jsonObject.getValue(stage).jsonObject
            assertEquals(value.getValue("provenance").jsonObject.string("outputTextSha256"),
                sha(value.string("outputText").toByteArray(Charsets.UTF_8)))
        }
        val plan = plan(missingAuthorityCase)
        val evidence = missingAuthorityOutput("evidence")
        val verified = CuratedWritingFeedbackV13Policy.validateVerification(evidence, plan)
        val compose = missingAuthorityOutput("compose")
        val repair = missingAuthorityOutput("repair")

        // 실행·검증: ABSENT를 교정 권한으로 바꾸거나 누락 학습자 사건을 삽입해 성공시키지 않는다.
        assertEquals("ABSENT", evidence.getValue("atoms").jsonObject.getValue("C4.A2").jsonObject.string("state"))
        assertTrue(evidence.getValue(CuratedWritingLanguageFormEvidence.key).jsonArray.isEmpty())
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.assemble(compose, plan, verified) }
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.assembleLocalRepair(repair, plan, verified, compose) }
        val schema = CuratedWritingFeedbackV13Policy.compositionSchema(plan, verified)
        assertFalse(matches(schema, compose))
        assertFalse(matches(CuratedWritingFeedbackV13Policy.localRepairSchema(plan, verified, compose), repair))
        assertEquals(0, schema.getValue("properties").jsonObject.getValue("necessaryCorrections").jsonObject
            .getValue("maxItems").jsonPrimitive.int)
        val result = CuratedWritingFeedbackV13Policy.assemble(CuratedWritingFeedbackV13TestPayload.content(), plan, verified)
        assertEquals(evidence, verified)
        val c4 = result.getValue("observations").jsonArray.single { it.jsonObject.string("requirementId") == "C4" }.jsonObject
        assertEquals("UNMET", c4.string("status"))
        assertEquals(listOf("PRESENT", "ABSENT"), c4.getValue("requirementParts").jsonArray.map { it.jsonObject.string("status") })
    }

    @Test
    fun `정상 MET에도 없는 필수 교정을 만들지 않고 의미 보존 선택 표현은 유지한다`() {
        // 준비: 정상 번역에는 충돌·문법 finding이 없으며 G04에는 소감 부분의 정상 선택 표현이 가능하다.
        val normalRow = cases.single { it.string("id") == "FT03" }
        val normalPlan = plan(normalRow)
        val normalVerified = CuratedWritingFeedbackV13Policy.validateVerification(represented(normalRow), normalPlan)
        val normalSchema = CuratedWritingFeedbackV13Policy.compositionSchema(normalPlan, normalVerified)
        val invented = CuratedWritingFeedbackV13TestPayload.correction("C1", "S1", "午後5時", "午後6時", "T1")
        assertEquals(0, normalSchema.getValue("properties").jsonObject.getValue("necessaryCorrections").jsonObject
            .getValue("maxItems").jsonPrimitive.int)
        assertFalse(matches(normalSchema, CuratedWritingFeedbackV13TestPayload.content(listOf(invented))))
        assertTrue(matches(normalSchema, CuratedWritingFeedbackV13TestPayload.content()))
        assertTrue(CuratedWritingFeedbackV13Policy.assemble(CuratedWritingFeedbackV13TestPayload.content(), normalPlan, normalVerified)
            .getValue("observations").jsonArray.all { it.jsonObject.string("status") == "MET" })

        // 실행: 필요한 교정이 없는 답안에서도 실제 소감·희망의 인과관계를 보존하는 선택 표현을 제안한다.
        val plan = plan(missingAuthorityCase)
        val verified = CuratedWritingFeedbackV13Policy.validateVerification(missingAuthorityOutput("evidence"), plan)
        val content = buildJsonObject {
            put("necessaryCorrections", JsonArray(emptyList()))
            put("optionalAlternatives", JsonArray(listOf(buildJsonObject {
                put("requirementId", "C5"); put("suggestionKind", "EXPRESSION_ONLY"); put("segmentId", "S3")
                put("patch", buildJsonObject { put("original", "楽しかったので"); put("replacement", "楽しかったため") })
                put("relationChecks", buildJsonObject {
                    listOf("causality", "chronology", "specificity", "stance").forEach { put(it, "PRESERVED") }
                })
                put("learningText", "理由を示す「ので」を「ため」に替えた、やや改まった表現です。")
            })))
            put("uncertainties", JsonArray(emptyList()))
        }

        // 검증: optional 배열을 닫지 않고 정상 표현을 저장하며 귀가 누락은 그대로 남긴다.
        assertTrue(matches(CuratedWritingFeedbackV13Policy.compositionSchema(plan, verified), content))
        val result = CuratedWritingFeedbackV13Policy.assemble(content, plan, verified)
        assertEquals(1, result.getValue("optionalAlternatives").jsonArray.size)
        assertEquals("楽しかったため、また参加したいです。", result.getValue("optionalAlternatives").jsonArray.single().jsonObject.string("replacement"))
        assertEquals("UNMET", result.getValue("observations").jsonArray.single { it.jsonObject.string("requirementId") == "C4" }.jsonObject.string("status"))
    }

    private fun authorityOutput(stage: String): JsonObject = Json.parseToJsonElement(
        authorityCase.getValue("stages").jsonObject.getValue(stage).jsonObject.string("outputText"),
    ).jsonObject

    private fun missingAuthorityOutput(stage: String): JsonObject = Json.parseToJsonElement(
        missingAuthorityCase.getValue("stages").jsonObject.getValue(stage).jsonObject.string("outputText"),
    ).jsonObject

    private fun emptyConditionAuthority(payload: JsonObject, field: String): JsonObject = JsonObject(payload +
        (field to JsonArray(payload.getValue(field).jsonArray.map { raw ->
            JsonObject(raw.jsonObject + ("sourceAuthorityId" to JsonPrimitive("")))
        })))

    private fun plan(row: JsonObject): CuratedWritingFeedbackV13Policy.Plan {
        val context = CuratedWritingFeedbackPolicy.Context(WritingType.valueOf(row.string("writingType")),
            row.getValue("public").jsonObject, row.string("originLanguage"), row.string("learningLanguage"))
        val requirements = CuratedWritingFeedbackPolicy.requirements(row.getValue("checklist").jsonArray)
        return checkNotNull(CuratedWritingFeedbackV13Policy.prepare(context, requirements,
            CuratedWritingFeedbackPolicy.segments(row.string("answer"))))
    }

    private fun output(row: JsonObject) = Json.parseToJsonElement(row.string("outputText")).jsonObject

    private fun represented(row: JsonObject): JsonObject {
        val original = output(row)
        return when (row.string("id")) {
            "G04" -> {
                val atoms = original.getValue("atoms").jsonObject
                JsonObject(original + ("atoms" to JsonObject(atoms + ("C4.A2" to
                    JsonObject(atoms.getValue("C4.A2").jsonObject + ("semanticReading" to
                        JsonPrimitive("The answer does not express taking the completed plant pot home.")))))))
            }
            "FT03" -> {
                val facts = original.getValue("facts").jsonObject
                JsonObject(original + ("facts" to JsonObject(facts + ("result_day" to buildJsonObject {
                    put("sourceId", "S1.Q2"); put("valueToken", "翌週月曜"); put("slotRelation", "MATCHED_EVENT")
                }))))
            }
            "G05" -> JsonObject(original + ("atoms" to JsonObject(original.getValue("atoms").jsonObject.mapValues { (_, raw) ->
                trueSupportFlags(raw.jsonObject)
            })))
            else -> error("unknown regression fixture")
        }
    }

    private fun trueSupportFlags(edge: JsonObject): JsonObject {
        if ("alternatives" in edge) return JsonObject(edge + ("alternatives" to JsonObject(
            edge.getValue("alternatives").jsonObject.mapValues { (_, value) -> trueSupportFlags(value.jsonObject) },
        )))
        return if (edge.string("state") == "SUPPORT") JsonObject(edge +
            listOf("actorMatches", "actionMatches", "targetMatches", "conditionMatches").associateWith { JsonPrimitive(true) }) else edge
    }

    private fun meaningSignature(value: JsonObject): JsonObject {
        fun signature(raw: JsonElement): JsonObject {
            val edge = raw.jsonObject
            return if ("alternatives" in edge) JsonObject(edge.getValue("alternatives").jsonObject.mapValues { signature(it.value) })
            else JsonObject(edge.filterKeys { it in setOf("state", "sourceIds", "recipient", "kind", "modality") })
        }
        return JsonObject(value.getValue("atoms").jsonObject.mapValues { signature(it.value) })
    }

    private fun branch(edge: JsonObject, state: String): JsonObject = edge.getValue("anyOf").jsonArray.single { raw ->
        raw.jsonObject.getValue("properties").jsonObject.getValue("state").jsonObject.getValue("enum").jsonArray
            .any { it.jsonPrimitive.content == state }
    }.jsonObject

    /** 과제 의미를 구현하지 않는 JSON-schema subset 검사다. enum/type/범위/패턴과 객체 구조만 대조한다. */
    private fun matches(schema: JsonObject, value: JsonElement): Boolean {
        if (schema["anyOf"]?.jsonArray?.none { matches(it.jsonObject, value) } == true) return false
        if (schema["enum"]?.jsonArray?.contains(value) == false) return false
        val type = schema["type"]?.jsonPrimitive?.content
        return when (type) {
            null -> true
            "object" -> {
                val objectValue = value as? JsonObject ?: return false
                val fields = schema.getValue("properties").jsonObject
                val required = schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                required.all { it in objectValue } &&
                    (schema["additionalProperties"] != JsonPrimitive(false) || fields.keys.containsAll(objectValue.keys)) &&
                    objectValue.all { (key, child) -> fields[key]?.let { matches(it.jsonObject, child) } ?: true }
            }
            "array" -> {
                val array = value as? JsonArray ?: return false
                array.size >= (schema["minItems"]?.jsonPrimitive?.int ?: 0) &&
                    array.size <= (schema["maxItems"]?.jsonPrimitive?.int ?: Int.MAX_VALUE) &&
                    array.all { matches(schema.getValue("items").jsonObject, it) }
            }
            "string" -> {
                val primitive = value as? JsonPrimitive ?: return false
                primitive.isString && primitive.content.length >= (schema["minLength"]?.jsonPrimitive?.int ?: 0) &&
                    primitive.content.length <= (schema["maxLength"]?.jsonPrimitive?.int ?: Int.MAX_VALUE) &&
                    (schema["pattern"]?.jsonPrimitive?.content?.let { Regex(it).containsMatchIn(primitive.content) } ?: true)
            }
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            else -> error("Unsupported schema type in regression test: $type")
        }
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
