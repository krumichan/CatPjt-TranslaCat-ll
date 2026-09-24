package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType.*
import jp.co.translacat.languagelearning.features.settings.domain.model.SelectionDeliveryStatus.*
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.support.MemorySelectionSettingsUnitOfWork
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import jp.co.translacat.languagelearning.support.SettingsFixtures as F

class ListeningSelectionRelayTest {
    private fun work() = MemorySelectionSettingsUnitOfWork().apply { settings.rows[123] = F.configured() }

    @Test
    fun `설정과 수신 이력을 함께 저장한다`() = runBlocking {
        val work = work()
        assertEquals(APPLIED, RememberListeningSelection(work).execute(123, 10, F.now, listOf(SUMMARY)))
        assertEquals("[\"SUMMARY\"]", work.settings.rows.getValue(123).defaultListeningTaskTypesJson)
        assertEquals(10L, work.deliveries.getValue(123).lastEventId)
    }

    @Test
    fun `동일 전달은 수정 시각과 값을 다시 바꾸지 않는다`() = runBlocking {
        val work = work();
        val op = RememberListeningSelection(work)
        op.execute(123, 10, F.now, listOf(SUMMARY));
        val before = work.settings.rows.getValue(123)
        assertEquals(DUPLICATE, op.execute(123, 10, F.now, listOf(DICTATION)))
        assertEquals(before, work.settings.rows.getValue(123))
    }

    @Test
    fun `수신 이력 저장 실패는 설정 변경도 롤백한다`() = runBlocking {
        val work = work().apply { failDelivery = true }
        assertFailsWith<IllegalStateException> {
            RememberListeningSelection(work).execute(
                123, 10, F.now, listOf(SUMMARY)
            )
        }
        assertEquals(F.configured(), work.settings.rows.getValue(123)); assertTrue(work.deliveries.isEmpty())
    }

    @Test
    fun `직접 같은 Task를 다시 PATCH해도 이전 세션 전달은 무효다`() = runBlocking {
        val work = work()
        UpdateUserSettings(work.settings).execute(
            123, UserSettingsChange(defaultListeningTaskTypes = listOf(DICTATION))
        )
        assertEquals(SUPERSEDED, RememberListeningSelection(work).execute(123, 10, F.now, listOf(SUMMARY)))
        assertEquals("[\"DICTATION\"]", work.settings.rows.getValue(123).defaultListeningTaskTypesJson)
    }

    @Test
    fun `같은 base의 후속 세션은 가장 큰 source ID의 선택을 유지한다`() = runBlocking {
        val work = work();
        val op = RememberListeningSelection(work)
        op.execute(123, 11, F.now, listOf(SUMMARY))
        assertEquals(DUPLICATE, op.execute(123, 10, F.now, listOf(DICTATION)))
        assertEquals("[\"SUMMARY\"]", work.settings.rows.getValue(123).defaultListeningTaskTypesJson)
    }

    @Test
    fun `유효하지 않은 조합이나 미설정 학습자는 수신 기록을 만들지 않는다`() = runBlocking {
        val work = work();
        val op = RememberListeningSelection(work)
        assertFailsWith<LearningBusinessException> { op.execute(123, 10, F.now, listOf(INTERPRETATION)) }
        work.settings.rows[123] = F.user()
        assertFailsWith<LearningBusinessException> { op.execute(123, 10, F.now, listOf(SUMMARY)) }
        assertTrue(work.deliveries.isEmpty())
    }

    @Test
    fun `마이크로초보다 세밀한 revision을 거부한다`() = runBlocking {
        val work = work()
        assertFailsWith<IllegalArgumentException> {
            RememberListeningSelection(work).execute(
                123, 10, F.now.plusNanos(1), listOf(SUMMARY)
            )
        }
        assertTrue(work.deliveries.isEmpty())
    }
}
