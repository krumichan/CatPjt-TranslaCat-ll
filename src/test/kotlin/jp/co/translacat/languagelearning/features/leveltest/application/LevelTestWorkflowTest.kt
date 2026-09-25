package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.leveltest.support.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LevelTestWorkflowTest {
    private class Rig {
        val work = MemoryLevelTest();
        val context = TestLevelContext();
        val ai = TestLevelAi();
        val store = MemoryAudioStore()
        val audio = LevelAudioService(work, store, "http://localhost:8081")
        val sessions = LevelSessionService(work, context)
        val answers = LevelAnswerService(work, context, ai, audio, 30)
        suspend fun begin() = sessions.start(123, LevelTestSessionType.INITIAL, "first")
        suspend fun at(number: Int): Pair<LevelSession, LevelItem> {
            val s = begin();
            val current = work.repo.saveSession(s.copy(currentQuestionNumber = number))
            return current to work.repo.saveItem(LevelFixtures.item(current, number))
        }

        suspend fun submit(
            s: LevelSession, item: LevelItem, key: String = "answer-${item.questionNumber}",
        ): LevelAnswerResult = when (item.data.answerMode) {
            LevelTestAnswerMode.CHOICE -> answers.submitText(
                123, s.id, item.id, key, if (item.questionNumber == 6) null else "A",
                if (item.questionNumber == 6) listOf("A", "B", "C", "D") else null, null,
            )

            LevelTestAnswerMode.TEXT -> answers.submitText(123, s.id, item.id, key, null, null, "回答です。")
            LevelTestAnswerMode.AUDIO -> answers.submitAudio(
                123, s.id, item.id, key, 1000, LevelFixtures.wav, "audio/wav",
            )
        }
    }

    @Test
    fun `빈 이력에서도 최초 세션을 만들고 20문항을 모두 완료한다`() {
        runBlocking {
            val r = Rig();
            val session = r.begin()
            repeat(20) { index ->
                val s = r.work.repo.session(session.id)!!;
                val item = r.work.repo.saveItem(LevelFixtures.item(s, index + 1)); r.submit(s, item)
            }
            val final = r.sessions.session(123, session.id)
            assertEquals(LevelTestSessionStatus.COMPLETED, final.status)
            assertEquals(90, final.baseLevelScore) // 객관식 12개 100점, 텍스트/오디오 8개 80점, 영역별 가중 평균
            assertEquals(20, r.work.repo.responses.size); assertEquals(20, r.work.repo.evaluations.size)
            assertEquals(final.baseLevelScore, r.sessions.baseline(123)?.score)
            assertEquals(session.uid, r.sessions.baseline(123)?.completionId)
            assertEquals(8, r.ai.calls)
        }
    }

    @Test
    fun `동일 키와 다른 키의 동시 시작은 활성 세션 한 개로 수렴한다`() {
        runBlocking {
            val r = Rig();
            val all = coroutineScope {
                (1..32).map {
                    async {
                        r.sessions.start(
                            123, LevelTestSessionType.INITIAL, "key-$it",
                        )
                    }
                }.awaitAll()
            }
            assertEquals(1, all.map { it.id }.distinct().size); assertEquals(1, r.work.repo.sessions.size)
        }
    }

    @Test
    fun `최초 설정 없이는 레벨 테스트를 시작하지 않는다`() {
        runBlocking {
            val r = Rig(); r.context.value = r.context.value.copy(originLanguage = null, learningLanguage = null)
            assertEquals(
                "LANGUAGE_LEARNING_SETTING_NOT_CONFIGURED", assertFailsWith<LevelTestException> { r.begin() }.code,
            )
            assertTrue(r.work.repo.sessions.isEmpty())
        }
    }

    @Test
    fun `INITIAL 없이 RECHECK를 시작하지 않는다`() {
        runBlocking {
            val r = Rig(); assertFailsWith<LevelTestException> {
            r.sessions.start(
                123, LevelTestSessionType.RECHECK, "recheck",
            )
        }
        }
    }

    @Test
    fun `세션 소유자가 다르면 조회와 답변을 거부한다`() {
        runBlocking {
            val r = Rig(); val (s, i) = r.at(1)
            assertFailsWith<LevelTestException> { r.sessions.session(456, s.id) }
            assertFailsWith<LevelTestException> { r.answers.submitText(456, s.id, i.id, "answer", "A", null, null) }
            assertTrue(r.work.repo.responses.isEmpty())
        }
    }

    @Test
    fun `같은 객관식 답변의 재전송은 한 번만 진행한다`() {
        runBlocking {
            val r = Rig(); val (s, i) = r.at(1); r.submit(s, i); r.submit(s, i)
            assertEquals(1, r.work.repo.responses.size); assertEquals(1, r.work.repo.evaluations.size)
            assertEquals(2, r.sessions.session(123, s.id).currentQuestionNumber)
            assertFailsWith<LevelTestException> { r.answers.submitText(123, s.id, i.id, "answer-1", "B", null, null) }
        }
    }

    @Test
    fun `동일 답변 동시 전송은 중복 채점되지 않는다`() {
        runBlocking {
            val r = Rig(); val (s, i) = r.at(1)
            coroutineScope { (1..20).map { async { runCatching { r.submit(s, i) } } }.awaitAll() }
            assertEquals(1, r.work.repo.responses.size); assertEquals(1, r.work.repo.evaluations.size)
            assertEquals(2, r.sessions.session(123, s.id).currentQuestionNumber)
        }
    }

    @Test
    fun `AI 실패 시 현재 문항을 유지하고 수동 재시도로 복구한다`() {
        runBlocking {
            val r = Rig(); val (s, i) = r.at(13); r.ai.fail = true
            assertFailsWith<IllegalStateException> { r.submit(s, i) }
            assertEquals(LevelTestItemStatus.EVALUATION_FAILED, r.work.repo.item(i.id)?.status)
            assertEquals(13, r.sessions.session(123, s.id).currentQuestionNumber)
            r.ai.fail = false; r.answers.retry(123, s.id, i.id)
            assertEquals(14, r.sessions.session(123, s.id).currentQuestionNumber); assertEquals(2, r.ai.calls)
        }
    }

    @Test
    fun `평가 불가 결과와 수동 재시도 상한을 유지한다`() {
        runBlocking {
            val r = Rig(); val (s, i) = r.at(13); r.ai.evaluable = false; r.ai.reason = "INSUFFICIENT_EVIDENCE"
            val result = r.submit(s, i); assertFalse(result.evaluation!!.evaluable)
            r.answers.retry(123, s.id, i.id)
            assertFailsWith<LevelTestException> { r.answers.retry(123, s.id, i.id) }
            assertEquals(13, r.sessions.session(123, s.id).currentQuestionNumber)
        }
    }

    @Test
    fun `재녹음은 revision을 올리고 재시도 횟수를 초기화한다`() {
        runBlocking {
            val r = Rig(); val (s, i) = r.at(18); r.ai.evaluable = false; r.ai.reason = "LOW_STT_CONFIDENCE"
            val first = r.submit(s, i); assertFailsWith<LevelTestException> { r.answers.retry(123, s.id, i.id) }
            r.ai.evaluable = true; r.ai.reason = null
            val second = r.submit(s, i, "new-recording")
            assertEquals(first.response.revision + 1, second.response.revision); assertEquals(
            0, second.response.manualRetryCount,
        )
            assertNotEquals(first.response.audioKey, second.response.audioKey)
            assertEquals(19, r.sessions.session(123, s.id).currentQuestionNumber)
        }
    }

    @Test
    fun `lease 만료 후 도착한 평가를 저장하지 않는다`() {
        runBlocking {
            val r = Rig(); val (s, i) = r.at(13); r.ai.afterCall = { r.work.now = r.work.now.plusSeconds(31) }
            assertFailsWith<LevelTestException> { r.submit(s, i) }
            assertTrue(r.work.repo.evaluations.isEmpty()); assertEquals(
            13, r.sessions.session(123, s.id).currentQuestionNumber,
        )
        }
    }

    @Test
    fun `최종 기준점 저장 실패는 최종 평가와 완료 상태도 롤백한다`() {
        runBlocking {
            val r = Rig();
            val session = r.begin()
            repeat(19) { n ->
                val s = r.work.repo.session(session.id)!!; r.submit(
                s, r.work.repo.saveItem(LevelFixtures.item(s, n + 1)),
            )
            }
            val s = r.work.repo.session(session.id)!!;
            val item = r.work.repo.saveItem(LevelFixtures.item(s, 20)); r.work.repo.failBaseline = true
            assertFailsWith<IllegalStateException> { r.submit(s, item) }
            assertNull(r.sessions.baseline(123)); assertEquals(19, r.work.repo.evaluations.size)
            assertEquals(LevelTestSessionStatus.IN_PROGRESS, r.sessions.session(123, s.id).status)
            r.work.repo.failBaseline = false; r.answers.retry(123, s.id, item.id)
            assertEquals(LevelTestSessionStatus.COMPLETED, r.sessions.session(123, s.id).status)
        }
    }

    @Test
    fun `당일 완료 제한과 다음날 재측정 기준 난이도를 보존한다`() {
        runBlocking {
            val r = Rig();
            val s = r.begin()
            r.work.repo.saveSession(
                s.copy(
                    status = LevelTestSessionStatus.COMPLETED, baseLevelScore = 87, proficiencyBand = "ADVANCED",
                    completedAt = r.work.now, completedDate = r.work.now.toLocalDate(),
                ),
            )
            r.work.repo.saveBaseline(
                LevelBaseline(
                    123, s.id, s.uid, s.sessionType, 87, "ADVANCED", r.work.now.toLocalDate(), s.startedAt, r.work.now,
                ),
            )
            assertFailsWith<LevelTestException> { r.sessions.start(123, LevelTestSessionType.INITIAL, "second") }
            assertEquals(
                "LEVEL_TEST_DAILY_LIMIT_REACHED",
                assertFailsWith<LevelTestException> {
                    r.sessions.start(
                        123, LevelTestSessionType.RECHECK, "again",
                    )
                }.code,
            )
            r.work.now = r.work.now.plusDays(1)
            assertEquals(5, r.sessions.start(123, LevelTestSessionType.RECHECK, "next-day").currentComplexityBand)
        }
    }

    @Test
    fun `재녹음 AI 장애는 이전 음성의 재녹음 사유를 물려받지 않는다`() {
        runBlocking {
            val r = Rig()
            val (session, item) = r.at(18)
            r.ai.evaluable = false
            r.ai.reason = "LOW_STT_CONFIDENCE"
            r.submit(session, item)
            r.ai.fail = true
            assertFailsWith<IllegalStateException> { r.submit(session, item, "new-recording") }
            val response = r.work.repo.submission(item.id)!!
            assertNull(r.work.repo.evaluation(response.id))
            r.ai.fail = false
            r.ai.evaluable = true
            r.ai.reason = null
            r.answers.retry(123, session.id, item.id)
            assertEquals(19, r.sessions.session(123, session.id).currentQuestionNumber)
        }
    }
}
