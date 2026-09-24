package jp.co.translacat.languagelearning.features.keyword.domain.policy

import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeywordLocale
import java.time.LocalDate
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeywordPolicyTest {
    @Test fun `정규화는 NFKC 공백 정리 Locale ROOT 소문자를 사용한다`() {
        assertEquals("it server", KeywordPolicy.normalize("  ＩＴ\t  Server  "))
        assertEquals("", KeywordPolicy.normalize(null))
        val original = Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); assertEquals("it", KeywordPolicy.normalize("IT")) }
        finally { Locale.setDefault(original) }
    }
    @Test fun `학습 시작 사실만 다음 날 적용을 결정한다`() {
        val date = LocalDate.of(2026, 9, 24)
        assertEquals(date, KeywordPolicy.effectiveDate(date, false))
        assertEquals(date.plusDays(1), KeywordPolicy.effectiveDate(date, true))
    }
    private val rows = listOf(SystemKeywordLocale(1, "ko-KR", "쇼핑"), SystemKeywordLocale(1, "ja-JP", "買い物"))
    @Test fun `한국어와 일본어 헤더 및 알 수 없는 로케일을 처리한다`() {
        assertEquals("쇼핑", KeywordDisplayPolicy.resolve(rows, "ko")[1]?.primary)
        assertEquals("買い物", KeywordDisplayPolicy.resolve(rows, "ja_JP")[1]?.primary)
        assertEquals("쇼핑", KeywordDisplayPolicy.resolve(rows, "en-US")[1]?.primary)
        assertNull(KeywordDisplayPolicy.resolve(rows, "ja")[1]?.secondary)
    }
    @Test fun `learning 로케일은 일본어 누락시 한국어로 fallback한다`() {
        val pair = KeywordDisplayPolicy.resolve(rows, "learning").getValue(1)
        assertEquals("買い物", pair.primary); assertEquals("쇼핑", pair.secondary)
        val fallback = KeywordDisplayPolicy.resolve(rows.take(1), "learning").getValue(1)
        assertEquals("쇼핑", fallback.primary); assertNull(fallback.secondary)
        assertEquals(emptyMap(), KeywordDisplayPolicy.resolve(rows.take(1), "ja"))
    }
    @Test fun `같은 두 표시명은 중복 표시하지 않는다`() {
        val same = rows.map { it.copy(displayName = "IT") }
        assertNull(KeywordDisplayPolicy.resolve(same, "learning").getValue(1).secondary)
    }
}
