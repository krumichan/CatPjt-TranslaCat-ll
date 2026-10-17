package jp.co.translacat.languagelearning.features.listening.infrastructure.persistence

import jp.co.translacat.languagelearning.features.listening.application.ListeningSessionService
import jp.co.translacat.languagelearning.features.listening.application.ListeningTransaction
import jp.co.translacat.languagelearning.features.listening.domain.model.*
import jp.co.translacat.languagelearning.features.settings.application.DefaultSettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.application.GetAdminSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.NewUserSettings
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedAdminSettingsUnitOfWork
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedSettingsReadQueries
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedSettingsUnitOfWork
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.repository.ExposedUserSettingsRepository
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class ListeningStateIntegrationTestLanguageScope {
    private val clock = Clock.fixed(Instant.parse("2026-10-16T01:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-10-16")
    private val dictationTasks = listOf(ListeningTaskType.DICTATION, ListeningTaskType.INTERPRETATION)

    private fun ListeningTransaction.readySet(language: String, learningDate: LocalDate = date): ListeningSetState {
        val setId = allocateId()
        val itemId = allocateId()
        val audioId = allocateId()
        val bytes = byteArrayOf(1, 2, 3)
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        insertAudio(ListeningAudioState(audioId, 904, itemId, 0, "audio/wav", checksum, bytes,
            nowUtc.plusDays(1), null))
        val value = ListeningSetState(
            setId, 904, learningDate.toString(), "ko", language, "DICTATION", "MY_LEVEL", 1,
            buildJsonObject { put("originLanguage", "ko"); put("learningLanguage", language) }, status = "READY",
            items = listOf(ListeningItemState(itemId, 1, buildJsonObject {
                put("sourceText", if (language == "en") "I will visit the park." else "公園に行きます。")
            }, status = "READY", audioId = audioId, audioDurationMs = 1000)),
        )
        insertSet(value)
        return value
    }

    @Test
    fun `일본어 진행 세션은 영어 자동 재개와 새 공식 세션을 막지 않고 명시 조회로 보존된다`() = LocalScratchMysql.use { db ->
        // 준비: 실제 저장소에 재생 가능한 두 언어의 세트를 만들고 일본어 세션을 먼저 시작한다.
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            val runner = JdbcTransactionRunner(factory.database, 4)
            val work = ExposedListeningUnitOfWork(runner, clock)
            val settings = DefaultSettingsServiceOperations(
                ExposedSettingsUnitOfWork(runner, clock), ExposedSettingsReadQueries(runner),
                GetAdminSettings(ExposedAdminSettingsUnitOfWork(runner)), clock,
            )
            val sets = work.write(904) {
                val repository = ExposedUserSettingsRepository { }
                val created = repository.create(NewUserSettings(904, 5, 5, 1, nowUtc))
                repository.save(created.copy(originLanguage = "ko", learningLanguage = "ja"))
                listOf(readySet("ja"), readySet("en"), readySet("en", date.minusDays(1)))
            }
            val service = ListeningSessionService(work, settings)
            val japanese = service.create(904, sets[0].id, dictationTasks, "ja-session")
            work.write(904) {
                val repository = ExposedUserSettingsRepository { }
                repository.save(checkNotNull(repository.findForUser(904)).copy(learningLanguage = "en"))
            }

            // 실행: 영어로 전환하면 이전 일본어 active는 자동 선택되지 않는다.
            assertNull(service.active(904))
            val english = service.create(904, sets[1].id, dictationTasks, "en-session")
            val englishActive = service.active(904)
            val sameLanguageConflict = assertFailsWith<LearningBusinessException> {
                service.create(904, sets[2].id, dictationTasks, "en-second-session")
            }

            // 검증: 같은 영어쌍의 중복만 차단하고 이전 언어의 명시 조회·재개는 계속 가능하다.
            assertEquals(english.id, englishActive?.id)
            assertEquals("LISTENING_ACTIVE_SESSION_EXISTS", sameLanguageConflict.code)
            assertEquals(japanese.setId, service.get(904, japanese.id).setId)
            assertEquals(japanese.id, service.resume(904, japanese.id).id)
            assertEquals(english.id, service.active(904)?.id)
            work.write(904) {
                val repository = ExposedUserSettingsRepository { }
                repository.save(checkNotNull(repository.findForUser(904)).copy(learningLanguage = "ja"))
            }
            assertEquals(japanese.id, service.active(904)?.id)
            assertEquals(2, work.read { sessions(904).size })
        } }
    }
}
