package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettingsChange
import jp.co.translacat.languagelearning.features.settings.domain.repository.AdminSettingsRepository
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import jp.co.translacat.languagelearning.support.SettingsFixtures as F

class AdminSettingsLifecycleTest {
    @Test
    fun `관리자 변경과 before after 감사는 동일 범위에서 처리된다`() = runBlocking {
        val work = FakeAdminWork();
        val result = UpdateAdminSettings(work).execute(987, AdminSettingsChange(dailyKeywordMaxCount = 8))
        assertEquals(8, result.dailyKeywordMaxCount); assertEquals(F.admin(), work.audit.single().second)
        assertEquals(result, work.audit.single().third); assertEquals(987L, work.audit.single().first)
    }

    @Test
    fun `검증 실패면 저장 및 감사가 모두 발생하지 않는다`() = runBlocking {
        val work = FakeAdminWork()
        assertFailsWith<LearningBusinessException> {
            UpdateAdminSettings(work).execute(
                987, AdminSettingsChange(dailyKeywordMaxCount = 21),
            )
        }
        assertEquals(F.admin(), work.current); assertTrue(work.audit.isEmpty())
    }

    @Test
    fun `감사 실패시 관리자 변경도 롤백된다`() = runBlocking {
        val work = FakeAdminWork().apply { failAudit = true }
        assertFailsWith<IllegalStateException> {
            UpdateAdminSettings(work).execute(
                987, AdminSettingsChange(dailyKeywordMaxCount = 8),
            )
        }
        assertEquals(F.admin(), work.current)
    }

    @Test
    fun `빈 변경도 감사는 기록하되 관리자 값은 그대로다`() = runBlocking {
        val work = FakeAdminWork(); UpdateAdminSettings(work).execute(987, AdminSettingsChange())
        assertEquals(F.admin(), work.current); assertEquals(1, work.audit.size)
    }

    @Test
    fun `관리자 식별자는 양수만 허용한다`() = runBlocking {
        val work = FakeAdminWork()
        assertFailsWith<IllegalArgumentException> { UpdateAdminSettings(work).execute(0, AdminSettingsChange()) }
        assertTrue(work.audit.isEmpty())
    }

    private class FakeAdminWork : AdminSettingsUnitOfWork {
        var current = F.admin();
        var failAudit = false
        val audit = mutableListOf<Triple<Long, AdminSettings, AdminSettings>>()
        override suspend fun <T> execute(block: AdminSettingsTransaction.() -> T): T {
            val before = current;
            val size = audit.size
            try {
                return block(
                    object : AdminSettingsTransaction {
                        override val nowUtc = F.now
                        override val settings = object : AdminSettingsRepository {
                            override fun loadForUpdate() = current
                            override fun save(settings: AdminSettings, adminUserId: Long, nowUtc: LocalDateTime) =
                                settings.also { current = it }

                            override fun appendAudit(
                                adminUserId: Long, before: AdminSettings, after: AdminSettings, nowUtc: LocalDateTime,
                            ) {
                                if (failAudit) error("감사 실패")
                                audit += Triple(adminUserId, before, after)
                            }
                        }
                    },
                )
            } catch (failure: Throwable) {
                current = before; while (audit.size > size) audit.removeAt(audit.lastIndex); throw failure
            }
        }
    }
}
