package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table.GrowthProfilesTable
import jp.co.translacat.languagelearning.features.writing.application.WritingGenerationState
import jp.co.translacat.languagelearning.features.writing.domain.model.NewWritingSet
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingManifestCodec
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingApproval
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.CuratedCatalogTable
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.WritingEvaluationsTable
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.WritingSetsTable
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.*

/** 소유한 격리 MySQL의 scratch catalog에서만 실행한다. */
class CuratedWritingStoreIntegrationTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-04T03:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-10-04")
    private val manifest = CuratedWritingManifestCodec.parse(
        checkNotNull(javaClass.classLoader.getResourceAsStream("writing/curated-candidates.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() },
    )

    @Test
    fun `후보 내용의 새 버전을 가져와도 이전 세트 snapshot과 catalog는 보존된다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            val runner = JdbcTransactionRunner(factory.database, 4)
            val original = CuratedWritingStore(runner, manifest, clock)
            runBlocking {
                // 준비: 첫 버전으로 저장된 세트를 만든 뒤 문항 하나의 내용과 버전을 함께 바꾼다.
                original.importManifest()
                seedProfile(runner, 303)
                val request = CuratedWritingStore.Start(303, date, WritingType.TRANSLATION,
                    "ko", "ja", false, true, 7)
                val saved = original.start(request)
                val setId = LearningPublicId.decode(saved.getValue("dailySetId").jsonPrimitive.content)
                val source = checkNotNull(javaClass.classLoader.getResourceAsStream("writing/curated-candidates.json"))
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = Json.parseToJsonElement(source).jsonObject
                val items = root.getValue("items").jsonArray
                val first = items.first().jsonObject
                val nextVersion = first.getValue("version").jsonPrimitive.int + 1
                val revised = JsonObject(first + mapOf(
                    "version" to JsonPrimitive(nextVersion),
                    "prompt" to JsonPrimitive(first.getValue("prompt").jsonPrimitive.content + " 追加"),
                ))
                val unhashed = JsonObject(revised.filterKeys { it != "contentHash" && it != "status" })
                val rehashed = JsonObject(revised + ("contentHash" to JsonPrimitive(
                    CuratedWritingManifestCodec.sha256(CuratedWritingManifestCodec.canonical(unhashed)))))
                val updated = JsonObject(root + ("items" to JsonArray(listOf(rehashed) + items.drop(1))))
                val revisedStore = CuratedWritingStore(runner, CuratedWritingManifestCodec.parse(updated.toString()), clock)

                // 실행: 새 버전을 별도 catalog 항목으로 가져온다.
                revisedStore.importManifest()

                // 검증: 이전 버전과 저장 snapshot은 유지되고 이력 조회가 재생성되지 않는다.
                assertEquals(saved, revisedStore.byId(303, setId, date, 7))
                runner.read {
                    assertEquals(151L, CuratedCatalogTable.selectAll().count())
                    assertEquals(2L, CuratedCatalogTable.selectAll().where {
                        CuratedCatalogTable.id eq first.getValue("id").jsonPrimitive.content
                    }.count())
                }
            }
        }
    }

    @Test
    fun `기존 경로와 큐레이션 경로는 양방향으로 같은 날짜 유형의 세트를 거부한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            val runner = JdbcTransactionRunner(factory.database, 4)
            val curated = CuratedWritingStore(runner, manifest, clock)
            val legacy = WritingGenerationState(ExposedWritingSetUnitOfWork(runner, clock))
            runBlocking {
                // 준비: 후보를 가져오고 두 독립 사용자의 레벨 테스트 완료 상태를 만든다.
                curated.importManifest()
                seedProfile(runner, 301)
                seedProfile(runner, 302)
                val request = CuratedWritingStore.Start(301, date, WritingType.FREE,
                    "ko", "ja", false, true, 7)

                // 실행: 큐레이션 선점 후 기존 경로가 같은 날짜·유형을 만들지 못한다.
                curated.start(request)
                val legacyFailure = assertFailsWith<IllegalArgumentException> {
                    legacy.getOrCreate(NewWritingSet(301, date, WritingType.FREE,
                        "curated-first", 1, "{}"))
                }

                // 검증: 정책 충돌을 반환하고 기존 평가형 세트는 추가되지 않았다.
                assertEquals("WRITING_POLICY_CONFLICT", legacyFailure.message)
                runner.read {
                    assertEquals(0L, WritingSetsTable.selectAll().where {
                        WritingSetsTable.userId eq 301L
                    }.count())
                }

                // 실행: 반대 순서에서도 기존 세트를 보존하고 큐레이션 생성은 거부한다.
                val existing = legacy.getOrCreate(NewWritingSet(302, date, WritingType.FREE,
                    "legacy-first", 1, "{}"))
                val curatedFailure = assertFailsWith<IllegalArgumentException> {
                    curated.start(request.copy(userId = 302))
                }

                // 검증: 충돌 후 기존 세트 재요청은 동일한 snapshot을 유지한다.
                assertEquals("WRITING_POLICY_CONFLICT", curatedFailure.message)
                assertEquals(existing, legacy.getOrCreate(NewWritingSet(302, date, WritingType.FREE,
                    "ignored-on-retry", 1, "{}")))
            }
        }
    }

    @Test
    fun `QA 후보 다섯 개의 원자 시작 제출 결과 조회 교체는 점수를 쓰지 않는다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            val runner = JdbcTransactionRunner(factory.database, 4)
            val store = CuratedWritingStore(runner, manifest, clock)
            runBlocking {
                // 준비: 후보만 가져오고 독립 사용자에게 기존 레벨 테스트 완료 상태를 둔다.
                store.importManifest()
                seedProfile(runner, 101)
                val request = CuratedWritingStore.Start(101, date, WritingType.TRANSLATION,
                    "ko", "ja", false, true, 7)

                // 실행: 첫 시작과 재전송은 같은 완성 세트를 돌려준다.
                val first = store.start(request)
                val again = store.start(request)
                val setId = LearningPublicId.decode(first.getValue("dailySetId").jsonPrimitive.content)
                val items = first.getValue("items").jsonArray

                // 검증: 정확한 슬롯, 선공개 차단, owner 격리와 조회 안정성.
                assertEquals(first, again)
                assertEquals(5, items.size)
                assertEquals(listOf(2, 3, 3, 3, 4), items.map { it.jsonObject.getValue("targetBand").jsonPrimitive.int })
                assertTrue(items.none { "reference" in it.jsonObject })
                assertNull(store.byId(202, setId, date, 7))
                assertEquals(first, store.byId(101, setId, date, 7))

                // 실행: 첫 답안은 현재 revision에만 귀속되고 해당 문항의 참고 근거만 공개된다.
                val firstItem = items.first().jsonObject
                val itemId = LearningPublicId.decode(firstItem.getValue("itemId").jsonPrimitive.content)
                val revision = firstItem.getValue("contentRevision").jsonPrimitive.content
                assertFailsWith<IllegalArgumentException> {
                    store.submit(101, itemId, "日本語の回答", "0".repeat(64), date, 7)
                }
                val submitted = store.submit(101, itemId, "日本語の回答", revision, date, 7)
                assertEquals(submitted, store.submit(101, itemId, "日本語の回答", revision, date, 7))
                val afterSubmit = submitted.getValue("items").jsonArray
                assertTrue("reference" in afterSubmit.first().jsonObject)
                assertTrue(afterSubmit.drop(1).none { "reference" in it.jsonObject })
                assertEquals("REFERENCE_ONLY", submitted.getValue("resultPolicy").jsonPrimitive.content)
                assertEquals("DISABLED_PENDING_QUALITY_GATE", submitted.getValue("personalizedFeedbackStatus").jsonPrimitive.content)

                // 실행: 답변한 슬롯과 원문은 보존하고 나머지 네 슬롯만 원자 교체한다.
                val replaced = store.replace(101, setId, date, 7, true, true)
                val nextItems = replaced.getValue("items").jsonArray
                assertEquals(firstItem.getValue("contentRevision"), nextItems.first().jsonObject.getValue("contentRevision"))
                assertEquals(1, replaced.getValue("replacementCount").jsonPrimitive.int)
                assertTrue(nextItems.drop(1).zip(items.drop(1)).all { (next, old) ->
                    next.jsonObject.getValue("contentRevision") != old.jsonObject.getValue("contentRevision")
                })
                assertFailsWith<IllegalArgumentException> {
                    val old = items[1].jsonObject
                    store.submit(101, LearningPublicId.decode(old.getValue("itemId").jsonPrimitive.content),
                        "古い回答", old.getValue("contentRevision").jsonPrimitive.content, date, 7)
                }

                // 검증: 비점수 답안은 기존 평가 행·공식 성장 평가 횟수를 만들지 않는다.
                runner.read {
                    assertEquals(0L, WritingEvaluationsTable.selectAll().count())
                    val profile = GrowthProfilesTable.selectAll().where { GrowthProfilesTable.userId eq 101L }.single()
                    assertEquals(60.0, profile[GrowthProfilesTable.baseLevelScore])
                    assertEquals(0, profile[GrowthProfilesTable.evaluationCount])
                }

                // 합성 승인 기록은 테스트 DB에서만 사용한다. 실제 manifest에는 승인 0개다.
                val syntheticApprovals = manifest.items.associate { item ->
                    (item.id to item.version) to CuratedWritingApproval(item.id, item.version,
                        item.contentHash, item.releaseId, "synthetic-test-only", clock.instant())
                }
                val approved = CuratedWritingStore(runner, manifest, clock, syntheticApprovals,
                    "a".repeat(64))
                approved.importManifest()
                seedProfile(runner, 102)
                val publicSet = approved.start(request.copy(userId = 102, qaOnly = false))
                assertEquals(5, publicSet.getValue("items").jsonArray.size)
                runner.read {
                    assertEquals(150L, CuratedCatalogTable.selectAll().where {
                        CuratedCatalogTable.reviewStatus eq "APPROVED"
                    }.count())
                }
                // 승인 파일에서 빠지면 새 선택만 닫히고 이미 저장된 결과는 읽힌다.
                store.importManifest()
                assertEquals(publicSet, store.byId(102,
                    LearningPublicId.decode(publicSet.getValue("dailySetId").jsonPrimitive.content), date, 7))
                runner.read {
                    assertEquals(0L, CuratedCatalogTable.selectAll().where {
                        CuratedCatalogTable.reviewStatus eq "APPROVED"
                    }.count())
                }
            }
        }
    }

    private suspend fun seedProfile(runner: JdbcTransactionRunner, userId: Long) {
        val work = ExposedWritingSetUnitOfWork(runner, clock)
        work.write(userId) {
            val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            growth.saveProfile(GrowthProfile(userId = userId, state = "ACTIVE", baseLevelScore = 60.0,
                createdAt = now, updatedAt = now))
        }
    }
}
