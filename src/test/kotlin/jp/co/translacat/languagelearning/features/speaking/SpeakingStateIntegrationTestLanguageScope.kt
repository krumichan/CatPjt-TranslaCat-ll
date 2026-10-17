package jp.co.translacat.languagelearning.features.speaking

import jp.co.translacat.languagelearning.features.speaking.infrastructure.ExposedSpeakingUnitOfWork
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.*

class SpeakingStateIntegrationTestLanguageScope {
    @Test
    fun `더 최신 일본어 active가 기존 영어 active를 가리지 않고 전체 언어 태그를 대조한다`() = LocalScratchMysql.use { db ->
        // 준비: 같은 사용자의 영어 세션 뒤에 일본어 세션을 저장한다.
        val fixture = SpeakingCoachingReadFixtures()
        val clock = Clock.fixed(fixture.now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            val work = ExposedSpeakingUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)
            val english = work.write(41) { records.saveSession(fixture.session.copy(id = 0,
                createIdempotencyKey = "english-active", snapshot = fixture.session.snapshot.copy(learningLanguage = "en-US"))) }
            val japanese = work.write(41) { records.saveSession(fixture.session.copy(id = 0,
                createIdempotencyKey = "japanese-active", snapshot = fixture.session.snapshot.copy(learningLanguage = "ja"))) }

            // 실행과 검증: SQL 후보를 선택할 때 언어 조건을 먼저 적용한다.
            work.read {
                assertEquals(japanese.id, records.active(41)?.id)
                assertEquals(english.id, records.active(41, " KO ", "EN_us")?.id)
                assertNull(records.active(41, "ko", "en"))
                assertEquals(2, records.sessions(41, fixture.now.toLocalDate(), fixture.now.toLocalDate()).size)
                assertEquals(japanese.id, records.session(41, japanese.id)?.id)
            }
        } }
    }
}
