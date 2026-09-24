package jp.co.translacat.languagelearning.features.settings.api.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class ConfiguredLanguagePairDto(val originLanguage: String, val learningLanguage: String)

@Serializable
internal data class ConfiguredLanguagePairsDto(val pairs: List<ConfiguredLanguagePairDto>)
