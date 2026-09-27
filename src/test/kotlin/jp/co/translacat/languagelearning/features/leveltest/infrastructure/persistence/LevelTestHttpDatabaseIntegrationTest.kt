package jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence

import jp.co.translacat.languagelearning.features.leveltest.application.LevelAnswerService
import jp.co.translacat.languagelearning.features.leveltest.application.LevelAudioService
import jp.co.translacat.languagelearning.features.leveltest.application.LevelQuestionService
import jp.co.translacat.languagelearning.features.leveltest.application.LevelSessionService
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestAnswerMode
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestItemType
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestSessionStatus
import jp.co.translacat.languagelearning.features.leveltest.infrastructure.SettingsLevelTestContext
import jp.co.translacat.languagelearning.features.leveltest.infrastructure.ai.HttpLevelTestAi
import jp.co.translacat.languagelearning.features.leveltest.infrastructure.storage.LocalLevelTestAudioStore
import jp.co.translacat.languagelearning.features.settings.application.DefaultSettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.GetAdminSettings
import jp.co.translacat.languagelearning.features.settings.application.UpdateUserSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedAdminSettingsUnitOfWork
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedSettingsReadQueries
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedSettingsUnitOfWork
import jp.co.translacat.languagelearning.shared.ai.HttpSpeechExecution
import jp.co.translacat.languagelearning.shared.ai.SpeechSynthesisCommand
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

/** 실제 Settings·세션·출제·평가·성장을 MySQL과 Python 범용 HTTP에 연결한다. 모델 출력만 합성이다. */
class LevelTestHttpDatabaseIntegrationTest {
    @Test
    fun `20문항 생성 답변 평가와 중복 보호 재시작 최종 성장을 실제 DB HTTP로 검증한다`() = LocalScratchMysql.use { db ->
        // 준비: 별도 scratch DB와 실제 파일 저장소를 사용한다. 기존 로컬 데이터에는 접근하지 않는다.
        val url = requireNotNull(System.getenv("LL_TEST_AI_URL"))
        require(URI(url).host in setOf("127.0.0.1", "localhost", "::1"))
        val audioRoot = Files.createTempDirectory(Path.of("build"), "level-http-audio-")
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                val runner = JdbcTransactionRunner(factory.database, 4)
                val settingsWork = ExposedSettingsUnitOfWork(runner)
                val adminWork = ExposedAdminSettingsUnitOfWork(runner)
                val settings = DefaultSettingsServiceOperations(
                    settingsWork, ExposedSettingsReadQueries(runner), GetAdminSettings(adminWork),
                )
                UpdateUserSettings(settingsWork).execute(
                    123, UserSettingsChange(originLanguage = "ko", learningLanguage = "en"),
                )
                val context = SettingsLevelTestContext(settings)
                val work = ExposedLevelTestUnitOfWork(runner)
                val audio = LevelAudioService(work, LocalLevelTestAudioStore(audioRoot), "http://127.0.0.1:18766")
                val sessions = LevelSessionService(work, context)
                val session = sessions.start(123, null, "synthetic-http-session")
                assertEquals(session.id, sessions.start(123, null, "synthetic-duplicate-start").id)
                val answerAudio = HttpSpeechExecution(url, "synthetic-local-model-key").use { speech ->
                    speech.synthesize(
                        SpeechSynthesisCommand(
                            "Synthetic reference", "marin", "en", "NORMAL",
                            Instant.now().plusSeconds(30), "synthetic-level-answer",
                        ),
                    )
                }

                HttpLevelTestAi(
                    url, "synthetic-local-model-key", 180, publishAudio = audio::publishReference,
                ).use { ai ->
                    var questions = LevelQuestionService(work, context, ai, audio, prefetchEnabled = false)
                    var answers = LevelAnswerService(work, context, ai, audio)

                    // 실행: 응답 종류별 실제 업무 경로로 제출하며 동일 요청과 다른 답변의 충돌을 함께 확인한다.
                    for (number in 1..20) {
                        val item = questions.current(123, session.id)
                        assertEquals(number, item.questionNumber)
                        assertEquals(item.id, questions.current(123, session.id).id)
                        val key = "synthetic-answer-$number"
                        val result = when (item.data.answerMode) {
                            LevelTestAnswerMode.CHOICE -> answers.submitText(
                                123, session.id, item.id, key,
                                item.data.internalAnswerKey.correctOptionKey, item.data.internalAnswerKey.correctOrder,
                                null,
                            )

                            LevelTestAnswerMode.TEXT -> answers.submitText(
                                123, session.id, item.id, key, null, null,
                                if (item.data.itemType == LevelTestItemType.LISTENING_DICTATION)
                                    item.data.referencePayload.getValue(
                                        "sourceText",
                                    ).jsonPrimitive.content else "Synthetic answer",
                            )

                            LevelTestAnswerMode.AUDIO -> answers.submitAudio(
                                123, session.id, item.id, key, 4000,
                                answerAudio.audioBytes, answerAudio.contentType,
                            )
                        }
                        assertTrue(result.evaluation?.evaluable == true, "q$number")
                        if (number == 1) {
                            val replay = answers.submitText(
                                123, session.id, item.id, key, item.data.internalAnswerKey.correctOptionKey,
                                item.data.internalAnswerKey.correctOrder, null,
                            )
                            assertEquals(result.response.id, replay.response.id)
                            assertEquals(result.evaluation, replay.evaluation)
                            assertEquals(
                                409,
                                assertFailsWith<LevelTestException> {
                                    answers.submitText(123, session.id, item.id, "different-answer", "B", null, null)
                                }.httpStatus,
                            )
                        }
                        if (number == 10) {
                            // 프로세스 메모리의 캐시 없이 새 서비스와 파일 저장소로 다음 문항을 이어간다.
                            val restartedWork = ExposedLevelTestUnitOfWork(JdbcTransactionRunner(factory.database, 4))
                            val restartedAudio = LevelAudioService(
                                restartedWork, LocalLevelTestAudioStore(audioRoot), "http://127.0.0.1:18766",
                            )
                            questions = LevelQuestionService(
                                restartedWork, context, ai, restartedAudio, prefetchEnabled = false,
                            )
                            answers = LevelAnswerService(restartedWork, context, ai, restartedAudio)
                        }
                    }
                }

                // 검증: 원본 평가 20개와 최종 기준점·성장은 한 번만 존재하고 새 DB 연결에서도 조회된다.
                assertEquals(LevelTestSessionStatus.COMPLETED, sessions.session(123, session.id).status)
                assertEquals(20, sessions.detail(123, session.id).items.size)
                assertNotNull(sessions.baseline(123))
                DatabaseFactory(db.settings()).use { reopened ->
                    val restored = LevelSessionService(
                        ExposedLevelTestUnitOfWork(JdbcTransactionRunner(reopened.database, 4)), context,
                    )
                    assertEquals(session.uid, restored.baseline(123)?.completionId)
                }
                db.connect().use { connection ->
                    listOf(
                        "language_learning_level_test_evaluation" to 20L,
                        "language_learning_level_test_baseline" to 1L,
                        "language_learning_activity" to 1L,
                    ).forEach { (table, count) ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                                assertTrue(result.next()); assertEquals(count, result.getLong(1), table)
                            }
                        }
                    }
                }
            }
        }
    }
}
