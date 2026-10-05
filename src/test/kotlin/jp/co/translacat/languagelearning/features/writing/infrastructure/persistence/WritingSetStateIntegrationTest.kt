package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthSignal
import jp.co.translacat.languagelearning.features.keyword.application.DefaultKeywordOperations
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningDate
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordChange
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordType
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.ExposedKeywordUnitOfWork
import jp.co.translacat.languagelearning.features.settings.application.DefaultSettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.GetAdminSettings
import jp.co.translacat.languagelearning.features.settings.application.UpdateUserSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedAdminSettingsUnitOfWork
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedSettingsReadQueries
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedSettingsUnitOfWork
import jp.co.translacat.languagelearning.features.writing.application.*
import jp.co.translacat.languagelearning.features.writing.domain.model.*
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationParser
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingItemRevision
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.*
import kotlin.test.*

/** 두 실제 MySQL pool 사이의 중복 요청과 lease fencing을 검사한다. */
class WritingSetStateIntegrationTest {
    private val firstClock = Clock.fixed(Instant.parse("2026-09-26T04:00:00Z"), ZoneOffset.UTC)
    private val nextClock = Clock.offset(firstClock, Duration.ofMinutes(21))

    @Test
    fun `재획득 전 lease 경계에 도착한 생성과 재생성은 기존 상태를 보존한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 실제 DB에 생성 lease를 만들고 정확한 만료 시각의 별도 작업자를 구성한다.
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), firstClock)
                val generation = WritingGenerationState(work)
                val setId = generation.getOrCreate(
                    NewWritingSet(909, LocalDate.parse("2026-09-26"), WritingType.FREE, "lease-boundary", 1, "{}"),
                ).id
                val expiredClaim = assertNotNull(generation.claimNext(909, setId))
                val expiredGeneration = state(factory, Clock.offset(firstClock, Duration.ofMinutes(20)))

                // 실행·검증: token이 바뀌지 않았어도 만료 시각부터 성공과 실패 모두 반영하지 않는다.
                assertFalse(expiredGeneration.publishItem(909, setId, expiredClaim, item(1), "v1"))
                assertFalse(expiredGeneration.fail(909, setId, expiredClaim.token, "LATE_TIMEOUT"))
                assertEquals(WritingSetStatus.GENERATING, generation.find(909, setId)?.status)
                work.read { assertTrue(items.list(909, setId).isEmpty()) }

                // 실행: 만료 직전 응답은 게시 가능하며 재생성은 기존 공개 문항을 유지한 채 시작한다.
                val beforeExpiry = state(factory, Clock.offset(firstClock, Duration.ofMinutes(20).minusMillis(1)))
                assertTrue(beforeExpiry.publishItem(909, setId, expiredClaim, item(1), "v1"))
                val regenerationClock = Clock.offset(firstClock, Duration.ofMinutes(20))
                val regeneration = WritingRegenerationState(
                    ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), regenerationClock),
                )
                val regenerationClaim = regeneration.claim(909, setId)
                val oldItem = work.read { items.list(909, setId).single() }
                val expiredRegeneration = WritingRegenerationState(
                    ExposedWritingSetUnitOfWork(
                        JdbcTransactionRunner(factory.database, 4),
                        Clock.offset(regenerationClock, Duration.ofMinutes(10)),
                    ),
                )

                // 검증: 만료된 교체를 거절하고 본문·revision·답변·재생성 횟수와 READY 상태를 보존한다.
                assertFalse(expiredRegeneration.publish(
                    regenerationClaim, listOf(item(1).copy(originText = "Late replacement")),
                ))
                work.read {
                    assertEquals(oldItem, items.list(909, setId).single())
                    assertFalse(items.hasAnswer(909, oldItem.id))
                }
                assertEquals(0, generation.find(909, setId)?.regenerationCount)
                assertEquals(WritingSetStatus.READY, generation.find(909, setId)?.status)

                // 실행·검증: 새 claim은 정상 교체하고 이전 작업자는 새 token을 해제할 수 없다.
                val fresh = expiredRegeneration.claim(909, setId)
                assertFalse(regeneration.release(regenerationClaim))
                assertTrue(expiredRegeneration.publish(fresh, listOf(item(1).copy(originText = "Fresh replacement"))))
                assertEquals(1, generation.find(909, setId)?.regenerationCount)
            }
        }
    }

    @Test
    fun `중복 최초 요청은 한 세트이고 다른 사용자와 이전 lease 결과는 격리된다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { leftFactory ->
            DatabaseFactory(db.settings()).use { rightFactory ->
                val left = state(leftFactory, firstClock)
                val right = state(rightFactory, firstClock)
                val seed = NewWritingSet(101, LocalDate.parse("2026-09-26"), WritingType.FREE, "synthetic-a", 5, "{}")
                runBlocking {
                    val sets = listOf(
                        async(Dispatchers.Default) { left.getOrCreate(seed) },
                        async(Dispatchers.Default) { right.getOrCreate(seed.copy(snapshotId = "synthetic-b")) },
                    ).awaitAll()
                    assertEquals(sets[0].id, sets[1].id)
                    assertEquals(1, sets.map { it.snapshotId }.distinct().size)
                    assertEquals(WritingSetStatus.GENERATING, sets[0].status)
                    val setId = sets[0].id
                    assertEquals(listOf(setId), left.recoverable().map { it.setId })

                    assertNull(right.find(202, setId))
                    val token1 = "11111111-1111-4111-8111-111111111111"
                    val token2 = "22222222-2222-4222-8222-222222222222"
                    assertEquals(token1, left.claim(101, setId, token1))
                    assertTrue(right.recoverable().isEmpty())
                    assertNull(right.claim(101, setId, token2))
                    assertNull(right.claim(202, setId, token2))
                    assertFalse(
                        right.publishItem(101, setId, WritingGenerationState.GenerationClaim(1, token2), item(1), "v1"),
                    )
                    assertFalse(right.fail(101, setId, token2, "VALIDATION_FAILED"))

                    val resumed = state(rightFactory, nextClock)
                    assertEquals(listOf(setId), resumed.recoverable().map { it.setId })
                    assertEquals(token2, resumed.claim(101, setId, token2))
                    assertFalse(
                        left.publishItem(101, setId, WritingGenerationState.GenerationClaim(1, token1), item(1), "v1"),
                    )
                    assertFalse(left.fail(101, setId, token1, "STALE"))
                    assertTrue(resumed.fail(101, setId, token2, "VALIDATION_FAILED"))
                    assertFalse(resumed.fail(101, setId, token2, "DUPLICATE"))
                    assertEquals(WritingSetStatus.FAILED, assertNotNull(left.find(101, setId)).status)
                    assertTrue(resumed.recoverable().isEmpty())
                }
            }
        }
        db.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM language_learning_daily_set WHERE user_id=101")
                    .use { rows ->
                        assertTrue(rows.next())
                        assertEquals(1L, rows.getLong(1))
                    }
            }
        }
        DatabaseFactory(db.settings()).use { restarted ->
            runBlocking {
                val existing = state(restarted, nextClock).getOrCreate(
                    NewWritingSet(
                        101, LocalDate.parse("2026-09-26"), WritingType.FREE, "synthetic-after-restart", 5, "{}",
                    ),
                )
                assertEquals(WritingSetStatus.FAILED, existing.status)
                assertTrue(existing.snapshotId in setOf("synthetic-a", "synthetic-b"))
            }
        }
    }

    @Test
    fun `문항 단위 게시와 실패 재시도는 기존 문항을 보존하고 마지막 문항에서 준비된다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                val state = state(factory, firstClock)
                val seed =
                    NewWritingSet(303, LocalDate.parse("2026-09-26"), WritingType.FREE, "synthetic-items", 2, "{}")
                val setId = state.getOrCreate(seed).id
                val first = assertNotNull(state.claimNext(303, setId))
                assertEquals(1, first.order)
                assertTrue(state.publishItem(303, setId, first, item(1), "v1"))
                assertFalse(state.publishItem(303, setId, first, item(1), "v1"))
                val second = assertNotNull(state.claimNext(303, setId))
                assertEquals(2, second.order)
                assertTrue(state.fail(303, setId, second.token, "PROVIDER_ERROR"))
                assertEquals(WritingSetStatus.PARTIAL, assertNotNull(state.find(303, setId)).status)
                assertEquals(WritingSetStatus.GENERATING, assertNotNull(state.retry(303, setId)).status)
                val resumed = assertNotNull(state.claimNext(303, setId))
                assertEquals(2, resumed.order)
                assertFalse(state.publishItem(303, setId, second, item(2), "v1"))
                assertTrue(state.publishItem(303, setId, resumed, item(2), "v1"))
                assertEquals(WritingSetStatus.READY, assertNotNull(state.find(303, setId)).status)
                assertNull(state.claimNext(303, setId))
            }
        }
        db.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT item_order, origin_text, language_complexity_band, diversity_metadata_json FROM language_learning_daily_item WHERE user_id=303 ORDER BY item_order",
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getInt(1))
                    assertEquals("Synthetic prompt 1", rows.getString(2))
                    assertEquals(3, rows.getInt(3))
                    assertEquals(
                        "WORK",
                        Json.parseToJsonElement(rows.getString(4)).jsonObject.getValue(
                            "scenarioCategory",
                        ).jsonPrimitive.content,
                    )
                    assertTrue(rows.next())
                    assertEquals(2, rows.getInt(1))
                    assertEquals("Synthetic prompt 2", rows.getString(2))
                    assertEquals(3, rows.getInt(3))
                    assertFalse(rows.next())
                }
            }
        }
    }

    @Test
    fun `재생성 실패와 응답 사이 제출은 기존 문항을 보존한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                val generation = state(factory, firstClock)
                val setId = generation.getOrCreate(
                    NewWritingSet(404, LocalDate.parse("2026-09-26"), WritingType.FREE, "synthetic-regen", 2, "{}"),
                ).id
                (1..2).forEach { order ->
                    val claim = assertNotNull(generation.claimNext(404, setId))
                    assertTrue(generation.publishItem(404, setId, claim, item(order), "v1"))
                }
                val regen = WritingRegenerationState(
                    ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), firstClock),
                )
                val failed = regen.claim(404, setId)
                assertFailsWith<IllegalArgumentException> { regen.publish(failed, listOf(item(1))) }
                assertTrue(regen.release(failed))
                val contested = regen.claim(404, setId)
                assertEquals(2, contested.targets.size)
                // 실제 MySQL에 답변을 삽입해, 모델 응답 대기 사이 제출을 재현한다.
                db.connect().use { connection ->
                    connection.prepareStatement(
                        "INSERT INTO language_learning_writing_answer (daily_item_id,user_id,attempt_date,answer_text,submitted_at,created_at,updated_at) VALUES (?,404,'2026-09-26','synthetic answer','2026-09-26 04:00:00','2026-09-26 04:00:00','2026-09-26 04:00:00')",
                    ).use { statement ->
                        statement.setLong(1, contested.targets.first().itemId)
                        assertEquals(1, statement.executeUpdate())
                    }
                }
                assertFailsWith<IllegalStateException> {
                    regen.publish(
                        contested,
                        listOf(item(1).copy(originText = "Changed 1"), item(2).copy(originText = "Changed 2")),
                    )
                }
                assertTrue(regen.release(contested))
                val safe = regen.claim(404, setId)
                assertEquals(listOf(2), safe.targets.map { it.order })
                assertTrue(regen.publish(safe, listOf(item(1).copy(originText = "Replacement 2"))))
                assertEquals(1, assertNotNull(generation.find(404, setId)).regenerationCount)
            }
        }
        db.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT item_order, origin_text FROM language_learning_daily_item WHERE user_id=404 ORDER BY item_order",
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("Synthetic prompt 1", rows.getString(2))
                    assertTrue(rows.next())
                    assertEquals("Replacement 2", rows.getString(2))
                    assertFalse(rows.next())
                }
            }
        }
    }

    @Test
    fun `답변은 재생성 lease와 문항 revision 및 당일 중복 제출을 보호한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), firstClock)
                val generation = WritingGenerationState(work)
                val regen = WritingRegenerationState(work)
                val answers = WritingAnswerState(work)
                val setId = generation.getOrCreate(
                    NewWritingSet(505, LocalDate.parse("2026-09-26"), WritingType.FREE, "synthetic-answer", 1, "{}"),
                ).id
                val generated = assertNotNull(generation.claimNext(505, setId))
                assertTrue(generation.publishItem(505, setId, generated, item(1), "v1"))
                val old = work.read { items.list(505, setId).single() }
                val oldRevision = WritingItemRevision.of(old)
                val claim = regen.claim(505, setId)
                assertFailsWith<IllegalArgumentException> {
                    answers.submit(505, old.id, "answer", oldRevision, LocalDate.parse("2026-09-26"), 7, true)
                }
                assertTrue(regen.publish(claim, listOf(item(1).copy(originText = "Revised synthetic prompt"))))
                assertFailsWith<IllegalArgumentException> {
                    answers.submit(505, old.id, "answer", oldRevision, LocalDate.parse("2026-09-26"), 7, true)
                }
                val current = work.read { items.list(505, setId).single() }
                val submitted = answers.submit(
                    505, old.id, " answer ", WritingItemRevision.of(current),
                    LocalDate.parse("2026-09-26"), 7, true,
                )
                assertEquals("answer", submitted.text)
                assertFailsWith<IllegalArgumentException> {
                    answers.submit(505, old.id, "duplicate", null, LocalDate.parse("2026-09-26"), 7, true)
                }
                assertFailsWith<IllegalArgumentException> { regen.claim(505, setId) }
            }
        }
    }

    @Test
    fun `평가 lease는 중복 성장을 막고 성공 결과와 완료 상태를 함께 커밋한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), firstClock)
                val generation = WritingGenerationState(work)
                val setId = generation.getOrCreate(
                    NewWritingSet(
                        606, LocalDate.parse("2026-09-26"), WritingType.FREE, "synthetic-evaluation", 1, "{}",
                    ),
                ).id
                val generated = assertNotNull(generation.claimNext(606, setId))
                assertTrue(generation.publishItem(606, setId, generated, item(1), "v1"))
                val stored = work.read { items.list(606, setId).single() }
                work.write(606) {
                    growth.saveProfile(GrowthProfile(606, state = "ACTIVE", createdAt = nowUtc, updatedAt = nowUtc))
                }
                val answer = WritingAnswerState(work).submit(
                    606, stored.id, "Synthetic answer", WritingItemRevision.of(stored),
                    LocalDate.parse("2026-09-26"), 7, true,
                )
                val evaluations = WritingEvaluationState(work)
                assertEquals(listOf(answer.id), evaluations.recoverable().map { it.answerId })
                val claim = assertNotNull(evaluations.claim(606, answer.id))
                assertNull(evaluations.claim(606, answer.id))
                val result = WritingEvaluationParser.parse(
                    Json.parseToJsonElement(
                        """
                    {"scores":{"meaning":80,"grammar":70,"vocabulary":60,"naturalness":75,"expression":65},
                     "strengths":[],"weaknesses":[],"corrections":[],"recommendedAnswers":["One","Two"],
                     "explanation":{"originText":"합성 설명","learningText":"Synthetic explanation"},
                     "profileSignals":{"strengthTags":["synthetic"],"weaknessTags":[],"grammarPatterns":[],
                        "vocabularyPatterns":[],"naturalnessPatterns":[],"expressionPatterns":[],
                        "meaningPatterns":[],"recommendedFocus":[]}}
                """.trimIndent(),
                    ),
                )
                assertFalse(
                    evaluations.publish(
                        claim.copy(token = "11111111-1111-4111-8111-111111111111"),
                        result, LocalDate.parse("2026-09-26"), emptyList(),
                    ),
                )
                assertTrue(evaluations.publish(claim, result, LocalDate.parse("2026-09-26"), emptyList()))
                assertFalse(evaluations.publish(claim, result, LocalDate.parse("2026-09-26"), emptyList()))
                assertEquals(WritingSetStatus.COMPLETED, assertNotNull(generation.find(606, setId)).status)
                work.read {
                    assertEquals(1, growth.profile(606)?.evaluationCount)
                    assertEquals(80.0, growth.profile(606)?.meaningScore)
                }
            }
        }
        db.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT status, overall_score FROM language_learning_writing_evaluation WHERE user_id=606",
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("SUCCESS", rows.getString(1))
                    assertTrue(rows.getInt(2) in 0..100)
                }
            }
        }
    }

    @Test
    fun `LL 문맥은 설정과 성장 진입조건을 지키고 중복 생성의 키워드 선택을 한 번만 저장한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 실제 LL 설정·키워드·Writing 저장소와 완료된 레벨의 합성 프로필을 구성한다.
                val runner = JdbcTransactionRunner(factory.database, 4)
                val settingsWork = ExposedSettingsUnitOfWork(runner, firstClock)
                val settings = DefaultSettingsServiceOperations(
                    settingsWork, ExposedSettingsReadQueries(runner),
                    GetAdminSettings(ExposedAdminSettingsUnitOfWork(runner, firstClock)), firstClock,
                )
                val keywords = DefaultKeywordOperations(
                    ExposedKeywordUnitOfWork(runner, firstClock),
                    KeywordLearningDate { settings.learningDate(it) },
                )
                val writing = ExposedWritingSetUnitOfWork(runner, firstClock)
                val context = WritingContextService(
                    settings, keywords, writing,
                    jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.ExposedKeywordLearningFacts(
                        runner,
                    ),
                )
                UpdateUserSettings(settingsWork).execute(707, UserSettingsChange("ko", "en", "Asia/Seoul", 5))
                keywords.createCustom(
                    707, false,
                    KeywordChange(
                        text = "Synthetic topic", type = KeywordType.TOPIC,
                        canonicalKey = "synthetic-topic",
                    ),
                )

                // 실행·검증: 레벨 검사가 실패하면 생성 세트나 선택 횟수를 남기지 않는다.
                val denied = assertFailsWith<LearningBusinessException> { context.getOrCreate(707, WritingType.FREE) }
                assertEquals("LEVEL_TEST_REQUIRED", denied.code)
                writing.read {
                    assertFalse(sets.existsForUser(707))
                    assertNull(growth.mastery(707, "synthetic-topic"))
                }
                writing.write(707) {
                    growth.saveProfile(
                        GrowthProfile(
                            707, state = "ACTIVE", baseLevelScore = 60.0,
                            meaningScore = 75.0,
                            createdAt = nowUtc, updatedAt = nowUtc,
                        ),
                    )
                    growth.saveSignal(
                        GrowthSignal(707, "RECOMMENDED_FOCUS", "Try a conditional next time", 1, nowUtc, nowUtc, nowUtc),
                    )
                }

                // 실행: 동시 생성은 LL에서 고른 하나의 snapshot으로 수렴한다.
                val created = listOf(
                    async(Dispatchers.Default) { context.getOrCreate(707, WritingType.FREE) },
                    async(Dispatchers.Default) { context.getOrCreate(707, WritingType.FREE) },
                ).awaitAll()
                val snapshot = Json.parseToJsonElement(created.first().snapshotJson).jsonObject

                // 검증: 설정 문항 수·기존 분포·키워드·기준 band를 보존하고 사용자별로 격리한다.
                assertEquals(created.first().id, created.last().id)
                assertEquals(5, created.first().sentenceCount)
                assertEquals(
                    Json.parseToJsonElement("{\"review\":1,\"normal\":3,\"challenge\":1}"),
                    snapshot["difficultyDistribution"],
                )
                assertEquals("ko", snapshot.getValue("originLanguage").jsonPrimitive.content)
                val profile = snapshot.getValue("learningProfile").jsonObject
                assertEquals(75.0, profile.getValue("skillScores").jsonObject.getValue("meaning").jsonPrimitive.double)
                assertEquals(JsonNull, profile.getValue("skillScores").jsonObject["grammar"])
                assertEquals(
                    JsonArray(listOf(JsonPrimitive("Try a conditional next time"))), profile["recommendedFocus"],
                )
                assertEquals(JsonArray(emptyList()), snapshot["recentlyLearnedExpressions"])
                assertEquals(
                    "language-learning-diversity",
                    snapshot.getValue("contentDiversityPolicyVersion").jsonPrimitive.content,
                )
                assertFalse("generationPolicyVersion" in snapshot)
                assertEquals(
                    3,
                    snapshot.getValue("languageComplexity").jsonObject
                        .getValue("baseComplexityBand").jsonPrimitive.int,
                )
                assertEquals(
                    1.0,
                    snapshot.getValue("selectedKeywords").jsonArray.single().jsonObject
                        .getValue("selectionWeight").jsonPrimitive.double,
                )
                assertEquals(
                    0,
                    snapshot.getValue("recentEvaluationSummary").jsonObject
                        .getValue("sampleCount").jsonPrimitive.int,
                )
                writing.read {
                    assertEquals(1, growth.mastery(707, "synthetic-topic")?.selectedCount)
                    assertNull(sets.findById(708, created.first().id))
                }

                // 실행·검증: 세 모드는 별도 세트를 만들지만 같은 정책으로 문항 수를 정한다.
                for (type in WritingType.entries.filterNot { it == WritingType.FREE }) {
                    assertEquals(5, context.getOrCreate(707, type).sentenceCount)
                }
                writing.read { assertEquals(3, growth.mastery(707, "synthetic-topic")?.selectedCount) }

                // 검증: 공통 홈·이력 조회도 Core 대신 실제 LL 저장 상태를 집계한다.
                val report = WritingReportService(ExposedWritingReportQueries(runner)).report(
                    707,
                    LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 26),
                )
                assertEquals(15, report.getValue("todayTotal").jsonPrimitive.int)
                assertEquals(3, report.getValue("history").jsonArray.size)
                assertTrue(
                    report.getValue("history").jsonArray.all {
                        it.jsonObject.getValue("activityId").jsonPrimitive.content.startsWith("WRITING:-")
                    },
                )
            }
        }
    }

    @Test
    fun `DELETE 초기화 후 옛 lease와 공개 ID는 새 세트를 수정하지 못한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 이 검사가 만든 임시 DB에 진행 중 작업을 만든다.
                val generation = state(factory, firstClock)
                val seed = NewWritingSet(808, LocalDate.parse("2026-09-26"), WritingType.FREE, "reset-before", 1, "{}")
                val old = generation.getOrCreate(seed)
                val oldClaim = assertNotNull(generation.claimNext(808, old.id))
                val oldPublicId = jp.co.translacat.languagelearning.shared.identity.LearningPublicId.encode(old.id)

                // 실행: AUTO_INCREMENT를 보존하는 DELETE 뒤 같은 사용자·날짜에 새 학습을 시작한다.
                db.connect().use { connection ->
                    connection.createStatement().use { statement ->
                        assertEquals(
                            1, statement.executeUpdate("DELETE FROM language_learning_daily_set WHERE user_id=808"),
                        )
                    }
                }
                val fresh = generation.getOrCreate(seed.copy(snapshotId = "reset-after"))
                val freshClaim = assertNotNull(generation.claimNext(808, fresh.id))

                // 검증: 과거 PK와 token을 그대로 보낸 늦은 결과도 현재 행에 연결되지 않는다.
                assertTrue(fresh.id > old.id)
                assertNull(
                    generation.find(
                        808,
                        jp.co.translacat.languagelearning.shared.identity.LearningPublicId.decode(
                            oldPublicId.toString(),
                        ),
                    ),
                )
                assertFalse(generation.publishItem(808, old.id, oldClaim, item(1), "v1"))
                assertFalse(generation.fail(808, old.id, oldClaim.token, "STALE"))
                assertEquals(WritingSetStatus.GENERATING, generation.find(808, fresh.id)!!.status)
                assertEquals(freshClaim.token, generation.find(808, fresh.id)!!.generationToken)
                assertTrue(generation.publishItem(808, fresh.id, freshClaim, item(1), "v1"))
                assertEquals(WritingSetStatus.READY, generation.find(808, fresh.id)!!.status)
            }
        }
    }

    private fun item(order: Int) = NewWritingItem(
        order = order, difficulty = WritingDifficulty.NORMAL, originText = "Synthetic prompt $order",
        keywords = listOf("sample"), focusMetrics = listOf("GRAMMAR"), focusReason = "Synthetic focus",
        languageComplexityBand = 3,
        diversityMetadataJson = "{\"scenarioCategory\":\"WORK\",\"communicativeIntent\":\"REQUEST\"}",
    )

    private fun state(factory: DatabaseFactory, clock: Clock) = WritingGenerationState(
        ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock),
    )
}
