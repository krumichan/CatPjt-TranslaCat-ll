package jp.co.translacat.languagelearning.features.settings.api.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class UserSettingsSnapshotDto(
    val userId: Long,
    val learningDate: String,
    val revision: String,
    val settings: UserSettingResponseDto,
)
