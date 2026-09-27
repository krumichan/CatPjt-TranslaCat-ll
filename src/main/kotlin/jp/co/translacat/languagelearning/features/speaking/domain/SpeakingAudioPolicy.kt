package jp.co.translacat.languagelearning.features.speaking.domain

import java.util.*

/** 원본 Core의 업로드 크기·클라이언트 길이·컨테이너 signature 검사다. STT 음향 판정과 구분한다. */
internal object SpeakingAudioPolicy {
    fun validate(bytes: ByteArray, contentType: String?, duration: Double?, policy: SpeakingSessionPolicySnapshot) {
        if (bytes.isEmpty() || bytes.size > policy.maxAudioFileBytes || duration == null ||
            duration < policy.minValidAudioSeconds || duration > policy.maxTurnAudioSeconds
        ) invalid()
        if (contentType == null || !contentType.lowercase(Locale.ROOT).startsWith("audio/")) invalid()

        // 원본과 같은 순서로 AAC와 MPEG frame signature를 구분한다.
        val second = bytes.getOrNull(1)?.toInt()?.and(0xff) ?: -1
        val first = bytes.firstOrNull()?.toInt()?.and(0xff) ?: -1
        val accepted = when {
            bytes.size < 4 -> emptySet()
            ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WAVE") -> setOf("audio/wav", "audio/x-wav", "audio/vnd.wave")
            ascii(bytes, 0, "OggS") -> setOf("audio/ogg")
            bytes.take(4) == listOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte()) -> setOf("audio/webm")
            first == 0xff && second and 0xf6 == 0xf0 -> setOf("audio/aac", "audio/x-aac")
            ascii(bytes, 0, "ID3") || first == 0xff && second and 0xe0 == 0xe0 && (second shr 1) and 0x03 != 0 -> setOf(
                "audio/mpeg", "audio/mp3",
            )

            bytes.size >= 12 && ascii(bytes, 4, "ftyp") -> setOf("audio/mp4", "audio/m4a", "audio/x-m4a")
            ascii(bytes, 0, "fLaC") -> setOf("audio/flac", "audio/x-flac")
            else -> emptySet()
        }
        if (contentType.lowercase(Locale.ROOT).substringBefore(';').trim() !in accepted) invalid()
    }

    private fun ascii(bytes: ByteArray, offset: Int, text: String): Boolean =
        bytes.size >= offset + text.length && text.indices.all { bytes[offset + it] == text[it].code.toByte() }

    private fun invalid(): Nothing = throw SpeakingFailure("INVALID_AUDIO")
}
