package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingGenerationPolicyTest {
    @Test
    fun `세 유형 콘텐츠에서 서버 metadata와 공개 조건을 조립한다`() {
        // 준비 / 실행
        for (type in WritingType.entries) {
            val manifest = CuratedWritingGenerationPolicy.assemble(content(type), "gen-fixture-001", "qa-generation-v1", type, 3, "library-plan")
            val item = CuratedWritingManifestCodec.parse(manifest.toString()).items.single()

            // 검증: 승인과 점수가 없고 공개 조건만 checklist와 연결된다.
            assertEquals(CuratedReviewStatus.DRAFT, item.status)
            assertEquals(3, item.band)
            assertEquals(item.checklist, item.visibleRequirements)
            assertTrue(item.validAlternative.contains("図書館"))
            assertFalse(item.visibleRequirements.contains(item.validAlternative))
        }
    }

    @Test
    fun `외부 metadata와 설명형 대안 및 누락 조건은 거절한다`() {
        // 준비
        val valid = content(WritingType.FREE)
        val invalid = listOf(
            JsonObject(valid + ("id" to JsonPrimitive("untrusted-external-id"))),
            JsonObject(valid + ("requirements" to JsonArray(emptyList()))),
            JsonObject(valid + ("validAlternative" to buildJsonObject {
                put("answer", "다른 타당한 답안"); put("explanation", "설명")
            })),
        )

        // 실행 / 검증
        invalid.forEach { candidate ->
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingGenerationPolicy.assemble(candidate, "gen-fixture-001", "qa-generation-v1", WritingType.FREE, 3, "library-plan")
            }
        }
    }

    @Test
    fun `한국어 안내 영역 전체에서 일본어와 실제 혼입 반례를 거절한다`() {
        // 준비: 첫 실모델 검증에서 저장됐던 일본어 안내와 혼합 설명을 동일 문자열로 재현한다.
        val valid = content(WritingType.GUIDED)
        val koreanFields = listOf("prompt", "alternativeNote", "levelEvidence", "providedFacts", "requiredIntents",
            "responseConstraints", "requirements", "validAlternative", "clearError", "boundaryAnswer")
        val invalid = koreanFields.map { field ->
            withKoreanField(valid, field, "最近始めた勉強習慣について、日本語で一段落書いてください。")
        } + listOf(
            withKoreanField(valid, "validAlternative", "すべての変更内容、理由、受付方法、確認の依頼を含み、指定された 안내문 형식을 따릅니다."),
            withKoreanField(valid, "levelEvidence", "日常적인 학습 습관을 설명하면서 시간과 목표를 연결합니다."),
            withKoreanField(valid, "prompt", "Write about your study routine in Japanese."),
        )

        // 실행 / 검증: 내용은 대체하거나 보정하지 않고 언어 계약 위반으로 거절한다.
        invalid.forEach { candidate ->
            val preserved = candidate.toString()
            val failure = assertFailsWith<IllegalArgumentException> {
                CuratedWritingGenerationPolicy.assemble(candidate, "gen-fixture-001", "qa-generation-v1", WritingType.GUIDED, 3, "library-plan")
            }
            assertEquals("WRITING_GENERATION_ORIGIN_LANGUAGE_INVALID", failure.message)
            assertEquals(preserved, candidate.toString())
        }
    }

    @Test
    fun `한국어 설명 안에서 인용한 일본어 표현과 시간 표기는 보존한다`() {
        // 준비: 일본어 예문 자체를 한국어 설명으로 오인하지 않으며, 필요한 인용은 허용한다.
        val explanation = "B3 과제에서 「平日」와 ‘毎朝’를 구별하고, 오후 9시와 빈도 또는 시간을 설명합니다."
        val base = content(WritingType.GUIDED)
        val koreanFields = listOf("prompt", "alternativeNote", "levelEvidence", "providedFacts", "requiredIntents",
            "responseConstraints", "requirements", "validAlternative", "clearError", "boundaryAnswer")

        // 실행 / 검증: 각 안내 필드의 인용과 실제 일본어 답안은 원형 그대로 남는다.
        koreanFields.forEach { field ->
            val candidate = withKoreanField(base, field, explanation)
            val manifest = CuratedWritingGenerationPolicy.assemble(candidate, "gen-fixture-001", "qa-generation-v1", WritingType.GUIDED, 3, "library-plan")
            val item = CuratedWritingManifestCodec.parse(manifest.toString()).items.single()
            assertEquals(base.getValue("referenceAnswers").jsonArray.map { it.jsonPrimitive.content }, item.referenceAnswers)
            if (field == "prompt") assertEquals(explanation, item.prompt)
            if (field == "requirements") assertEquals(explanation, item.visibleRequirements.first())
        }
    }

    @Test
    fun `국소 복구 입력은 원래 후보와 결함 근거를 함께 보존한다`() {
        // 준비
        val original = content(WritingType.FREE)
        val notes = "안내의 언어 혼입만 고치고 학습자가 선택한 내용과 정상 답안은 유지하세요."

        // 실행
        val ordinary = CuratedWritingGenerationPolicy.input(WritingType.FREE, 3, "learning-routine", emptyList())
        val repair = CuratedWritingGenerationPolicy.input(WritingType.FREE, 3, "learning-routine", emptyList(), original, notes)

        // 검증: 일반 입력과 복구 입력을 구별하며 평가 정책과 단계는 바꾸지 않는다.
        assertFalse("repairCandidateContent" in ordinary)
        assertFalse("repairNotes" in ordinary)
        assertEquals(original, repair.getValue("repairCandidateContent"))
        assertEquals(notes, repair.getValue("repairNotes").jsonPrimitive.content)
        assertEquals(ordinary, JsonObject(repair - setOf("repairCandidateContent", "repairNotes")))
        assertEquals("curated-writing-generation-v2", CuratedWritingGenerationPolicy.version)
    }

    @Test
    fun `후보 또는 결함 근거가 없는 불완전 복구 입력은 거절한다`() {
        // 준비
        val original = content(WritingType.FREE)
        val incomplete = listOf(original to null, null to "설명 언어를 고칩니다.", original to " ")

        // 실행 / 검증
        incomplete.forEach { (candidate, notes) ->
            val failure = assertFailsWith<IllegalArgumentException> {
                CuratedWritingGenerationPolicy.input(WritingType.FREE, 3, "learning-routine", emptyList(), candidate, notes)
            }
            assertEquals("WRITING_GENERATION_REPAIR_CONTEXT_INVALID", failure.message)
        }
    }

    private fun withKoreanField(content: JsonObject, field: String, value: String): JsonObject {
        val replacement = when (field) {
            "providedFacts", "requiredIntents", "responseConstraints", "requirements" -> buildJsonArray { add(value) }
            "validAlternative", "clearError", "boundaryAnswer" -> JsonObject(content.getValue(field).jsonObject +
                ("explanation" to JsonPrimitive(value)))
            else -> JsonPrimitive(value)
        }
        return JsonObject(content + (field to replacement))
    }

    companion object {
        internal fun content(type: WritingType): JsonObject = buildJsonObject {
            put("prompt", when (type) {
                WritingType.TRANSLATION -> "지역의 역사를 조사하려고 도서관에서 향토 자료를 읽을 계획입니다."
                WritingType.GUIDED -> "도서관 담당자에게 자료 이용 계획과 이유를 알리는 안내문을 쓰세요."
                WritingType.FREE -> "도서관에서 어떤 자료를 어떻게 이용할지 이유와 함께 쓰세요."
            })
            put("alternativeNote", "자료와 이용 목적은 스스로 선택할 수 있습니다.")
            put("levelEvidence", "구체적인 계획과 그 이유를 연결해 설명합니다.")
            listOf("providedFacts", "requiredIntents", "responseConstraints").forEach { key ->
                put(key, buildJsonArray { if (type == WritingType.GUIDED) add("도서관 자료의 이용 방법과 이유를 밝히세요.") })
            }
            put("requirements", buildJsonArray { add("이용할 자료와 방법을 밝히세요."); add("이용 이유를 설명하세요.") })
            put("referenceAnswers", buildJsonArray {
                add("地域の歴史を調べたいので、図書館で郷土資料を読みます。")
                add("小説の表現を学ぶため、図書館で短編集を借ります。")
            })
            put("validAlternative", buildJsonObject { put("answer", "旅行の準備のため、図書館で地図を調べます。"); put("explanation", "다른 목적과 자료도 조건을 충족합니다.") })
            put("clearError", buildJsonObject { put("answer", "図書館に資料がありますが、何をするか決めていません。"); put("explanation", "자료와 이용 방법이 없습니다.") })
            put("boundaryAnswer", buildJsonObject { put("answer", "調べたいことがあるので、図書館へ行きます。"); put("explanation", "자료와 구체적 방법이 불분명합니다.") })
        }
    }
}
