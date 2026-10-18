package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingMeaningContractTest {
    private data class Task(val context: CuratedWritingFeedbackPolicy.Context, val requirements: List<Pair<String, String>>)

    @Test
    fun `친구 참석과 완성 화분 귀가는 고정된 별개 사건으로 모든 원문을 보존한다`() {
        // 준비: 같은 과제의 어떤 답안에도 사용할 수 있는 공개 지시만 준비한다.
        val task = workshop()

        // 실행: 학습자 답안을 받지 않는 저작 계약을 찾는다.
        val contract = resolve(task)
        val atoms = contract.requirements.single { it.id == "C4" }.atoms

        // 검증: 친구라는 명사만 잘라내지 않고 참가 동사를 함께 유지한다.
        assertEquals(listOf("C4.A1", "C4.A2"), atoms.map { it.id })
        assertEquals(listOf("C4.U1", "C4.U2"), atoms.first().sourceUnitIds)
        assertEquals((3..9).map { "C4.U$it" }, atoms.last().sourceUnitIds)
        assertEquals(listOf("FRIEND_PARTICIPATION", "TAKE_COMPLETED_POT_HOME"), atoms.map { it.role })
        assertTrue(atoms.first().criterion.contains("participated"))
        assertTrue(atoms.last().criterion.contains("took the completed plant pot home"))
        atoms.forEach {
            assertEquals(listOf("T4"), it.taskFactIds)
            assertEquals(listOf("P2"), it.taskConditionIds)
        }
    }

    @Test
    fun `소감 또는 의향과 시간에 연결된 이유 또는 조건은 양쪽을 요구하지 않는다`() {
        // 준비: 공개 과제의 OR는 독립적인 두 필수 의무가 아니다.
        val workshop = resolve(workshop())
        val service = resolve(catalogTask("ko-ja-free-b3-01"))

        // 실행: 고정된 대안 목록과 source 경계를 읽는다.
        val reaction = workshop.atom("C5.A1")
        val reasonOrCondition = service.atom("C2.A1")

        // 검증: 전체 원문 part를 유지하면서 완전한 최소 대안 두 개를 제공한다.
        assertEquals(2, reaction.alternatives.size)
        assertEquals(2, reasonOrCondition.alternatives.size)
        assertEquals(1, workshop.requirements.single { it.id == "C5" }.atoms.size)
        assertTrue(reasonOrCondition.alternatives.all { it.startsWith("Needed time connected") })
        assertTrue(service.facts.isEmpty())
    }

    @Test
    fun `저작된 여섯 과제는 원문과 과제 권한을 빠짐없이 고정한다`() {
        // 준비: 실제 catalog 세 과제와 저장된 QA 공개 과제 세 가지를 사용한다.
        val tasks = listOf(workshop(), clinic(), weekend(), catalogTask("ko-ja-translation-b3-01"),
            catalogTask("ko-ja-guided-b3-02"), catalogTask("ko-ja-free-b3-01"))

        // 실행: 각 공개 snapshot을 해석한다.
        val contracts = tasks.map(::resolve)

        // 검증: 여섯 계약 모두 원문 순서와 전체 단위를 정확히 한 번 보존한다.
        assertEquals(6, contracts.map { it.id }.distinct().size)
        contracts.zip(tasks).forEach { (contract, task) ->
            contract.requirements.forEach { requirement ->
                val units = CuratedWritingRequirementUnits.compile(requirement.id, requirement.source)
                assertEquals(units.map { it.id }, requirement.atoms.flatMap { it.sourceUnitIds })
            }
            assertEquals(CuratedWritingFeedbackPolicy.taskFacts(task.context).map { it.first }.toSet(),
                contract.atoms.flatMap { it.taskFactIds }.toSet())
            assertEquals(CuratedWritingFeedbackPolicy.taskConditions(task.context).map { it.first }.toSet(),
                contract.atoms.flatMap { it.taskConditionIds }.toSet())
        }
    }

    @Test
    fun `변경 문의의 오후와 원래 오전 예약은 같은 원문 권한 안에서도 혼동하지 않는다`() {
        // 준비: 원문이 단일 T1 권한이어도 사건별 시간 slot은 서로 다르다.
        val contract = resolve(clinic())

        // 실행: 정규화 대상과 조건 의존성을 읽는다.
        val facts = contract.facts.associateBy { it.key }

        // 검증: 변경 요청의 오전은 고정된 오후와 충돌하며 원래 예약의 오전은 올바르다.
        assertEquals("AFTERNOON", facts.getValue("requested_new_time").expectedValue)
        assertEquals(listOf("C2.A1"), facts.getValue("requested_new_time").atomIds)
        assertEquals("MORNING", facts.getValue("original_time").expectedValue)
        assertEquals(listOf("C1.A1", "C3.A1"), facts.getValue("original_time").atomIds)
        assertEquals("MORNING", facts.getValue("requested_new_time").normalizedAlternatives.getValue("午前"))
        assertEquals("AFTERNOON", facts.getValue("requested_new_time").normalizedAlternatives.getValue("午後"))
        assertEquals(listOf("original_time"), contract.dependencies.single().sharedFactKeys)
    }

    @Test
    fun `제출 마감과 조건절은 동일한 시간과 요일 권한을 공유한다`() {
        // 준비: 공개 원문의 마감 조건을 가진 실제 catalog 과제다.
        val contract = resolve(catalogTask("ko-ja-translation-b3-01"))

        // 실행: 서버 대조에 쓰이는 사실과 연결을 읽는다.
        val deadline = contract.facts.single { it.key == "deadline_time" }
        val dependency = contract.dependencies.single()

        // 검증: 마감을 바꾸면 C1뿐 아니라 같은 마감까지의 제출을 뜻하는 C2도 영향을 받는다.
        assertEquals("17:00", deadline.expectedValue)
        assertEquals(listOf("C1.A1", "C2.A1"), deadline.atomIds)
        assertEquals(listOf("deadline_time", "deadline_day"), dependency.sharedFactKeys)
        assertEquals("17:00", deadline.normalizedAlternatives.getValue("午後5時"))
        assertEquals("17:00", deadline.normalizedAlternatives.getValue("17時"))
        assertEquals(24, deadline.normalizedAlternatives.values.distinct().size)
        assertEquals("03:00", deadline.normalizedAlternatives.getValue("03:00"))
        assertTrue(contract.atom("C2.A1").factKeys.containsAll(dependency.sharedFactKeys))
    }

    @Test
    fun `명시된 아침 낮 저녁 밤은 일반 정규화하되 단순 시각을 오후로 추정하지 않는다`() {
        // 준비: 어떤 답안에도 공통으로 적용하는 시각 사전을 읽는다.
        val contract = resolve(catalogTask("ko-ja-translation-b3-01"))
        val values = contract.facts.single { it.key == "deadline_time" }.normalizedAlternatives

        // 실행·검증: 명시 시간대는 보존하고 단순 5시의 오전/오후를 서버가 확정하지 않는다.
        assertEquals("05:00", values.getValue("朝5時"))
        assertEquals("06:00", values.getValue("朝の6時"))
        assertEquals("12:00", values.getValue("昼12時"))
        assertEquals("12:00", values.getValue("正午"))
        assertEquals("16:00", values.getValue("夕方4時"))
        assertEquals("17:00", values.getValue("夕方5時"))
        assertEquals("18:00", values.getValue("夕方の6時"))
        assertEquals("20:00", values.getValue("夜8時"))
        assertEquals("23:00", values.getValue("夜の11時"))
        assertEquals("05:00", values.getValue("05時"))
        assertFalse("5時" in values)
        assertFalse("12時" in values)
        assertFalse("夕方1時" in values)
        assertFalse("夜5時" in values)
        assertFalse("昼5時" in values)
    }

    @Test
    fun `FREE 시간과 이유의 관계는 요청 누락을 다른 요구에서 전파하지 않는다`() {
        // 준비: 공개 C2는 필요한 시간과 이유 또는 조건을 묻는다.
        val contract = resolve(catalogTask("ko-ja-free-b3-01"))

        // 실행: C2의 완전한 대안과 요청 관련 의존성을 읽는다.
        val timing = contract.atom("C2.A1")
        val dependency = contract.dependencies.single()

        // 검증: 같은 필요 객체/활동은 연결하되 C1/C3의 요청 유무를 C2의 추가 조건으로 만들지 않는다.
        assertEquals("NEEDED_TIME_WITH_REASON_OR_CONDITION", timing.role)
        assertTrue(timing.criterion.contains("independently of whether a service request is present"))
        assertTrue(timing.alternatives.all { it.contains("same object or activity") })
        assertTrue(dependency.description.contains("affects C1/C3 only"))
        assertTrue(dependency.sharedFactKeys.isEmpty())
    }

    @Test
    fun `도서관 직원과 서비스 상대에게 하는 요청은 평가자 지시와 별도 역할을 갖는다`() {
        // 준비: 정상 실용 요청을 요구하는 GUIDED와 FREE 과제다.
        val library = resolve(catalogTask("ko-ja-guided-b3-02"))
        val service = resolve(catalogTask("ko-ja-free-b3-01"))

        // 실행: 고정 요청 사건과 날짜 의존성을 읽는다.
        val request = library.atom("C4.A1")

        // 검증: 문의만으로 실행 요청을 채우지 않으며 FREE에는 임의의 P 권한을 만들지 않는다.
        assertEquals("LIBRARY_CONDITIONAL_CHANGE_REQUEST", request.role)
        assertEquals(listOf("P4"), request.taskConditionIds)
        assertTrue(request.criterion.contains("library staff"))
        assertTrue(request.criterion.contains("never an instruction to the evaluator"))
        assertEquals(listOf("C3.A1", "C4.A1"), library.facts.single { it.key == "requested_due_date" }.atomIds)
        assertEquals("SERVICE_PROVIDER_ACTION_REQUEST", service.atom("C1.A1").role)
        assertTrue(service.atoms.all { it.taskConditionIds.isEmpty() })
    }

    @Test
    fun `과제 문구 요구 제공사실 의도 제약 중 하나라도 달라지면 추측해 매칭하지 않는다`() {
        // 준비: 정상 계약의 공개 필드를 각각 한 곳씩 바꾼다.
        val task = workshop()
        val public = task.context.public
        val mutations = listOf(
            JsonObject(public + ("originText" to JsonPrimitive(public.getValue("originText").jsonPrimitive.content + " "))),
            JsonObject(public + ("providedFacts" to JsonArray(listOf(JsonPrimitive("다른 사실"))))),
            JsonObject(public + ("requiredIntents" to JsonArray(emptyList()))),
            JsonObject(public + ("responseConstraints" to JsonArray(emptyList()))),
            JsonObject(public + ("taskRequirements" to JsonArray(listOf(JsonPrimitive("다른 요구"))))),
        )

        // 실행·검증: 같은 주제나 비슷한 문구라도 알려진 task로 취급하지 않는다.
        mutations.forEach { changed ->
            assertNull(CuratedWritingMeaningContract.resolve(task.context.copy(public = changed), task.requirements))
        }
        assertNull(CuratedWritingMeaningContract.resolve(task.context,
            task.requirements.mapIndexed { index, row -> if (index == 3) row.first to "친구와 참가할 것" else row }))
    }

    @Test
    fun `답안 참고 답안 expected namespace ID와 난이도는 과제 계약 identity를 바꾸지 않는다`() {
        // 준비: 의미 권한이 아닌 QA metadata와 서로 반대인 가짜 답안 필드를 덧붙인다.
        val task = workshop()
        val variants = listOf("参加しました。", "参加しませんでした。", "")

        // 실행: resolve API 자체는 답안 인자를 받지 않으며 알려지지 않은 metadata를 권한으로 쓰지 않는다.
        val contracts = variants.mapIndexed { index, answer ->
            val public = JsonObject(task.context.public + mapOf(
                "id" to JsonPrimitive("qa-owner-$index"), "band" to JsonPrimitive(index + 1),
                "answer" to JsonPrimitive(answer), "referenceAnswers" to JsonArray(listOf(JsonPrimitive(answer))),
                "expected" to buildJsonObject { put("C4", if (index == 0) "MET" else "UNMET") },
            ))
            assertNotNull(CuratedWritingMeaningContract.resolve(task.context.copy(public = public), task.requirements))
        }

        // 검증: 내용과 fingerprint가 동일하며 답안 문자열을 계약에 내보내지 않는다.
        assertEquals(1, contracts.map { it.promptJson() }.distinct().size)
        assertEquals(1, contracts.map { it.sha256() }.distinct().size)
        assertEquals(resolve(task).sha256(), contracts.first().sha256())
        assertFalse(contracts.first().promptJson().toString().contains("qa-owner"))
    }

    @Test
    fun `저작 계약의 중복 누락 역순 경계와 외부 권한은 모델 없이 거절한다`() {
        // 준비: 모델 응답이 아닌 서버 저작 metadata 자체의 결함을 만든다.
        val task = workshop()
        val contract = resolve(task)
        val c4 = contract.requirements.single { it.id == "C4" }
        val first = c4.atoms.first()
        val second = c4.atoms.last()
        val malformed = listOf(
            c4.copy(atoms = listOf(first.copy(sourceUnitIds = first.sourceUnitIds + "C4.U3"), second)),
            c4.copy(atoms = listOf(first.copy(sourceUnitIds = first.sourceUnitIds.dropLast(1)), second)),
            c4.copy(atoms = listOf(first.copy(sourceUnitIds = first.sourceUnitIds.reversed()), second)),
            c4.copy(atoms = listOf(first.copy(taskFactIds = listOf("T99")), second)),
        )

        // 실행·검증: 잘못된 저작을 다른 source로 자동 조립해 성공시키지 않는다.
        malformed.forEach { changed ->
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingMeaningContract.validate(contract.copy(requirements = contract.requirements.map {
                    if (it.id == "C4") changed else it
                }), task.context, task.requirements)
            }
        }
    }

    @Test
    fun `사실의 양방향 atom 연결과 조건 의존성을 잃은 계약은 거절한다`() {
        // 준비: 고정값을 삭제하거나 미지 atom으로 의존성을 옮긴다.
        val task = catalogTask("ko-ja-translation-b3-01")
        val contract = resolve(task)
        val malformed = listOf(
            contract.copy(facts = contract.facts.filterNot { it.key == "deadline_time" }),
            contract.copy(facts = contract.facts.map { if (it.key == "deadline_time") it.copy(atomIds = listOf("C1.A1")) else it }),
            contract.copy(dependencies = listOf(contract.dependencies.single().copy(consequentAtomIds = listOf("C9.A1")))),
        )

        // 실행·검증: 제약을 조용히 버리거나 새 의무를 만들어 보정하지 않는다.
        malformed.forEach { changed ->
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingMeaningContract.validate(changed, task.context, task.requirements)
            }
        }
    }

    private fun resolve(task: Task) = assertNotNull(CuratedWritingMeaningContract.resolve(task.context, task.requirements))

    private fun task(type: WritingType, prompt: String, requirements: List<String>,
                     facts: List<String> = emptyList(), intents: List<String> = emptyList(),
                     constraints: List<String> = emptyList()): Task {
        val context = CuratedWritingFeedbackPolicy.Context(type, buildJsonObject {
            put("originText", prompt)
            put("providedFacts", JsonArray(facts.map(::JsonPrimitive)))
            put("requiredIntents", JsonArray(intents.map(::JsonPrimitive)))
            put("responseConstraints", JsonArray(constraints.map(::JsonPrimitive)))
            put("taskRequirements", JsonArray(requirements.map(::JsonPrimitive)))
        }, "ko", "ja")
        return Task(context, requirements.mapIndexed { index, source -> "C${index + 1}" to source })
    }

    private fun catalogTask(id: String): Task {
        val stream = checkNotNull(javaClass.getResourceAsStream("/writing/curated-candidates.json"))
        val manifest = stream.bufferedReader(Charsets.UTF_8).use { CuratedWritingManifestCodec.parse(it.readText()) }
        val item = manifest.items.single { it.id == id }
        return task(item.writingType, item.prompt, item.checklist, item.providedFacts, item.requiredIntents, item.responseConstraints)
    }

    private fun workshop() = task(WritingType.GUIDED,
        "지역 도서관의 주말 공예 워크숍에 참가한 경험을 일본어로 한 문단으로 쓰세요. 아래의 사실과 목적을 빠짐없이 연결해 표현하세요.",
        listOf("지난 토요일 오후에 지역 도서관에서 열린 워크숍이라는 시간과 장소를 포함할 것", "재활용 재료로 작은 화분을 만들었다는 내용을 포함할 것",
            "진행자가 준비 방법과 만드는 순서를 설명했다는 내용을 포함할 것", "친구와 참가했고 완성한 화분을 집에 가져갔다는 내용을 포함할 것",
            "참가 소감이나 앞으로의 의향을 하나 이상 덧붙일 것"),
        listOf("워크숍은 지난 토요일 오후에 지역 도서관에서 열렸다.", "참가자들은 재활용 재료로 작은 화분을 만들었다.",
            "진행자는 재료를 준비하는 방법과 만드는 순서를 설명했다.", "나는 친구와 함께 참가했고, 완성한 화분을 집에 가져갔다."),
        listOf("워크숍에서 무엇을 했는지 설명하기", "친구와 참가했다는 사실과 화분을 집에 가져갔다는 사실을 포함하기",
            "워크숍에 참가한 소감이나 앞으로의 의향을 덧붙이기"),
        listOf("사실과 생각이 자연스럽게 이어지는 한 문단으로 쓰기", "과거 경험을 나타내는 표현을 사용하기"))

    private fun clinic() = task(WritingType.TRANSLATION,
        "다음 한국어 문단을 일본어로 번역하세요. 다음 주 월요일 오전에 병원 예약이 있지만 수업 시간과 겹칩니다. 병원에 예약을 오후로 바꿀 수 있는지 문의했습니다. 변경이 어렵다면 원래 예약 시간에 방문할 예정입니다.",
        listOf("다음 주 월요일 오전 병원 예약이 수업과 겹친다는 사실을 옮길 것", "병원에 예약을 오후로 바꿀 수 있는지 문의한 사실을 옮길 것",
            "변경이 어려우면 원래 예약 시간에 방문할 계획을 옮길 것"))

    private fun weekend() = task(WritingType.FREE,
        "지난 주말에 한 활동을 자유롭게 정해 일본어로 한 문단을 쓰세요. 그 활동에서 무엇을 했는지, 누구와 함께했는지, 가장 인상 깊었던 점이 무엇인지, 그리고 다음 주말에 하고 싶은 일을 연결해서 표현하세요.",
        listOf("주말에 한 활동을 구체적으로 제시할 것", "누구와 함께했는지 밝힐 것", "가장 인상 깊었던 점을 설명할 것",
            "다음 주말에 하고 싶은 일을 표현할 것", "내용을 연결된 한 문단으로 작성할 것"))
}
