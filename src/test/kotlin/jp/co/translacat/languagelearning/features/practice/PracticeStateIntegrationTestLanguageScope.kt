package jp.co.translacat.languagelearning.features.practice

import jp.co.translacat.languagelearning.features.practice.application.PracticeAnswerService
import jp.co.translacat.languagelearning.features.practice.application.PracticeReadService
import jp.co.translacat.languagelearning.features.practice.domain.*
import jp.co.translacat.languagelearning.features.practice.infrastructure.ExposedPracticeUnitOfWork
import jp.co.translacat.languagelearning.features.settings.domain.model.NewUserSettings
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.repository.ExposedUserSettingsRepository
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

/** 실제 scope 키와 언어별 읽기·저장 조건을 무작위 로컬 catalog에서 검증한다. */
class PracticeStateIntegrationTestLanguageScope {
    private val clock = Clock.fixed(Instant.parse("2026-10-16T01:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.parse("2026-10-16")
    private fun request(language: String) = """{"originLanguage":"ko","learningLanguage":"$language"}"""

    private fun question(text: String) = PracticeQuestionContent(
        order = 1, questionType = PracticeQuestionType.SINGLE_CHOICE, difficulty = PracticeDifficulty.CURRENT,
        complexityBand = 3, prompt = text, options = listOf("A", "B", "C", "D").map { PracticeOption(it, "Option $it") },
        correctAnswer = listOf("A"), skillTag = "MEANING", explanationOrigin = "합성 해설",
        explanationLearning = "Synthetic explanation", canonicalKey = "shared-key", targetExpression = text,
    )

    @Test
    fun `이전 일본어 단어 답변은 영어 활성화 후에도 일본어 숙련도와 성장 신호에만 반영한다`() = LocalScratchMysql.use { db ->
        // 준비: 같은 canonical key를 영어·일본어·귀속 불명 legacy scope에 각각 저장한다.
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            val work = ExposedPracticeUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)
            val questionId = work.write(901) {
                val settings = ExposedUserSettingsRepository { }
                val created = settings.create(NewUserSettings(901, 5, 5, 5, nowUtc))
                settings.save(created.copy(originLanguage = "ko", learningLanguage = "en"))
                records.scoped("ko", "ja").saveMastery(VocabularyMastery(901, "shared-key", "日本語", 20.0, 1))
                records.scoped("ko", "en").saveMastery(VocabularyMastery(901, "shared-key", "English", 80.0, 1))
                records.scoped(null, null).saveMastery(VocabularyMastery(901, "shared-key", "Legacy", 55.0, 1))
                val set = records.save(PracticeSet(
                    0, 901, today.minusDays(1), PracticeDomain.VOCABULARY, "CONTEXTUAL_CHOICE", 3, 1,
                    request("ja"), generationStatus = PracticeGenerationStatus.READY, startedAt = nowUtc,
                ))
                records.addQuestion(set.id, question("日本語")).id
            }

            // 실행
            PracticeAnswerService(work).submit(901, questionId, listOf("A"))
            val current = PracticeReadService(work).mastery(901)

            // 검증: 옛 답안을 제출해도 현재 영어 점수·표현·신호가 바뀌지 않는다.
            assertEquals(1, current.getValue("total").jsonPrimitive.int)
            assertEquals(80.0, current.getValue("averageScore").jsonPrimitive.double)
            work.read {
                assertEquals(48.0, records.scoped("ko", "ja").masteries(901).single().score)
                assertEquals(80.0, records.masteries(901).single().score)
                assertEquals(55.0, records.scoped(null, null).masteries(901).single().score)
                assertTrue(growth.signals(901, "STRENGTH", 10).isEmpty())
                assertEquals(1, growth.scoped("ko", "ja").signals(901, "STRENGTH", 10).single().occurrenceCount)
            }
        } }
    }

    @Test
    fun `영어 읽기 난이도와 최근 실수는 일본어 및 언어 미상 과제를 섞지 않는다`() = LocalScratchMysql.use { db ->
        // 준비: 영어 기록보다 최근에 일본어 다섯 건과 손상된 옛 snapshot을 저장한다.
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            val work = ExposedPracticeUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)
            work.write(902) {
                val settings = ExposedUserSettingsRepository { }
                val created = settings.create(NewUserSettings(902, 5, 5, 5, nowUtc))
                settings.save(created.copy(originLanguage = "KO", learningLanguage = "EN"))
                val sources = listOf(request("en")) + List(5) { request("ja") } + listOf("{}", "invalid-json")
                sources.forEachIndexed { index, source ->
                    val set = records.save(PracticeSet(
                        0, 902, today.minusDays((sources.size - index).toLong()), PracticeDomain.READING,
                        "COMPREHENSION", 3, 1, source, status = PracticeStatus.COMPLETED,
                        generationStatus = PracticeGenerationStatus.READY, officialScore = if (index == 0) 40.0 else 95.0,
                        startedAt = nowUtc, completedAt = nowUtc,
                    ))
                    val question = records.addQuestion(set.id, question(if (index == 0) "English source" else "Other source"))
                    records.addAttempt(PracticeAttempt(0, question.id, 1, listOf("B"), false, nowUtc))
                }
            }

            // 실행
            val scores = work.read { records.recentScores(902, PracticeDomain.READING, "COMPREHENSION") }
            val mistakes = work.read { records.recentMistakes(902, PracticeDomain.READING) }

            // 검증: scope 필터를 SQL LIMIT보다 먼저 적용하고 손상 metadata도 안전하게 제외한다.
            assertEquals(listOf(40.0), scores)
            assertEquals(listOf("English source"), mistakes.map { it.prompt })
            assertEquals(8, work.read { records.range(902, today.minusDays(9), today).size })
        } }
    }

    @Test
    fun `같은 학습일과 모드라도 일본어 세트를 영어 세트로 재사용하지 않는다`() = LocalScratchMysql.use { db ->
        // 준비: timezone 변경으로 같은 날짜가 다시 와도 언어별 일일 세트를 저장할 수 있어야 한다.
        DatabaseFactory(db.settings()).use { factory -> runBlocking {
            val work = ExposedPracticeUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)
            val sets = work.write(903) {
                val settings = ExposedUserSettingsRepository { }
                val created = settings.create(NewUserSettings(903, 5, 5, 5, nowUtc))
                settings.save(created.copy(originLanguage = "ko", learningLanguage = "en"))
                listOf("ja", "en").map { language -> records.save(PracticeSet(
                    0, 903, today, PracticeDomain.READING, "COMPREHENSION", 3, 5, request(language),
                    generationStatus = PracticeGenerationStatus.READY, startedAt = nowUtc,
                )) }
            }

            // 실행
            val english = work.read { records.today(903, today, PracticeDomain.READING) }
            work.write(903) {
                val settings = ExposedUserSettingsRepository { }
                settings.save(checkNotNull(settings.findForUser(903)).copy(learningLanguage = "ja"))
            }
            val japanese = work.read { records.today(903, today, PracticeDomain.READING) }

            // 검증: 현재 목록은 분리되지만 명시 ID로 조회하는 원래 세트는 계속 보존한다.
            assertEquals(listOf(sets[1].id), english.map { it.id })
            assertEquals(listOf(sets[0].id), japanese.map { it.id })
            assertEquals(sets[1], work.read { records.find(903, sets[1].id) })
        } }
    }
}
