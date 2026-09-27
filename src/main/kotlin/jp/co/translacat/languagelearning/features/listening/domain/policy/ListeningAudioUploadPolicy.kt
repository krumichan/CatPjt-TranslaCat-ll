package jp.co.translacat.languagelearning.features.listening.domain.policy

import jp.co.translacat.languagelearning.shared.error.LearningBusinessException

internal object ListeningAudioUploadPolicy {
    fun validate(bytes: ByteArray, contentType: String, maxBytes: Long, durationMs: Int, maxSeconds: Int) {
        // 기존 업로드 단계는 크기·신고 길이·magic과 MIME만 확인한다. 발화 품질 검사는 평가 단계가 담당한다.
        if (bytes.size < 4 || bytes.size > maxBytes || durationMs <= 0 || durationMs > maxSeconds * 1000L) invalid()
        fun ascii(offset: Int, text: String) = bytes.size >= offset + text.length &&
            bytes.copyOfRange(offset, offset + text.length).toString(Charsets.US_ASCII) == text

        val mimes = when {
            ascii(0, "RIFF") && ascii(8, "WAVE") -> setOf("audio/wav", "audio/x-wav", "audio/vnd.wave")
            ascii(0, "OggS") -> setOf("audio/ogg")
            bytes.take(4).map { it.toInt() and 255 } == listOf(0x1a, 0x45, 0xdf, 0xa3) -> setOf("audio/webm")
            ascii(0, "ID3") || (bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 0xe0 == 0xe0) -> setOf(
                "audio/mpeg", "audio/mp3",
            )

            bytes.size >= 12 && ascii(4, "ftyp") -> setOf("audio/mp4", "audio/m4a", "audio/x-m4a")
            ascii(0, "fLaC") -> setOf("audio/flac", "audio/x-flac")
            else -> emptySet()
        }
        if (contentType.lowercase().substringBefore(';').trim() !in mimes) invalid()
    }

    private fun invalid(): Nothing =
        throw LearningBusinessException("LISTENING_AUDIO_INVALID", "Listening Audio 형식·크기·길이가 올바르지 않습니다.")
}
