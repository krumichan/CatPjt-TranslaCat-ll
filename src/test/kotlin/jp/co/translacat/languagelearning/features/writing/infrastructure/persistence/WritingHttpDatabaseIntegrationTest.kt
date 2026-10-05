package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import jp.co.translacat.languagelearning.bootstrap.configureInternalAuthentication
import jp.co.translacat.languagelearning.bootstrap.configureSerialization
import jp.co.translacat.languagelearning.bootstrap.configureStatusPages
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.writing.api.WritingAnswerRouteContext
import jp.co.translacat.languagelearning.features.writing.api.writingPreparedRoutes
import jp.co.translacat.languagelearning.features.writing.application.*
import jp.co.translacat.languagelearning.features.writing.domain.model.*
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingDiversityValidator
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingItemRevision
import jp.co.translacat.languagelearning.shared.ai.HttpModelExecution
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.shared.security.InternalApiSettings
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.Date
import kotlin.collections.ArrayDeque
import kotlin.collections.emptyList
import kotlin.collections.first
import kotlin.collections.getValue
import kotlin.collections.listOf
import kotlin.collections.map
import kotlin.collections.mapOf
import kotlin.collections.plus
import kotlin.collections.single
import kotlin.test.*

/** 실제 MySQL 상태→LL 업무 worker→Python 인증/범용 HTTP→합성 Provider→LL 판정/성장. */
class WritingHttpDatabaseIntegrationTest {
    @Test
    fun `인증된 Ktor Writing 생성 요청은 Python HTTP와 MySQL까지 연결되고 중복 게시를 막는다`() =
        LocalScratchMysql.use { db ->
            // 준비: JWT 사용자와 실제 scratch DB, 테스트 전용 Python Provider를 연결한다.
            val request = Json.parseToJsonElement(
                requireNotNull(
                    javaClass.getResource(
                        "/contracts/writing-candidate-policy-python-golden.json",
                    ),
                ).readText(),
            ).jsonArray.first().jsonObject.getValue("request").jsonObject
            val key = ByteArray(32) { it.toByte() }
            val auth = InternalApiSettings(
                enabled = true,
                secretBase64 = Base64.getEncoder().encodeToString(key),
            )

            fun token(userId: Long) = JWT.create().withIssuer("translacat-be")
                .withAudience("translacat-ll").withSubject(userId.toString())
                .withClaim("service", "translacat-be").withClaim("tokenUse", "ll-internal")
                .withArrayClaim("roles", arrayOf("USER"))
                .withIssuedAt(Date.from(Instant.now()))
                .withExpiresAt(Date.from(Instant.now().plusSeconds(60)))
                .sign(Algorithm.HMAC256(key))

            val root = "/internal/v1/language-learning/writing/daily/sets"
            DatabaseFactory(db.settings()).use { factory ->
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), Clock.systemUTC())
                val state = WritingGenerationState(work)
                HttpModelExecution(
                    requireNotNull(System.getenv("LL_TEST_AI_URL")),
                    "synthetic-local-model-key",
                ).use { model ->
                    val ids = ArrayDeque(listOf("synthetic-candidate", "synthetic-regenerate-review"))
                    val worker = WritingGenerationWorker(
                        state, work, WritingGenerationExecution(model),
                        WritingReviewExecution(model), candidateId = { ids.removeFirst() },
                        regeneration = WritingRegenerationState(work),
                    )
                    testApplication {
                        environment { config = MapApplicationConfig() }
                        application {
                            configureSerialization()
                            configureStatusPages()
                            configureInternalAuthentication(auth)
                            routing {
                                writingPreparedRoutes(
                                    state, worker, WritingReadService(work),
                                    createSet = { userId, type ->
                                        state.getOrCreate(
                                            NewWritingSet(
                                                userId, LocalDate.parse("2026-09-26"),
                                                type, request.getValue("requestId").jsonPrimitive.content,
                                                1, request.toString(),
                                            ),
                                        )
                                    },
                                    readContext = { LocalDate.parse("2026-09-26") to 7 },
                                    answers = WritingAnswerState(work),
                                    evaluation = WritingEvaluationWorker(
                                        WritingEvaluationState(work),
                                        WritingEvaluationExecution(model),
                                    ),
                                    answerContext = {
                                        WritingAnswerRouteContext(
                                            LocalDate.parse("2026-09-26"),
                                            7, true, "ko", "en",
                                        )
                                    },
                                    regenerationAllowed = { true },
                                )
                            }
                        }

                        // 실행: 미인증 요청과 잘못된 본문을 거부하고 정상 요청을 비동기 게시한다.
                        assertEquals(
                            HttpStatusCode.Unauthorized,
                            client.post(root) {
                                contentType(ContentType.Application.Json)
                                setBody("""{"writingType":"FREE"}""")
                            }.status,
                        )
                        assertEquals(
                            HttpStatusCode.BadRequest,
                            client.post(root) {
                                bearerAuth(token(622))
                                contentType(ContentType.Application.Json)
                                setBody("{}")
                            }.status,
                        )
                        val created = client.post(root) {
                            bearerAuth(token(622))
                            contentType(ContentType.Application.Json)
                            setBody("""{"writingType":"FREE"}""")
                        }
                        assertEquals(HttpStatusCode.Accepted, created.status)
                        val publicSetId = Json.parseToJsonElement(created.bodyAsText()).jsonObject
                            .getValue("dailySetId").jsonPrimitive.long
                        val setId = LearningPublicId.decode(publicSetId.toString())
                        withTimeout(30_000) {
                            while (state.find(622, setId)?.status != WritingSetStatus.READY) delay(100)
                        }
                        val duplicate = client.post(root) {
                            bearerAuth(token(622))
                            contentType(ContentType.Application.Json)
                            setBody("""{"writingType":"FREE"}""")
                        }
                        val owned = client.get("$root/$publicSetId") { bearerAuth(token(622)) }
                        val foreign = client.get("$root/$publicSetId") { bearerAuth(token(623)) }
                        val legacy = client.post("$root/$setId/regenerate") { bearerAuth(token(622)) }

                        // 검증: 소유권과 idempotency가 유지되며 승인 문항은 하나다.
                        assertEquals(HttpStatusCode.OK, duplicate.status)
                        assertEquals(HttpStatusCode.OK, owned.status)
                        val body = Json.parseToJsonElement(owned.bodyAsText()).jsonObject
                        assertEquals("READY", body.getValue("status").jsonPrimitive.content)
                        assertEquals(1, body.getValue("generatedItemCount").jsonPrimitive.int)
                        assertEquals(1, body.getValue("items").jsonArray.size)
                        val item = body.getValue("items").jsonArray.single().jsonObject
                        assertTrue(item.getValue("canSubmit").jsonPrimitive.boolean)
                        assertEquals(HttpStatusCode.BadRequest, foreign.status)
                        assertEquals(
                            "LANGUAGE_LEARNING_DAILY_SET_NOT_FOUND",
                            Json.parseToJsonElement(foreign.bodyAsText())
                                .jsonObject.getValue("code").jsonPrimitive.content,
                        )
                        assertEquals(HttpStatusCode.BadRequest, legacy.status)
                        assertEquals(
                            "LEARNING_ID_INVALID",
                            Json.parseToJsonElement(legacy.bodyAsText())
                                .jsonObject.getValue("code").jsonPrimitive.content,
                        )
                        assertEquals(1, work.read { items.count(622, setId) })
                        assertEquals(WritingSetStatus.READY, state.find(622, setId)?.status)

                        // 실행: 현재 문항 revision을 넣어 답변을 제출하고 평가 완료를 기다린다.
                        work.write(622) {
                            growth.saveProfile(
                                GrowthProfile(
                                    622, state = "ACTIVE",
                                    createdAt = nowUtc, updatedAt = nowUtc,
                                ),
                            )
                        }
                        val itemId = item.getValue("itemId").jsonPrimitive.long
                        val answerBody = buildJsonObject {
                            put("answer", "Synthetic answer")
                            put("contentRevision", item.getValue("contentRevision"))
                        }.toString()
                        val submitted =
                            client.post("/internal/v1/language-learning/writing/daily/items/$itemId/answers") {
                                bearerAuth(token(622))
                                contentType(ContentType.Application.Json)
                                setBody(answerBody)
                            }
                        assertEquals(HttpStatusCode.OK, submitted.status)
                        val answerId = LearningPublicId.decode(
                            Json.parseToJsonElement(submitted.bodyAsText()).jsonObject
                                .getValue("answerId").jsonPrimitive.content,
                        )
                        withTimeout(30_000) {
                            while (state.find(622, setId)?.status != WritingSetStatus.COMPLETED) {
                                val status = WritingReadService(work).answer(622, answerId)
                                    ?.getValue("evaluationStatus")?.jsonPrimitive?.content
                                check(status != "FAILED") { "WRITING_TEST_EVALUATION_FAILED" }
                                delay(100)
                            }
                        }
                        val duplicateAnswer = client.post(
                            "/internal/v1/language-learning/writing/daily/items/$itemId/answers",
                        ) {
                            bearerAuth(token(622))
                            contentType(ContentType.Application.Json)
                            setBody(answerBody)
                        }

                        // 검증: 원본 평가·성장을 한 번만 반영하고 당일 중복 답변을 거부한다.
                        assertEquals(HttpStatusCode.BadRequest, duplicateAnswer.status)
                        assertEquals(
                            "LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED",
                            Json.parseToJsonElement(duplicateAnswer.bodyAsText())
                                .jsonObject.getValue("code").jsonPrimitive.content,
                        )
                        val result = Json.parseToJsonElement(
                            client.get("$root/$publicSetId") {
                                bearerAuth(token(622))
                            }.bodyAsText(),
                        ).jsonObject
                        assertEquals(
                            "SUCCESS",
                            result.getValue("items").jsonArray.single().jsonObject
                                .getValue("attempts").jsonArray.single().jsonObject
                                .getValue("evaluationStatus").jsonPrimitive.content,
                        )
                        assertEquals(1, work.read { growth.profile(622)?.evaluationCount })

                        // 실행: 다른 사용자의 미응답 READY 문항은 동기 재생성 후에만 교체한다.
                        val seed = JsonObject(
                            request + mapOf(
                                "requestId" to JsonPrimitive("synthetic-regeneration-seed"),
                                "snapshotId" to JsonPrimitive("synthetic-regeneration-seed"),
                            ),
                        )
                        val regeneratingSet = state.getOrCreate(
                            NewWritingSet(
                                624,
                                LocalDate.parse("2026-09-26"), WritingType.FREE,
                                "synthetic-regeneration-seed", 1, seed.toString(),
                            ),
                        )
                        val claim = assertNotNull(state.claimNext(624, regeneratingSet.id))
                        assertTrue(
                            state.publishItem(
                                624, regeneratingSet.id, claim,
                                NewWritingItem(
                                    1, WritingDifficulty.NORMAL, "원래 학습 문항입니다.", emptyList(),
                                    listOf("MEANING"), "조건과 이유를 연결합니다.",
                                ),
                                "v1",
                            ),
                        )
                        val original = work.read { items.list(624, regeneratingSet.id).single() }
                        val regenerated =
                            client.post("$root/${LearningPublicId.encode(regeneratingSet.id)}/regenerate") {
                                bearerAuth(token(624))
                            }

                        // 검증: 같은 item ID의 미응답 내용만 독립 검증 후 교체한다.
                        assertEquals(HttpStatusCode.OK, regenerated.status)
                        val replacement = Json.parseToJsonElement(regenerated.bodyAsText()).jsonObject
                        assertEquals(1, replacement.getValue("regenerationCount").jsonPrimitive.int)
                        val current = work.read { items.list(624, regeneratingSet.id).single() }
                        assertEquals(original.id, current.id)
                        assertEquals("친구에게 새 약속 시간을 알려 주고 이유를 설명해 주세요.", current.originText)

                        // 실행: 별도 세트에서 합법적 Provider 거부를 받은 동기 재생성을 호출한다.
                        val refusalSeed = JsonObject(
                            request + mapOf(
                                "requestId" to JsonPrimitive("synthetic-regeneration-refusal-seed"),
                                "snapshotId" to JsonPrimitive("synthetic-regeneration-refusal-seed"),
                            ),
                        )
                        val refusedSet = state.getOrCreate(
                            NewWritingSet(
                                625,
                                LocalDate.parse("2026-09-26"), WritingType.FREE,
                                "synthetic-regeneration-refusal-seed", 1, refusalSeed.toString(),
                            ),
                        )
                        val refusalClaim = assertNotNull(state.claimNext(625, refusedSet.id))
                        assertTrue(
                            state.publishItem(
                                625, refusedSet.id, refusalClaim,
                                NewWritingItem(
                                    1, WritingDifficulty.NORMAL, "원래 학습 문항입니다.", emptyList(),
                                    listOf("MEANING"), "조건과 이유를 연결합니다.",
                                ),
                                "v1",
                            ),
                        )
                        val beforeRefusal = work.read { items.list(625, refusedSet.id).single() }
                        val refusedRegeneration =
                            client.post("$root/${LearningPublicId.encode(refusedSet.id)}/regenerate") {
                                bearerAuth(token(625))
                            }

                        // 검증: 기존 문항/revision과 횟수를 보존하고 lease만 해제한다.
                        assertEquals(HttpStatusCode.UnprocessableEntity, refusedRegeneration.status)
                        assertEquals(
                            "WRITING_GENERATION_VALIDATION_EXHAUSTED",
                            Json.parseToJsonElement(refusedRegeneration.bodyAsText()).jsonObject
                                .getValue("code").jsonPrimitive.content,
                        )
                        val afterRefusal = work.read { items.list(625, refusedSet.id).single() }
                        assertEquals(beforeRefusal, afterRefusal)
                        assertEquals(0, state.find(625, refusedSet.id)?.regenerationCount)
                        assertEquals(null, state.find(625, refusedSet.id)?.generationToken)
                        assertEquals(WritingSetStatus.READY, state.find(625, refusedSet.id)?.status)
                    }
                }
            }
        }

    @Test
    fun `재기동 뒤 만료된 생성 lease는 저장된 snapshot으로 한 번만 복구한다`() =
        LocalScratchMysql.use { db ->
            // 준비: 생성 전 세트를 첫 DB pool에 저장하고 pool을 닫아 재기동 간격을 만든다.
            val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
            val request = Json.parseToJsonElement(
                requireNotNull(
                    javaClass.getResource(
                        "/contracts/writing-candidate-policy-python-golden.json",
                    ),
                ).readText(),
            ).jsonArray.first().jsonObject.getValue("request").jsonObject
            var setId = 0L
            DatabaseFactory(db.settings()).use { first ->
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(first.database, 4), Clock.systemUTC())
                runBlocking {
                    setId = WritingGenerationState(work).getOrCreate(
                        NewWritingSet(
                            621,
                            LocalDate.parse("2026-09-26"), WritingType.FREE, "synthetic-draft", 1,
                            request.toString(),
                        ),
                    ).id
                }
            }
            DatabaseFactory(db.settings()).use { restarted ->
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(restarted.database, 4), Clock.systemUTC())
                val state = WritingGenerationState(work)
                HttpModelExecution(url, "synthetic-local-model-key").use { model ->
                    val worker = WritingGenerationWorker(
                        state, work, WritingGenerationExecution(model),
                        WritingReviewExecution(model), candidateId = { "synthetic-candidate" },
                    )
                    val recovery = WritingGenerationRecovery(state, worker)

                    // 실행: 새 pool에서 대상 조회와 독립 생성·검증을 수행하고 다시 조회한다.
                    runBlocking {
                        assertEquals(listOf(setId), state.recoverable().map { it.setId })
                        val first = recovery.runOnce()
                        val second = recovery.runOnce()

                        // 검증: 요청의 원래 snapshot으로 한 문항만 게시하고 재실행은 비어 있다.
                        assertEquals(1, first.single().published)
                        assertTrue(second.isEmpty())
                        assertEquals(WritingSetStatus.READY, state.find(621, setId)?.status)
                        assertEquals(1, work.read { items.count(621, setId) })
                    }
                }
            }
        }

    @Test
    fun `일시적 생성 Provider 실패는 기존 생성 횟수 안에서만 다시 호출한다`() =
        LocalScratchMysql.use { db ->
            // 준비: 첫 생성만 일시적 오류를 반환하는 합성 Provider와 새 Writing 세트를 둔다.
            val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
            val baseline = Json.parseToJsonElement(
                requireNotNull(
                    javaClass.getResource(
                        "/contracts/writing-candidate-policy-python-golden.json",
                    ),
                ).readText(),
            ).jsonArray.first().jsonObject.getValue("request").jsonObject
            val request = JsonObject(baseline + ("requestId" to JsonPrimitive("synthetic-generation-retry")))
            DatabaseFactory(db.settings()).use { factory ->
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), Clock.systemUTC())
                val state = WritingGenerationState(work)
                HttpModelExecution(url, "synthetic-local-model-key").use { model ->
                    runBlocking {
                        val set = state.getOrCreate(
                            NewWritingSet(
                                620, LocalDate.parse("2026-09-26"),
                                WritingType.FREE, "synthetic-generation-retry", 1, request.toString(),
                            ),
                        )
                        val worker = WritingGenerationWorker(
                            state, work, WritingGenerationExecution(model),
                            WritingReviewExecution(model), candidateId = { "synthetic-candidate" },
                        )

                        // 실행: 실제 범용 HTTP의 일시적 실패 뒤 다음 기존 생성 시도를 사용한다.
                        val result = worker.process(620, set.id, request)

                        // 검증: 추가 예산 없이 두 생성 호출과 한 독립 검증으로 한 문항만 게시한다.
                        assertEquals(null, result.failureCode)
                        assertEquals(2, result.generationCalls)
                        assertEquals(1, result.reviewCalls)
                        assertEquals(1, result.published)
                        assertEquals(1, work.read { items.count(620, set.id) })
                    }
                }
            }
        }

    @Test
    fun `재생성 Provider 거부는 기존 문항과 revision을 보존하고 lease만 해제한다`() =
        LocalScratchMysql.use { db ->
            // 준비: 미응답 READY 문항 하나와 거부 응답을 내는 합성 Provider를 둔다.
            val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
            val baseline = Json.parseToJsonElement(
                requireNotNull(
                    javaClass.getResource(
                        "/contracts/writing-candidate-policy-python-golden.json",
                    ),
                ).readText(),
            ).jsonArray.first().jsonObject.getValue("request").jsonObject
            val seed = JsonObject(
                baseline + mapOf(
                    "requestId" to JsonPrimitive("synthetic-regeneration-seed"),
                    "snapshotId" to JsonPrimitive("synthetic-regeneration-seed"),
                ),
            )
            val request = JsonObject(seed + ("requestId" to JsonPrimitive("synthetic-regenerate-refusal")))
            DatabaseFactory(db.settings()).use { factory ->
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), Clock.systemUTC())
                val state = WritingGenerationState(work)
                HttpModelExecution(url, "synthetic-local-model-key").use { model ->
                    runBlocking {
                        val set = state.getOrCreate(
                            NewWritingSet(
                                619, LocalDate.parse("2026-09-26"),
                                WritingType.FREE, "synthetic-regeneration-seed", 1, seed.toString(),
                            ),
                        )
                        val initial = assertNotNull(state.claimNext(619, set.id))
                        assertTrue(
                            state.publishItem(
                                619, set.id, initial,
                                NewWritingItem(
                                    1, WritingDifficulty.NORMAL, "원래 학습 문항입니다.", emptyList(), listOf("MEANING"),
                                    "조건과 이유를 연결합니다.",
                                ),
                                "v1",
                            ),
                        )
                        val original = work.read { items.list(619, set.id).single() }
                        val revision = WritingItemRevision.of(original)
                        val worker = WritingGenerationWorker(
                            state, work, WritingGenerationExecution(model),
                            WritingReviewExecution(model), regeneration = WritingRegenerationState(work),
                        )

                        // 실행: 정상 형식의 Provider 거부를 받은 재생성은 교체를 실행하지 않는다.
                        val result = worker.regenerate(619, set.id, request)

                        // 검증: 기존 문항과 revision이 그대로이고 횟수·lease도 늘지 않는다.
                        assertEquals("WRITING_GENERATION_VALIDATION_EXHAUSTED", result.failureCode)
                        assertEquals(0, result.published)
                        assertEquals(4, result.generationCalls)
                        val retained = work.read { items.list(619, set.id).single() }
                        assertEquals(original, retained)
                        assertEquals(revision, WritingItemRevision.of(retained))
                        assertEquals(0, state.find(619, set.id)?.regenerationCount)
                        assertEquals(null, state.find(619, set.id)?.generationToken)
                        assertEquals(WritingSetStatus.READY, state.find(619, set.id)?.status)
                    }
                }
            }
        }

    @Test
    fun `재생성은 새 문항 검증을 마친 뒤 미응답 원본만 한 트랜잭션에서 교체한다`() =
        LocalScratchMysql.use { db ->
            // 준비: 원본 한 문항을 게시해 READY 세트를 만든 뒤 재생성 요청을 준비한다.
            val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
            val baseline = Json.parseToJsonElement(
                requireNotNull(
                    javaClass.getResource(
                        "/contracts/writing-candidate-policy-python-golden.json",
                    ),
                ).readText(),
            ).jsonArray.first().jsonObject.getValue("request").jsonObject
            val seed = JsonObject(
                baseline + mapOf(
                    "requestId" to JsonPrimitive("synthetic-regeneration-seed"),
                    "snapshotId" to JsonPrimitive("synthetic-regeneration-seed"),
                ),
            )
            val request = JsonObject(seed + ("requestId" to JsonPrimitive("synthetic-regenerate")))
            DatabaseFactory(db.settings()).use { factory ->
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), Clock.systemUTC())
                val state = WritingGenerationState(work)
                HttpModelExecution(url, "synthetic-local-model-key").use { model ->
                    runBlocking {
                        val set = state.getOrCreate(
                            NewWritingSet(
                                617, LocalDate.parse("2026-09-26"),
                                WritingType.FREE, "synthetic-regeneration-seed", 1, seed.toString(),
                            ),
                        )
                        val initial = assertNotNull(state.claimNext(617, set.id))
                        assertTrue(
                            state.publishItem(
                                617, set.id, initial,
                                NewWritingItem(
                                    1, WritingDifficulty.NORMAL, "원래 학습 문항입니다.", emptyList(), listOf("MEANING"),
                                    "조건과 이유를 연결합니다.",
                                    diversityMetadataJson = JsonObject(
                                        mapOf(
                                            "scenarioCategory" to JsonPrimitive("WORK"),
                                            "communicativeIntent" to JsonPrimitive("REQUEST"),
                                            "taskArchetype" to JsonPrimitive("old prompt"),
                                            "semanticSummary" to JsonPrimitive("기존 문항"),
                                        ),
                                    ).toString(),
                                ),
                                "v1",
                            ),
                        )
                        val original = work.read { items.list(617, set.id).single() }
                        val revision = WritingItemRevision.of(original)
                        val ids = ArrayDeque(listOf("synthetic-regenerate-review"))
                        val worker = WritingGenerationWorker(
                            state, work, WritingGenerationExecution(model),
                            WritingReviewExecution(model), candidateId = { ids.removeFirst() },
                            regeneration = WritingRegenerationState(work),
                        )

                        // 실행: 새 후보의 Python 범용 실행 HTTP와 독립 Mini 검증을 끝낸 뒤 교체한다.
                        val result = worker.regenerate(617, set.id, request)

                        // 검증: 기존 item ID만 보존하고 내용/revision을 한 번 변경한다.
                        assertEquals(null, result.failureCode)
                        assertEquals(1, result.published)
                        assertEquals(1, result.generationCalls)
                        assertEquals(1, result.reviewCalls)
                        val replaced = work.read { items.list(617, set.id).single() }
                        assertEquals(original.id, replaced.id)
                        assertEquals("친구에게 새 약속 시간을 알려 주고 이유를 설명해 주세요.", replaced.originText)
                        assertTrue(revision != WritingItemRevision.of(replaced))
                        assertEquals(1, state.find(617, set.id)?.regenerationCount)
                    }
                }
            }
        }

    @Test
    fun `확정 B4 불일치 두 건은 B3 목표를 유지한 마지막 방향 생성 한 번만 수행한다`() =
        LocalScratchMysql.use { db ->
            // 준비: 같은 슬롯의 B4 관측 두 건과 B3 최종 후보를 내는 합성 Provider를 둔다.
            val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
            val baseline = Json.parseToJsonElement(
                requireNotNull(
                    javaClass.getResource(
                        "/contracts/writing-candidate-policy-python-golden.json",
                    ),
                ).readText(),
            ).jsonArray.first().jsonObject.getValue("request").jsonObject
            val request = JsonObject(baseline + ("requestId" to JsonPrimitive("synthetic-direction")))
            DatabaseFactory(db.settings()).use { factory ->
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), Clock.systemUTC())
                val state = WritingGenerationState(work)
                HttpModelExecution(url, "synthetic-local-model-key").use { model ->
                    runBlocking {
                        val set = state.getOrCreate(
                            NewWritingSet(
                                616, LocalDate.parse("2026-09-26"),
                                WritingType.FREE, "synthetic-direction", 1, request.toString(),
                            ),
                        )
                        val ids = ArrayDeque(
                            listOf(
                                "synthetic-direction-first",
                                "synthetic-direction-adjudication", "synthetic-direction-second",
                                "synthetic-direction-final",
                            ),
                        )
                        val worker = WritingGenerationWorker(
                            state, work, WritingGenerationExecution(model),
                            WritingReviewExecution(model), candidateId = { ids.removeFirst() },
                        )

                        // 실행: 일반 검증과 인접 band 재검증 뒤 기존 예산의 방향 생성으로 진행한다.
                        val result = worker.process(616, set.id, request)

                        // 검증: 불일치 문항을 덮어쓰지 않고 B3 문항 하나만 게시한다.
                        assertEquals(
                            null, result.failureCode,
                            "generation=${result.generationCalls}, review=${result.reviewCalls}",
                        )
                        assertEquals(2, result.generationCalls)
                        assertEquals(4, result.reviewCalls)
                        assertEquals(1, result.published)
                        val item = work.read { items.list(616, set.id).single() }
                        assertEquals(3, item.languageComplexityBand)
                        assertEquals("친구에게 내일 만날 시간을 알려 주고 이유를 설명해 주세요.", item.originText)
                    }
                }
            }
        }

    @Test
    fun `원문 의미 보존 실패는 게시하지 않고 후속 Provider 거부도 별도 실패로 남긴다`() =
        LocalScratchMysql.use { db ->
            // 준비: 의미가 바뀌었다는 합법적인 Mini 판정과 후속 생성의 합법적 거부를 고정한다.
            val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
            val fixture = Json.parseToJsonElement(
                requireNotNull(
                    javaClass.getResource(
                        "/contracts/writing-source-recovery-python-golden.json",
                    ),
                ).readText(),
            ).jsonObject
            val request = JsonObject(
                fixture.getValue("request").jsonObject +
                    ("requestId" to JsonPrimitive("synthetic-source-preservation-fail")),
            )
            DatabaseFactory(db.settings()).use { factory ->
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), Clock.systemUTC())
                val state = WritingGenerationState(work)
                HttpModelExecution(url, "synthetic-local-model-key").use { model ->
                    runBlocking {
                        val set = state.getOrCreate(
                            NewWritingSet(
                                615, LocalDate.parse("2026-09-26"),
                                WritingType.TRANSLATION, "synthetic-source-preservation-fail", 1,
                                request.toString(),
                            ),
                        )
                        val ids = ArrayDeque(
                            listOf(
                                "synthetic-source-original", "synthetic-source-batch",
                                "synthetic-source-review-fail",
                            ),
                        )
                        val worker = WritingGenerationWorker(
                            state, work, WritingGenerationExecution(model),
                            WritingReviewExecution(model), candidateId = { ids.removeFirst() },
                            sourceRecovery = WritingSourceRecoveryExecution(model),
                        )

                        // 실행: 수정본 검증 거부 후 남은 새 생성에서 Provider가 정상 거부한다.
                        val result = worker.process(615, set.id, request)

                        // 검증: 원본 generation.py처럼 script 복구 뒤 의미 거부는 일반 검증 소진이다.
                        // 유효한 복구 후보를 얻으면 source_exhausted를 해제한 뒤 의미 검증을 수행한다.
                        assertEquals("WRITING_GENERATION_VALIDATION_EXHAUSTED", result.failureCode)
                        assertEquals(2, result.generationCalls)
                        assertEquals(2, result.reviewCalls)
                        assertEquals(0, result.published)
                        assertEquals(0, work.read { items.count(615, set.id) })
                        assertEquals(WritingSetStatus.FAILED, state.find(615, set.id)?.status)
                    }
                }
            }
        }

    @Test
    fun `원문 제안 hash가 틀리면 게시하지 않고 남은 한 번의 새 생성만 사용한다`() =
        LocalScratchMysql.use { db ->
            // 준비: 첫 제안의 item hash가 잘못된 합성 Provider 사례를 분리한다.
            val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
            val fixture = Json.parseToJsonElement(
                requireNotNull(
                    javaClass.getResource(
                        "/contracts/writing-source-recovery-python-golden.json",
                    ),
                ).readText(),
            ).jsonObject
            val request = JsonObject(
                fixture.getValue("request").jsonObject +
                    ("requestId" to JsonPrimitive("synthetic-source-bad-proposal")),
            )
            DatabaseFactory(db.settings()).use { factory ->
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), Clock.systemUTC())
                val state = WritingGenerationState(work)
                HttpModelExecution(url, "synthetic-local-model-key").use { model ->
                    runBlocking {
                        val set = state.getOrCreate(
                            NewWritingSet(
                                614, LocalDate.parse("2026-09-26"),
                                WritingType.TRANSLATION, "synthetic-source-bad-proposal", 1,
                                request.toString(),
                            ),
                        )
                        val ids = ArrayDeque(
                            listOf(
                                "synthetic-source-original", "synthetic-bad-source-batch",
                                "synthetic-source-fresh",
                            ),
                        )
                        val worker = WritingGenerationWorker(
                            state, work, WritingGenerationExecution(model),
                            WritingReviewExecution(model), candidateId = { ids.removeFirst() },
                            sourceRecovery = WritingSourceRecoveryExecution(model),
                        )

                        // 실행: 잘못된 제안을 버린 뒤 축소 문맥의 새 후보를 독립 검증한다.
                        val result = worker.process(614, set.id, request)

                        // 검증: 원문 제안은 저장되지 않고 새 후보 하나만 게시된다.
                        assertEquals(
                            null, result.failureCode,
                            "generation=${result.generationCalls}, review=${result.reviewCalls}",
                        )
                        assertEquals(2, result.generationCalls)
                        assertEquals(2, result.reviewCalls)
                        assertEquals(1, result.published)
                        assertEquals(
                            fixture.getValue("localized").jsonObject.getValue("originText")
                                .jsonPrimitive.content,
                            work.read { items.list(614, set.id).single().originText },
                        )
                    }
                }
            }
        }

    @Test
    fun `TRANSLATION 원문 script 오류는 필드 제안과 독립 의미 검증 후에만 게시한다`() =
        LocalScratchMysql.use { db ->
            // 준비: 합성 Python 계약과 격리 MySQL에 생성 중인 세트를 둔다.
            val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
            val fixture = Json.parseToJsonElement(
                requireNotNull(
                    javaClass.getResource(
                        "/contracts/writing-source-recovery-python-golden.json",
                    ),
                ).readText(),
            ).jsonObject
            val request = fixture.getValue("request").jsonObject
            DatabaseFactory(db.settings()).use { factory ->
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), Clock.systemUTC())
                val state = WritingGenerationState(work)
                HttpModelExecution(url, "synthetic-local-model-key").use { model ->
                    runBlocking {
                        val set = state.getOrCreate(
                            NewWritingSet(
                                613, LocalDate.parse("2026-09-26"),
                                WritingType.TRANSLATION, "synthetic-source", 1, request.toString(),
                            ),
                        )
                        val ids = ArrayDeque(
                            listOf(
                                "synthetic-source-original", "synthetic-source-batch",
                                "synthetic-source-review",
                            ),
                        )
                        val worker = WritingGenerationWorker(
                            state, work, WritingGenerationExecution(model),
                            WritingReviewExecution(model), candidateId = { ids.removeFirst() },
                            sourceRecovery = WritingSourceRecoveryExecution(model),
                        )

                        // 실행: LL worker가 Python 범용 실행 API와 테스트 Provider를 호출한다.
                        val result = worker.process(613, set.id, request)

                        // 검증: 제안만으로 게시하지 않고 복구본의 새 Mini 판정 뒤에 저장한다.
                        assertEquals(
                            null, result.failureCode,
                            "generation=${result.generationCalls}, review=${result.reviewCalls}, diagnostic=${result.diagnosticCode}",
                        )
                        assertEquals(1, result.generationCalls)
                        assertEquals(2, result.reviewCalls)
                        assertEquals(1, result.published)
                        assertEquals(WritingSetStatus.READY, state.find(613, set.id)?.status)
                        val items = work.read { items.list(613, set.id) }
                        assertEquals(1, items.size)
                        assertEquals(
                            fixture.getValue("localized").jsonObject.getValue("originText")
                                .jsonPrimitive.content,
                            items.single().originText,
                        )
                    }
                }
            }
        }

    @Test
    fun `note 언어 오류는 Nano 제안과 별도 Mini 검증 후에만 MySQL에 게시한다`() = LocalScratchMysql.use { db ->
        val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
        val fixture = Json.parseToJsonElement(
            requireNotNull(
                javaClass.getResource(
                    "/contracts/writing-note-python-golden.json",
                ),
            ).readText(),
        ).jsonObject
        val request = JsonObject(
            fixture.getValue("request").jsonObject +
                ("requestId" to JsonPrimitive("synthetic-note-generation")),
        )
        DatabaseFactory(db.settings()).use { factory ->
            val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), Clock.systemUTC())
            val state = WritingGenerationState(work)
            HttpModelExecution(url, "synthetic-local-model-key").use { model ->
                runBlocking {
                    val set = state.getOrCreate(
                        NewWritingSet(
                            610, LocalDate.parse("2026-09-26"),
                            WritingType.FREE, "synthetic-note-generation", 1, request.toString(),
                        ),
                    )
                    val ids = ArrayDeque(listOf("synthetic-note", "synthetic-note-review"))
                    val worker = WritingGenerationWorker(
                        state, work, WritingGenerationExecution(model),
                        WritingReviewExecution(model), candidateId = { ids.removeFirst() },
                        note = WritingNoteLocalizationExecution(model),
                    )
                    val result = worker.process(610, set.id, request)
                    assertEquals(null, result.failureCode)
                    assertEquals(1, result.published)
                    assertEquals(1, result.generationCalls)
                    assertEquals(3, result.reviewCalls)
                    val item = work.read { items.list(610, set.id).single() }
                    assertEquals(
                        fixture.getValue("localized").jsonObject.getValue("focusReason").jsonPrimitive.content,
                        item.focusReason,
                    )
                    assertEquals(WritingSetStatus.READY, state.find(610, set.id)?.status)
                }
            }
        }
    }

    @Test
    fun `B4 원본은 게시하지 않고 수정본의 독립 B5 검증 뒤에만 게시한다`() = LocalScratchMysql.use { db ->
        val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
        val fixture = Json.parseToJsonElement(
            requireNotNull(
                javaClass.getResource(
                    "/contracts/writing-repair-choice-http.json",
                ),
            ).readText(),
        ).jsonObject
        val request = fixture.getValue("request").jsonObject
        val revised = fixture.getValue("revised").jsonObject
        DatabaseFactory(db.settings()).use { factory ->
            val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), Clock.systemUTC())
            val state = WritingGenerationState(work)
            HttpModelExecution(url, "synthetic-local-model-key").use { model ->
                runBlocking {
                    val set = state.getOrCreate(
                        NewWritingSet(
                            611, LocalDate.parse("2026-09-26"),
                            WritingType.FREE, "synthetic-repair", 1, request.toString(),
                        ),
                    )
                    val ids = ArrayDeque(listOf("synthetic-repair-original", "synthetic-repair-revised"))
                    val worker = WritingGenerationWorker(
                        state, work, WritingGenerationExecution(model),
                        WritingReviewExecution(model), candidateId = { ids.removeFirst() },
                    )
                    val result = worker.process(611, set.id, request)
                    assertEquals(null, result.failureCode)
                    assertEquals(2, result.generationCalls)
                    assertEquals(2, result.reviewCalls)
                    assertEquals(1, result.published)
                    assertEquals(WritingSetStatus.READY, state.find(611, set.id)?.status)
                    val item = work.read { items.list(611, set.id).single() }
                    assertEquals(5, item.languageComplexityBand)
                    assertEquals(revised.getValue("originText").jsonPrimitive.content, item.originText)
                }
            }
        }
    }

    @Test
    fun `수정본 검증의 잘못된 교차 필드는 게시하지 않고 실패로 남긴다`() = LocalScratchMysql.use { db ->
        val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
        val fixture = Json.parseToJsonElement(
            requireNotNull(
                javaClass.getResource(
                    "/contracts/writing-repair-choice-http.json",
                ),
            ).readText(),
        ).jsonObject
        val request = fixture.getValue("request").jsonObject
        DatabaseFactory(db.settings()).use { factory ->
            val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), Clock.systemUTC())
            val state = WritingGenerationState(work)
            HttpModelExecution(url, "synthetic-local-model-key").use { model ->
                runBlocking {
                    val set = state.getOrCreate(
                        NewWritingSet(
                            612, LocalDate.parse("2026-09-26"),
                            WritingType.FREE, "synthetic-repair", 1, request.toString(),
                        ),
                    )
                    val ids = ArrayDeque(listOf("synthetic-repair-original", "synthetic-repaired-malformed"))
                    val worker = WritingGenerationWorker(
                        state, work, WritingGenerationExecution(model),
                        WritingReviewExecution(model), candidateId = { ids.removeFirst() },
                    )
                    val failed = worker.process(612, set.id, request)
                    assertEquals("VERIFIER_SCHEMA_INVALID", failed.failureCode)
                    assertEquals("REVISION_STATUS_ISSUE_MISMATCH", failed.diagnosticCode)
                    assertEquals(0, failed.published)
                    assertEquals(WritingSetStatus.FAILED, state.find(612, set.id)?.status)
                    assertEquals(0, work.read { items.count(612, set.id) })
                }
            }
        }
    }

    @Test
    fun `생성 worker가 실제 Python 범용 HTTP와 테스트 Provider를 거쳐 승인 문항만 MySQL에 게시한다`() =
        LocalScratchMysql.use { db ->
            val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
            val golden = Json.parseToJsonElement(
                requireNotNull(
                    javaClass.getResource(
                        "/contracts/writing-candidate-policy-python-golden.json",
                    ),
                ).readText(),
            ).jsonArray.first().jsonObject
            val baseline = golden.getValue("request").jsonObject
            val clock = Clock.systemUTC()
            var readySetId = 0L
            var refusedSetId = 0L
            DatabaseFactory(db.settings()).use { factory ->
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)
                val state = WritingGenerationState(work)
                HttpModelExecution(url, "synthetic-local-model-key").use { model ->
                    val worker = WritingGenerationWorker(
                        state, work, WritingGenerationExecution(model),
                        WritingReviewExecution(model), clock, candidateId = { "synthetic-candidate" },
                    )
                    runBlocking {
                        val set = state.getOrCreate(
                            NewWritingSet(
                                606, LocalDate.parse("2026-09-26"),
                                WritingType.FREE, "synthetic-draft", 1, baseline.toString(),
                            ),
                        )
                        readySetId = set.id
                        assertFailsWith<IllegalArgumentException> {
                            worker.process(
                                606, set.id,
                                JsonObject(
                                    baseline +
                                        ("originLanguage" to JsonPrimitive("ja")),
                                ),
                            )
                        }
                        assertEquals(WritingSetStatus.GENERATING, state.find(606, set.id)?.status)
                        val result = worker.process(606, set.id, baseline)
                        assertEquals(null, result.failureCode)
                        assertEquals(1, result.published)
                        assertEquals(1, result.generationCalls)
                        assertEquals(1, result.reviewCalls)
                        assertEquals(WritingSetStatus.READY, state.find(606, set.id)?.status)
                        assertEquals(0, worker.process(606, set.id, baseline).published)
                        val item = work.read { items.list(606, set.id).single() }
                        assertEquals(3, item.languageComplexityBand)
                        val metadata = Json.parseToJsonElement(assertNotNull(item.diversityMetadataJson)).jsonObject
                        assertEquals(
                            WritingDiversityValidator.hash(item.originText),
                            metadata.getValue("contentHash").jsonPrimitive.content,
                        )
                        val history = work.read {
                            fingerprints.context(
                                606, "en",
                                java.time.LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC),
                            )
                        }
                        assertEquals(1, history.getValue("sameFeatureRecent").jsonArray.size)
                        assertEquals(
                            metadata.getValue("contentHash"),
                            history.getValue("exactContentHashes90d").jsonArray.single(),
                        )

                        val refusalRequest = JsonObject(
                            baseline +
                                ("requestId" to JsonPrimitive("synthetic-generation-refusal")),
                        )
                        val refused = state.getOrCreate(
                            NewWritingSet(
                                607, LocalDate.parse("2026-09-26"),
                                WritingType.FREE, "synthetic-generation-refusal", 1, refusalRequest.toString(),
                            ),
                        )
                        refusedSetId = refused.id
                        val failed = worker.process(607, refused.id, refusalRequest)
                        assertEquals("WRITING_GENERATION_VALIDATION_EXHAUSTED", failed.failureCode)
                        assertEquals(0, failed.published)
                        assertEquals(WritingSetStatus.FAILED, state.find(607, refused.id)?.status)
                        assertEquals(0, work.read { items.count(607, refused.id) })
                    }
                }
            }
            DatabaseFactory(db.settings()).use { restarted ->
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(restarted.database, 4), clock)
                runBlocking {
                    assertEquals(1, work.read { items.list(606, readySetId).size })
                    assertEquals(0, work.read { items.list(607, refusedSetId).size })
                    assertEquals(
                        1,
                        work.read {
                            fingerprints.context(
                                606, "en",
                                java.time.LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC),
                            )
                                .getValue("sameFeatureRecent").jsonArray.size
                        },
                    )
                }
            }
        }

    @Test
    fun `평가 성공과 합법적 Provider 거부는 중복 없이 각 상태로 영속된다`() = LocalScratchMysql.use { db ->
        val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
        val clock = Clock.fixed(Instant.parse("2026-09-26T04:00:00Z"), ZoneOffset.UTC)
        DatabaseFactory(db.settings()).use { factory ->
            val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)
            val generation = WritingGenerationState(work)
            val evaluation = WritingEvaluationState(work)
            HttpModelExecution(url, "synthetic-local-model-key").use { model ->
                val worker = WritingEvaluationWorker(evaluation, WritingEvaluationExecution(model))
                val recovery = WritingEvaluationRecovery(evaluation, worker) {
                    WritingEvaluationLanguageContext("ko", "en", LocalDate.parse("2026-09-26"))
                }
                runBlocking {
                    // 준비: 서로 다른 답변 세 건을 PENDING으로 저장한다.
                    val success = prepare(707, "Synthetic answer", generation, work)
                    val refusal = prepare(808, "Synthetic refusal", generation, work)
                    val malformed = prepare(909, "Synthetic malformed", generation, work)

                    // 실행: PENDING scanner가 정상·거부·프로토콜 오류를 각 lease로 처리한다.
                    assertEquals(listOf(true, false, false), recovery.runOnce())
                    assertTrue(recovery.runOnce().isEmpty())
                    assertFalse(worker.process(707, success.second, "ko", "en", LocalDate.parse("2026-09-26")))

                    // 검증: 성공만 완료/Growth에 반영하고 나머지 답변은 재실행하지 않는다.
                    assertEquals(WritingSetStatus.COMPLETED, assertNotNull(generation.find(707, success.first)).status)
                    assertEquals(WritingSetStatus.READY, assertNotNull(generation.find(808, refusal.first)).status)
                    assertEquals(WritingSetStatus.READY, assertNotNull(generation.find(909, malformed.first)).status)
                    val reads = WritingReadService(work, clock)
                    val today = LocalDate.parse("2026-09-26")
                    val succeeded = assertNotNull(reads.byId(707, success.first, today, 7))
                    val refused = assertNotNull(reads.byId(808, refusal.first, today, 7))
                    val attempt = succeeded.getValue("items").jsonArray.single().jsonObject
                        .getValue("attempts").jsonArray.single().jsonObject
                    assertEquals("SUCCESS", attempt.getValue("evaluationStatus").jsonPrimitive.content)
                    assertTrue(attempt.getValue("evaluation") is JsonObject)
                    assertEquals(
                        "REFUSAL",
                        refused.getValue("items").jsonArray.single().jsonObject
                            .getValue("attempts").jsonArray.single().jsonObject
                            .getValue("evaluationFailureMessage").jsonPrimitive.content,
                    )
                    work.read {
                        assertEquals(1, growth.profile(707)?.evaluationCount)
                        assertEquals(49.0, growth.profile(707)?.meaningScore)
                        assertEquals(0, growth.profile(808)?.evaluationCount)
                        assertEquals(0, growth.profile(909)?.evaluationCount)
                    }
                }
            }
        }
        // 같은 scratch DB를 새 pool로 열어 재시작 뒤에도 상태가 유지되는지 확인한다.
        DatabaseFactory(db.settings()).use { restarted ->
            val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(restarted.database, 4), clock)
            runBlocking {
                work.read {
                    assertEquals(1, growth.profile(707)?.evaluationCount)
                    assertEquals(0, growth.profile(808)?.evaluationCount)
                    assertEquals(0, growth.profile(909)?.evaluationCount)
                }
            }
        }
        db.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT user_id,status,overall_score,failure_message FROM language_learning_writing_evaluation ORDER BY user_id",
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(707L, rows.getLong(1))
                    assertEquals("SUCCESS", rows.getString(2))
                    assertEquals(69, rows.getInt(3))
                    assertTrue(rows.next())
                    assertEquals(808L, rows.getLong(1))
                    assertEquals("FAILED", rows.getString(2))
                    assertEquals("REFUSAL", rows.getString(4))
                    assertTrue(rows.next())
                    assertEquals(909L, rows.getLong(1))
                    assertEquals("FAILED", rows.getString(2))
                    assertEquals("WRITING_EVALUATION_SCHEMA_INVALID", rows.getString(4))
                    assertFalse(rows.next())
                }
            }
        }
    }

    private suspend fun prepare(
        userId: Long,
        answerText: String,
        generation: WritingGenerationState,
        work: ExposedWritingSetUnitOfWork,
    ): Pair<Long, Long> {
        val date = LocalDate.parse("2026-09-26")
        val set = generation.getOrCreate(
            NewWritingSet(
                userId, date, WritingType.FREE,
                "synthetic-http-$userId", 1,
                "{\"selectedKeywords\":[],\"learningProfile\":null,\"originLanguage\":\"ko\",\"learningLanguage\":\"en\"}",
            ),
        )
        val claim = assertNotNull(generation.claimNext(userId, set.id))
        assertTrue(
            generation.publishItem(
                userId, set.id, claim,
                NewWritingItem(
                    1, WritingDifficulty.NORMAL, "Synthetic prompt 1", emptyList(), emptyList(), "Synthetic focus",
                ),
                "v1",
            ),
        )
        val item = work.read { items.list(userId, set.id).single() }
        work.write(userId) {
            growth.saveProfile(GrowthProfile(userId, state = "ACTIVE", createdAt = nowUtc, updatedAt = nowUtc))
        }
        val answer = WritingAnswerState(work).submit(userId, item.id, answerText, null, date, 7, true)
        return set.id to answer.id
    }
}
