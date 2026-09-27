package jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence

import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelItem
import jp.co.translacat.languagelearning.features.leveltest.support.LevelFixtures
import jp.co.translacat.languagelearning.shared.diversity.ExposedGenerationFingerprintRepository
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class LevelTestPersistenceIntegrationTestHistory {
    @Test
    fun `기존LL 장기 문항과 다른기능 이력을 실제DB에서 보존하고 신규Level이력을 공유한다`() = LocalScratchMysql.use { db ->
        // 준비: fingerprint가 없는 기존 LL 문항120개와 다른 기능을 별도 scratch DB에 넣는다.
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                val clock = Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC)
                val runner = JdbcTransactionRunner(factory.database, 4)
                val work = ExposedLevelTestUnitOfWork(runner, clock)
                val latest = work.write(123) {
                    var last = LevelFixtures.session(nowUtc)
                    repeat(6) { batch ->
                        last = records.saveSession(
                            LevelFixtures.session(nowUtc)
                                .copy(
                                    id = 0, uid = "synthetic-history-$batch", userId = 123, learningLanguage = "en",
                                    idempotencyKey = "synthetic-history-$batch",
                                ),
                        )
                        repeat(20) { number ->
                            val index = batch * 20 + number
                            val data = LevelFixtures.question(last, number + 1)
                            records.saveItem(
                                LevelItem(
                                    sessionId = last.id, questionNumber = number + 1,
                                    data = data.copy(
                                        promptText = "Synthetic history $index",
                                        diversityMetadata = data.diversityMetadata.copy(contentHash = "own-$index"),
                                    ),
                                    createdAt = nowUtc.minusMinutes((119 - index).toLong()),
                                ),
                            )
                        }
                    }
                    last
                }
                work.write(456) { Unit }
                runner.write {
                    val other = ExposedGenerationFingerprintRepository({}, "WRITING")
                    val now = java.time.LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                    for ((user, language, hash) in listOf(
                        Triple(123L, "en", "cross-match"), Triple(123L, "ja", "wrong-language"),
                        Triple(456L, "en", "wrong-user"),
                    )) {
                        other.register(
                            user, 999, language, "Synthetic $hash", buildJsonObject { put("contentHash", hash) }, now,
                        )
                    }
                }

                // 실행: 원본 LL 문항은 수정하지 않고 현재·동일 기능·교차 기능을 함께 조회한다.
                val before = work.read { records.items(latest.id) }
                val result = work.read {
                    history.context(
                        123, "en", before, records.recentItems(123, "en", nowUtc.minusDays(90)), nowUtc,
                    )
                }

                // 검증: 새 fingerprint가 없는 기존문항도 최신80으로 선택되고 다른 사용자/언어는 제외된다.
                val same = result.getValue("sameFeatureRecent").jsonArray
                assertEquals(80, same.size)
                assertEquals("own-119", same.first().jsonObject.getValue("contentHash").jsonPrimitive.content)
                assertEquals("own-40", same.last().jsonObject.getValue("contentHash").jsonPrimitive.content)
                assertEquals(
                    listOf("cross-match"),
                    result.getValue("crossFeatureRecent").jsonArray.map {
                        it.jsonObject.getValue(
                            "contentHash",
                        ).jsonPrimitive.content
                    },
                )
                assertEquals(before, work.read { records.items(latest.id) })

                // 실행·검증: 채택 후 공유 등록은 Writing에서도 다른 기능 이력으로 읽힌다.
                work.write(123) { history.register(latest, before.last(), nowUtc) }
                val writing = runner.read {
                    ExposedGenerationFingerprintRepository({}, "WRITING").context(
                        123, "en", java.time.LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                    )
                }
                assertEquals(
                    "LEVEL_TEST",
                    writing.getValue("crossFeatureRecent").jsonArray.single().jsonObject.getValue(
                        "sourceType",
                    ).jsonPrimitive.content,
                )
            }
        }
    }
}
