package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelSelfSelectionPolicy
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import jp.co.translacat.languagelearning.features.leveltest.support.LevelFixtures
import jp.co.translacat.languagelearning.features.leveltest.support.MemoryLevelTest
import jp.co.translacat.languagelearning.features.leveltest.support.TestLevelContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** 自己選択を実測と区別し、所有者・言語・再送・既存結果の保護を検証する。 */
class LevelSelfSelectionTest {
    @Test
    fun roundedIntegerMidpointsMatchExistingBands() {
        // 準備・実行・検証: 現在の整数区間の中央値と帯域の対応はサーバーが決定する。
        listOf(Triple("B1", 20, "FOUNDATION"), Triple("B2", 47, "BASIC"), Triple("B3", 62, "INTERMEDIATE"))
            .forEach { (band, score, name) ->
                assertEquals(score, LevelSelfSelectionPolicy.selection(band).score)
                assertEquals(name, LevelTestRules.band(score))
            }
    }

    @Test
    fun higherBandsAndMalformedSelectionsAreRejected() {
        // 準備・実行・検証: UIを迂回してもB4/B5や数値を選択できない。
        listOf(null, "", " ", "B0", "B4", "B5", "b2", "2", "B2 ", "FOUNDATION").forEach { band ->
            assertFailsWith<LevelTestException> { LevelSelfSelectionPolicy.selection(band) }
        }
    }

    @Test
    fun everyDomainIsInitializedWithoutAiQuestionsOrEvaluations() = runBlocking {
        // 準備: AI設定が無効でも自己選択は利用できる。
        val work = MemoryLevelTest()
        val service = LevelSessionService(work, Context(aiEnabled = false))

        // 実行: 各帯域を別の所有者で初期化する。
        listOf("B1" to 20, "B2" to 47, "B3" to 62).forEachIndexed { index, (band, score) ->
            val session = service.selfSelect(index + 1L, band, "selection-$index")

            // 検証: 0問の自己選択履歴であり、実測の評価記録は一件も作らない。
            assertEquals(LevelTestSessionType.SELF_SELECTED, session.sessionType)
            assertEquals(LevelTestSessionStatus.COMPLETED, session.status)
            assertEquals(0, session.totalQuestions)
            assertEquals(0, session.currentQuestionNumber)
            assertEquals(score, session.baseLevelScore)
            assertEquals(LevelTestDomain.entries.associateWith { score }, session.domainScores)
            assertEquals(score, service.status(index + 1L).baseline?.score)
            assertTrue(service.status(index + 1L).initialCompleted)
        }
        assertTrue(work.repo.items.isEmpty())
        assertTrue(work.repo.responses.isEmpty())
        assertTrue(work.repo.evaluations.isEmpty())
        assertTrue(work.repo.candidates.isEmpty())
    }

    @Test
    fun concurrentReplaysReturnExactlyOneInitialization() = runBlocking {
        // 準備・実行: 同じ所有者とキーへの同時送信を直列化する。
        val work = MemoryLevelTest()
        val service = LevelSessionService(work, Context())
        val values = (1..12).map { async { service.selfSelect(1, "B2", "same-request") } }.awaitAll()

        // 検証: 所有者の初期基準点と履歴は一件だけである。
        assertEquals(1, values.map { it.id }.distinct().size)
        assertEquals(1, work.repo.sessions.size)
        assertEquals(1, work.repo.baselines.size)
    }

    @Test
    fun differentBandAndLanguageCannotReuseARequestKey() = runBlocking {
        // 準備: 最初の言語で自己選択する。
        val work = MemoryLevelTest()
        val context = Context()
        val service = LevelSessionService(work, context)
        val original = service.selfSelect(1, "B1", "first")

        // 実行・検証: 別帯域・別言語の要求は元のキーに結び付けない。
        assertFailsWith<LevelTestException> { service.selfSelect(1, "B3", "first") }
        context.learning = "en"
        assertFailsWith<LevelTestException> { service.selfSelect(1, "B1", "first") }
        val english = service.selfSelect(1, "B3", "english")
        assertNotEquals(original.id, english.id)
        context.learning = "ja"
        assertEquals(20, service.status(1).baseline?.score)
        assertEquals(2, service.history(1).size)
    }

    @Test
    fun ownerIdsAreIndependentEvenWithTheSameRequestKey() = runBlocking {
        // 準備・実行: 別の所有者は同じ再送キーでも別の初期化になる。
        val work = MemoryLevelTest()
        val service = LevelSessionService(work, Context())
        val first = service.selfSelect(1, "B1", "same")
        val second = service.selfSelect(2, "B3", "same")

        // 検証: 他人の履歴を返さず、各所有者の基準点を保持する。
        assertNotEquals(first.id, second.id)
        assertEquals(20, service.status(1).baseline?.score)
        assertEquals(62, service.status(2).baseline?.score)
    }

    @Test
    fun initializedLevelsCannotBeOverwrittenByAnotherSelection() = runBlocking {
        // 準備・実行: 別のキーでも既存の初期点を変更できない。
        val work = MemoryLevelTest()
        val service = LevelSessionService(work, Context())
        val original = service.selfSelect(1, "B2", "first")
        assertFailsWith<LevelTestException> { service.selfSelect(1, "B3", "second") }

        // 検証: 初回の結果は不変である。
        assertEquals(listOf(original), service.history(1))
        assertEquals(47, service.status(1).baseline?.score)
    }

    @Test
    fun selectionDoesNotConsumeTodaysRealTestAllowance() = runBlocking {
        // 準備: 自己選択の直後でも実測による再測定は可能にする。
        val work = MemoryLevelTest()
        val service = LevelSessionService(work, Context())
        service.selfSelect(1, "B1", "selection")

        // 実行・検証: 同日の実測は20問で開始する。
        val measured = service.start(1, LevelTestSessionType.RECHECK, "measured")
        assertEquals(LevelTestSessionStatus.IN_PROGRESS, measured.status)
        assertEquals(20, measured.totalQuestions)
        assertEquals(1, measured.currentComplexityBand)
    }

    @Test
    fun anOpenedFirstQuestionCanBeAbandonedWithoutLosingItsRecord() = runBlocking {
        // 準備: 最初の問題まで表示したが、解答は送っていない。
        val work = MemoryLevelTest()
        val service = LevelSessionService(work, Context())
        val active = service.start(1, LevelTestSessionType.INITIAL, "test")
        val item = work.write(1) { records.saveItem(LevelFixtures.item(active, 1)) }
        assertTrue(service.status(1).selfSelectionAvailable)

        // 実行: ユーザーがB3を確定する。
        val selected = service.selfSelect(1, "B3", "skip")

        // 検証: 未回答の試験を完了扱いにせず、新しい初期点だけを履歴に残す。
        assertEquals(62, selected.baseLevelScore)
        assertEquals(0, selected.totalQuestions)
        assertEquals(LevelTestSessionStatus.ABANDONED, service.session(1, active.id).status)
        assertEquals(item, work.repo.item(item.id))
        assertNull(service.status(1).active)
        assertFalse(service.status(1).selfSelectionAvailable)
        assertEquals(listOf(selected), service.history(1))
        assertEquals(selected, service.selfSelect(1, "B3", "skip"))
    }

    /** 첫 문항에 머물러 있어도 이미 저장한 답변이 있으면 직접 선택을 막는다. */
    @Test
    fun aSubmittedFirstAnswerIsProtectedEvenWhenEvaluationFailed() = runBlocking {
        // 준비: 첫 답변은 저장됐지만 평가에 실패해 다음 문항으로 이동하지 않았다.
        val work = MemoryLevelTest()
        val service = LevelSessionService(work, TestLevelContext())
        val active = service.start(1, LevelTestSessionType.INITIAL, "test")
        val item = work.write(1) {
            val failed = records.saveItem(LevelFixtures.item(active, 1).copy(status = LevelTestItemStatus.EVALUATION_FAILED))
            records.saveSubmission(
                LevelSubmission(itemId = failed.id, idempotencyKey = "answer", fingerprint = "answer",
                    selectedOptionKey = "A", submittedAt = nowUtc),
            )
            failed
        }

        // 실행 및 검증: API를 직접 호출해도 답변과 기존 세션을 보존한다.
        assertFalse(service.status(1).selfSelectionAvailable)
        val conflict = assertFailsWith<LevelTestException> { service.selfSelect(1, "B1", "skip") }
        assertEquals("LEVEL_TEST_IN_PROGRESS", conflict.code)
        assertNotNull(work.repo.submission(item.id))
        assertFailsWith<LevelTestException> { service.start(2, LevelTestSessionType.SELF_SELECTED, "bypass") }
        assertEquals(active, service.session(1, active.id))
    }

    @Test
    fun baselineFailureRollsBackTheNewSession() = runBlocking {
        // 準備・実行: 基準点保存に失敗したら、試験中断と自己選択を両方取り消す。
        val work = MemoryLevelTest()
        val service = LevelSessionService(work, Context())
        val active = service.start(1, LevelTestSessionType.INITIAL, "test")
        work.repo.failBaseline = true
        assertFailsWith<IllegalStateException> { service.selfSelect(1, "B2", "failed") }

        // 検証: 次回の正常な再送が利用できる。
        assertEquals(listOf(active), work.repo.sessions(1))
        assertTrue(work.repo.baselines.isEmpty())
        work.repo.failBaseline = false
        assertEquals(47, service.selfSelect(1, "B2", "failed").baseLevelScore)
    }

    private class Context(private val aiEnabled: Boolean = true) : LevelTestContextProvider {
        var learning = "ja"

        /** 所有者から独立したテスト用の言語・AI設定を返す。 */
        override suspend fun current(userId: Long) = LevelTestContext(
            originLanguage = "ko", learningLanguage = learning, timezone = "Asia/Seoul",
            aiEvaluationEnabled = aiEnabled,
            poolTarget = 1000, poolReplenishmentEnabled = false,
            profilePolicyVersion = "listening-profile", modelConfigVersion = "listening-model-config",
            automaticRetryLimit = 2,
        )

        override suspend fun activeLanguagePairs() = listOf("ko" to learning)
        override suspend fun poolPolicy() = false to 1000
    }
}
