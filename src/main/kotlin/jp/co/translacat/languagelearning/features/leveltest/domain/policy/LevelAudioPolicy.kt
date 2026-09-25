package jp.co.translacat.languagelearning.features.leveltest.domain.policy

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import java.util.*

internal object LevelAudioPolicy {
    fun validate(bytes: ByteArray, contentType: String): String {
        fun ascii(offset: Int, value: String): Boolean =
            bytes.size >= offset + value.length && value.indices.all { bytes[offset + it].toInt() == value[it].code }

        val first = bytes.getOrNull(0)?.toInt()?.and(255) ?: -1
        val second = bytes.getOrNull(1)?.toInt()?.and(255) ?: -1
        val supported = when {
            bytes.size < 4 -> emptySet()
            ascii(0, "RIFF") && ascii(8, "WAVE") -> setOf("audio/wav", "audio/x-wav", "audio/vnd.wave")
            ascii(0, "OggS") -> setOf("audio/ogg")
            first == 0x1a && second == 0x45 && bytes[2].toInt().and(255) == 0xdf && bytes[3].toInt()
                .and(255) == 0xa3 -> setOf("audio/webm")

            first == 0xff && second.and(0xf6) == 0xf0 -> setOf("audio/aac", "audio/x-aac")
            ascii(0, "ID3") || (first == 0xff && second.and(0xe0) == 0xe0 && (second shr 1).and(3) != 0) -> setOf(
                "audio/mpeg", "audio/mp3",
            )

            bytes.size >= 12 && ascii(4, "ftyp") -> setOf("audio/mp4", "audio/m4a", "audio/x-m4a")
            ascii(0, "fLaC") -> setOf("audio/flac", "audio/x-flac")
            else -> emptySet()
        }
        val normalized = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (bytes.size !in 1..LevelTestRules.MAX_AUDIO_BYTES || normalized !in supported) {
            throw LevelTestException("LEVEL_TEST_AUDIO_INVALID", 400, "음성 크기·파일 서명·Content-Type을 확인해 주세요.")
        }
        return normalized
    }
}
