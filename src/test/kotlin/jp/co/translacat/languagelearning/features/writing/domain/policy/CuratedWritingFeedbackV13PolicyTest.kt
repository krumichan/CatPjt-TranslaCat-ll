package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingFeedbackV13PolicyTest {
    @Test
    fun `답안 source는 공백과 줄바꿈도 그대로 보존하되 빈 줄을 의미 근거로 선택할 수 없다`() {
        // 준비: 원문 인용은 재작성 없이 복원되어야 하며 빈 줄은 사건 근거가 아니다.
        val answer = "明日の午後、商品を取り置きしていただけますか。\n仕事の後に伺います。"
        val plan = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-free-b3-01", answer)

        // 실행·검증: 전체 원문은 byte 의미의 문자열을 보존하고 whitespace만인 source 선택은 거절한다.
        assertEquals(answer, plan.sources.joinToString("") { it.text })
        val blank = plan.sources.first { it.text.isBlank() }
        val invalid = CuratedWritingFeedbackV13TestPayload.verified(plan)
            .atom("C1.A1", CuratedWritingFeedbackV13TestPayload.edge("SUPPORT", listOf(blank.id)))
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.validateVerification(invalid, plan) }
    }

    @Test
    fun `알려진 공개 과제만 준비하고 변경된 과제는 추측 적용하지 않는다`() {
        // 준비: 동일한 공개 과제와 다른 prompt를 구분한다.
        val plan = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-translation-b3-01", "今週の金曜日の午後5時までに申請書を提出すれば、来週の月曜日に結果をメールで受け取れます。")

        // 실행·검증: 근접 문구나 같은 ID만으로 계약을 재사용하지 않는다.
        assertNotNull(CuratedWritingFeedbackV13Policy.prepare(plan.context, plan.requirements, plan.segments))
        val changed = plan.context.copy(public = JsonObject(plan.context.public + ("originText" to JsonPrimitive("다른 과제"))))
        assertNull(CuratedWritingFeedbackV13Policy.prepare(changed, plan.requirements, plan.segments))
    }

    @Test
    fun `고정 친구 참석과 귀가 원자를 교정 생성 단계가 재분할할 수 없다`() {
        // 준비: 참석은 표현했고 귀가는 쓰지 않은 독립 근거를 만든다.
        val plan = CuratedWritingFeedbackV13TestPayload.workshop("先週の土曜日の午後、図書館で友達と参加しました。リサイクル材料で小さな植木鉢を作り、講師に準備と手順を教えてもらいました。楽しかったです。")
        val verified = CuratedWritingFeedbackV13TestPayload.verified(plan)
            .atom("C4.A2", CuratedWritingFeedbackV13TestPayload.edge("ABSENT", emptyList()))
            .alternative("C5.A1", "V2", CuratedWritingFeedbackV13TestPayload.edge("ABSENT", emptyList()))

        // 실행: 모델에는 교정 배열만 허용하고 source parts는 서버가 조립한다.
        val checked = CuratedWritingFeedbackV13Policy.assemble(CuratedWritingFeedbackV13TestPayload.content(), plan, verified)
        val row = checked.rows().getValue("C4")
        val parts = row.getValue("requirementParts").jsonArray

        // 검증: 친구+참가가 한 완전한 부분이고, 귀가 누락과 OR 소감의 충족이 서로 독립이다.
        assertEquals("UNMET", row.string("status"))
        assertEquals(listOf("친구와 참가했고 ", "완성한 화분을 집에 가져갔다는 내용을 포함할 것"), parts.map { it.jsonObject.string("requirementQuote") })
        assertEquals(listOf("PRESENT", "ABSENT"), parts.map { it.jsonObject.string("status") })
        assertEquals("MET", checked.rows().getValue("C5").string("status"))
        assertEquals(plan.contract.sha256(), checked.string("meaningContractHash"))
        assertFalse(CuratedWritingFeedbackV13Policy.compositionSchema(plan, verified).toString().contains("unitAssignments"))
    }

    @Test
    fun `근거는 서버 ID로 복원하고 비연속 답안도 원문을 새로 인용하지 않는다`() {
        // 준비: 여러 문장과 쉼표에 걸친 실제 문자열을 선택한다.
        val plan = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-free-b3-01", "明日の午後に伺います。仕事が終わるためです。商品を、取り置きしていただけますか。")
        val extracted = CuratedWritingFeedbackV13TestPayload.extracted(plan)

        // 실행·검증: 잘못된 ID나 재작성 quote 필드를 추가한 응답은 거절한다.
        CuratedWritingFeedbackV13Policy.validateExtraction(extracted, plan)
        val original = extracted.getValue("observations").jsonArray.first().jsonObject
        val forged = JsonObject(original + ("quote" to JsonPrimitive("상품을 보관해 달라고 요청했다")))
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV13Policy.validateExtraction(buildJsonObject { put("observations", JsonArray(listOf(forged))) }, plan)
        }
        val invalid = CuratedWritingFeedbackV13TestPayload.verified(plan)
            .atom("C1.A1", CuratedWritingFeedbackV13TestPayload.edge("SUPPORT", listOf("S999.Q1")))
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.validateVerification(invalid, plan) }
    }

    @Test
    fun `당사자 요청은 허용하지만 평가자 지시나 행위 불일치를 충족 근거로 삼지 못한다`() {
        // 준비: 실제 요청과 자기 계획은 독립 checker에서 구별해야 할 서로 다른 관계다.
        val plan = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-free-b3-01", "明日の午後まで商品を取り置きしていただけますか。仕事の後に伺いたいためです。")
        val good = CuratedWritingFeedbackV13TestPayload.verified(plan)
        val source = plan.sources.map { it.id }
        val evaluator = CuratedWritingFeedbackV13TestPayload.edge("SUPPORT", source, recipient = "EVALUATOR", kind = "INSTRUCTION")
        val selfPlan = CuratedWritingFeedbackV13TestPayload.edge("SUPPORT", source, recipient = "SELF")
        val wrongAction = JsonObject(CuratedWritingFeedbackV13TestPayload.edge("SUPPORT", source) + ("actionMatches" to JsonPrimitive(false)))

        // 실행·검증: 정상 참가자 요청은 검증되고 지시·행위가 맞지 않는 SUPPORT는 거절한다.
        CuratedWritingFeedbackV13Policy.validateVerification(good, plan)
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.validateVerification(good.atom("C1.A1", evaluator), plan) }
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.validateVerification(good.atom("C1.A1", selfPlan), plan) }
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.validateVerification(good.atom("C3.A1", selfPlan), plan) }
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.validateVerification(good.atom("C1.A1", wrongAction), plan) }
        val absent = good.atom("C1.A1", CuratedWritingFeedbackV13TestPayload.edge("ABSENT", emptyList()))
            .atom("C3.A1", CuratedWritingFeedbackV13TestPayload.edge("ABSENT", emptyList()))
        assertEquals("UNMET", CuratedWritingFeedbackV13Policy.assemble(CuratedWritingFeedbackV13TestPayload.content(), plan, absent).rows().getValue("C1").string("status"))
    }

    @Test
    fun `변경 문의의 오전은 고정 오후와 서버 대조하고 틀린 시각 교정도 거절한다`() {
        // 준비: 모델의 SUPPORT 자체와 무관하게 변경 사건의 명시 값은 오전이다.
        val plan = CuratedWritingFeedbackV13TestPayload.clinic()
        val verified = clinicVerification(plan)
        val correct = CuratedWritingFeedbackV13TestPayload.correction("C2", "S2", "午前", "午後", "T1")

        // 실행·검증: 누락 교정과 정오로의 틀린 교정은 실패하고 실제 오후 교정만 저장한다.
        val selfInquiry = JsonObject(verified.getValue("atoms").jsonObject.getValue("C2.A1").jsonObject +
            ("recipient" to JsonPrimitive("SELF")))
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV13Policy.validateVerification(verified.atom("C2.A1", selfInquiry), plan)
        }
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.assemble(CuratedWritingFeedbackV13TestPayload.content(), plan, verified) }
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV13Policy.assemble(CuratedWritingFeedbackV13TestPayload.content(listOf(JsonObject(correct + ("patch" to buildJsonObject { put("original", "午前"); put("replacement", "正午") })))), plan, verified)
        }
        val result = CuratedWritingFeedbackV13Policy.assemble(CuratedWritingFeedbackV13TestPayload.content(listOf(correct)), plan, verified)
        assertEquals("UNMET", result.rows().getValue("C2").string("status"))
        assertEquals("CONFLICT", result.rows().getValue("C2").getValue("requirementParts").jsonArray.single().jsonObject.string("status"))
        assertEquals("MET", result.rows().getValue("C1").string("status"))
    }

    @Test
    fun `명시 오전을 implicit으로 숨기거나 원래 시각 근거와 바꾸면 거절한다`() {
        // 준비: 원래 예약 오전과 변경문의 오전은 별개의 source다.
        val plan = CuratedWritingFeedbackV13TestPayload.clinic()
        val good = clinicVerification(plan)
        val requested = plan.sources.first { it.segmentId == "S2" }.id
        val original = plan.sources.first { it.segmentId == "S1" && it.text.contains("午前") }.id

        // 실행·검증: 관련 atom의 명시 값 은폐와 다른 사건에서 고른 토큰 연결을 모두 거절한다.
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV13Policy.validateVerification(good.fact("requested_new_time", requested, "NONE", "IMPLICIT_CONTEXT"), plan)
        }
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV13Policy.validateVerification(good.fact("requested_new_time", original, "午前"), plan)
        }
    }

    @Test
    fun `마감 충돌은 같은 조건에도 전파하고 단일 국소 교정으로 두 요구를 수리한다`() {
        // 준비: 같은 18시 마감이 제출 조건에도 걸린다.
        val plan = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-translation-b3-01", "今週の金曜日の18時までに申請書を提出すれば、来週の月曜日に結果をメールで受け取れます。")
        val verified = CuratedWritingFeedbackV13TestPayload.verified(plan)
        val patch = CuratedWritingFeedbackV13TestPayload.correction("C1", "S1", "18時", "17時", "T1")

        // 실행: 重複 교정 없이 원래 답안 판정과 교정은 별도로 보존한다.
        val result = CuratedWritingFeedbackV13Policy.assemble(CuratedWritingFeedbackV13TestPayload.content(listOf(patch)), plan, verified)

        // 검증: C1/C2 모두 원래 오류 상태이며, 저장 교정은 하나이고 C3 이메일은 정상이다.
        assertEquals(listOf("UNMET", "UNMET", "MET"), result.getValue("observations").jsonArray.map { it.jsonObject.string("status") })
        assertEquals(1, result.getValue("necessaryCorrections").jsonArray.size)
    }

    @Test
    fun `긴 시각 안의 짧은 숫자 토큰으로 원문 값을 바꿔 읽지 못한다`() {
        // 준비: 오후5시와 18시의 가장 긴 명시 표현만 값을 갖는다.
        val plans = listOf("午後5時" to "5時", "18時" to "8時").map { (actual, forged) ->
            CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-translation-b3-01", "今週の金曜日の${actual}までに申請書を提出すれば、来週の月曜日に結果をメールで受け取れます。") to forged
        }

        // 실행·검증: 모델이 부분 숫자 enum을 골라도 같은 문자열이 있다는 이유로 수용하지 않는다.
        plans.forEach { (plan, forged) ->
            val good = CuratedWritingFeedbackV13TestPayload.verified(plan)
            val source = plan.sources.first().id
            CuratedWritingFeedbackV13Policy.validateVerification(good, plan)
            assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.validateVerification(good.fact("deadline_time", source, forged), plan) }
        }
    }

    @Test
    fun `숫자 경계나 정시 뒤 분 단위를 잘라 다른 값으로 정규화하지 않는다`() {
        // 준비: 유효한 사전 값 17시를 더 긴 명시 시각 내부에서 억지 선택한다.
        val plans = listOf("117時", "17時30分", "17時半").map { actual ->
            CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-translation-b3-01", "今週の金曜日の${actual}までに申請書を提出すれば、来週の月曜日に結果をメールで受け取れます。")
        }

        // 실행·검증: 문자열 포함만으로 정시라는 서버 판정을 만들지 않는다.
        plans.forEach { plan ->
            val forged = CuratedWritingFeedbackV13TestPayload.verified(plan).fact("deadline_time", plan.sources.first().id, "17時")
            assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.validateVerification(forged, plan) }
        }
    }

    @Test
    fun `실제 문법 오류 양성과 잘못된 필수 교정 음성을 모두 유지한다`() {
        // 준비: 같은 atom 의미와 별개로 실제 활용 오류 위치만 독립 근거로 둔다.
        val answer = "明日の午後に商品を取り置きしていただけるますか。仕事が終わってから伺いたいためです。"
        val plan = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-free-b3-01", answer)
        val form = buildJsonObject { put("segmentId", "S1"); put("quote", "取り置きしていただけるますか"); put("errorCore", "いただけるます"); put("formReason", "INFLECTION") }
        val verified = JsonObject(CuratedWritingFeedbackV13TestPayload.verified(plan) + (CuratedWritingLanguageFormEvidence.key to JsonArray(listOf(form))))
        val grammar = CuratedWritingFeedbackV13TestPayload.correction("C1", "S1", "いただけるます", "いただけます", "", "LANGUAGE_FORM", "INFLECTION")

        // 실행·검증: 필요한 오류의 교정을 지우지 못하고 근거 없는 추가 교정도 거절한다.
        val good = CuratedWritingFeedbackV13Policy.assemble(CuratedWritingFeedbackV13TestPayload.content(listOf(grammar)), plan, verified)
        assertEquals(1, good.getValue("necessaryCorrections").jsonArray.size)
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.assemble(CuratedWritingFeedbackV13TestPayload.content(), plan, verified) }
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV13Policy.assemble(CuratedWritingFeedbackV13TestPayload.content(listOf(grammar)), plan, CuratedWritingFeedbackV13TestPayload.verified(plan))
        }
    }

    @Test
    fun `국소 복구는 정상 교정을 보존하고 무효 행만 제거 또는 교체한다`() {
        // 준비: 실제 시간 충돌 교정과 근거 없는 문법 선호가 함께 있다.
        val plan = CuratedWritingFeedbackV13TestPayload.clinic()
        val verified = clinicVerification(plan)
        val correct = CuratedWritingFeedbackV13TestPayload.correction("C2", "S2", "午前", "午後", "T1")
        val falseGrammar = CuratedWritingFeedbackV13TestPayload.correction("C3", "S3", "予定です", "つもりです", "", "LANGUAGE_FORM", "INFLECTION")
        val draft = CuratedWritingFeedbackV13TestPayload.content(listOf(correct, falseGrammar))
        val additions = buildJsonObject {
            put("additionalNecessaryCorrections", JsonArray(emptyList()))
            put("additionalOptionalAlternatives", JsonArray(emptyList()))
            put("additionalUncertainties", JsonArray(emptyList()))
        }

        // 실행·검증: 正常 patch는 바이트 그대로 남으며 재전송 중복이나 근거 변경 키는 실패한다.
        val repaired = CuratedWritingFeedbackV13Policy.assembleLocalRepair(additions, plan, verified, draft)
        assertEquals(correct.getValue("patch"), repaired.getValue("necessaryCorrections").jsonArray.single().jsonObject.getValue("patch"))
        assertEquals("UNMET", repaired.rows().getValue("C2").string("status"))
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV13Policy.assembleLocalRepair(JsonObject(additions + ("additionalNecessaryCorrections" to JsonArray(listOf(correct)))), plan, verified, draft)
        }
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.assembleLocalRepair(JsonObject(additions + ("atoms" to JsonObject(emptyMap()))), plan, verified, draft) }
    }

    @Test
    fun `권한 형식만 맞고 사실값을 잘못 고친 행은 국소 복구에서 고정하지 않는다`() {
        // 준비: 오전을 오후가 아닌 정오로 바꾼 교정은 원문과 권한 형식만 유효하다.
        val plan = CuratedWritingFeedbackV13TestPayload.clinic()
        val verified = clinicVerification(plan)
        val wrong = CuratedWritingFeedbackV13TestPayload.correction("C2", "S2", "午前", "正午", "T1")
        val correct = CuratedWritingFeedbackV13TestPayload.correction("C2", "S2", "午前", "午後", "T1")
        val draft = CuratedWritingFeedbackV13TestPayload.content(listOf(wrong))
        val additions = buildJsonObject {
            put("additionalNecessaryCorrections", JsonArray(listOf(correct)))
            put("additionalOptionalAlternatives", JsonArray(emptyList())); put("additionalUncertainties", JsonArray(emptyList()))
        }

        // 실행·검증: 잘못된 행만 교체하며 frozen 의미·근거와 원래 UNMET 상태는 유지한다.
        val result = CuratedWritingFeedbackV13Policy.assembleLocalRepair(additions, plan, verified, draft)
        assertEquals(correct.getValue("patch"), result.getValue("necessaryCorrections").jsonArray.single().jsonObject.getValue("patch"))
        assertEquals("UNMET", result.rows().getValue("C2").string("status"))
    }

    @Test
    fun `틀린 값과 정답을 대안으로 함께 남긴 교정은 완료로 보지 않는다`() {
        // 준비: 같은 변경 사건에서 오전을 남기거나 다른 틀린 시각을 새로 넣는다.
        val plan = CuratedWritingFeedbackV13TestPayload.clinic()
        val verified = clinicVerification(plan)
        val replacements = listOf("午前または午後", "午後または午前", "午後か朝")

        // 실행·검증: expected token을 추가하는 것만으로 충돌값을 고쳤다고 인정하지 않는다.
        replacements.forEach { replacement ->
            val patch = CuratedWritingFeedbackV13TestPayload.correction("C2", "S2", "午前", replacement, "T1")
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingFeedbackV13Policy.assemble(CuratedWritingFeedbackV13TestPayload.content(listOf(patch)), plan, verified)
            }
        }
    }

    @Test
    fun `같은 source의 다른 사건 시각을 보존하면서 변경 시각만 고칠 수 있다`() {
        // 준비: 원래 시각과 변경 대상이 같은 source 안에 있으므로 전체 source의 값 집합으로 막지 않는다.
        val original = CuratedWritingFeedbackV13TestPayload.clinic()
        val answer = "来週の月曜日の午前に病院の予約がありますが、授業と重なっています。午前の元の予約から午前の別の時間に変更できるか聞きました。変更が難しければ、元の予約時間に行く予定です。"
        val plan = assertNotNull(CuratedWritingFeedbackV13Policy.prepare(original.context, original.requirements,
            CuratedWritingFeedbackPolicy.segments(answer)))
        val verified = clinicVerification(plan)
        val patch = CuratedWritingFeedbackV13TestPayload.correction("C2", "S2", "午前の別の時間", "午後の別の時間", "T1")

        // 실행·검증: 실제 바뀐 대상만 오후이고 기존 예약의 오전은 그대로 저장한다.
        val result = CuratedWritingFeedbackV13Policy.assemble(CuratedWritingFeedbackV13TestPayload.content(listOf(patch)), plan, verified)
        assertEquals("午前の元の予約から午後の別の時間に変更できるか聞きました。",
            result.getValue("necessaryCorrections").jsonArray.single().jsonObject.string("replacement"))
        assertEquals("MET", result.rows().getValue("C1").string("status"))
    }

    @Test
    fun `명시 제목의 접두 부분을 다른 책의 제목과 같다고 정규화하지 않는다`() {
        // 준비: 정상 제목과 긴 다른 제목은 모두 실제 인용 표기를 사용한다.
        fun plan(title: String) = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-guided-b3-02",
            "借りている${title}の返却日は6月15日ですが、まだ読み終わっていません。6月22日まで延長できますか。可能でしたら返却日を6月22日に変更していただけますか。")
        val normal = plan("『星の旅』")
        val wrong = listOf(plan("『星の旅行』"), plan("「星の旅行」"))

        // 실행·검증: 정상 인용 제목은 허용하고 접두 token 또는 implicit 은폐는 거절한다.
        CuratedWritingFeedbackV13Policy.validateVerification(libraryVerification(normal), normal)
        wrong.forEach { value ->
            val forged = libraryVerification(value)
            assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV13Policy.validateVerification(forged, value) }
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingFeedbackV13Policy.validateVerification(forged.fact("book_title", value.sources.first().id, "NONE", "IMPLICIT_CONTEXT"), value)
            }
        }
    }

    @Test
    fun `같은 문장의 용어 인용을 책 제목으로 오인하지 않는다`() {
        // 준비: 과제는 제목의 인용 부호를 강제하지 않으며 별도의 용어 인용도 허용한다.
        val plan = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-guided-b3-02",
            "借りている星の旅について「返却期限」を確認しています。返却日は6月15日ですが、まだ読み終わっていません。6月22日まで延長できますか。可能でしたら返却日を6月22日に変更していただけますか。")

        // 실행·검증: 실제 제목 token은 유지하고 반환 기한 용어는 제목 비교에 끼워 넣지 않는다.
        CuratedWritingFeedbackV13Policy.validateVerification(libraryVerification(plan), plan)
    }

    private fun libraryVerification(plan: CuratedWritingFeedbackV13Policy.Plan): JsonObject {
        val sources = plan.sources
        return CuratedWritingFeedbackV13TestPayload.verified(plan)
            .fact("book_title", sources.first { it.text.contains("星の旅") }.id, "星の旅")
            .fact("current_due_date", sources.first { it.text.contains("6月15日") }.id, "6月15日")
            .fact("requested_due_date", sources.first { it.text.contains("6月22日") }.id, "6月22日")
    }

    private fun clinicVerification(plan: CuratedWritingFeedbackV13Policy.Plan): JsonObject {
        val all = CuratedWritingFeedbackV13TestPayload.verified(plan)
        return all.atom("C1.A1", CuratedWritingFeedbackV13TestPayload.edge("SUPPORT", plan.sources.filter { it.segmentId == "S1" }.map { it.id }))
            .atom("C2.A1", CuratedWritingFeedbackV13TestPayload.edge("SUPPORT", plan.sources.filter { it.segmentId == "S2" }.map { it.id }))
            .atom("C3.A1", CuratedWritingFeedbackV13TestPayload.edge("SUPPORT", plan.sources.filter { it.segmentId == "S3" }.map { it.id }))
            .fact("original_time", plan.sources.first { it.segmentId == "S1" && it.text.contains("午前") }.id, "午前")
            .fact("requested_new_time", plan.sources.first { it.segmentId == "S2" }.id, "午前")
    }

    private fun JsonObject.rows() = getValue("observations").jsonArray.associate { it.jsonObject.string("requirementId") to it.jsonObject }
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}

/** 합성 계약 검사 전용이다. 이 helper의 SUPPORT는 언어 의미의 정답 판정을 주장하지 않는다. */
internal object CuratedWritingFeedbackV13TestPayload {
    fun catalog(id: String, answer: String): CuratedWritingFeedbackV13Policy.Plan {
        val stream = checkNotNull(javaClass.getResourceAsStream("/writing/curated-candidates.json"))
        val manifest = stream.bufferedReader(Charsets.UTF_8).use { CuratedWritingManifestCodec.parse(it.readText()) }
        val item = manifest.items.single { it.id == id }
        return prepare(item.writingType, item.prompt, item.checklist, answer, item.providedFacts, item.requiredIntents, item.responseConstraints)
    }

    fun workshop(answer: String) = prepare(WritingType.GUIDED,
        "지역 도서관의 주말 공예 워크숍에 참가한 경험을 일본어로 한 문단으로 쓰세요. 아래의 사실과 목적을 빠짐없이 연결해 표현하세요.",
        listOf("지난 토요일 오후에 지역 도서관에서 열린 워크숍이라는 시간과 장소를 포함할 것", "재활용 재료로 작은 화분을 만들었다는 내용을 포함할 것",
            "진행자가 준비 방법과 만드는 순서를 설명했다는 내용을 포함할 것", "친구와 참가했고 완성한 화분을 집에 가져갔다는 내용을 포함할 것", "참가 소감이나 앞으로의 의향을 하나 이상 덧붙일 것"), answer,
        listOf("워크숍은 지난 토요일 오후에 지역 도서관에서 열렸다.", "참가자들은 재활용 재료로 작은 화분을 만들었다.", "진행자는 재료를 준비하는 방법과 만드는 순서를 설명했다.", "나는 친구와 함께 참가했고, 완성한 화분을 집에 가져갔다."),
        listOf("워크숍에서 무엇을 했는지 설명하기", "친구와 참가했다는 사실과 화분을 집에 가져갔다는 사실을 포함하기", "워크숍에 참가한 소감이나 앞으로의 의향을 덧붙이기"),
        listOf("사실과 생각이 자연스럽게 이어지는 한 문단으로 쓰기", "과거 경험을 나타내는 표현을 사용하기"))

    fun clinic() = prepare(WritingType.TRANSLATION,
        "다음 한국어 문단을 일본어로 번역하세요. 다음 주 월요일 오전에 병원 예약이 있지만 수업 시간과 겹칩니다. 병원에 예약을 오후로 바꿀 수 있는지 문의했습니다. 변경이 어렵다면 원래 예약 시간에 방문할 예정입니다.",
        listOf("다음 주 월요일 오전 병원 예약이 수업과 겹친다는 사실을 옮길 것", "병원에 예약을 오후로 바꿀 수 있는지 문의한 사실을 옮길 것", "변경이 어려우면 원래 예약 시간에 방문할 계획을 옮길 것"),
        "来週の月曜日の午前に病院の予約がありますが、授業と重なっています。病院に午前の別の時間に変更できるか聞きました。変更が難しければ、元の予約時間に行く予定です。")

    fun prepare(type: WritingType, prompt: String, requirements: List<String>, answer: String,
                facts: List<String> = emptyList(), intents: List<String> = emptyList(), constraints: List<String> = emptyList()): CuratedWritingFeedbackV13Policy.Plan {
        val context = CuratedWritingFeedbackPolicy.Context(type, buildJsonObject {
            put("originText", prompt); put("providedFacts", JsonArray(facts.map(::JsonPrimitive)))
            put("requiredIntents", JsonArray(intents.map(::JsonPrimitive))); put("responseConstraints", JsonArray(constraints.map(::JsonPrimitive)))
        }, "ko", "ja")
        return checkNotNull(CuratedWritingFeedbackV13Policy.prepare(context, requirements.mapIndexed { index, text -> "C${index + 1}" to text }, CuratedWritingFeedbackPolicy.segments(answer)))
    }

    fun extracted(plan: CuratedWritingFeedbackV13Policy.Plan): JsonObject = buildJsonObject {
        put("observations", JsonArray(plan.sources.map { source -> buildJsonObject {
            put("sourceIds", JsonArray(listOf(JsonPrimitive(source.id))))
            put("actor", "learner"); put("action", "synthetic observed proposition"); put("target", "task participant"); put("condition", "")
            put("recipient", "TASK_PARTICIPANT"); put("kind", "EVENT"); put("modality", "AFFIRMED")
        } }))
    }

    fun edge(state: String, sources: List<String>, recipient: String = "TASK_PARTICIPANT", kind: String = "EVENT"): JsonObject = buildJsonObject {
        put("state", state); put("sourceIds", JsonArray(sources.map(::JsonPrimitive)))
        listOf("actorMatches", "actionMatches", "targetMatches", "conditionMatches").forEach { put(it, state == "SUPPORT") }
        put("recipient", recipient); put("kind", kind); put("modality", "AFFIRMED"); put("semanticReading", "Synthetic actor-action-target-condition relation")
    }

    fun verified(plan: CuratedWritingFeedbackV13Policy.Plan): JsonObject = buildJsonObject {
        put("atoms", buildJsonObject {
            plan.contract.atoms.forEach { atom ->
                val value = edge("SUPPORT", plan.sources.map { it.id })
                put(atom.id, if (atom.alternatives.isEmpty()) value else buildJsonObject {
                    put("alternatives", buildJsonObject { atom.alternatives.indices.forEach { put("V${it + 1}", value) } })
                })
            }
        })
        put("facts", buildJsonObject {
            plan.contract.facts.forEach { fact ->
                val found = plan.sources.flatMap { source -> fact.normalizedAlternatives.keys.filter { source.text.contains(it) }.map { source to it } }
                    .sortedByDescending { it.second.length }.firstOrNull()
                put(fact.key, buildJsonObject {
                    put("sourceId", found?.first?.id ?: plan.sources.first().id)
                    put("valueToken", found?.second ?: "NONE")
                    put("slotRelation", if (found == null) "IMPLICIT_CONTEXT" else "MATCHED_EVENT")
                })
            }
        })
        put(CuratedWritingLanguageFormEvidence.key, JsonArray(emptyList()))
    }

    fun content(corrections: List<JsonElement> = emptyList()): JsonObject = buildJsonObject {
        put("necessaryCorrections", JsonArray(corrections)); put("optionalAlternatives", JsonArray(emptyList())); put("uncertainties", JsonArray(emptyList()))
    }

    fun correction(requirementId: String, segmentId: String, original: String, replacement: String, authority: String,
                   issueType: String = "TASK_FACT_CONFLICT", reason: String = "NONE"): JsonObject = buildJsonObject {
        put("requirementId", requirementId); put("segmentId", segmentId)
        put("patch", buildJsonObject { put("original", original); put("replacement", replacement) })
        put("issueType", issueType); put("sourceFactId", authority); put("sourceAuthorityId", ""); put("formReason", reason)
        put("learningText", "課題の指定と食い違う部分または実際の活用誤りを、その箇所だけ修正します。")
    }
}

private fun JsonObject.atom(id: String, value: JsonObject): JsonObject = JsonObject(this + ("atoms" to JsonObject(getValue("atoms").jsonObject + (id to value))))
private fun JsonObject.alternative(id: String, choice: String, value: JsonObject): JsonObject {
    val atom = getValue("atoms").jsonObject.getValue(id).jsonObject
    return this.atom(id, JsonObject(atom + ("alternatives" to JsonObject(atom.getValue("alternatives").jsonObject + (choice to value)))))
}
private fun JsonObject.fact(id: String, source: String, token: String, relation: String = "MATCHED_EVENT"): JsonObject =
    JsonObject(this + ("facts" to JsonObject(getValue("facts").jsonObject + (id to buildJsonObject {
        put("sourceId", source); put("valueToken", token); put("slotRelation", relation)
    }))))
