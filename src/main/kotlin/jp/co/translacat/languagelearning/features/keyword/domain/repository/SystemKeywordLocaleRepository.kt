package jp.co.translacat.languagelearning.features.keyword.domain.repository

import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeywordLocale

internal interface SystemKeywordLocaleRepository {
    fun findAll(): List<SystemKeywordLocale>
}
