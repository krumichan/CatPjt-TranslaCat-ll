package jp.co.translacat.languagelearning.features.overview

import jp.co.translacat.languagelearning.features.growth.application.*
import jp.co.translacat.languagelearning.features.growth.domain.model.*
import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import jp.co.translacat.languagelearning.features.leveltest.application.*
import jp.co.translacat.languagelearning.features.listening.application.*
import jp.co.translacat.languagelearning.features.overview.application.OverviewService
import jp.co.translacat.languagelearning.features.practice.application.*
import jp.co.translacat.languagelearning.features.practice.domain.*
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.model.*
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.features.speaking.SpeakingCoachingReadFixtures
import jp.co.translacat.languagelearning.features.speaking.application.*
import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.features.writing.application.*
import jp.co.translacat.languagelearning.support.SettingsFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import java.time.LocalDateTime
import kotlin.test.*

class OverviewLanguagePairIsolationTest {
    @Test
    fun `현재 영어 대시보드와 추세는 일본어 및 언어 미상 원본 점수를 섞지 않는다`() = runBlocking {
        // 준비: 같은 날짜에 영어 80점·일본어 20점·언어 미상 99점의 원본과 Growth 행을 둔다.
        val fixture = Fixture()

        // 실행: 실제 기능별 보고서와 통합 projection을 함께 호출한다.
        val dashboard = fixture.overview.dashboard(41, fixture.today, fixture.today, null, null)
        val writing = fixture.overview.trend(41, "WRITING", fixture.today, fixture.today)
        val speaking = fixture.overview.trend(41, "SPEAKING", fixture.today, fixture.today)
        val reading = fixture.overview.trend(41, "READING", fixture.today, fixture.today)

        // 검증: 언어 snapshot이 확인된 현재 영어 활동만 점수와 오늘 완료 수에 반영한다.
        assertEquals("en", dashboard.getValue("learningLanguage").jsonPrimitive.content)
        val performance = dashboard.getValue("activityPerformance").jsonObject
        for (source in listOf("writing", "speaking", "reading")) {
            assertEquals(80.0, performance.getValue(source).jsonObject.getValue("recentScore").jsonPrimitive.double)
            assertEquals(1.0, performance.getValue(source).jsonObject.getValue("today").jsonObject.getValue("completed").jsonPrimitive.double)
        }
        for (trend in listOf(writing, speaking, reading)) {
            assertEquals(1, trend.getValue("sampleCount").jsonPrimitive.int)
            assertTrue(trend.getValue("metrics").jsonObject.isNotEmpty())
            trend.getValue("metrics").jsonObject.values.forEach { points ->
                assertEquals(80.0, points.jsonArray.single().jsonObject.getValue("score").jsonPrimitive.double)
            }
        }
    }

    @Test
    fun `활성 언어를 바꿔 조회해도 과거 언어와 미상 원문 이력은 삭제되지 않는다`() = runBlocking {
        // 준비: pending 설정은 현재 언어 판정에 사용하지 않는다.
        val fixture = Fixture()
        fixture.current = fixture.current.copy(learningLanguage = "ja", pendingLearningLanguage = "en")

        // 실행
        val dashboard = fixture.overview.dashboard(41, fixture.today, fixture.today, null, null)
        val histories = listOf("WRITING", "SPEAKING", "READING").map {
            fixture.overview.history(41, it, "30d", null, null)
        }

        // 검증: 현재 일본어 점수는 20점이며 명시적 과거 조회는 세 언어의 기록을 모두 보존한다.
        val performance = dashboard.getValue("activityPerformance").jsonObject
        for (source in listOf("writing", "speaking", "reading")) {
            assertEquals(20.0, performance.getValue(source).jsonObject.getValue("recentScore").jsonPrimitive.double)
        }
        histories.forEach { rows ->
            assertEquals(3, rows.size)
            assertEquals(setOf("en", "ja", null), rows.map {
                it.jsonObject.getValue("learningLanguage").jsonPrimitive.contentOrNull
            }.toSet())
            rows.filter { it.jsonObject.getValue("learningLanguage") != JsonNull }.forEach {
                assertEquals("ko", it.jsonObject.getValue("originLanguage").jsonPrimitive.content)
            }
        }
        assertEquals(3, fixture.writingData.sets.size)
        assertEquals(3, fixture.speakingSessions.size)
        assertEquals(3, fixture.practiceSets.size)
    }

    @Test
    fun `언어 태그 표기 차이만 허용하고 지역 태그와 미상 언어는 합치지 않는다`() {
        // 준비
        val pair = ConfiguredLanguagePair("ko-KR", "en-US")

        // 실행 / 검증
        assertTrue(pair.matches(" KO_kr ", " EN_us "))
        assertFalse(pair.matches("ko-KR", "en-GB"))
        assertFalse(pair.matches("ko", "en"))
        assertFalse(pair.matches(null, "en-US"))
        assertFalse(pair.matches("ko-KR", "UNKNOWN"))
    }

    @Test
    fun `새 말하기의 추천은 다른 언어 표본을 합쳐 최소 근거를 채우지 않는다`() = runBlocking {
        // 준비: 같은 추천 신호가 en·ja·UNKNOWN에 하나씩 있어 전체 합계만 최소 두 근거를 넘는다.
        val fixture = Fixture()

        // 실행
        val focus = fixture.speaking.recommendedFocus(41)

        // 검증: 현재 영어 근거는 하나이므로 다른 언어를 빌려 추천하지 않는다.
        assertTrue(focus.isEmpty())
        assertEquals(3, fixture.speakingSessions.size)
    }

    private class Fixture {
        val now: LocalDateTime = LocalDateTime.parse("2026-10-03T01:00:00")
        val today = now.toLocalDate()
        var current = SettingsFixtures.configured(41).copy(learningLanguage = "en", pendingLearningLanguage = "ja")
        private val languages = listOf("en", "ja", null)
        private val scores = listOf(80, 20, 99)
        private val settings = port<SettingsServiceOperations> { name, _ ->
            when (name) {
                "userSnapshot" -> UserSettingsSnapshot(41, today, UserSettingsResult(current, SettingsFixtures.policy()))
                "learningDate" -> today
                else -> error("Unexpected settings operation: $name")
            }
        }
        val writingData = WritingReportData(
            languages.mapIndexed { index, language -> WritingReportSet(index + 1L, today, "FREE", 1,
                "COMPLETED", 1, if (language == null) null else "ko", language) },
            scores.mapIndexed { index, score -> WritingReportEvaluation(index + 1L, today, today,
                "DAILY", "SUCCESS", now, List(6) { score }, "{}") },
            3,
        )
        private val writing = WritingReportService { writingData }
        val speakingSessions = languages.mapIndexed { index, language ->
            SpeakingCoachingReadFixtures(index + 1L).session.let { original -> original.copy(
                snapshot = original.snapshot.copy(learningLanguage = language ?: "UNKNOWN",
                    resultKind = SpeakingResultKind.SCORED_EVALUATION),
                status = SpeakingSessionStatus.EVALUATED, totalDurationSeconds = 60, completedAt = now,
            ) }
        }
        private val speakingRepository = port<SpeakingRepository> { name, args ->
            when (name) {
                "sessions" -> speakingSessions
                "result" -> {
                    val id = args!![0] as Long
                    SpeakingResultRecord(id, 0, SpeakingResultKind.SCORED_EVALUATION, "EVALUATED",
                        buildJsonObject {
                            put("overallScore", scores[id.toInt() - 1]); put("evaluationConfidence", .9)
                            put("profileSignals", buildJsonArray { add(buildJsonObject {
                                put("patternKey", "cross-language-signal"); put("confidence", .9)
                                put("direction", "WEAKNESS"); put("recommendedFocus", "synthetic-focus")
                            }) })
                        }, now)
                }
                "turns" -> emptyList<SpeakingTurnRecord>()
                else -> error("Unexpected Speaking operation: $name")
            }
        }
        private val speakingWork = object : SpeakingUnitOfWork {
            private val transaction = object : SpeakingTransaction {
                override val records = speakingRepository
                override val nowUtc = now
                override val growth: GrowthRepository get() = error("No Speaking growth access")
            }
            override suspend fun <T> read(block: SpeakingTransaction.() -> T): T = transaction.block()
            override suspend fun <T> write(userId: Long, block: SpeakingTransaction.() -> T): T = error("No writes")
            override suspend fun <T> catalogWrite(block: SpeakingTransaction.() -> T): T = error("No writes")
        }
        val speaking = SpeakingReportService(speakingWork, settings, SpeakingReadService(speakingWork, settings),
            { _, _ -> JsonNull }, WritingReportQueries { writingData })
        val practiceSets = languages.mapIndexed { index, language -> PracticeSet(index + 1L, 41, today,
            PracticeDomain.READING, "READING", 2, 1,
            if (language == null) "{broken" else """{"originLanguage":"ko","learningLanguage":"$language"}""",
            status = PracticeStatus.COMPLETED, officialScore = scores[index].toDouble(), startedAt = now, completedAt = now) }
        private val practiceRepository = port<PracticeRepository> { name, args ->
            when (name) {
                "range" -> practiceSets
                "attempts" -> listOf(PracticeAttempt(1, 1, 1, listOf("A"), true, now))
                "metrics" -> listOf(PracticeMetric("INFERENCE", scores[(args!![0] as Long).toInt() - 1].toDouble(), 1))
                else -> error("Unexpected Practice operation: $name")
            }
        }
        private val practice = PracticeReadService(object : PracticeUnitOfWork {
            private val transaction = object : PracticeTransaction {
                override val records = practiceRepository
                override val nowUtc = now
                override val growth: GrowthRepository get() = error("No Practice growth access")
            }
            override suspend fun <T> read(block: PracticeTransaction.() -> T): T = transaction.block()
            override suspend fun <T> write(userId: Long, block: PracticeTransaction.() -> T): T = error("No writes")
        })
        private val listening = ListeningReadService(object : ListeningUnitOfWork {
            private val transaction = port<ListeningTransaction> { name, _ ->
                when (name) {
                    "sets", "sessions", "metricHistory", "recommendations" -> emptyList<Any>()
                    else -> error("Unexpected Listening operation: $name")
                }
            }
            override suspend fun <T> read(block: ListeningTransaction.() -> T): T = transaction.block()
            override suspend fun <T> write(userId: Long, block: ListeningTransaction.() -> T): T = error("No writes")
        }, settings)
        private val activities = scores.mapIndexed { index, score -> GrowthActivity(index + 1L, 41, "SPEAKING",
            "ll-speaking-${index + 1}", today, "Synthetic", 60, "EVALUATED", score.toDouble(), .9, now, now,
            createdAt = now, updatedAt = now) }
        private val growth = object : GrowthUnitOfWork {
            private val transaction = object : GrowthTransaction {
                override val records = port<GrowthRepository> { name, args -> when (name) {
                    "activities" -> if (args!![4] == 0L) activities else emptyList<GrowthActivity>()
                    "metrics" -> listOf(GrowthMetric("FLUENCY", "EVALUATED", scores[(args!![0] as Long).toInt() - 1].toDouble(), .9, null))
                    else -> error("Unexpected Growth operation: $name")
                } }
                override fun requireActiveIfPresent(userId: Long) = Unit
            }
            override suspend fun <T> read(block: GrowthTransaction.() -> T): T = transaction.block()
            override suspend fun <T> write(userId: Long, block: GrowthTransaction.() -> T): T = error("No writes")
        }
        val overview = OverviewService(settings, writing, speaking, listening, practice, growth,
            LevelSessionService(port { name, _ -> error("No Level operation: $name") },
                port { name, _ -> error("No Level context: $name") }),
            { _, _ -> JsonNull }, writingDetail = { _, _ -> JsonNull })
    }

    private companion object {
        inline fun <reified T> port(crossinline call: (String, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name, args)
            } as T
    }
}
