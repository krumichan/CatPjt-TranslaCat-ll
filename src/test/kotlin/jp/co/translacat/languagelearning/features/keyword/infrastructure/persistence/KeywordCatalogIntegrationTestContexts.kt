package jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.keyword.application.*
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordChange
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordType
import jp.co.translacat.languagelearning.features.listening.application.ListeningContextService
import jp.co.translacat.languagelearning.features.listening.infrastructure.persistence.ExposedListeningUnitOfWork
import jp.co.translacat.languagelearning.features.practice.application.PracticeContextService
import jp.co.translacat.languagelearning.features.practice.domain.PracticeDomain
import jp.co.translacat.languagelearning.features.practice.infrastructure.ExposedPracticeUnitOfWork
import jp.co.translacat.languagelearning.features.settings.application.DefaultSettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.GetAdminSettings
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.UpdateUserSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedAdminSettingsUnitOfWork
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedSettingsReadQueries
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedSettingsUnitOfWork
import jp.co.translacat.languagelearning.features.speaking.application.SpeakingContextService
import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.features.speaking.infrastructure.ExposedSpeakingUnitOfWork
import jp.co.translacat.languagelearning.features.writing.application.WritingContextService
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.ExposedWritingSetUnitOfWork
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import jp.co.translacat.languagelearning.support.SettingsFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeywordCatalogIntegrationTestContexts {
    private val clock = Clock.fixed(Instant.parse("2026-09-26T04:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.of(2026, 9, 26)

    @Test
    fun `공식 Speaking 평가가 없어도 내일 예약 키워드는 오늘 Writing 진입에서 승격되지 않는다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 실제 설정·키워드·Speaking 저장소에 공개된 세션만 만들고 공식 평가 Activity는 만들지 않는다.
                val fixture = fixture(factory, 901)
                val keyword = fixture.keywords.createCustom(
                    901, fixture.facts.hasStartedLearning(901),
                    KeywordChange(text = "Synthetic tomorrow", type = KeywordType.TOPIC),
                )
                assertEquals(date.plusDays(1), keyword.pendingEffectiveDate)
                assertTrue(fixture.writing.read { growth.activities(901, "SPEAKING", date, date, 0, 1).isEmpty() })

                // 실행: 실제 Writing 문맥 생성이 키워드 후보 조회와 세트 저장을 수행한다.
                val result = WritingContextService(fixture.settings, fixture.keywords, fixture.writing, fixture.facts)
                    .getOrCreate(901, WritingType.FREE)

                // 검증: 내일 예약은 그대로 남고 오늘 출제 snapshot에는 포함되지 않는다.
                val stored = fixture.keywordWork.execute(901) { custom.findForUser(901).single() }
                assertFalse(stored.active)
                assertEquals(date.plusDays(1), stored.pendingEffectiveDate)
                assertTrue(
                    Json.parseToJsonElement(result.snapshotJson).jsonObject.getValue(
                        "selectedKeywords",
                    ).jsonArray.isEmpty(),
                )
            }
        }
    }

    @Test
    fun `네 학습 문맥은 Speaking 시작 사용자의 예약을 보존하고 다음날에만 적용한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                for ((index, feature) in listOf("WRITING", "PRACTICE", "LISTENING", "SPEAKING").withIndex()) {
                    // 준비: 기존 즉시 활성 키워드와 내일 신규·수정·해제 예약을 같은 사용자에 만든다.
                    val user = 920L + index
                    val fixture = fixture(factory, user, speakingReady = false)
                    assertFalse(fixture.facts.hasStartedLearning(user))
                    val keep = fixture.keywords.createCustom(
                        user, false, KeywordChange(text = "Synthetic active", type = KeywordType.TOPIC),
                    )
                    val edit = fixture.keywords.createCustom(
                        user, false, KeywordChange(text = "Synthetic original", type = KeywordType.TOPIC),
                    )
                    val remove = fixture.keywords.createCustom(
                        user, false, KeywordChange(text = "Synthetic removal", type = KeywordType.TOPIC),
                    )
                    seedSpeaking(fixture.runner, user)
                    val started = fixture.facts.hasStartedLearning(user)
                    assertTrue(started)
                    fixture.keywords.updateCustom(user, started, edit.id, KeywordChange(text = "Synthetic revised"))
                    fixture.keywords.deleteCustom(user, started, remove.id)
                    fixture.keywords.createCustom(
                        user, started, KeywordChange(text = "Synthetic tomorrow", type = KeywordType.TOPIC),
                    )
                    val original = fixture.keywordWork.execute(user) { custom.findForUser(user) }
                    assertTrue(fixture.writing.read { growth.activities(user, "SPEAKING", date, date, 0, 1).isEmpty() })

                    // 실행: 각 실제 context의 후보 준비와 snapshot 저장을 독립 사용자로 실행한다.
                    val selected = when (feature) {
                        "WRITING" -> Json.parseToJsonElement(
                            WritingContextService(
                                fixture.settings, fixture.keywords,
                                fixture.writing, fixture.facts,
                            ).getOrCreate(user, WritingType.FREE).snapshotJson,
                        )
                            .jsonObject.getValue("selectedKeywords").jsonArray.map {
                                it.jsonObject.getValue(
                                    "text",
                                ).jsonPrimitive.content
                            }

                        "PRACTICE" -> Json.parseToJsonElement(
                            PracticeContextService(
                                fixture.settings, fixture.keywords,
                                ExposedPracticeUnitOfWork(fixture.runner, clock), fixture.facts,
                            ).today(user, PracticeDomain.READING, "COMPREHENSION")
                                .requestJson,
                        ).jsonObject.getValue("selectedKeywords").jsonArray.map { it.jsonPrimitive.content }

                        "LISTENING" -> ListeningContextService(
                            fixture.settings, fixture.keywords,
                            ExposedListeningUnitOfWork(fixture.runner, clock), fixture.facts,
                        ).getOrCreate(user).request
                            .getValue("setContext").jsonObject.getValue("selectedKeywords").jsonArray
                            .map { it.jsonObject.getValue("text").jsonPrimitive.content }

                        else -> SpeakingContextService(
                            fixture.settings, fixture.keywords,
                            ExposedSpeakingUnitOfWork(fixture.runner, clock), fixture.facts,
                        ) { emptyList() }.create(
                            user,
                            SpeakingCreateRequest(
                                keywordBasedTopic = true, practiceMode = SpeakingPracticeMode.FREE,
                                conversationStartMode = ConversationStartMode.AI_FIRST,
                                correctionMode = CorrectionMode.CONVERSATION,
                                targetMinutes = 5, idempotencyKey = "synthetic-keyword-context",
                            ),
                        )
                            .snapshot.selectedKeywords.map { it.getValue("text").jsonPrimitive.content }
                    }

                    // 검증: 오늘 원본 키워드만 쓰고 DB 예약 payload/날짜를 손대지 않으며 다른 사용자도 조회하지 않는다.
                    assertEquals(
                        setOf("Synthetic active", "Synthetic original", "Synthetic removal"), selected.toSet(), feature,
                    )
                    assertEquals(original, fixture.keywordWork.execute(user) { custom.findForUser(user) }, feature)
                    assertFalse(fixture.facts.hasStartedLearning(9999))
                    assertTrue(fixture.keywords.candidates(9999, false, date).isEmpty())

                    // 실행·검증: 다음 학습일에는 원본 예약 규칙대로 생성·수정·해제가 한 번에 적용된다.
                    val tomorrow = fixture.keywords.candidates(user, true, date.plusDays(1))
                    assertEquals(
                        setOf("Synthetic active", "Synthetic revised", "Synthetic tomorrow"),
                        tomorrow.map { it.text }.toSet(), feature,
                    )
                    assertEquals(
                        keep.id,
                        fixture.keywordWork.execute(user) {
                            custom.findForUser(user)
                                .first { it.text == "Synthetic active" }.id
                        },
                    )
                }
            }
        }
    }

    private suspend fun fixture(factory: DatabaseFactory, userId: Long, speakingReady: Boolean = true): Fixture {
        val runner = JdbcTransactionRunner(factory.database, 4)
        val settingsWork = ExposedSettingsUnitOfWork(runner, clock)
        val settings = DefaultSettingsServiceOperations(
            settingsWork, ExposedSettingsReadQueries(runner),
            GetAdminSettings(ExposedAdminSettingsUnitOfWork(runner, clock)), clock,
        )
        UpdateUserSettings(settingsWork).execute(userId, UserSettingsChange("ko", "en", "Asia/Seoul", 5))
        val keywordWork = ExposedKeywordUnitOfWork(runner, clock)
        val keywords = DefaultKeywordOperations(keywordWork, KeywordLearningDate { date })
        val writing = ExposedWritingSetUnitOfWork(runner, clock)
        writing.write(userId) {
            growth.saveProfile(
                GrowthProfile(userId, state = "ACTIVE", baseLevelScore = 60.0, createdAt = nowUtc, updatedAt = nowUtc),
            )
        }
        if (speakingReady) seedSpeaking(runner, userId)
        return Fixture(runner, settings, keywordWork, keywords, writing, ExposedKeywordLearningFacts(runner))
    }

    private suspend fun seedSpeaking(runner: JdbcTransactionRunner, userId: Long) {
        // 유효한 이전 세션 snapshot을 저장해 네 문맥의 실제 조회도 같은 준비 자료를 읽게 한다.
        val speaking = ExposedSpeakingUnitOfWork(runner, clock)
        speaking.write(userId) {
            val policy = SpeakingSessionPolicySnapshot.from(SettingsFixtures.admin())
            val snapshot = SpeakingSessionSnapshot(
                null, "Synthetic topic", "FREE_TALK", null, "Synthetic topic", null, null,
                emptyList(), "ko", "en", SpeakingPracticeMode.FREE, ConversationStartMode.USER_FIRST,
                ConversationStartMode.USER_FIRST,
                CorrectionMode.CONVERSATION, 5, policy.maxTurns, "marin", "NORMAL", policy, null,
                SpeakingResultKind.SESSION_COACHING, "free-session-coaching-v1",
            )
            records.saveSession(
                SpeakingSessionRecord(
                    userId = userId, createIdempotencyKey = "synthetic-ready",
                    learningDate = date, snapshot = snapshot, status = SpeakingSessionStatus.COMPLETED,
                    opening = buildJsonObject { put("_executionState", "READY") }, startedAt = nowUtc,
                ),
            )
        }
    }

    private data class Fixture(
        val runner: JdbcTransactionRunner,
        val settings: SettingsServiceOperations,
        val keywordWork: KeywordUnitOfWork,
        val keywords: KeywordOperations,
        val writing: ExposedWritingSetUnitOfWork,
        val facts: KeywordLearningFacts,
    )
}
