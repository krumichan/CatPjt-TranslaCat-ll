package jp.co.translacat.languagelearning.features.resultjournal.application

import jp.co.translacat.languagelearning.features.resultjournal.domain.exception.ResultJournalConflict
import jp.co.translacat.languagelearning.features.resultjournal.domain.model.IncomingLearningResult
import jp.co.translacat.languagelearning.features.resultjournal.domain.model.ReceiptOutcome
import jp.co.translacat.languagelearning.features.resultjournal.domain.model.ResultKind
import jp.co.translacat.languagelearning.support.MemoryResultJournal
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import jp.co.translacat.languagelearning.support.ResultJournalFixtures as F

class AcceptLearningResultTest {
    @Test
    fun `첫 기록은 순번 1로 한 번만 수신한다`() {
        runBlocking {
            val db = MemoryResultJournal();
            val service = AcceptLearningResult(db, F.SOURCE);
            val event = F.event()
            val receipt = service.execute(event)
            assertEquals(ReceiptOutcome.RECORDED, receipt.outcome)
            assertEquals(event.eventId, receipt.eventId)
            assertEquals(event.payloadSha256, receipt.payloadSha256)
            assertEquals(1L, db.streams[F.SOURCE to 123])
        }
    }

    @Test
    fun `응답을 잃어 재전달해도 한 번만 저장한다`() {
        runBlocking {
            val db = MemoryResultJournal();
            val service = AcceptLearningResult(db, F.SOURCE);
            val event = F.event()
            service.execute(event)
            repeat(10) { assertEquals(ReceiptOutcome.DUPLICATE, service.execute(event).outcome) }
            assertEquals(1, db.events.size)
        }
    }

    @Test
    fun `같은 ID의 다른 본문은 충돌이며 원본을 유지한다`() {
        runBlocking {
            val db = MemoryResultJournal();
            val service = AcceptLearningResult(db, F.SOURCE);
            val event = F.event()
            service.execute(event)
            val changed = F.event(payload = F.PAYLOAD + " ").copy(eventId = event.eventId)
            assertFailsWith<ResultJournalConflict> { service.execute(changed) }
            assertEquals(event, db.events[event.eventId])
        }
    }

    @Test
    fun `같은 ID라도 메타데이터가 다르면 중복 성공이 아니다`() {
        runBlocking {
            val db = MemoryResultJournal();
            val service = AcceptLearningResult(db, F.SOURCE);
            val event = F.event()
            service.execute(event)
            for (changed in listOf(
                event.copy(userId = 124),
                event.copy(sequence = 2),
                event.copy(referenceId = "124"),
                event.copy(occurredAt = "2026-09-24T03:00:01Z")
            )) {
                assertFailsWith<ResultJournalConflict> { service.execute(changed) }
            }
            assertEquals(1, db.events.size)
        }
    }

    @Test
    fun `순번 누락을 건너뛰지 않고 뒤늦은 첫 기록 뒤 재시도한다`() {
        runBlocking {
            val db = MemoryResultJournal();
            val service = AcceptLearningResult(db, F.SOURCE);
            val second = F.event(2)
            assertFailsWith<ResultJournalConflict> { service.execute(second) }
            assertTrue(db.events.isEmpty())
            service.execute(F.event(1)); service.execute(second)
            assertEquals(2L, db.streams[F.SOURCE to 123])
        }
    }

    @Test
    fun `기록된 과거 순번에 새 eventId를 넣지 않는다`() {
        runBlocking {
            val db = MemoryResultJournal();
            val service = AcceptLearningResult(db, F.SOURCE)
            service.execute(F.event())
            assertFailsWith<ResultJournalConflict> { service.execute(F.event()) }
            assertEquals(1, db.events.size)
        }
    }

    @Test
    fun `저장 후 stream 갱신이 실패하면 기록도 롤백한다`() {
        runBlocking {
            val db = MemoryResultJournal().apply { failAdvance = true }
            val service = AcceptLearningResult(db, F.SOURCE);
            val event = F.event()
            assertFailsWith<IllegalStateException> { service.execute(event) }
            assertTrue(db.events.isEmpty()); assertTrue(db.streams.isEmpty())
            db.failAdvance = false
            assertEquals(ReceiptOutcome.RECORDED, service.execute(event).outcome)
        }
    }

    @Test
    fun `다른 사용자는 독립적인 순번을 사용한다`() {
        runBlocking {
            val db = MemoryResultJournal();
            val service = AcceptLearningResult(db, F.SOURCE)
            service.execute(F.event(userId = 123)); service.execute(F.event(userId = 124))
            assertEquals(2, db.streams.size)
        }
    }

    @Test
    fun `다른 원본 DB epoch는 트랜잭션 전에 거부한다`() {
        runBlocking {
            val db = MemoryResultJournal();
            val service = AcceptLearningResult(db, F.SOURCE)
            assertFailsWith<ResultJournalConflict> {
                service.execute(
                    F.event().copy(sourceInstanceId = "7a8abfea-a0d1-4458-95e8-66cb5e68d9a0")
                )
            }
            assertEquals(0, db.transactions)
        }
    }

    @Test
    fun `해시 또는 버전 오류는 트랜잭션 전에 거부한다`() {
        runBlocking {
            val db = MemoryResultJournal();
            val service = AcceptLearningResult(db, F.SOURCE)
            assertFailsWith<IllegalArgumentException> {
                service.execute(
                    F.event().copy(payloadSha256 = "0".repeat(64))
                )
            }
            assertFailsWith<IllegalArgumentException> { service.execute(F.event().copy(schemaVersion = 2)) }
            assertEquals(0, db.transactions)
        }
    }

    @Test
    fun `UTF8 바이트 수로 본문 제한을 검사한다`() {
        assertEquals(64, IncomingLearningResult.hash("가".repeat(87381)).length)
        assertFailsWith<IllegalArgumentException> { IncomingLearningResult.hash("가".repeat(87382)) }
    }

    @Test
    fun `불충분한 Speaking은 집계 대상 플래그가 false다`() {
        assertFalse(ResultKind.SPEAKING_INSUFFICIENT.aggregationEligible)
        assertTrue(ResultKind.SPEAKING_SCORED.aggregationEligible)
        assertFailsWith<IllegalArgumentException> { ResultKind.valueOf("SESSION_COACHING") }
    }

    @Test
    fun `원장 cursor가 훼손되었으면 중복 ACK로 숨기지 않는다`() {
        runBlocking {
            val db = MemoryResultJournal();
            val service = AcceptLearningResult(db, F.SOURCE);
            val event = F.event()
            service.execute(event); db.streams[F.SOURCE to 123] = 0
            assertFailsWith<ResultJournalConflict> { service.execute(event) }
        }
    }

    @Test
    fun `해시는 언어별 구현에서 같은 UTF8 값이다`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", IncomingLearningResult.hash("abc")
        )
    }

    @Test
    fun `임의 UUID 양수 ID 및 시각 정밀도를 검사한다`() {
        assertFailsWith<IllegalArgumentException> { F.event().copy(eventId = "event-1").validate() }
        assertFailsWith<IllegalArgumentException> { F.event().copy(userId = 0).validate() }
        assertFailsWith<IllegalArgumentException> { F.event().copy(sequence = 0).validate() }
        assertFailsWith<IllegalArgumentException> {
            F.event().copy(occurredAt = "2026-09-24T03:00:00.123456789Z").validate()
        }
    }
}
