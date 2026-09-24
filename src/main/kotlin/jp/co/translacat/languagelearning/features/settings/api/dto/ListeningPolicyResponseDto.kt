package jp.co.translacat.languagelearning.features.settings.api.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class ListeningPolicyResponseDto(
    val enabled: Boolean,
    val defaultItemCount: Int,
    val minItemCount: Int,
    val maxItemCount: Int,
    val hardItemLimit: Int,
    val referenceAudioMaxSeconds: Int,
    val repeatAudioMaxSeconds: Int,
    val maxAudioFileBytes: Long,
    val maxRerecordCount: Int,
    val resumeHours: Int,
    val referenceAudioRetentionDays: Int,
    val userAudioRetentionDays: Int,
    val reportedAudioRetentionDays: Int,
    val automaticRetryLimit: Int,
    val manualRetryLimit: Int,
    val practiceAttemptLimit: Int,
    val profilePolicyVersion: String,
    val modelConfigVersion: String,
    val referenceTtsRegenerationEnabled: Boolean,
)
