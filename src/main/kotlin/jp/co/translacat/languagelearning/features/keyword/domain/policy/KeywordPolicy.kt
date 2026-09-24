package jp.co.translacat.languagelearning.features.keyword.domain.policy

import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordType
import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeyword
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import java.text.Normalizer
import java.time.LocalDate
import java.util.*

internal object KeywordPolicy {
    // Java String.trim/isBlank 및 기본 정규식의 공백 의미를 유지한다.
    fun trim(value: String): String = value.trim { it <= ' ' }
    fun blank(value: String): Boolean = value.codePoints().allMatch { Character.isWhitespace(it) }
    fun normalize(value: String?): String =
        if (value == null) "" else trim(Normalizer.normalize(value, Normalizer.Form.NFKC)).replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)

    fun validate(text: String?, type: KeywordType?) {
        if (text == null || blank(text) || trim(text).length > 200 || type == null) {
            throw LearningBusinessException("SETTING_INVALID", "Keyword 값이 유효하지 않습니다.")
        }
    }

    fun canonical(value: String?, fallback: String): String {
        val normalized = normalize(value)
        val result = if (blank(normalized)) fallback else normalized
        // 기존 DB 길이 제약을 SQL 예외 대신 업무 오류로 명시한다.
        if (result.length > 200) throw LearningBusinessException("SETTING_INVALID", "canonicalKey는 200자 이하여야 합니다.")
        return result
    }

    fun effectiveDate(today: LocalDate, hasStartedLearning: Boolean): LocalDate =
        if (hasStartedLearning) today.plusDays(1) else today

    fun hierarchy(type: KeywordType, parent: SystemKeyword?, system: Boolean, currentId: Long? = null) {
        if (type == KeywordType.TOPIC) {
            if (parent != null) throw invalidHierarchy()
            return
        }
        if (parent == null) {
            if (system) throw invalidHierarchy()
            return
        }
        if (!parent.active || parent.type != KeywordType.TOPIC || parent.parentKeywordId != null || (currentId != null && currentId == parent.id)) throw invalidHierarchy()
    }

    fun sortOrder(requested: Int?, fallback: Int): Int = (requested ?: fallback).also {
        if (it < 0) throw invalidHierarchy()
    }

    fun invalidHierarchy() = LearningBusinessException("KEYWORD_HIERARCHY_INVALID", "Keyword 계층 구조가 유효하지 않습니다.")
    fun duplicated() = LearningBusinessException("KEYWORD_DUPLICATED", "동일한 Keyword가 이미 존재합니다.")
    fun notFound() = LearningBusinessException("KEYWORD_NOT_FOUND", "Keyword를 찾을 수 없습니다.")
}
