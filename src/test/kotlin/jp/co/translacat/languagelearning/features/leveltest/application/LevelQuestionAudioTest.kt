package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.api.LevelResponseMapper
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelEvaluationData
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelReferenceAudio
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelSubmission
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestSessionStatus
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelQuestionPolicy
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import jp.co.translacat.languagelearning.features.leveltest.infrastructure.storage.LocalLevelTestAudioStore
import jp.co.translacat.languagelearning.features.leveltest.support.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.nio.file.Files
import java.util.*
import kotlin.test.*

class LevelQuestionAudioTest {
    @Test
    fun `빈 문제풀은 AI 생성과 검증 후 현재 문항으로 저장한다`() {
        runBlocking {
            val w = MemoryLevelTest();
            val ctx = TestLevelContext();
            val ai = TestLevelAi();
            val audio = LevelAudioService(w, MemoryAudioStore(), "http://localhost")
            val s = LevelSessionService(w, ctx).start(123, null, "start")
            val service = LevelQuestionService(w, ctx, ai, audio)
            val item = service.current(123, s.id)
            assertEquals(1, item.questionNumber); assertEquals(1, w.repo.pool.size); assertEquals(1, w.repo.items.size)
            assertEquals(item, service.current(123, s.id))
            assertTrue(w.repo.candidates.values.any { it.questionNumber == 2 && it.status == "PENDING" })
        }
    }

    @Test
    fun `잘못된 AI 언어 문항은 저장하지 않고 후보 실패를 남긴다`() {
        runBlocking {
            val w = MemoryLevelTest();
            val ctx = TestLevelContext();
            val ai = TestLevelAi();
            val audio = LevelAudioService(w, MemoryAudioStore(), "http://localhost")
            ai.generateCall =
                { c, _ -> LevelFixtures.question(c.session, c.number, c.band).copy(instructionLanguage = "ko") }
            val s = LevelSessionService(w, ctx).start(123, null, "start")
            assertFailsWith<LevelTestException> { LevelQuestionService(w, ctx, ai, audio).current(123, s.id) }
            assertTrue(w.repo.items.isEmpty()); assertEquals("FAILED", w.repo.candidates.values.single().status)
        }
    }

    @Test
    fun `생성 중 lease 만료는 늦은 문항을 붙이지 않는다`() {
        runBlocking {
            val w = MemoryLevelTest();
            val ctx = TestLevelContext();
            val ai = TestLevelAi();
            val audio = LevelAudioService(w, MemoryAudioStore(), "http://localhost")
            ai.generateCall = { c, _ ->
                w.now = w.now.plusSeconds(31); LevelFixtures.question(c.session, c.number, c.band)
                .copy(
                    diversityMetadata = LevelFixtures.question(c.session, c.number, c.band).diversityMetadata.copy(
                        scenarioCategory = c.scenarios.first(),
                    ),
                )
            }
            val s = LevelSessionService(w, ctx).start(123, null, "start")
            assertFailsWith<LevelTestException> { LevelQuestionService(w, ctx, ai, audio, 30).current(123, s.id) }
            assertTrue(w.repo.items.isEmpty()); assertTrue(w.repo.pool.isEmpty())
        }
    }

    @Test
    fun `자동 보충은 관리자 정책이 꺼져 있으면 AI를 호출하지 않는다`() {
        runBlocking {
            val w = MemoryLevelTest();
            val ctx = TestLevelContext();
            val ai = TestLevelAi();
            var calls = 0
            ai.generateCall = { _, _ -> calls++; error("호출 금지") }
            LevelPoolMaintenance(w, ctx, ai, LevelAudioService(w, MemoryAudioStore(), "http://localhost")).refillOnce()
            assertEquals(0, calls); assertTrue(w.repo.pool.isEmpty())
        }
    }

    @Test
    fun `활성화한 자동 보충은 한 번에 한 후보만 저장한다`() {
        runBlocking {
            val w = MemoryLevelTest();
            val ctx = TestLevelContext(); ctx.value = ctx.value.copy(poolReplenishmentEnabled = true)
            LevelPoolMaintenance(
                w, ctx, TestLevelAi(), LevelAudioService(w, MemoryAudioStore(), "http://localhost"),
            ).refillOnce()
            assertEquals(1, w.repo.pool.size); assertNull(w.repo.maintenance)
        }
    }

    @Test
    fun `업로드는 예약된 토큰으로만 가능하고 같은 바이트 재전송은 허용한다`() {
        runBlocking {
            val w = MemoryLevelTest();
            val store = MemoryAudioStore();
            val audio = LevelAudioService(w, store, "http://localhost")
            val upload = audio.reserveReference(123);
            val token = URI(upload.uploadUrl).rawQuery.substringAfter("token=")
            assertFailsWith<LevelTestException> {
                audio.upload(
                    upload.objectKey, "wrong", LevelFixtures.wav, "audio/wav",
                )
            }
            repeat(2) { audio.upload(upload.objectKey, token, LevelFixtures.wav, "audio/wav") }
            audio.verify(
                LevelReferenceAudio(upload.objectKey, "audio/wav", 1000, LevelTestRules.sha256(LevelFixtures.wav)),
                upload,
            )
            assertEquals(1, store.data.size); assertTrue(audio.healthy(upload.objectKey))
        }
    }

    @Test
    fun `만료된 업로드 토큰과 체크섬 불일치를 거부한다`() {
        runBlocking {
            val w = MemoryLevelTest();
            val audio = LevelAudioService(w, MemoryAudioStore(), "http://localhost")
            val upload = audio.reserveReference(123);
            val token = URI(upload.uploadUrl).rawQuery.substringAfter("token=")
            audio.upload(upload.objectKey, token, LevelFixtures.wav, "audio/wav")
            assertFailsWith<LevelTestException> {
                audio.verify(
                    LevelReferenceAudio(upload.objectKey, "audio/wav", 1000, "wrong"), upload,
                )
            }
            w.now = w.now.plusMinutes(11)
            assertFailsWith<LevelTestException> {
                audio.upload(
                    upload.objectKey, token, LevelFixtures.wav, "audio/wav",
                )
            }
        }
    }

    @Test
    fun `답변 음성은 완료 전에는 노출하지 않고 7일 후 정리한다`() {
        runBlocking {
            val w = MemoryLevelTest();
            val ctx = TestLevelContext();
            val store = MemoryAudioStore();
            val audio = LevelAudioService(w, store, "http://localhost")
            val session = w.repo.saveSession(LevelFixtures.session());
            val item = w.repo.saveItem(LevelFixtures.item(session, 18))
            val saved = audio.storeAnswer(123, LevelFixtures.wav, "audio/wav")
            w.repo.saveSubmission(
                LevelSubmission(
                    itemId = item.id, idempotencyKey = "audio", fingerprint = "hash", audioKey = saved.key,
                    submittedAt = w.now,
                ),
            )
            val reads = LevelReadService(w, audio, TestLevelAi(), ctx)
            assertFailsWith<LevelTestException> { reads.answer(123, item.id) }
            w.repo.saveSession(session.copy(status = LevelTestSessionStatus.COMPLETED))
            assertTrue(reads.answer(123, item.id).bytes.contentEquals(LevelFixtures.wav))
            assertFailsWith<LevelTestException> { reads.answer(456, item.id) }
            w.now = w.now.plusDays(7); audio.sweep()
            assertEquals("DELETED", w.repo.audio(saved.key)?.status); assertFalse(store.exists(saved.key))
        }
    }

    @Test
    fun `로컬 파일 저장은 경로 탈출과 기존 바이트 덮어쓰기를 거부한다`() {
        runBlocking {
            val root = Files.createTempDirectory("ll-level-audio-test")
            try {
                val store = LocalLevelTestAudioStore(root);
                val key = "${UUID.randomUUID()}.audio"
                store.store(key, LevelFixtures.wav); store.store(key, LevelFixtures.wav)
                assertFailsWith<Exception> { store.store("../outside.audio", LevelFixtures.wav) }
                assertFailsWith<Exception> { store.store(key, LevelFixtures.wav + 1.toByte()) }
                assertTrue(store.load(key).contentEquals(LevelFixtures.wav)); store.delete(key); assertFalse(
                    store.exists(key),
                )
            } finally {
                Files.walk(root)
                    .use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }
        }
    }

    @Test
    fun `활성 따라말하기 원문은 18번만 별도 필드에 노출한다`() {
        val s = LevelFixtures.session().copy(id = 1)
        for (n in 18..19) {
            val i = LevelFixtures.item(s, n)
                .copy(
                    data = LevelFixtures.question(s, n)
                        .copy(
                            promptText = "秘密の原文です。",
                            referencePayload = buildJsonObject { put("referenceText", "秘密の原文です。") },
                        ),
                )
            val dto = LevelResponseMapper.question(s, i, null, true)
            assertEquals("", dto.promptText)
            if (n == 18) assertEquals("秘密の原文です。", dto.repeatReferenceText) else assertNull(
                dto.repeatReferenceText,
            )
        }
    }

    @Test
    fun `평가 응답의 다른 세션 점수와 비정상 score를 거부한다`() {
        val s = LevelFixtures.session().copy(id = 1);
        val i = LevelFixtures.item(s, 13).copy(id = 1)
        val e = LevelEvaluationData(
            sessionId = 1, itemId = 1, domain = i.data.domain, itemType = i.data.itemType, evaluable = true, score = 80,
            evaluationVersion = "test",
        )
        LevelQuestionPolicy.validateEvaluation(e, s, i)
        assertFailsWith<LevelTestException> { LevelQuestionPolicy.validateEvaluation(e.copy(sessionId = 2), s, i) }
        assertFailsWith<LevelTestException> { LevelQuestionPolicy.validateEvaluation(e.copy(score = 101), s, i) }
        assertFailsWith<LevelTestException> { LevelQuestionPolicy.validateEvaluation(e.copy(score = null), s, i) }
    }
}
