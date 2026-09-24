package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.support.MemorySettingsUnitOfWork
import kotlinx.coroutines.runBlocking
import java.time.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import jp.co.translacat.languagelearning.support.SettingsFixtures as F

class SettingsServiceOperationsTest {

    private class Queries : SettingsReadQueries {
        var timezone: String? = null
        var fail = false

        override suspend fun findTimezone(userId: Long): String? {
            if (fail) {
                error("테스트 조회 실패")
            }

            return timezone
        }

        override suspend fun listeningPolicy() = error("범위 밖")

        override suspend fun configuredLanguagePairs() = listOf(
            ConfiguredLanguagePair(
                originLanguage = "ko",
                learningLanguage = "ja",
            ),
        )
    }

    private fun operations(
        work: MemorySettingsUnitOfWork,
        queries: Queries,
        instant: Instant,
    ) = DefaultSettingsServiceOperations(
        unitOfWork = work,
        queries = queries,
        getAdmin = GetAdminSettings(
            object : AdminSettingsUnitOfWork {
                override suspend fun <T> execute(
                    block: AdminSettingsTransaction.() -> T,
                ): T = error("범위 밖")
            },
        ),
        clock = Clock.fixed(
            instant,
            ZoneOffset.UTC,
        ),
    )

    @Test
    fun `설정 없는 날짜 조회는 learner와 개인 설정을 만들지 않는다`() {
        runBlocking {
            val work = MemorySettingsUnitOfWork()
            val queries = Queries()

            val op = operations(
                work = work,
                queries = queries,
                instant = Instant.parse("2026-09-24T00:30:00Z"),
            )

            assertEquals(
                LocalDate.of(2026, 9, 24),
                op.learningDate(123),
            )

            assertTrue(work.learners.isEmpty())
            assertTrue(work.rows.isEmpty())
        }
    }

    @Test
    fun `날짜 조회는 현재 저장된 timezone만 사용한다`() {
        runBlocking {
            val work = MemorySettingsUnitOfWork()
            val queries = Queries().apply {
                timezone = "America/New_York"
            }

            val op = operations(
                work = work,
                queries = queries,
                instant = Instant.parse("2026-09-24T00:30:00Z"),
            )

            assertEquals(
                LocalDate.of(2026, 9, 23),
                op.learningDate(123),
            )

            assertTrue(work.rows.isEmpty())
        }
    }

    @Test
    fun `조회 장애를 기본 timezone으로 숨기지 않는다`() {
        runBlocking {
            val queries = Queries().apply {
                fail = true
            }

            val op = operations(
                work = MemorySettingsUnitOfWork(),
                queries = queries,
                instant = Instant.EPOCH,
            )

            assertFailsWith<IllegalStateException> {
                op.learningDate(123)
            }
        }
    }

    @Test
    fun `snapshot 날짜는 pending timezone 승격 뒤의 활성값으로 계산한다`() {
        runBlocking {
            val work = MemorySettingsUnitOfWork().apply {
                now = LocalDateTime.parse("2026-09-24T00:30:00")

                rows[123] = F.configured().copy(
                    pendingTimezone = "America/New_York",
                    pendingEffectiveDate = LocalDate.of(2026, 9, 24),
                )
            }

            val result = operations(
                work = work,
                queries = Queries(),
                instant = Instant.parse("2026-09-24T00:30:00Z"),
            ).userSnapshot(123)

            assertEquals(
                "America/New_York",
                result.result.settings.timezone,
            )

            assertEquals(
                LocalDate.of(2026, 9, 23),
                result.learningDate,
            )
        }
    }

    @Test
    fun `언어쌍 조회는 설정 최초 생성이나 pending 승격을 호출하지 않는다`() {
        runBlocking {
            val work = MemorySettingsUnitOfWork()

            assertEquals(
                listOf(
                    ConfiguredLanguagePair(
                        originLanguage = "ko",
                        learningLanguage = "ja",
                    ),
                ),
                operations(
                    work = work,
                    queries = Queries(),
                    instant = Instant.EPOCH,
                ).configuredLanguagePairs(),
            )

            assertTrue(work.rows.isEmpty())
        }
    }

    @Test
    fun `사용자 식별자는 양수만 허용한다`() {
        runBlocking {
            val op = operations(
                work = MemorySettingsUnitOfWork(),
                queries = Queries(),
                instant = Instant.EPOCH,
            )

            assertFailsWith<IllegalArgumentException> {
                op.userSnapshot(0)
            }

            assertFailsWith<IllegalArgumentException> {
                op.learningDate(-1)
            }
        }
    }
}
