package jp.co.translacat.languagelearning.features.keyword.domain.policy

import jp.co.translacat.languagelearning.features.growth.domain.model.KeywordMastery
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordCandidate
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordSource
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordType
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeywordSelectionParityTest {
    @Test
    fun `기존 Java 정책 replay와 날짜 난수 및 정규화 결과가 같다`() {
        // 준비: BE의 실제 KeywordSelectionPolicy·WeightPolicy를 실행한 합성 기대값이다.
        val date = LocalDate.of(2026, 9, 26)
        val candidates = (0..5).map { index ->
            KeywordCandidate(
                "synthetic-$index", "synthetic-$index",
                KeywordSource.CUSTOM, if (index < 3) KeywordType.TOPIC else KeywordType.VOCABULARY,
                "synthetic-$index", date.minusDays(index * 2L),
            )
        }
        val expected = mapOf(
            1 to listOf("synthetic-4=1.0"),
            2 to listOf("synthetic-2=1.0", "synthetic-5=0.9798"),
            4 to listOf("synthetic-2=0.75", "synthetic-5=0.7348", "synthetic-4=1.0", "synthetic-1=0.2354"),
            6 to listOf(
                "synthetic-2=0.75", "synthetic-5=0.7348", "synthetic-4=1.0", "synthetic-1=0.2354",
                "synthetic-3=0.5966", "synthetic-0=0.25",
            ),
        )

        // 실행: 같은 후보와 숙련도를 Kotlin 정책에 전달한다.
        val actual = expected.keys.associateWith { limit ->
            KeywordSelectionPolicy.select(123, date, candidates, limit) { key ->
                val index = key.substringAfterLast('-').toInt()
                if (index % 2 == 0) null else KeywordMastery(
                    123, key, 20.0 + index * 10,
                    3, date.minusDays(index.toLong()), 4, date.atStartOfDay(), date.atStartOfDay(),
                )
            }.map { "${it.canonicalKey}=${it.weight}" }
        }

        // 검증: 유형별 우선 선택·선택 순서·소수점 반올림을 보존한다.
        assertEquals(expected, actual)
        assertTrue(KeywordSelectionPolicy.select(123, date, emptyList(), 5) { null }.isEmpty())
        assertTrue(KeywordSelectionPolicy.select(123, date, candidates, 0) { null }.isEmpty())
    }
}
