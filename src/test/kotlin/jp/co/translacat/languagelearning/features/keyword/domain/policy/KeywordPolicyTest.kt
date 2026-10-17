package jp.co.translacat.languagelearning.features.keyword.domain.policy

import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeywordLocale
import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeyword
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordType
import java.time.LocalDate
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeywordPolicyTest {
    @Test
    fun `정규화는 NFKC 공백 정리 Locale ROOT 소문자를 사용한다`() {
        assertEquals("it server", KeywordPolicy.normalize("  ＩＴ\t  Server  "))
        assertEquals("", KeywordPolicy.normalize(null))
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR")); assertEquals("it", KeywordPolicy.normalize("IT"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `학습 시작 사실만 다음 날 적용을 결정한다`() {
        val date = LocalDate.of(2026, 9, 24)
        assertEquals(date, KeywordPolicy.effectiveDate(date, false))
        assertEquals(date.plusDays(1), KeywordPolicy.effectiveDate(date, true))
    }

    private val rows = listOf(SystemKeywordLocale(1, "ko-KR", "쇼핑"), SystemKeywordLocale(1, "ja-JP", "買い物"))

    @Test
    fun `한국어와 일본어 헤더 및 알 수 없는 로케일을 처리한다`() {
        assertEquals("쇼핑", KeywordDisplayPolicy.resolve(rows, "ko")[1]?.primary)
        assertEquals("買い物", KeywordDisplayPolicy.resolve(rows, "ja_JP")[1]?.primary)
        assertEquals("쇼핑", KeywordDisplayPolicy.resolve(rows, "en-US")[1]?.primary)
        assertNull(KeywordDisplayPolicy.resolve(rows, "ja")[1]?.secondary)
    }

    @Test
    fun `learning 로케일은 일본어 누락시 한국어로 fallback한다`() {
        val pair = KeywordDisplayPolicy.resolve(rows, "learning").getValue(1)
        assertEquals("買い物", pair.primary); assertEquals("쇼핑", pair.secondary)
        val fallback = KeywordDisplayPolicy.resolve(rows.take(1), "learning").getValue(1)
        assertEquals("쇼핑", fallback.primary); assertNull(fallback.secondary)
        assertEquals(emptyMap(), KeywordDisplayPolicy.resolve(rows.take(1), "ja"))
    }

    @Test
    fun `같은 두 표시명은 중복 표시하지 않는다`() {
        val same = rows.map { it.copy(displayName = "IT") }
        assertNull(KeywordDisplayPolicy.resolve(same, "learning").getValue(1).secondary)
    }

    @Test
    fun `영어 학습 표시는 원문 언어별 보조 이름과 실제 영어 이름을 사용한다`() {
        // 준비
        val localized = rows + SystemKeywordLocale(1, "en-US", "Shopping")

        // 실행
        val korean = KeywordDisplayPolicy.resolve(localized, "learning", "ko", "en-US").getValue(1)
        val japanese = KeywordDisplayPolicy.resolve(localized, "learning", "ja-JP", "en").getValue(1)

        // 검증: UI의 한국어·일본어 선택은 학습 언어가 영어여도 그대로 유지한다.
        assertEquals("Shopping", korean.primary)
        assertEquals("쇼핑", korean.secondary)
        assertEquals("Shopping", japanese.primary)
        assertEquals("買い物", japanese.secondary)
        assertEquals("쇼핑", KeywordDisplayPolicy.resolve(localized, "ko", "ko", "en").getValue(1).primary)
        assertEquals("買い物", KeywordDisplayPolicy.resolve(localized, "ja", "ko", "en").getValue(1).primary)
        assertEquals("Shopping", KeywordDisplayPolicy.resolve(localized, "EN_us").getValue(1).primary)
    }

    @Test
    fun `기존 카탈로그 영어 표시는 고정 자료로 보충하고 관리자가 저장한 영어 이름은 보존한다`() {
        // 준비: DB 이관 직후에는 en-US 행이 없어도 공용 canonical 이름으로 표시할 수 있다.
        val catalog = listOf(SystemKeyword(id = 1, text = "shopping", normalizedText = "shopping",
            type = KeywordType.TOPIC, canonicalKey = "SHOPPING", parentKeywordId = null, sortOrder = 0))

        // 실행
        val supplemented = KeywordDisplayPolicy.resolve(rows, "learning", "ko", "en", catalog).getValue(1)
        val managed = KeywordDisplayPolicy.resolve(rows + SystemKeywordLocale(1, "en-US", "Shopping trips"),
            "learning", "ja", "en", catalog).getValue(1)
        val englishOrigin = KeywordDisplayPolicy.resolve(rows, "learning", "en", "ja", catalog).getValue(1)

        // 검증
        assertEquals("Shopping", supplemented.primary)
        assertEquals("쇼핑", supplemented.secondary)
        assertEquals("Shopping trips", managed.primary)
        assertEquals("買い物", managed.secondary)
        assertEquals("買い物", englishOrigin.primary)
        assertEquals("Shopping", englishOrigin.secondary)
    }

    @Test
    fun `영어 자료가 없는 개인 공용 항목은 원문 표시명으로 fallback한다`() {
        // 준비
        val customCatalog = listOf(SystemKeyword(id = 1, text = "custom", normalizedText = "custom",
            type = KeywordType.TOPIC, canonicalKey = "CUSTOM_TOPIC", parentKeywordId = null, sortOrder = 0))

        // 실행
        val fallback = KeywordDisplayPolicy.resolve(rows, "learning", "ja", "en", customCatalog).getValue(1)

        // 검증
        assertEquals("買い物", fallback.primary)
        assertNull(fallback.secondary)
    }
}
