package jp.co.translacat.languagelearning.features.keyword.application

import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordChange
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordList
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordType
import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeywordLocale
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.support.MemoryKeywordUnitOfWork
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.*

class KeywordOperationsTest {
    private val today = LocalDate.of(2026, 9, 24)
    private val db = MemoryKeywordUnitOfWork()
    private var date = today
    private val service = DefaultKeywordOperations(db, KeywordLearningDate { date })
    private fun topic(text: String = "IT") = KeywordChange(text = text, type = KeywordType.TOPIC)

    @Test
    fun `빈 카탈로그와 개인 목록을 안전하게 반환한다`() {
        runBlocking {
            assertEquals(KeywordList(emptyList(), emptyList()), service.list(123, false, "ko"))
            assertTrue(service.candidates(123, false, today).isEmpty())
        }
    }

    @Test
    fun `처음 학습하기 전에는 커스텀 키워드가 즉시 활성화된다`() {
        runBlocking {
            val created = service.createCustom(123, false, topic())
            assertTrue(created.active); assertTrue(created.selected); assertNull(created.pendingEffectiveDate)
            assertEquals("it", created.canonicalKey)
            assertEquals(listOf("IT"), service.candidates(123, false, today).map { it.text })
        }
    }

    @Test
    fun `학습 시작 후 새 커스텀 키워드는 다음 날 후보가 된다`() {
        runBlocking {
            val created = service.createCustom(123, true, topic())
            assertTrue(created.active) // 화면은 예약 의도를 표시한다.
            assertEquals(today.plusDays(1), created.pendingEffectiveDate)
            assertFalse(db.customRows.getValue(created.id).active)
            assertTrue(service.candidates(123, true, today).isEmpty())
            assertEquals(1, service.candidates(123, true, today.plusDays(1)).size)
        }
    }

    @Test
    fun `미학습자의 GET은 미래 예약도 즉시 승격한다`() {
        runBlocking {
            val created = service.createCustom(123, true, topic())
            service.list(123, false, "ko")
            val stored = db.customRows.getValue(created.id)
            assertTrue(stored.active); assertNull(stored.pendingEffectiveDate)
            assertEquals(today.plusDays(1), stored.availableFrom)
        }
    }

    @Test
    fun `커스텀 수정 화면과 오늘 문제 생성의 텍스트를 분리한다`() {
        runBlocking {
            val id = service.createCustom(123, false, topic()).id
            val view = service.updateCustom(123, true, id, KeywordChange(text = "Business"))
            assertEquals("Business", view.text)
            assertEquals("IT", service.candidates(123, true, today).single().text)
            assertEquals("Business", service.candidates(123, true, today.plusDays(1)).single().text)
            assertEquals("it", view.canonicalKey)
        }
    }

    @Test
    fun `수정에서 생략된 부모는 원본대로 연결을 해제한다`() {
        runBlocking {
            val parent = service.createSystem(900, topic())
            val id = service.createCustom(
                123, false, KeywordChange(text = "deploy", type = KeywordType.VOCABULARY, parentKeywordId = parent.id),
            ).id
            val view = service.updateCustom(123, true, id, KeywordChange(text = "release"))
            assertNull(view.parentKeywordId)
            assertEquals(parent.id, db.customRows.getValue(id).parentSystemKeywordId)
            date = today.plusDays(1)
            service.list(123, true, "ko")
            assertNull(db.customRows.getValue(id).parentSystemKeywordId)
        }
    }

    @Test
    fun `다른 사용자의 커스텀 키워드를 변경하거나 비활성화할 수 없다`() {
        runBlocking {
            val id = service.createCustom(123, false, topic()).id
            assertEquals(
                "KEYWORD_NOT_FOUND",
                assertFailsWith<LearningBusinessException> {
                    service.updateCustom(456, false, id, topic("Other"))
                }.code,
            )
            assertFailsWith<LearningBusinessException> { service.deleteCustom(456, false, id) }
            assertEquals("IT", db.customRows.getValue(id).text)
        }
    }

    @Test
    fun `중복은 비활성 및 예약된 텍스트까지 검사하고 사용자별로 구분한다`() {
        runBlocking {
            val id = service.createCustom(123, false, topic()).id
            service.updateCustom(123, true, id, KeywordChange(text = "New"))
            assertEquals(
                "KEYWORD_DUPLICATED",
                assertFailsWith<LearningBusinessException> { service.createCustom(123, true, topic("Ｎｅｗ")) }.code,
            )
            service.createCustom(456, false, topic("New"))
            assertEquals(2, db.customRows.size)
        }
    }

    @Test
    fun `삭제는 물리 삭제가 아닌 활성값 기준 예약 비활성화다`() {
        runBlocking {
            val id = service.createCustom(123, false, topic()).id
            service.updateCustom(123, true, id, KeywordChange(text = "New"))
            service.deleteCustom(123, true, id)
            val row = db.customRows.getValue(id)
            assertTrue(row.active); assertFalse(row.desiredActive); assertEquals("IT", row.desiredText)
            assertEquals(1, service.candidates(123, true, today).size)
            assertTrue(service.candidates(123, true, today.plusDays(1)).isEmpty())
            assertEquals(1, db.customRows.size)
        }
    }

    @Test
    fun `시스템 선택은 사용자당 한 행이며 최초 availableFrom은 유지한다`() {
        runBlocking {
            val keyword = service.createSystem(900, topic())
            service.selectSystem(123, false, keyword.id, true)
            val result = service.selectSystem(123, true, keyword.id, false)
            assertFalse(result.selected); assertEquals(today.plusDays(1), result.pendingEffectiveDate)
            assertEquals(1, db.selectionRows.size); assertEquals(today, db.selectionRows.values.single().availableFrom)
            assertEquals(1, service.candidates(123, true, today).size)
            assertTrue(service.candidates(123, true, today.plusDays(1)).isEmpty())
        }
    }

    @Test
    fun `비활성 시스템 키워드는 일반 목록 후보 선택에서 제외한다`() {
        runBlocking {
            val keyword = service.createSystem(900, topic())
            service.selectSystem(123, false, keyword.id, true)
            service.updateSystem(900, keyword.id, KeywordChange(active = false))
            assertTrue(service.list(123, true, "ko").systemKeywords.isEmpty())
            assertTrue(service.candidates(123, true, today).isEmpty())
            assertEquals(1, service.listSystem().size)
            assertFailsWith<LearningBusinessException> { service.selectSystem(123, false, keyword.id, true) }
        }
    }

    @Test
    fun `시스템 어휘는 활성 root 토픽 부모가 필요하고 3단계는 거부한다`() {
        runBlocking {
            assertFailsWith<LearningBusinessException> {
                service.createSystem(
                    900, KeywordChange(text = "deploy", type = KeywordType.VOCABULARY),
                )
            }
            val parent = service.createSystem(900, topic())
            val word = service.createSystem(
                900, KeywordChange(text = "deploy", type = KeywordType.VOCABULARY, parentKeywordId = parent.id),
            )
            assertFailsWith<LearningBusinessException> {
                service.createCustom(
                    123, false, KeywordChange(text = "child", type = KeywordType.VOCABULARY, parentKeywordId = word.id),
                )
            }
            assertFailsWith<LearningBusinessException> {
                service.updateSystem(
                    900, parent.id, KeywordChange(parentKeywordId = parent.id),
                )
            }
        }
    }

    @Test
    fun `자식이나 커스텀 예약 참조가 있는 토픽은 비활성화할 수 없다`() {
        runBlocking {
            val parent = service.createSystem(900, topic())
            val other = service.createSystem(900, topic("Business"))
            val custom = service.createCustom(
                123, false, KeywordChange(text = "deploy", type = KeywordType.VOCABULARY, parentKeywordId = parent.id),
            )
            service.updateCustom(123, true, custom.id, KeywordChange(parentKeywordId = other.id))
            assertFailsWith<LearningBusinessException> {
                service.updateSystem(
                    900, parent.id, KeywordChange(active = false),
                )
            }
            assertFailsWith<LearningBusinessException> {
                service.updateSystem(
                    900, other.id, KeywordChange(active = false),
                )
            }
            assertTrue(db.systemRows.getValue(parent.id).active); assertTrue(db.systemRows.getValue(other.id).active)
        }
    }

    @Test
    fun `시스템 중복 및 음수 정렬 순서를 거부한다`() {
        runBlocking {
            service.createSystem(900, topic())
            assertFailsWith<LearningBusinessException> { service.createSystem(900, topic("ＩＴ")) }
            assertFailsWith<LearningBusinessException> {
                service.createSystem(
                    900, topic("Other").copy(sortOrder = -1),
                )
            }
            assertEquals(1, db.systemRows.size)
        }
    }

    @Test
    fun `커스텀 어휘에는 부모가 없어도 된다`() {
        runBlocking {
            assertNull(
                service.createCustom(
                    123, false, KeywordChange(text = "deploy", type = KeywordType.VOCABULARY),
                ).parentKeywordId,
            )
        }
    }

    @Test
    fun `잘못된 수정은 앞선 pending 승격도 같은 키워드 트랜잭션에서 롤백한다`() {
        runBlocking {
            val id = service.createCustom(123, true, topic()).id
            val before = db.customRows.getValue(id)
            date = today.plusDays(1)
            assertFailsWith<LearningBusinessException> {
                service.updateCustom(
                    123, true, id, KeywordChange(text = " "),
                )
            }
            assertEquals(before, db.customRows.getValue(id))
        }
    }

    @Test
    fun `저장 실패는 새로운 learner와 키워드를 남기지 않는다`() {
        runBlocking {
            db.failWrites = true
            assertFailsWith<IllegalStateException> { service.createCustom(123, false, topic()) }
            assertTrue(db.learnerIds.isEmpty()); assertTrue(db.customRows.isEmpty())
        }
    }

    @Test
    fun `값이 바뀌지 않은 GET은 저장을 반복하지 않는다`() {
        runBlocking {
            service.createCustom(123, false, topic())
            val writes = db.writes
            repeat(3) { service.list(123, false, "ko") }
            assertEquals(writes, db.writes)
        }
    }

    @Test
    fun `표시 언어만 바뀌며 canonical 및 후보 텍스트는 보존한다`() {
        runBlocking {
            val keyword = service.createSystem(900, topic("shopping"))
            db.localeRows += SystemKeywordLocale(keyword.id, "ko-KR", "쇼핑")
            db.localeRows += SystemKeywordLocale(keyword.id, "ja-JP", "ショッピング")
            service.selectSystem(123, false, keyword.id, true)
            val view = service.list(123, false, "learning").systemKeywords.single()
            assertEquals("ショッピング", view.displayName); assertEquals("쇼핑", view.secondaryDisplayName)
            assertEquals("shopping", view.text); assertEquals("shopping", view.canonicalKey)
            assertEquals("shopping", service.candidates(123, false, today).single().text)
        }
    }

    @Test
    fun `시스템은 정렬 순서와 ID 커스텀은 ID로 반환한다`() {
        runBlocking {
            service.createSystem(900, topic("B").copy(sortOrder = 10))
            service.createSystem(900, topic("A").copy(sortOrder = 0))
            assertEquals(listOf("A", "B"), service.listSystem().map { it.text })
        }
    }

    @Test
    fun `필수 값과 canonical 길이를 검증한다`() {
        runBlocking {
            for (input in listOf(
                KeywordChange(), topic(" "), topic("x".repeat(201)), topic().copy(canonicalKey = "x".repeat(201)),
            )) {
                assertFailsWith<LearningBusinessException> { service.createCustom(123, false, input) }
            }
            assertFailsWith<IllegalArgumentException> { service.list(0, false, "ko") }
        }
    }
}
