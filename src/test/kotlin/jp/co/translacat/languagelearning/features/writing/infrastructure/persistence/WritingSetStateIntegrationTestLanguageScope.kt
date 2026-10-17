package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.settings.domain.model.NewUserSettings
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.repository.ExposedUserSettingsRepository
import jp.co.translacat.languagelearning.features.writing.application.WritingGenerationState
import jp.co.translacat.languagelearning.features.writing.domain.model.NewWritingSet
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WritingSetStateIntegrationTestLanguageScope {
    @Test
    fun `언어와 시간대를 바꿔 같은 날짜를 만나도 일본어 세트를 영어로 재사용하지 않는다`() {
        // 준비: 원본과 학습일·유형은 같고 대상 언어만 다른 두 생성 요청을 준비한다.
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4))
                val state = WritingGenerationState(work)
                val date = LocalDate.of(2026, 10, 16)
                fun seed(language: String) = NewWritingSet(
                    123, date, WritingType.FREE, "writing-$language", 1,
                    """{"originLanguage":"ko","learningLanguage":"$language"}""",
                )
                runBlocking {
                    // 실행: 두 언어를 각각 시작하고, 같은 언어의 재전송은 저장된 snapshot을 반환한다.
                    val japanese = state.getOrCreate(seed("ja"))
                    val english = state.getOrCreate(seed("en"))
                    val repeated = state.getOrCreate(seed("en").copy(snapshotId = "different-en-snapshot"))
                    work.write(123) {
                        val settings = ExposedUserSettingsRepository { }
                        val created = settings.create(NewUserSettings(123, 5, 5, 5, nowUtc))
                        settings.save(created.copy(originLanguage = "ko", learningLanguage = "en"))
                    }

                    // 검증: 현재 조회는 영어, 옛 ID와 명시적 일본어 조회는 원래 세트를 그대로 보존한다.
                    assertNotEquals(japanese.id, english.id)
                    assertEquals(english.id, repeated.id)
                    work.read {
                        assertEquals(english.id, sets.find(123, date, WritingType.FREE)!!.id)
                        assertEquals(japanese.id, sets.scoped("ko", "ja").find(123, date, WritingType.FREE)!!.id)
                        assertEquals(japanese.snapshotJson, sets.findById(123, japanese.id)!!.snapshotJson)
                    }
                }
            }
        }
    }
}
