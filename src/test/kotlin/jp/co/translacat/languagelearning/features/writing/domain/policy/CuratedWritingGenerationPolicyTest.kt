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

    @Test
    fun `새 생성과 국소 복구는 공개 의미 기준을 선언하고 출력 schema와 저장 결과는 보존한다`() {
        // 준비: 고정 코어를 쓰지 않는 세 유형의 신규 요청과 복구 요청을 비교한다.
        WritingType.entries.forEach { type ->
            val ordinary = CuratedWritingGenerationPolicy.input(type, 3, "library-plan", emptyList())
            val repair = CuratedWritingGenerationPolicy.input(type, 3, "library-plan", emptyList(),
                content(type), "경계 답안의 근거를 공개 조건과 비교해 주세요.")
            val legacy = JsonObject(ordinary - "semanticCriteriaVersion")

            // 실행: 새 계약의 모델 지시는 공유 기준을 포함하되 기존 콘텐츠 스키마를 바꾸지 않는다.
            val instructions = CuratedWritingGenerationPolicy.instructions(ordinary)
            val currentManifest = CuratedWritingGenerationPolicy.assemble(content(type), "gen-criteria-fixture",
                "qa-generation-v1", type, 3, "library-plan", ordinary)
            val legacyManifest = CuratedWritingGenerationPolicy.assemble(content(type), "gen-criteria-fixture",
                "qa-generation-v1", type, 3, "library-plan", legacy)

            // 검증: 기준 선언은 모델 입력에만 있고 승인·점수·문항 내용에 추가되지 않는다.
            assertEquals(JsonPrimitive(CuratedWritingSemanticCriteria.version), ordinary["semanticCriteriaVersion"])
            assertEquals(ordinary["semanticCriteriaVersion"], repair["semanticCriteriaVersion"])
            assertTrue(instructions.contains(CuratedWritingSemanticCriteria.instructions))
            assertEquals(instructions, CuratedWritingGenerationPolicy.instructions(repair))
            assertEquals(CuratedWritingGenerationPolicy.schema(legacy), CuratedWritingGenerationPolicy.schema(ordinary))
            assertEquals(legacyManifest, currentManifest)
        }
    }

    @Test
    fun `기존 저장 일반 생성과 복구 및 B2 선택의 지시는 원본 hash를 유지한다`() {
        // 준비: 변경 직전 저장 계약의 지시문 hash를 고정하여 과거 요청이 새 기준으로 바뀌지 않게 한다.
        val legacy = buildJsonObject {
            put("writingType", "GUIDED"); put("targetBand", 3)
            put("originLanguage", "ko"); put("learningLanguage", "ja")
            put("topic", "community-workshop"); put("recentPromptsToAvoid", buildJsonArray {})
        }
        val legacyRepair = JsonObject(legacy + mapOf(
            "repairCandidateContent" to content(WritingType.GUIDED),
            "repairNotes" to JsonPrimitive("예전 요청에 저장한 보고입니다."),
        ))
        val core = CuratedWritingGenerationPolicy.input(WritingType.TRANSLATION, 2, "clinic-appointment", emptyList())
        val replacement = CuratedWritingGenerationPolicy.input(WritingType.TRANSLATION, 2, "clinic-appointment",
            emptyList(), buildJsonObject { put("coreId", "clinic-appointment-mon") }, "다른 주제를 원합니다.",
            replaceCore = true)

        // 실행 / 검증: 일반 생성과 B2 두 경로의 기존 지시를 그대로 재현하고 입력도 변형하지 않는다.
        listOf(legacy, legacyRepair).forEach { input ->
            val before = input.toString()
            assertEquals("d39dbb232d683b35dd4fbce728be21e4cbba1c48fe5f90f09ea3b507b7e76bf1",
                CuratedWritingManifestCodec.sha256(CuratedWritingGenerationPolicy.instructions(input)))
            assertEquals(before, input.toString())
        }
        assertFalse("semanticCriteriaVersion" in core)
        assertFalse("semanticCriteriaVersion" in replacement)
        assertEquals("2877abdcd957cbdfd393df46b4f29bc3bbf64b728b3f4a892092e36b9209d6fd",
            CuratedWritingManifestCodec.sha256(CuratedWritingGenerationPolicy.instructions(core)))
        assertEquals("9d30519477bc19a221ba232d3ad9931c257a79bf9dc3ead0105a1676b2dbb57c",
            CuratedWritingManifestCodec.sha256(CuratedWritingGenerationPolicy.instructions(replacement)))
    }

    @Test
    fun `알 수 없거나 손상된 의미 기준은 다른 지시로 폴백하지 않는다`() {
        // 준비: 저장 버전을 알아볼 수 없는 경우 유료 실행 전에 중단해야 한다.
        val input = CuratedWritingGenerationPolicy.input(WritingType.FREE, 3, "library-plan", emptyList())
        val invalid = listOf(JsonPrimitive("public-meaning-unknown"), JsonPrimitive(1), JsonNull, buildJsonObject {})

        // 실행 / 검증: 빈 값과 다른 타입도 이전 지시로 숨기지 않는다.
        invalid.forEach { version ->
            val failure = assertFailsWith<IllegalArgumentException> {
                CuratedWritingGenerationPolicy.instructions(JsonObject(input + ("semanticCriteriaVersion" to version)))
            }
            assertEquals("WRITING_GENERATION_SEMANTIC_CRITERIA_INVALID", failure.message)
        }
    }

    @Test
    fun `B2 고정 골격의 스무 변형은 날짜와 핵심 사실을 보존한 DRAFT로 조립된다`() {
        // 준비: 기존 다섯 골격과 이유·요청·조건·대조를 더한 다섯 골격을 각각 검사한다.
        val topics = listOf("home-task", "library-use", "public-transport", "community-meeting", "weekend-plan",
            "reading-group-change", "clinic-appointment", "rainy-day-choice", "group-presentation", "gym-repair-notice")
        val prompts = mutableSetOf<String>()

        // 실행 / 검증: 서버가 모든 변형을 조립하고 서로 다른 원문을 내는지 확인한다.
        topics.forEach { topic ->
            val ids = CuratedWritingB2Core.ids(topic)
            assertEquals(2, ids.size)
            val input = CuratedWritingGenerationPolicy.input(WritingType.TRANSLATION, 2, topic, emptyList())
            assertEquals(ids, input.getValue("allowedCoreIds").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(setOf("coreId"), CuratedWritingGenerationPolicy.schema(input).getValue("properties").jsonObject.keys)
            ids.forEach { id ->
                val manifest = CuratedWritingGenerationPolicy.assemble(buildJsonObject { put("coreId", id) },
                    "gen-${id}", "qa-generation-v1", WritingType.TRANSLATION, 2, topic, input)
                val item = CuratedWritingManifestCodec.parse(manifest.toString()).items.single()
                assertEquals(CuratedReviewStatus.DRAFT, item.status)
                assertEquals(2, item.band)
                assertTrue(prompts.add(item.prompt), "중복 B2 원문: $id")
                assertEquals(2, item.referenceAnswers.size)
                assertEquals(item.checklist, item.visibleRequirements)
                assertFalse(item.prompt.contains("{{"))
                assertTrue(item.referenceAnswers.none { "{{" in it || "妹" in it || "弟" in it })
                assertFalse("十分間" in item.referenceAnswers.joinToString())
            }
        }
        assertEquals(20, prompts.size)
    }

    @Test
    fun `새 B2 골격은 요청과 조건의 구별을 답안과 오답 사례에 유지한다`() {
        // 준비
        val reading = CuratedWritingB2Core.content("reading-group-change-tue", "reading-group-change")
        val clinic = CuratedWritingB2Core.content("clinic-appointment-mon", "clinic-appointment")
        val group = CuratedWritingB2Core.content("group-presentation-thu", "group-presentation")
        val gym = CuratedWritingB2Core.content("gym-repair-notice-mon", "gym-repair-notice")

        // 검증: 이전/새 시간, 조건절과 대조의 핵심 구분이 한 가지 오류 사례에서만 뒤집힌다.
        assertTrue(reading.getValue("prompt").jsonPrimitive.content.contains("두 시 삼십 분"))
        assertTrue(reading.getValue("clearError").jsonObject.getValue("answer").jsonPrimitive.content.contains("午後3時"))
        assertTrue(clinic.getValue("referenceAnswers").jsonArray.all { it.jsonPrimitive.content.contains("午後") })
        assertTrue(clinic.getValue("boundaryAnswer").jsonObject.getValue("answer").jsonPrimitive.content.contains("難しければ、午前中に"))
        assertTrue(group.getValue("referenceAnswers").jsonArray.all { it.jsonPrimitive.content.contains("空") })
        assertTrue(group.getValue("clearError").jsonObject.getValue("answer").jsonPrimitive.content.contains("推測で埋め"))
        assertTrue(gym.getValue("referenceAnswers").jsonArray.all { it.jsonPrimitive.content.contains("2階") && it.jsonPrimitive.content.contains("1階") })
        assertTrue(gym.getValue("boundaryAnswer").jsonObject.getValue("answer").jsonPrimitive.content.contains("利用しにくく"))
    }

    @Test
    fun `B2 선택은 허용된 ID만 받아들이고 사용한 두 변형 뒤에는 종료한다`() {
        val topic = "library-use"
        val input = CuratedWritingGenerationPolicy.input(WritingType.TRANSLATION, 2, topic, emptyList())
        val ids = input.getValue("allowedCoreIds").jsonArray.map { it.jsonPrimitive.content }
        listOf(
            buildJsonObject { put("coreId", "home-task-sat") },
            buildJsonObject { put("coreId", ids.first()); put("prompt", "주입") },
            CuratedWritingB2Core.content(ids.first(), topic),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingGenerationPolicy.assemble(invalid, "gen-invalid", "qa-generation-v1",
                    WritingType.TRANSLATION, 2, topic, input)
            }
        }
        val second = CuratedWritingGenerationPolicy.input(WritingType.TRANSLATION, 2, topic, emptyList(),
            recentCoreIds = setOf(ids.first()))
        assertEquals(listOf(ids.last()), second.getValue("allowedCoreIds").jsonArray.map { it.jsonPrimitive.content })
        val repair = CuratedWritingGenerationPolicy.input(WritingType.TRANSLATION, 2, topic, emptyList(),
            buildJsonObject { put("coreId", ids.first()) }, "날짜 표현을 더 명확하게 선택하세요.")
        assertEquals(listOf(ids.last()), repair.getValue("allowedCoreIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("날짜 표현을 더 명확하게 선택하세요.", repair.getValue("repairNotes").jsonPrimitive.content)
        // 옛 저장 입력에는 새 의미 대체 버전이 없으므로 같은 주제 조립 계약을 그대로 적용한다.
        val legacyRepair = CuratedWritingGenerationPolicy.assemble(buildJsonObject { put("coreId", ids.last()) },
            "gen-legacy-repair", "qa-generation-v1", WritingType.TRANSLATION, 2, topic, repair)
        assertEquals(topic, CuratedWritingManifestCodec.parse(legacyRepair.toString()).items.single().topicKey)
        val exhausted = assertFailsWith<IllegalArgumentException> {
            CuratedWritingGenerationPolicy.input(WritingType.TRANSLATION, 2, topic, emptyList(),
                recentCoreIds = ids.toSet())
        }
        assertEquals("WRITING_CONTENT_NOT_AVAILABLE", exhausted.message)
        val unsupported = assertFailsWith<IllegalArgumentException> {
            CuratedWritingGenerationPolicy.input(WritingType.TRANSLATION, 2, "unknown-topic", emptyList())
        }
        assertEquals("WRITING_REQUEST_INVALID", unsupported.message)
    }

    @Test
    fun `B2 의미 대체는 원본 주제와 사용 코어를 제외하고 선택한 실제 주제로 조립한다`() {
        // 준비: 날짜 교체로 해결할 수 없는 사용자 보고와 서버 관측은 서로 구분해서 전달한다.
        val topic = "library-use"
        val originalId = CuratedWritingB2Core.ids(topic).first()
        val usedId = CuratedWritingB2Core.ids("home-task").first()
        val original = buildJsonObject { put("coreId", originalId) }
        val rawInput = CuratedWritingGenerationPolicy.input(WritingType.TRANSLATION, 2, topic, emptyList(),
            original, "참고 답안의 의미가 맞는지 걱정됩니다.", setOf(originalId, usedId),
            replaceCore = true, repairReasonCodes = listOf("AUTOMATED_CHECK_ONLY", "CORE_CONTENT_FIXED"))
        val input = JsonObject(rawInput + ("repairOfRequestKey" to JsonPrimitive("original")))

        // 실행: 허용된 다른 의미 골격만 고르고 원본 콘텐츠는 그대로 남긴다.
        val options = input.getValue("coreOptions").jsonArray.map { it.jsonObject }
        val selected = options.first()
        val manifest = CuratedWritingGenerationPolicy.assemble(buildJsonObject {
            put("coreId", selected.getValue("coreId"))
        }, "gen-replacement", "qa-generation-v1", WritingType.TRANSLATION, 2, topic, input)
        val item = CuratedWritingManifestCodec.parse(manifest.toString()).items.single()

        // 검증: 서버 문구·조건을 비교할 수 있고 사용자 보고를 확정 결함이나 승인으로 바꾸지 않는다.
        assertEquals(17, options.size)
        assertTrue(options.none { it.getValue("topicKey").jsonPrimitive.content == topic ||
            it.getValue("coreId").jsonPrimitive.content == usedId })
        assertTrue(options.all { it.getValue("prompt").jsonPrimitive.content.isNotBlank() &&
            it.getValue("requirements").jsonArray.isNotEmpty() })
        assertEquals(CuratedWritingB2Core.content(originalId, topic), input.getValue("repairCandidateContent"))
        assertEquals(selected.getValue("topicKey").jsonPrimitive.content, item.topicKey)
        assertNotEquals(topic, item.topicKey)
        assertEquals(CuratedReviewStatus.DRAFT, item.status)
        assertTrue(CuratedWritingGenerationPolicy.instructions(input).contains("unverified observations"))
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingGenerationPolicy.assemble(original, "gen-forbidden", "qa-generation-v1",
                WritingType.TRANSLATION, 2, topic, input)
        }
    }

    @Test
    fun `과거 자유 생성 B2 원본의 주제도 현재 코어로 의미 대체할 수 있다`() {
        // 준비: 고정 코어 이전에 저장한 원본에는 coreId가 없고 주제도 현재 목록 밖일 수 있다.
        val original = content(WritingType.TRANSLATION)
        val input = JsonObject(CuratedWritingGenerationPolicy.input(WritingType.TRANSLATION, 2,
            "legacy-library-plan", emptyList(), original, "다른 상황의 과제로 바꿔 주세요.",
            replaceCore = true) + ("repairOfRequestKey" to JsonPrimitive("legacy-original")))

        // 실행
        val selected = input.getValue("allowedCoreIds").jsonArray.first()
        val manifest = CuratedWritingGenerationPolicy.assemble(buildJsonObject { put("coreId", selected) },
            "gen-legacy-replacement", "qa-generation-v1", WritingType.TRANSLATION, 2, "legacy-library-plan", input)

        // 검증: 원본 자료는 그대로 제공하고 새 후보는 서버 코어의 실제 주제를 기록한다.
        assertEquals(original, input.getValue("repairCandidateContent"))
        assertEquals(20, input.getValue("allowedCoreIds").jsonArray.size)
        assertEquals(CuratedWritingB2Core.topic(selected.jsonPrimitive.content),
            CuratedWritingManifestCodec.parse(manifest.toString()).items.single().topicKey)
    }

    @Test
    fun `B2 의미 대체 재고가 모두 소진되면 호출 입력을 만들지 않는다`() {
        // 준비
        val topic = "home-task"
        val options = CuratedWritingB2Core.replacementOptions(topic, emptySet())
        val used = options.map { it.jsonObject.getValue("coreId").jsonPrimitive.content }.toSet()

        // 실행 / 검증
        val failure = assertFailsWith<IllegalArgumentException> {
            CuratedWritingGenerationPolicy.input(WritingType.TRANSLATION, 2, topic, emptyList(),
                buildJsonObject { put("coreId", "home-task-sat") }, "다른 의미 과제를 원합니다.",
                used, replaceCore = true)
        }
        assertEquals("WRITING_CONTENT_NOT_AVAILABLE", failure.message)
    }

    @Test
    fun `B2 편집 검토에서 찾은 결제 장소와 경계 사례의 독립 결함은 재발하지 않는다`() {
        val home = CuratedWritingB2Core.content("home-task-sat", "home-task")
        assertTrue(home.getValue("clearError").jsonObject.getValue("answer").jsonPrimitive.content.contains("一緒にお茶"))
        val transport = CuratedWritingB2Core.content("public-transport-mon", "public-transport")
        assertTrue(transport.getValue("boundaryAnswer").jsonObject.getValue("answer").jsonPrimitive.content.contains("車内で"))
        assertTrue(transport.getValue("boundaryAnswer").jsonObject.getValue("explanation").jsonPrimitive.content.contains("정류장"))
        val library = CuratedWritingB2Core.content("library-use-wed", "library-use")
        assertTrue(library.getValue("boundaryAnswer").jsonObject.getValue("answer").jsonPrimitive.content.contains("一時間ぐらい"))
        val community = CuratedWritingB2Core.content("community-meeting-fri", "community-meeting")
        assertTrue(community.getValue("boundaryAnswer").jsonObject.getValue("answer").jsonPrimitive.content.contains("公園について"))
        assertTrue(community.getValue("boundaryAnswer").jsonObject.getValue("answer").jsonPrimitive.content.contains("地域の会議"))
        val weekend = CuratedWritingB2Core.content("weekend-plan-sat", "weekend-plan")
        assertTrue(weekend.getValue("referenceAnswers").jsonArray.none { it.jsonPrimitive.content.contains("歩きながら写真") })
        assertTrue(weekend.getValue("boundaryAnswer").jsonObject.getValue("answer").jsonPrimitive.content.contains("近くの食堂"))
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
