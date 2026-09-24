package jp.co.translacat.languagelearning.features.listening.domain.model

/** LL DB가 소유하는 Listening 운영 정책이다. BE용 기본값을 별도로 만들지 않는다. */
internal data class ListeningPolicy(
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
