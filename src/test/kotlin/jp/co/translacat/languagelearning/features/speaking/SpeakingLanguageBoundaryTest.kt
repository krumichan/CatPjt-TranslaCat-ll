package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningFacts
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsResult
import jp.co.translacat.languagelearning.features.settings.application.model.UserSettingsSnapshot
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.features.speaking.application.*
import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.support.SettingsFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import kotlin.test.*

class SpeakingLanguageBoundaryTest {
    @Test
    fun `새 영어 세션은 이전 일본어 active를 재사용하지 않고 명시한 옛 상세는 보존한다`() = runBlocking {
        // 준비
        val fixture = Fixture()
        val japanese = fixture.add(21, "ja")

        // 실행
        val created = fixture.context.create(41, fixture.request("new-english"))
        val old = fixture.read.detail(41, japanese.id).getValue("session").jsonObject

        // 검증
        assertEquals("en", created.snapshot.learningLanguage)
        assertEquals("ja", old.getValue("learningLanguage").jsonPrimitive.content)
        assertEquals(2, fixture.sessions.size)
    }

    @Test
    fun `현재 언어 active가 이전 언어보다 오래되어도 조회하고 중복 생성을 거부한다`() = runBlocking {
        // 준비
        val fixture = Fixture()
        val english = fixture.add(20, "en")
        fixture.add(21, "ja")

        // 실행
        val active = fixture.read.active(41)
        val error = assertFailsWith<SpeakingFailure> { fixture.context.create(41, fixture.request("duplicate")) }

        // 검증
        assertEquals(LearningPublicId.encode(english.id).toString(), active!!.getValue("session").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("SESSION_NOT_ACTIVE", error.code)
        assertEquals(2, fixture.sessions.size)
    }

    @Test
    fun `이전 언어만 진행 중이면 active가 없고 당일 완료도 현재 언어만 집계한다`() = runBlocking {
        // 준비
        val fixture = Fixture()
        val english = fixture.add(20, "en", SpeakingSessionStatus.COMPLETED)
        fixture.add(21, "ja")

        // 실행
        val active = fixture.read.active(41)
        val today = fixture.read.today(41).map { it.jsonObject }

        // 검증
        assertNull(active)
        val free = today.single { it.getValue("practiceMode").jsonPrimitive.content == "FREE" }
        assertEquals(LearningPublicId.encode(english.id).toString(), free.getValue("sessionId").jsonPrimitive.content)
        assertTrue(free.getValue("completed").jsonPrimitive.boolean)
    }

    @Test
    fun `다른 언어 세션의 멱등 키를 재사용하면 명시적으로 거부한다`() = runBlocking {
        // 준비
        val fixture = Fixture()
        fixture.add(21, "ja")

        // 실행
        val error = assertFailsWith<SpeakingFailure> { fixture.context.create(41, fixture.request("session-21")) }

        // 검증
        assertEquals("SPEAKING_LANGUAGE_CHANGED", error.code)
        assertEquals(409, error.status)
        assertEquals(1, fixture.sessions.size)
    }

    @Test
    fun `언어를 바꿔도 전체 언어 일일 비용 한도는 유지한다`() = runBlocking {
        // 준비
        val fixture = Fixture()
        repeat(SettingsFixtures.admin().dailySpeakingSessionLimit) { index ->
            fixture.add(20L + index, "ja", SpeakingSessionStatus.COMPLETED)
        }

        // 실행
        val error = assertFailsWith<SpeakingFailure> { fixture.context.create(41, fixture.request("limited")) }

        // 검증
        assertEquals("DAILY_LIMIT_EXCEEDED", error.code)
        assertEquals(SettingsFixtures.admin().dailySpeakingSessionLimit, fixture.sessions.size)
    }

    @Test
    fun `설정 승격이 문맥 준비 뒤 겹쳐도 생성 snapshot 언어의 성장만 읽는다`() = runBlocking {
        // 준비: settings 응답은 일본어이고 잠금을 얻을 때 실제 활성 설정은 영어로 바뀌었다고 가정한다.
        val fixture = Fixture("ja")

        // 실행
        val created = fixture.context.create(41, fixture.request("captured-japanese"))

        // 검증: 기본 repository의 현재 언어 profile은 fixture가 거부한다.
        assertEquals(listOf<Pair<String?, String?>>("ko" to "ja"), fixture.growthScopes)
        assertEquals("ja", created.snapshot.learningLanguage)
        assertEquals(20.0, created.snapshot.learningProfile!!.getValue("baseLevelScore").jsonPrimitive.double)
    }

    private class Fixture(language: String = "en") {
        private val source = SpeakingCoachingReadFixtures()
        val sessions = mutableListOf<SpeakingSessionRecord>()
        val growthScopes = mutableListOf<Pair<String?, String?>>()
        private val settings = port<SettingsServiceOperations> { name, _ -> when (name) {
            "userSnapshot" -> UserSettingsSnapshot(41, source.now.toLocalDate(), UserSettingsResult(
                SettingsFixtures.configured(41).copy(learningLanguage = language), SettingsFixtures.policy()))
            "adminPolicy" -> SettingsFixtures.admin()
            else -> error("Unexpected settings operation: $name")
        } }
        private val scopedGrowth = port<GrowthRepository> { name, _ -> when (name) {
            "profile" -> GrowthProfile(41, state = "ACTIVE", baseLevelScore = 20.0,
                createdAt = source.now, updatedAt = source.now)
            "signals", "masteries" -> emptyList<Any>()
            else -> error("Unexpected source growth operation: $name")
        } }
        private val growth = port<GrowthRepository> { name, args -> when (name) {
            "scoped" -> {
                growthScopes += (args!![0] as String?) to (args[1] as String?)
                scopedGrowth
            }
            else -> error("Unscoped current-language growth must not be read: $name")
        } }
        private val records = port<SpeakingRepository> { name, args -> when (name) {
            "sessionByIdempotency" -> sessions.firstOrNull { it.createIdempotencyKey == args!![1] }
            "session" -> sessions.firstOrNull { it.id == args!![1] }
            "sessions" -> sessions.sortedByDescending { it.id }.filter { it.openingReady }
            "active" -> sessions.sortedByDescending { it.id }.firstOrNull { value ->
                value.status == SpeakingSessionStatus.IN_PROGRESS && value.openingState != "FAILED" &&
                    (args!!.size == 1 || ConfiguredLanguagePair(args[1] as String, args[2] as String)
                        .matches(value.snapshot.originLanguage, value.snapshot.learningLanguage))
            }
            "saveSession" -> (args!![0] as SpeakingSessionRecord).let { value ->
                val saved = if (value.id == 0L) value.copy(id = (sessions.maxOfOrNull { it.id } ?: 0) + 1) else value
                sessions.removeAll { it.id == saved.id }
                sessions += saved
                saved
            }
            "turns", "results" -> emptyList<Any>()
            "job", "result", "audio" -> null
            else -> error("Unexpected Speaking operation: $name")
        } }
        private val work = object : SpeakingUnitOfWork {
            private val transaction = object : SpeakingTransaction {
                override val records = this@Fixture.records
                override val growth = this@Fixture.growth
                override val nowUtc = source.now
            }
            override suspend fun <T> read(block: SpeakingTransaction.() -> T): T = transaction.block()
            override suspend fun <T> write(userId: Long, block: SpeakingTransaction.() -> T): T = transaction.block()
            override suspend fun <T> catalogWrite(block: SpeakingTransaction.() -> T): T = error("No catalog writes")
        }
        val read = SpeakingReadService(work, settings)
        val context = SpeakingContextService(settings,
            port<KeywordOperations> { name, _ -> error("No keyword operation: $name") }, work,
            KeywordLearningFacts { false }, { emptyList() })

        fun add(id: Long, language: String, status: SpeakingSessionStatus = SpeakingSessionStatus.IN_PROGRESS) =
            source.session.copy(id = id, createIdempotencyKey = "session-$id", status = status,
                completedAt = if (status == SpeakingSessionStatus.COMPLETED) source.now else null,
                snapshot = source.session.snapshot.copy(learningLanguage = language)).also { sessions += it }

        fun request(key: String) = SpeakingCreateRequest(customTopic = "Synthetic topic",
            practiceMode = SpeakingPracticeMode.FREE, conversationStartMode = ConversationStartMode.AI_FIRST,
            correctionMode = CorrectionMode.CONVERSATION, targetMinutes = 5, idempotencyKey = key)
    }

    private companion object {
        inline fun <reified T> port(crossinline call: (String, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name, args)
            } as T
    }
}
