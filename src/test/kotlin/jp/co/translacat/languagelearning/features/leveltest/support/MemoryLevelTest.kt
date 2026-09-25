package jp.co.translacat.languagelearning.features.leveltest.support

import jp.co.translacat.languagelearning.features.leveltest.application.*
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import jp.co.translacat.languagelearning.features.leveltest.domain.repository.LevelTestRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.util.*

/** 업무 rollback/동시 진입 테스트용이다. SQL 잠금은 별도 MySQL 테스트에서 검증한다. */
internal class MemoryLevelTest : LevelTestUnitOfWork {
    val repo = Records()
    var now = LocalDateTime.parse("2026-09-25T01:00:00")
    private val mutex = Mutex()
    override suspend fun <T> write(userId: Long?, block: LevelTestTransaction.() -> T): T = mutex.withLock {
        require(userId == null || userId > 0)
        val restore = repo.backup()
        try {
            block(scope())
        } catch (failure: Throwable) {
            restore(); throw failure
        }
    }

    override suspend fun <T> read(block: LevelTestTransaction.() -> T): T = mutex.withLock { block(scope()) }
    private fun scope() = object : LevelTestTransaction {
        override val records = repo;
        override val nowUtc get() = now
    }

    class Records : LevelTestRepository {
        val sessions = linkedMapOf<Long, LevelSession>()
        val baselines = linkedMapOf<Long, LevelBaseline>()
        val items = linkedMapOf<Long, LevelItem>()
        val responses = linkedMapOf<Long, LevelSubmission>()
        val evaluations = linkedMapOf<Long, LevelEvaluation>()
        val candidates = linkedMapOf<Long, LevelCandidate>()
        val pool = linkedMapOf<Long, LevelPoolQuestion>()
        val audio = linkedMapOf<String, LevelAudio>()
        var maintenance: Pair<String, LocalDateTime>? = null
        var failBaseline = false
        fun backup(): () -> Unit {
            val a = sessions.toMap();
            val b = baselines.toMap();
            val c = items.toMap();
            val d = responses.toMap()
            val e = evaluations.toMap();
            val f = candidates.toMap();
            val g = pool.toMap();
            val h = audio.toMap();
            val lease = maintenance
            return {
                sessions.clear(); sessions.putAll(a); baselines.clear(); baselines.putAll(
                b,
            ); items.clear(); items.putAll(c)
                responses.clear(); responses.putAll(d); evaluations.clear(); evaluations.putAll(
                e,
            ); candidates.clear(); candidates.putAll(f)
                pool.clear(); pool.putAll(g); audio.clear(); audio.putAll(h); maintenance = lease
            }
        }

        private fun next(map: Map<Long, *>) = (map.keys.maxOrNull() ?: 0) + 1
        override fun claimMaintenance(token: String, now: LocalDateTime, until: LocalDateTime): Boolean {
            if (maintenance?.second?.isAfter(now) == true) return false
            maintenance = token to until; return true
        }

        override fun releaseMaintenance(token: String): Boolean {
            if (maintenance?.first != token) return false; maintenance = null; return true
        }

        override fun ownsMaintenance(token: String, now: LocalDateTime) =
            maintenance?.let { it.first == token && it.second > now } == true

        override fun session(id: Long) = sessions[id]
        override fun sessionByKey(userId: Long, key: String) =
            sessions.values.firstOrNull { it.userId == userId && it.idempotencyKey == key }

        override fun sessions(userId: Long) =
            sessions.values.filter { it.userId == userId }.sortedByDescending { it.startedAt }

        override fun saveSession(value: LevelSession) =
            value.copy(id = if (value.id == 0L) next(sessions) else value.id).also { sessions[it.id] = it }

        override fun baseline(userId: Long) = baselines[userId]
        override fun saveBaseline(value: LevelBaseline) {
            check(!failBaseline) { "기준점 저장 실패" }; baselines[value.userId] = value
        }

        override fun items(sessionId: Long) =
            items.values.filter { it.sessionId == sessionId }.sortedBy { it.questionNumber }

        override fun item(id: Long) = items[id]
        override fun itemAt(sessionId: Long, number: Int) =
            items.values.firstOrNull { it.sessionId == sessionId && it.questionNumber == number }

        override fun saveItem(value: LevelItem) =
            value.copy(id = if (value.id == 0L) next(items) else value.id).also { items[it.id] = it }

        override fun deleteUnansweredItem(id: Long) {
            check(submission(id) == null); items.remove(id)
        }

        override fun submission(itemId: Long) = responses.values.singleOrNull { it.itemId == itemId }
        override fun saveSubmission(value: LevelSubmission) =
            value.copy(id = if (value.id == 0L) next(responses) else value.id).also { responses[it.id] = it }

        override fun evaluation(responseId: Long) = evaluations[responseId]
        override fun clearEvaluation(responseId: Long) {
            evaluations.remove(responseId)
        }

        override fun saveEvaluation(value: LevelEvaluation) {
            evaluations[value.responseId] = value
        }

        override fun candidate(sessionId: Long, number: Int, band: Int) =
            candidates.values.singleOrNull { it.sessionId == sessionId && it.questionNumber == number && it.band == band }

        override fun saveCandidate(value: LevelCandidate) =
            value.copy(id = if (value.id == 0L) next(candidates) else value.id).also { candidates[it.id] = it }

        override fun queuedCandidates(limit: Int) =
            candidates.values.filter { it.status in setOf("PENDING", "GENERATING") }.take(limit)

        override fun pool(id: Long) = pool[id]
        override fun poolQuestions(origin: String, learning: String) =
            pool.values.filter { it.originLanguage == origin && it.learningLanguage == learning }

        override fun savePool(value: LevelPoolQuestion): LevelPoolQuestion {
            val old =
                pool.values.firstOrNull { it.originLanguage == value.originLanguage && it.learningLanguage == value.learningLanguage && it.data.diversityMetadata.contentHash == value.data.diversityMetadata.contentHash }
            return value.copy(id = if (value.id != 0L) value.id else old?.id ?: next(pool)).also { pool[it.id] = it }
        }

        override fun recentItems(userId: Long, learning: String, since: LocalDateTime) =
            items.values.filter { sessions[it.sessionId]?.let { s -> s.userId == userId && s.learningLanguage == learning } == true && it.createdAt >= since }

        override fun audio(key: String) = audio[key]
        override fun saveAudio(value: LevelAudio) {
            audio[value.key] = value
        }

        override fun expiredAudio(now: LocalDateTime, limit: Int) =
            audio.values.filter { it.status != "DELETED" && (it.retentionUntil?.let { d -> d <= now } == true || it.status == "RESERVED" && it.uploadUntil?.let { d -> d <= now } == true) }
                .take(limit)
    }
}

internal class MemoryAudioStore : LevelTestAudioStore {
    val data = mutableMapOf<String, ByteArray>()
    override suspend fun store(key: String, bytes: ByteArray) {
        data[key]?.let { check(it.contentEquals(bytes)) }; data[key] = bytes.copyOf()
    }

    override suspend fun load(key: String) =
        data[key]?.copyOf() ?: throw LevelTestException("LANGUAGE_LEARNING_LEVEL_TEST_NOT_FOUND", 404, "없음")

    override suspend fun delete(key: String) {
        data.remove(key)
    }

    override suspend fun exists(key: String) = key in data
}

internal class TestLevelContext : LevelTestContextProvider {
    var value =
        LevelTestContext("ko", "ja", "Asia/Tokyo", true, 1000, false, "listening-profile", "listening-model-config", 2)

    override suspend fun current(userId: Long) = value
    override suspend fun activeLanguagePairs() = listOf("ko" to "ja")
    override suspend fun poolPolicy() = value.poolReplenishmentEnabled to value.poolTarget
}

internal object LevelFixtures {
    val wav = "RIFF0000WAVEdata00000000".toByteArray()
    fun question(session: LevelSession, number: Int, band: Int = session.currentComplexityBand): LevelQuestionData {
        val slot = LevelTestRules.slot(number)
        val mode = when (number) {
            in 1..12 -> LevelTestAnswerMode.CHOICE; in 13..17 -> LevelTestAnswerMode.TEXT; else -> LevelTestAnswerMode.AUDIO
        }
        return LevelQuestionData(
            "test", session.id, number, 20, slot.domain, slot.itemType, band, "選択してください。", "ja", mode,
            if (mode == LevelTestAnswerMode.CHOICE) null else if (number == 14) "ko" else "ja", "明日は駅へ行きます。",
            if (mode == LevelTestAnswerMode.CHOICE) listOf(
                LevelOption("A", "駅へ行く"), LevelOption("B", "家にいる"), LevelOption("C", "本を読む"),
                LevelOption("D", "食事をする"),
            ) else emptyList(),
            LevelAnswerKey(
                if (number != 6) "A" else null, if (number == 6) listOf("A", "B", "C", "D") else emptyList(),
                "UNIQUE_ANSWER",
                if (mode == LevelTestAnswerMode.CHOICE && number != 6) mapOf(
                    "A" to 100, "B" to 0, "C" to 0, "D" to 0,
                ) else emptyMap(),
            ),
            JsonObject(emptyMap()),
            LevelDiversityMetadata(
                "DAILY_LIFE", requiresBackgroundKnowledge = false,
                contentHash = LevelTestRules.sha256("${session.uid}:$number:$band".toByteArray()),
                similarityKey = "$number-$band",
            ),
            if (mode == LevelTestAnswerMode.TEXT) 500 else null, if (mode == LevelTestAnswerMode.AUDIO) 30 else null,
            "test-generation",
        )
    }

    fun session(now: LocalDateTime = LocalDateTime.parse("2026-09-25T01:00:00")) = LevelSession(
        uid = UUID.randomUUID().toString(), userId = 123, sessionType = LevelTestSessionType.INITIAL,
        originLanguage = "ko", learningLanguage = "ja", timezone = "Asia/Tokyo", startedAt = now, lastActivityAt = now,
        idempotencyKey = "session-test",
    )

    fun item(session: LevelSession, number: Int, now: LocalDateTime = session.startedAt) =
        LevelItem(sessionId = session.id, questionNumber = number, data = question(session, number), createdAt = now)
}

internal class TestLevelAi : LevelTestAi {
    var calls = 0
    var evaluable = true
    var reason: String? = null
    var fail = false
    var afterCall: suspend () -> Unit = {}
    var generateCall: suspend (LevelGenerationContext, LevelAudioUpload?) -> LevelQuestionData = { c, _ ->
        LevelFixtures.question(c.session, c.number, c.band)
            .copy(
                diversityMetadata = LevelFixtures.question(c.session, c.number, c.band).diversityMetadata.copy(
                    scenarioCategory = c.scenarios.first(),
                ),
            )
    }

    override suspend fun generate(context: LevelGenerationContext, upload: LevelAudioUpload?) =
        generateCall(context, upload)

    override suspend fun evaluate(
        session: LevelSession, item: LevelItem, response: LevelSubmission, audio: ByteArray?,
    ): LevelEvaluationData {
        calls++; afterCall(); check(!fail) { "테스트 AI 실패" }
        return LevelEvaluationData(
            sessionId = session.id, itemId = item.id, domain = item.data.domain, itemType = item.data.itemType,
            evaluable = evaluable, score = if (evaluable) 80 else null, reasonCode = reason,
            evaluationVersion = "test-evaluation",
        )
    }

    override suspend fun synthesize(session: LevelSession, item: LevelItem, text: String, policy: LevelTestContext) =
        LevelAudioBytes(LevelFixtures.wav, "audio/wav")
}
