package jp.co.translacat.languagelearning.features.leveltest.domain.model

import java.time.LocalDateTime

/** 경로·업로드 토큰 원문을 응답 DTO에 노출하지 않는다. */
internal data class LevelAudio(
    val key: String,
    val ownerUserId: Long?,
    val purpose: String,
    val contentType: String,
    val tokenHash: String? = null,
    val uploadUntil: LocalDateTime? = null,
    val checksum: String? = null,
    val sizeBytes: Long = 0,
    val status: String = "RESERVED",
    val retentionUntil: LocalDateTime? = null,
    val createdAt: LocalDateTime,
)

internal data class LevelAudioBytes(val bytes: ByteArray, val contentType: String)
