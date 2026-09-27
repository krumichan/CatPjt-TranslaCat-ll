package jp.co.translacat.languagelearning.features.listening.infrastructure.persistence

import jp.co.translacat.languagelearning.features.keyword.application.DefaultKeywordOperations
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningDate
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.ExposedKeywordUnitOfWork
import jp.co.translacat.languagelearning.features.listening.application.*
import jp.co.translacat.languagelearning.features.listening.domain.model.*
import jp.co.translacat.languagelearning.features.settings.application.DefaultSettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.GetAdminSettings
import jp.co.translacat.languagelearning.features.settings.application.UpdateUserSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedAdminSettingsUnitOfWork
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedSettingsReadQueries
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedSettingsUnitOfWork
import jp.co.translacat.languagelearning.shared.ai.*
import jp.co.translacat.languagelearning.shared.diversity.ExposedGenerationFingerprintRepository
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

class ListeningStateIntegrationTest {
    private val now = Clock.fixed(Instant.parse("2026-09-26T05:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `실제 설정 문맥과 생성 TTS 작업은 중복 요청에도 한 문항과 오디오를 게시한다`() = LocalScratchMysql.use { database ->
        // 준비: 모델 출력만 고정하고 설정·키워드·문맥·worker·fingerprint·blob 저장은 실제 구현을 사용한다.
        DatabaseFactory(database.settings()).use { factory ->
            val runner = JdbcTransactionRunner(factory.database, 4)
            val work = ExposedListeningUnitOfWork(runner, now)
            val settingsWork = ExposedSettingsUnitOfWork(runner, now)
            val settings = DefaultSettingsServiceOperations(
                settingsWork, ExposedSettingsReadQueries(runner),
                GetAdminSettings(ExposedAdminSettingsUnitOfWork(runner)), now,
            )
            val keywords = DefaultKeywordOperations(
                ExposedKeywordUnitOfWork(runner, now), KeywordLearningDate { settings.learningDate(it) },
            )
            val context = ListeningContextService(
                settings, keywords, work,
                jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.ExposedKeywordLearningFacts(
                    runner,
                ),
            )
            val fixture = Json.parseToJsonElement(
                checkNotNull(javaClass.getResourceAsStream("/contracts/listening-generation-python-golden.json"))
                    .bufferedReader().use { it.readText() },
            ).jsonObject
            val calls = mutableListOf<ModelExecutionCommand>()
            val output = JsonObject(
                mapOf(
                    "items" to JsonArray(
                        fixture.getValue("response").jsonObject.getValue("items").jsonArray.map {
                            JsonObject(it.jsonObject + ("languageComplexityBand" to JsonPrimitive(2)))
                        },
                    ),
                ),
            )
            val generation = ListeningGenerationExecution(
                ModelExecutionPort { command ->
                    calls += command
                    ModelExecutionResult(output, 1, 1, "test-provider", "synthetic")
                },
            )
            var synthesisCalls = 0
            val speech = SpeechExecutionPort { command ->
                synthesisCalls++
                assertEquals("marin", command.voice)
                SpeechSynthesisResult(wave(10), "audio/wav", "test-provider", "synthetic", 999.0)
            }
            val worker = ListeningGenerationWorker(work, settings, generation, speech, now)
            runBlocking {
                UpdateUserSettings(settingsWork).execute(
                    801,
                    UserSettingsChange(originLanguage = "ko", learningLanguage = "ja", dailyListeningGoalCount = 1),
                )

                // 실행: 실제 최초 생성과 중복 요청 후 영속 job을 순서대로 처리한다.
                val set = context.getOrCreate(801)
                assertEquals(set.id, context.getOrCreate(801).id)
                val generationJob = work.read { recoverable(10).single() }
                worker.process(generationJob)
                val ttsJob = work.read { recoverable(10).single() }
                worker.process(ttsJob)
                worker.process(generationJob)
                worker.process(ttsJob)

                // 검증: Provider가 신고한 999초 대신 실제 WAV 10초로 채택하며 중복 실행은 없다.
                val ready = assertNotNull(work.read { set(801, set.id) })
                assertEquals("READY", ready.status)
                assertEquals(1, ready.items.size)
                assertEquals(10_000, ready.items.single().audioDurationMs)
                assertEquals(1, calls.size)
                assertEquals(1, synthesisCalls)
                assertNotNull(work.read { audio(801, checkNotNull(ready.items.single().audioId))?.bytes })
                assertTrue(work.read { recoverable(10).isEmpty() })
                val prompt = calls.single().messages.single().content.substringAfter("\n\n")
                val request = Json.parseToJsonElement(prompt).jsonObject
                assertEquals(
                    "MY_LEVEL", request.getValue("userContext").jsonObject.getValue("level").jsonPrimitive.content,
                )
                assertEquals(2, request.getValue("setContext").jsonObject.getValue("itemCount").jsonPrimitive.int)
                assertTrue(request.getValue("setContext").jsonObject.containsKey("selectedKeywords"))
                assertEquals(
                    8.0, request.getValue("constraints").jsonObject.getValue("audioSecondsMin").jsonPrimitive.double,
                )

                // 실행: 모드의 공식 Task 전체를 저장하고 평가 중인 답변을 보호한다.
                val sessions = ListeningSessionService(work, settings)
                val session = sessions.create(801, set.id, null, "synthetic-session-flow")
                val attempt = session.attempts.single()
                val reads = ListeningReadService(work, settings)
                val hiddenHistory = reads.history(801, session.id).getValue("attempts").jsonArray.single().jsonObject
                assertEquals(JsonNull, hiddenHistory.getValue("sourceText"))
                assertTrue(hiddenHistory.getValue("referenceMeanings").jsonArray.isEmpty())
                val source = ready.items.single().content.getValue("sourceText").jsonPrimitive.content
                sessions.answer(801, session.id, attempt.id, ListeningTaskType.DICTATION, source, emptyList())
                sessions.answer(801, session.id, attempt.id, ListeningTaskType.INTERPRETATION, "합성 의미 답변", emptyList())
                sessions.submit(801, session.id, attempt.id, 1200)
                assertFailsWith<jp.co.translacat.languagelearning.shared.error.LearningBusinessException> {
                    sessions.answer(
                        801, session.id, attempt.id, ListeningTaskType.DICTATION, "late overwrite", emptyList(),
                    )
                }
                val semanticFixture = Json.parseToJsonElement(
                    checkNotNull(javaClass.getResourceAsStream("/contracts/listening-semantic-python-golden.json"))
                        .bufferedReader().use { it.readText() },
                ).jsonArray.first().jsonObject.getValue("response").jsonObject
                val semantic = ListeningEvaluationExecution(
                    ModelExecutionPort {
                        ModelExecutionResult(
                            JsonObject(
                                semanticFixture + mapOf(
                                    "deliveredMeaningUnits" to ready.items.single().content.getValue("keyMeaningUnits"),
                                    "omittedMeaningUnits" to JsonArray(emptyList()),
                                    "misunderstoodMeaningUnits" to JsonArray(emptyList()),
                                ),
                            ),
                            1, 1, "test-provider", "synthetic",
                        )
                    },
                )
                val noSpeech = object : SpeechTranscriptionPort {
                    override suspend fun normalize(command: AudioDecodeCommand): AudioDecodeResult =
                        error("텍스트 평가에서 호출되지 않습니다.")

                    override suspend fun transcribe(command: SpeechTranscriptionCommand): SpeechTranscriptionResult =
                        error("텍스트 평가에서 호출되지 않습니다.")
                }
                val evaluator =
                    ListeningEvaluationWorker(work, settings, semantic, ListeningRepeatExecution(noSpeech), now)
                val evaluations = work.read { recoverable(10).filter { it.type == "EVALUATE" } }
                assertEquals(2, evaluations.size)
                evaluations.forEach { evaluator.process(it) }
                evaluations.forEach { evaluator.process(it) }

                // 검증: 원래 답변과 원본 Task 평가를 보존하고 중복 완료가 진행도·Growth를 늘리지 않는다.
                val finished = sessions.get(801, session.id)
                assertEquals("COMPLETED", finished.status)
                assertEquals(1, finished.completedItemCount)
                assertEquals(1, finished.evaluatedItemCount)
                assertEquals(1200L, finished.actualDurationMs)
                assertEquals(
                    source,
                    finished.attempts.single().tasks.single { it.taskType == ListeningTaskType.DICTATION }.answerText,
                )
                assertEquals(2, finished.attempts.single().tasks.count { it.evaluation != null })
                assertEquals(1, work.read { set(801, set.id)?.completedItemCount })
                assertEquals(
                    1,
                    work.read {
                        growth.activities(
                            801, "LISTENING", LocalDate.parse("2026-09-26"), LocalDate.parse("2026-09-26"), 0, 10,
                        ).size
                    },
                )

                // 실행 및 검증: 공식 근거와 독립성 이력이 중복 없이 저장되고 재계산 job을 재실행할 수 없다.
                val profileJob = work.read { recoverable(10).single { it.type == "PROFILE" } }
                val profiles = ListeningProfileWorker(work)
                profiles.process(profileJob)
                profiles.process(profileJob)
                val history = work.read { metricHistory(801, "ja") }
                assertEquals(1, history.count { it.metric == "LISTENING_INDEPENDENCE" })
                assertTrue(history.all { it.profileApplied })
                assertTrue(
                    history.groupBy { it.metric }.values.all { rows -> rows.count { it.recencyWeight == 1.0 } == 1 },
                )
                assertTrue(work.read { metricHistory(802, "ja").isEmpty() })

                // 실행 및 검증: 조회는 LL 원본 평가의 별도 ID·시각과 공개 조건을 그대로 사용한다.
                val records = finished.attempts.single().tasks.filter { it.evaluation != null }
                    .map { it.evaluationRecords.single() }
                assertEquals(2, records.map { it.id }.distinct().size)
                assertTrue(records.all { it.id !in finished.attempts.single().tasks.map { task -> task.id } })
                val report = reads.report(801, LocalDate.parse("2026-09-26"), LocalDate.parse("2026-09-26"), null)
                assertEquals(2, report.getValue("evaluations").jsonArray.size)
                assertEquals(
                    "LISTENING:-${session.id}",
                    report.getValue("history").jsonArray.single().jsonObject.getValue(
                        "activityId",
                    ).jsonPrimitive.content,
                )
                assertEquals(
                    "COMPLETED",
                    report.getValue("history").jsonArray.single().jsonObject.getValue(
                        "evaluationStatus",
                    ).jsonPrimitive.content,
                )
                val historyView = reads.history(801, session.id).getValue("attempts").jsonArray.single().jsonObject
                assertEquals(source, historyView.getValue("sourceText").jsonPrimitive.content)
                val dashboard = reads.dashboard(801, LocalDate.parse("2026-09-26"), LocalDate.parse("2026-09-26"), null)
                assertEquals(2, dashboard.getValue("taskTrends").jsonArray.size)
                assertTrue(
                    reads.metricTrends(801, "ja", LocalDate.parse("2026-09-26"), LocalDate.parse("2026-09-26"), null)
                        .isNotEmpty(),
                )
                assertTrue(
                    reads.report(802, LocalDate.parse("2026-09-26"), LocalDate.parse("2026-09-26"), null)
                        .getValue("history").jsonArray.isEmpty(),
                )

                // 검증: 다른 사용자의 식별자도 기존 외부 오류 계약을 유지하며 존재 여부를 드러내지 않는다.
                assertEquals(
                    "LANGUAGE_LEARNING_DAILY_SET_NOT_FOUND",
                    assertFailsWith<jp.co.translacat.languagelearning.shared.error.LearningBusinessException> {
                        reads.set(
                            802, set.id,
                        )
                    }.code,
                )
                assertEquals(
                    "SESSION_NOT_FOUND",
                    assertFailsWith<jp.co.translacat.languagelearning.shared.error.LearningBusinessException> {
                        reads.session(
                            802, session.id,
                        )
                    }.code,
                )
                assertEquals(
                    "LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND",
                    assertFailsWith<jp.co.translacat.languagelearning.shared.error.LearningBusinessException> {
                        reads.item(
                            802, session.id, ready.items.single().id,
                        )
                    }.code,
                )
                assertEquals(
                    "LISTENING_AUDIO_INVALID",
                    assertFailsWith<jp.co.translacat.languagelearning.shared.error.LearningBusinessException> {
                        reads.responseAudio(
                            802, attempt.tasks.first().id,
                        )
                    }.code,
                )
                val retry = ListeningGenerationService(work, settings)
                assertEquals(
                    "LANGUAGE_LEARNING_DAILY_SET_NOT_FOUND",
                    assertFailsWith<jp.co.translacat.languagelearning.shared.error.LearningBusinessException> {
                        retry.retryGeneration(
                            802, set.id,
                        )
                    }.code,
                )
                assertEquals(
                    "LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND",
                    assertFailsWith<jp.co.translacat.languagelearning.shared.error.LearningBusinessException> {
                        retry.retryTts(
                            802, ready.items.single().id,
                        )
                    }.code,
                )
            }
        }
    }

    @Test
    fun `프로필 추천 재계산은 활동 중복과 해제 상태 및 설명 outbox를 보존한다`() = LocalScratchMysql.use { database ->
        // 준비: 실제 DB에 네 공식 활동의 약점 근거와 같은 활동의 오래된 중복을 저장한다.
        DatabaseFactory(database.settings()).use { factory ->
            val work = ExposedListeningUnitOfWork(JdbcTransactionRunner(factory.database, 4), now)
            runBlocking {
                work.write(801) {
                    repeat(5) { index ->
                        saveMetricHistory(
                            ListeningMetricHistoryState(
                                allocateId(), 801, "ja", ListeningTaskType.DICTATION,
                                "LISTENING_RECOGNITION", 40.0, .9, 1.0, 1.0, 1.0, .9, "INDEPENDENT",
                                "activity-${minOf(index, 3)}", "evaluation-$index", true, false, true,
                                "listening-evaluation", "listening-profile",
                                nowUtc.minusMinutes(index.toLong()).toString(),
                            ),
                        )
                    }
                    ListeningProfiles.recalculate(this, 801, "ja")
                }

                // 실행: 같은 근거 재계산은 추천과 이미 예약된 설명을 추가하지 않는다.
                work.write(801) { ListeningProfiles.recalculate(this, 801, "ja") }
                val profile = ListeningProfiles.profiles(work.read { metricHistory(801, "ja") }).first()
                val recommendation = work.read { recommendations(801, "ja").single() }

                // 검증: 중복 활동은 가중치 0, 최신 활동 네 개만 점수에 반영한다.
                assertEquals(4, profile.sampleCount)
                assertEquals("ACTIVE", profile.weaknessState)
                assertEquals("DICTATION", recommendation.recommendedTask)
                assertEquals(1, work.read { recoverable(10).count { it.type == "EXPLANATION" } })
                assertEquals(1, work.read { metricHistory(801, "ja").count { it.finalWeight == 0.0 } })

                // 실행 및 검증: 추천 해제 이후 재계산해도 다시 활성화하지 않는다.
                work.write(801) {
                    saveRecommendation(recommendation.copy(status = "DISMISSED", dismissedAt = nowUtc.toString()))
                    ListeningProfiles.recalculate(this, 801, "ja")
                }
                assertEquals("DISMISSED", work.read { recommendations(801, "ja").single().status })
                assertTrue(work.read { recommendations(802, "ja").isEmpty() })
            }
        }
    }

    @Test
    fun `재시도 예약은 cooldown 전 회수를 막고 기존 자동 상한에서 종료한다`() = LocalScratchMysql.use { database ->
        // 준비
        DatabaseFactory(database.settings()).use { factory ->
            val runner = JdbcTransactionRunner(factory.database, 4)
            val work = ExposedListeningUnitOfWork(runner, now)
            runBlocking {
                val id = work.write(801) { enqueue(801, 1, "GENERATE", "synthetic-cooldown", JsonObject(emptyMap())) }
                val first = checkNotNull(
                    work.write(801) { claim(801, id, "11111111-1111-4111-8111-111111111111", nowUtc.plusMinutes(2)) },
                )

                // 실행 및 검증: Provider cooldown을 넘기기 전에는 같은 작업을 다시 실행할 수 없다.
                assertEquals(1, first.attemptCount)
                assertEquals(
                    ListeningJobFailure.RETRY,
                    work.write(801) { fail(first, "PROVIDER_RATE_LIMITED", true, 1, Duration.ofSeconds(30)) },
                )
                assertTrue(work.read { recoverable(10).isEmpty() })
                val later = ExposedListeningUnitOfWork(runner, Clock.offset(now, Duration.ofSeconds(31)))
                val second = checkNotNull(
                    later.write(801) { claim(801, id, "22222222-2222-4222-8222-222222222222", nowUtc.plusMinutes(2)) },
                )

                // 검증: 자동 재시도 1회 이후 오류는 영속 FAILED로 남고 이전 lease도 권한을 잃는다.
                assertEquals(2, second.attemptCount)
                assertEquals(ListeningJobFailure.STALE, later.write(801) { fail(first, "PROVIDER_TIMEOUT", true, 1) })
                assertEquals(
                    ListeningJobFailure.EXHAUSTED, later.write(801) { fail(second, "PROVIDER_TIMEOUT", true, 1) },
                )
                assertTrue(later.read { recoverable(10).isEmpty() })
            }
        }
    }

    @Test
    fun `공통 fingerprint는 Listening hash와 기능별 문맥 및 사용자 격리를 보존한다`() = LocalScratchMysql.use { database ->
        // 준비: 서로 다른 source와 사용자가 같은 저장 테이블을 사용한다.
        DatabaseFactory(database.settings()).use { factory ->
            val work = ExposedListeningUnitOfWork(JdbcTransactionRunner(factory.database, 4), now)
            fun metadata(hash: String) = buildJsonObject {
                put("contentHash", hash)
                put("scenarioCategory", "SYNTHETIC")
                put("grammarFocusCodes", JsonArray(emptyList()))
            }
            runBlocking {
                work.write(801) {
                    assertTrue(
                        fingerprints.register(
                            801, 1, "en", "Synthetic listening.", metadata("listening-normalized-hash"), nowUtc,
                        ),
                    )
                    assertTrue(
                        ExposedGenerationFingerprintRepository({}, "WRITING").register(
                            801, 2, "en", "Synthetic writing.", metadata("writing-normalized-hash"), nowUtc,
                        ),
                    )
                }
                work.write(802) {
                    assertTrue(
                        fingerprints.register(
                            802, 3, "en", "Other synthetic user.", metadata("other-user-hash"), nowUtc,
                        ),
                    )
                }

                // 실행
                val context = work.read { fingerprints.context(801, "en", nowUtc) }

                // 검증: 재정규화하지 않고 등록한 hash와 SAME/CROSS 분류를 유지한다.
                val same = context.getValue("sameFeatureRecent").jsonArray.single().jsonObject
                val cross = context.getValue("crossFeatureRecent").jsonArray.single().jsonObject
                assertEquals("LISTENING", same.getValue("sourceType").jsonPrimitive.content)
                assertEquals("listening-normalized-hash", same.getValue("contentHash").jsonPrimitive.content)
                assertEquals("WRITING", cross.getValue("sourceType").jsonPrimitive.content)
                assertEquals(
                    setOf("listening-normalized-hash", "writing-normalized-hash"),
                    context.getValue("exactContentHashes90d").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                )
                assertTrue(
                    work.read {
                        fingerprints.context(801, "ja", nowUtc)
                            .getValue("sameFeatureRecent").jsonArray.isEmpty()
                    },
                )
            }
        }
    }

    @Test
    fun `두 pool의 동일 날짜 생성은 사용자 잠금 아래 한 세트로 수렴한다`() = LocalScratchMysql.use { database ->
        // 준비: 실제 MySQL에 독립 pool을 연결한다.
        DatabaseFactory(database.settings()).use { first ->
            DatabaseFactory(database.settings()).use { second ->
                val works =
                    listOf(first, second).map { ExposedListeningUnitOfWork(JdbcTransactionRunner(it.database, 4), now) }
                runBlocking {
                    // 실행
                    val created = works.map { work ->
                        async(Dispatchers.Default) {
                            work.write(801) {
                                sets(801, LocalDate.parse("2026-09-26")).singleOrNull() ?: ListeningSetState(
                                    allocateId(), 801, "2026-09-26", "ko", "en", "DICTATION", "MY_LEVEL", 5,
                                    JsonObject(emptyMap()),
                                ).also(::insertSet)
                            }
                        }
                    }.awaitAll()

                    // 검증: 실제 쓰기 경쟁과 두 사용자 격리를 확인한다.
                    assertEquals(created.first().id, created.last().id)
                    works.first().read {
                        assertEquals(1, sets(801).size)
                        assertNull(set(802, created.first().id))
                    }
                }
            }
        }
    }

    @Test
    fun `재시작 후 만료 lease를 회수하고 이전 작업 결과와 중복 완료를 거절한다`() = LocalScratchMysql.use { database ->
        var jobId = 0L
        var firstClaim: ListeningJob? = null
        // 준비 및 실행: 첫 pool에서 작업을 시작하고 정상 종료로 연결을 모두 닫는다.
        DatabaseFactory(database.settings()).use { factory ->
            val work = ExposedListeningUnitOfWork(JdbcTransactionRunner(factory.database, 4), now)
            runBlocking {
                jobId = work.write(801) { enqueue(801, 1, "GENERATE", "synthetic-generate", JsonObject(emptyMap())) }
                firstClaim =
                    work.write(801) { claim(801, jobId, "11111111-1111-4111-8111-111111111111", nowUtc.plusMinutes(2)) }
                assertNotNull(firstClaim)
            }
        }

        // 실행: 새 pool과 만료 이후 clock으로 복구한다.
        DatabaseFactory(database.settings()).use { factory ->
            val work = ExposedListeningUnitOfWork(
                JdbcTransactionRunner(factory.database, 4), Clock.offset(now, Duration.ofMinutes(3)),
            )
            runBlocking {
                assertEquals(listOf(jobId), work.read { recoverable(10).map { it.id } })
                val resumed =
                    work.write(801) { claim(801, jobId, "22222222-2222-4222-8222-222222222222", nowUtc.plusMinutes(2)) }

                // 검증: 다른 사용자·옛 token·중복 commit은 job을 완료할 수 없다.
                assertNotNull(resumed)
                assertNull(
                    work.write(802) {
                        claim(
                            802, jobId, "33333333-3333-4333-8333-333333333333", nowUtc.plusMinutes(2),
                        )
                    },
                )
                assertFalse(work.write(801) { finish(checkNotNull(firstClaim)) })
                assertTrue(work.write(801) { finish(resumed) })
                assertFalse(work.write(801) { finish(resumed) })
                assertEquals(emptyList(), work.read { recoverable(10) })
            }
        }
    }

    @Test
    fun `세션 revision 충돌 rollback과 오디오 사용자 격리 및 보존 기한을 검사한다`() = LocalScratchMysql.use { database ->
        // 준비
        DatabaseFactory(database.settings()).use { factory ->
            val work = ExposedListeningUnitOfWork(JdbcTransactionRunner(factory.database, 4), now)
            runBlocking {
                val initial = work.write(801) {
                    val set = ListeningSetState(
                        allocateId(), 801, "2026-09-26", "ko", "en", "DICTATION", "MY_LEVEL", 5, JsonObject(emptyMap()),
                    )
                    insertSet(set)
                    val value = ListeningSessionState(
                        allocateId(), 801, set.id, "synthetic-session", listOf(ListeningTaskType.DICTATION),
                        nowUtc.toString(), nowUtc.toString(), nowUtc.plusHours(2).toString(),
                    )
                    insertSession(value)
                    value
                }
                val updated = work.write(801) { updateSession(initial.copy(actualDurationMs = 1000)) }

                // 실행 및 검증: 늦은 revision 쓰기는 실패하고 이미 저장한 상태를 보존한다.
                assertFailsWith<IllegalStateException> {
                    work.write(801) {
                        updateSession(
                            initial.copy(actualDurationMs = 9000),
                        )
                    }
                }
                assertEquals(updated, work.read { session(801, initial.id) })
                var audioId = 0L
                work.write(801) {
                    audioId = allocateId()
                    insertAudio(
                        ListeningAudioState(
                            audioId, 801, initial.id, 1, "audio/wav", hash(byteArrayOf(1, 2, 3)),
                            byteArrayOf(1, 2, 3), nowUtc.plusDays(1), null,
                        ),
                    )
                }
                assertNull(work.read { audio(802, audioId) })
                assertContentEquals(byteArrayOf(1, 2, 3), work.read { audio(801, audioId)?.bytes })

                // 실행 및 검증: 실패한 transaction은 새 blob도 남기지 않는다.
                var rollbackId = 0L
                assertFailsWith<IllegalStateException> {
                    work.write(801) {
                        rollbackId = allocateId()
                        insertAudio(
                            ListeningAudioState(
                                rollbackId, 801, initial.id, 2, "audio/wav", hash(byteArrayOf(4)),
                                byteArrayOf(4), nowUtc.plusDays(1), null,
                            ),
                        )
                        error("SYNTHETIC_ROLLBACK")
                    }
                }
                assertNull(work.read { audio(801, rollbackId) })
                val expired = ExposedListeningUnitOfWork(
                    JdbcTransactionRunner(factory.database, 4), Clock.offset(now, Duration.ofDays(2)),
                )
                assertEquals(1, expired.write(801) { expireAudio(801) })
                assertNull(expired.read { audio(801, audioId)?.bytes })
                assertNotNull(expired.read { audio(801, audioId)?.deletedAt })
            }
        }
    }

    private fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun wave(seconds: Int): ByteArray {
        val size = seconds * 16_000 * 2
        return java.nio.ByteBuffer.allocate(44 + size).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(size)
            repeat(size / 2) { putShort(1000) }
        }.array()
    }
}
