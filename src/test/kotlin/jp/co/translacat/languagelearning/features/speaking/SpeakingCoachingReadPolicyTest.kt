package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.support.SettingsFixtures
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingCoachingReadPolicy
import jp.co.translacat.languagelearning.features.speaking.domain.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.LocalDateTime
import kotlin.test.*

class SpeakingCoachingReadPolicyTest {
    @Test
    fun `현재 근거와 일치하는 저장 코칭과 도움말 사용 표시는 그대로 읽는다`() {
        // 준비
        val fixture = SpeakingCoachingReadFixtures()

        // 실행
        val result = fixture.read()

        // 검증: 새로운 점수·코칭을 만들거나 저장된 생성 상태를 변경하지 않는다.
        assertEquals("AVAILABLE", result.getValue("evidenceAvailability").jsonPrimitive.content)
        assertEquals(fixture.result.response["items"], result["items"])
        assertEquals("GROUNDED", result.getValue("contentStatus").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), result["evidenceLimitations"])
        assertFalse("overallScore" in result)
    }

    @Test
    fun `삭제 제외 revision 전사 도움말 변경은 해당 인용을 숨기되 다른 근거는 보존한다`() {
        // 준비
        val fixture = SpeakingCoachingReadFixtures()
        val first = fixture.turns.first()
        val changed = listOf(
            fixture.turns.drop(1),
            listOf(first.copy(excludedFromEvaluation = true), fixture.turns.last()),
            listOf(first.copy(recordingRevision = 2), fixture.turns.last()),
            listOf(first.copy(content = first.content.copy(transcript = null)), fixture.turns.last()),
            listOf(first.copy(content = first.content.copy(transcript = "Different source")), fixture.turns.last()),
            listOf(first.copy(content = first.content.copy(sttConfidence = .2)), fixture.turns.last()),
            listOf(first.copy(content = first.content.copy(assistanceUsage = emptyList())), fixture.turns.last()),
            listOf(first.copy(sessionId = 999), fixture.turns.last()),
        )

        // 실행·검증: 첫 turn 삭제는 뒤 turn이 참조한 질문도 사라져 둘 다 제한될 수 있다.
        changed.forEachIndexed { index, turns ->
            val result = fixture.read(turns)
            assertTrue(result.getValue("evidenceAvailability").jsonPrimitive.content in setOf("LIMITED", "UNAVAILABLE"))
            assertTrue(result.getValue("items").jsonArray.none {
                it.jsonObject.getValue("evidence").jsonObject["turnId"] == JsonPrimitive(first.id.toString())
            }, "case $index")
            assertEquals("GROUNDED", result.getValue("contentStatus").jsonPrimitive.content)
        }
        assertEquals(2, fixture.result.response.getValue("items").jsonArray.size)
        assertEquals("UNAVAILABLE", fixture.read(emptyList()).getValue("evidenceAvailability").jsonPrimitive.content)
    }

    @Test
    fun `다른 세션 알 수 없는 정책과 잘못된 저장 hash는 정상 근거로 내보내지 않는다`() {
        // 준비
        val fixture = SpeakingCoachingReadFixtures()
        val mutatedResponse = JsonObject(fixture.result.response + ("sourceSnapshotHash" to JsonPrimitive("different")))
        val scenarios = listOf(
            SpeakingCoachingReadPolicy.project(fixture.session, fixture.result, null, fixture.turns),
            SpeakingCoachingReadPolicy.project(fixture.session, fixture.result.copy(sessionId = 999), fixture.job, fixture.turns),
            SpeakingCoachingReadPolicy.project(fixture.session, fixture.result, fixture.job.copy(sessionId = 999), fixture.turns),
            SpeakingCoachingReadPolicy.project(
                fixture.session.copy(snapshot = fixture.session.snapshot.copy(resultPolicyVersion = "future-policy")),
                fixture.result, fixture.job, fixture.turns,
            ),
            SpeakingCoachingReadPolicy.project(
                fixture.session, fixture.result.copy(response = mutatedResponse), fixture.job, fixture.turns,
            ),
        )

        // 검증
        scenarios.forEach {
            assertEquals("UNAVAILABLE", it.getValue("evidenceAvailability").jsonPrimitive.content)
            assertTrue(it.getValue("items").jsonArray.isEmpty())
        }
    }

    @Test
    fun `답하지 않은 질문이나 바뀐 이전 질문을 원래 응답 문맥처럼 표시하지 않는다`() {
        // 준비
        val fixture = SpeakingCoachingReadFixtures()
        val first = fixture.turns.first()

        // 실행
        val result = fixture.read(listOf(
            first.copy(content = first.content.copy(assistantText = "A different question")), fixture.turns.last(),
        ))

        // 검증: 첫 발화의 인용은 남고 바뀐 질문에 연결된 두 번째 인용은 숨긴다.
        assertEquals("LIMITED", result.getValue("evidenceAvailability").jsonPrimitive.content)
        assertEquals("1", result.getValue("items").jsonArray.single().jsonObject
            .getValue("evidence").jsonObject.getValue("turnId").jsonPrimitive.content)
    }

    @Test
    fun `인용되지 않은 turn이나 빈 코칭에서도 전체 원본이 달라지면 요약 근거를 제한한다`() {
        // 준비: 첫 turn만 인용한 코칭과 인용이 없는 기존 결과를 각각 구성한다.
        val fixture = SpeakingCoachingReadFixtures()
        val one = fixture.result.copy(response = JsonObject(fixture.result.response + (
            "items" to JsonArray(fixture.result.response.getValue("items").jsonArray.take(1))
        )))
        val empty = fixture.result.copy(response = JsonObject(fixture.result.response + mapOf(
            "items" to JsonArray(emptyList()), "contentStatus" to JsonPrimitive("NO_USABLE_EVIDENCE"),
        )))
        val changed = listOf(fixture.turns.first(), fixture.turns.last().copy(
            content = fixture.turns.last().content.copy(transcript = "Changed unquoted source"),
        ))

        // 실행
        val unquotedChange = SpeakingCoachingReadPolicy.project(fixture.session, one, fixture.job, changed)
        val originalEmpty = SpeakingCoachingReadPolicy.project(fixture.session, empty, fixture.job, fixture.turns)
        val changedEmpty = SpeakingCoachingReadPolicy.project(fixture.session, empty, fixture.job, emptyList())

        // 검증: 빈 items만으로 원본 유효성을 승인하지 않는다.
        assertEquals("LIMITED", unquotedChange.getValue("evidenceAvailability").jsonPrimitive.content)
        assertEquals(1, unquotedChange.getValue("items").jsonArray.size)
        assertEquals("AVAILABLE", originalEmpty.getValue("evidenceAvailability").jsonPrimitive.content)
        assertEquals("UNAVAILABLE", changedEmpty.getValue("evidenceAvailability").jsonPrimitive.content)
    }

    @Test
    fun `제출 당시 이미 제외된 원본도 빈 코칭의 요약 근거로 다시 노출하지 않는다`() {
        // 준비: 현재 원본과 저장 요청 모두 같은 제외 상태이며 코칭 인용은 없는 경우다.
        val fixture = SpeakingCoachingReadFixtures()
        val turns = fixture.turns.map { it.copy(excludedFromEvaluation = true) }
        val request = JsonObject(fixture.job.request + ("userTurns" to JsonArray(
            fixture.job.request.getValue("userTurns").jsonArray.map {
                JsonObject(it.jsonObject + ("excludedFromEvaluation" to JsonPrimitive(true)))
            },
        )))
        val empty = fixture.result.copy(response = JsonObject(fixture.result.response + mapOf(
            "items" to JsonArray(emptyList()), "contentStatus" to JsonPrimitive("NO_USABLE_EVIDENCE"),
        )))

        // 실행
        val response = SpeakingCoachingReadPolicy.project(fixture.session, empty,
            fixture.job.copy(request = request), turns)

        // 검증: 변경이 없다는 사실만으로 제외된 대화의 요약을 AVAILABLE로 만들지 않는다.
        assertEquals("UNAVAILABLE", response.getValue("evidenceAvailability").jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive("SOURCE_EVIDENCE_CHANGED_OR_UNAVAILABLE"))),
            response["evidenceLimitations"])
        assertEquals("NO_USABLE_EVIDENCE", response.getValue("contentStatus").jsonPrimitive.content)
    }
}

internal class SpeakingCoachingReadFixtures(sessionId: Long = 17) {
    val now: LocalDateTime = LocalDateTime.parse("2026-10-03T01:00:00")
    val session: SpeakingSessionRecord = SpeakingSessionRecord(
        id = sessionId, userId = 41, createIdempotencyKey = "synthetic", learningDate = now.toLocalDate(),
        snapshot = SpeakingSessionSnapshot(
            null, "Synthetic topic", "FREE_TALK", null, null, null, null, emptyList(), "ko", "en",
            SpeakingPracticeMode.FREE, ConversationStartMode.AI_FIRST, ConversationStartMode.AI_FIRST,
            CorrectionMode.CONVERSATION, 5, 10, "marin", "NORMAL",
            SpeakingSessionPolicySnapshot.from(SettingsFixtures.admin()), null,
            SpeakingResultKind.SESSION_COACHING, "free-session-coaching-v1",
        ),
        opening = buildJsonObject { put("assistantText", "What did you do?") }, startedAt = now,
        sessionSummary = "Synthetic conversation summary.",
    )
    val turns = (1L..2L).map { id ->
        SpeakingTurnRecord(
            id = id, sessionId = session.id, turnIndex = id.toInt(), idempotencyKey = "turn-$id",
            recordingRevision = 1, status = SpeakingTurnStatus.READY,
            uploadToken = "synthetic", uploadExpiresAt = now.plusMinutes(1),
            content = SpeakingTurnContent(
                durationSeconds = 10.0, transcript = "I visited a museum $id.", sttConfidence = .9,
                assistantText = "Which exhibit did you enjoy?",
                assistanceUsage = listOf(SpeakingAssistanceType.SAMPLE_ANSWER),
            ),
        )
    }
    private val assistance = buildJsonArray {
        add(buildJsonObject { put("type", "SAMPLE_ANSWER"); put("count", 1) })
    }
    val request = buildJsonObject {
        put("requestId", "coaching-17")
        put("sessionId", session.id.toString())
        put("resultKind", "SESSION_COACHING")
        put("resultPolicyVersion", "free-session-coaching-v1")
        put("sourceSnapshotHash", "fixed-synthetic-source-hash")
        put("userTurns", JsonArray(turns.map { turn -> buildJsonObject {
            put("turnId", turn.id.toString()); put("turnIndex", turn.turnIndex)
            put("recordingRevision", turn.recordingRevision); put("transcript", turn.content.transcript)
            put("excludedFromEvaluation", false); put("assistanceUsage", assistance)
        } }))
        put("assistantTurns", buildJsonArray {
            add(buildJsonObject { put("turnId", "opening"); put("turnIndex", 0); put("text", "What did you do?") })
            add(buildJsonObject { put("turnId", "1"); put("turnIndex", 1); put("text", "Which exhibit did you enjoy?") })
            add(buildJsonObject { put("turnId", "2"); put("turnIndex", 2); put("text", "Which exhibit did you enjoy?") })
        })
    }
    val job = SpeakingJobRecord(
        sessionId = session.id, problemIndex = 0, resultKind = SpeakingResultKind.SESSION_COACHING,
        resultPolicyVersion = "free-session-coaching-v1", sourceSnapshotHash = "fixed-synthetic-source-hash",
        request = request, availableAt = now,
    )
    val result = SpeakingResultRecord(
        sessionId = session.id, problemIndex = 0, resultKind = SpeakingResultKind.SESSION_COACHING,
        status = "GROUNDED", updatedAt = now, id = 31,
        response = buildJsonObject {
            listOf("requestId", "sessionId", "resultKind", "resultPolicyVersion", "sourceSnapshotHash")
                .forEach { put(it, request.getValue(it)) }
            put("schemaVersion", "speaking-session-coaching-schema-v1")
            put("contentStatus", "GROUNDED")
            put("limitationReasons", JsonArray(emptyList()))
            put("promptVersion", "speaking-session-coaching-prompt-v1")
            put("items", JsonArray(turns.map { turn -> buildJsonObject {
                put("observationId", "observation-${turn.id}"); put("kind", "ALTERNATIVE")
                put("message", "다른 표현을 확인하세요."); put("suggestedExpression", "I explored a museum.")
                put("suggestionIsLearnerEvidence", false)
                put("evidence", buildJsonObject {
                    put("turnId", turn.id.toString()); put("turnIndex", turn.turnIndex)
                    put("recordingRevision", turn.recordingRevision)
                    put("transcriptExcerpt", "visited a museum")
                    put("transcriptHash", MessageDigest.getInstance("SHA-256")
                        .digest(checkNotNull(turn.content.transcript).toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) })
                    put("referenceAssistantTurnId", if (turn.id == 1L) "opening" else "1")
                    put("assistanceUsage", assistance)
                    put("sourceProvenance", "AUTOMATIC_SPEECH_RECOGNITION")
                    put("verbatimAccuracyVerified", false)
                })
            } }))
        },
    )

    fun read(current: List<SpeakingTurnRecord> = turns) = SpeakingCoachingReadPolicy.project(session, result, job, current)
}
