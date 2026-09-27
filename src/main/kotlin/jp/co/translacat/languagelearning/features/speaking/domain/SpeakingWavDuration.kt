package jp.co.translacat.languagelearning.features.speaking.domain

/** 기존 Python WAV 검사의 PCM 범위·완전한 frame·stream 길이 sentinel 정책을 보존한다. */
internal object SpeakingWavDuration {
    fun seconds(bytes: ByteArray): Double {
        require(bytes.size >= 44 && bytes.ascii(0, "RIFF") && bytes.ascii(8, "WAVE"))
        val riffSize = bytes.uint(4)
        require(riffSize == 0xffffffffL || bytes.size.toLong() >= riffSize + 8)
        val riffEnd = if (riffSize == 0xffffffffL) bytes.size.toLong() else minOf(bytes.size.toLong(), riffSize + 8)

        // fmt가 data보다 먼저 나타나야 하며 WAV의 첫 data chunk만 원본처럼 검사한다.
        var offset = 12L
        var channels = 0
        var width = 0
        var rate = 0L
        while (offset + 8 <= bytes.size) {
            val at = offset.toInt()
            val size = bytes.uint(at + 4)
            if (bytes.ascii(at, "data")) {
                require(channels in 1..2 && width in 1..4 && rate in 8000..96000)
                val frameBytes = channels * width
                val available = (riffEnd - offset - 8).coerceAtLeast(0)
                val read = minOf(size, available)
                require(read % frameBytes == 0L)
                val frames = if (size == 0xffffffffL) {
                    require(read == bytes.size.toLong() - offset - 8)
                    read / frameBytes
                } else {
                    require(size / frameBytes > 0 && read == size && read == size / frameBytes * frameBytes)
                    size / frameBytes
                }
                // Python은 sentinel의 선언 frame 수로 양수 검사를 하고 실제 길이를 측정한다.
                return frames.toDouble() / rate
            }
            require(size != 0xffffffffL && offset + 8 + size <= bytes.size && offset + 8 + size <= riffEnd)
            if (bytes.ascii(at, "fmt ") && channels == 0) {
                require(size >= 16)
                val format = bytes.ushort(at + 8)
                channels = bytes.ushort(at + 10)
                rate = bytes.uint(at + 12)
                width = (bytes.ushort(at + 22) + 7) / 8
                if (format == 0xfffe) {
                    // 현재 Python wave가 허용하는 WAVE_FORMAT_EXTENSIBLE PCM 하위 형식이다.
                    require(size >= 40 && bytes.ushort(at + 24) >= 22)
                    val pcm = byteArrayOf(1, 0, 0, 0, 0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113)
                    require(bytes.copyOfRange(at + 32, at + 48).contentEquals(pcm))
                } else require(format == 1)
            }
            offset += 8 + size + size % 2
        }
        throw IllegalArgumentException("SPEAKING_WAV_INVALID")
    }

    private fun ByteArray.ascii(at: Int, value: String) = at >= 0 && at + value.length <= size &&
        value.indices.all { this[at + it] == value[it].code.toByte() }

    private fun ByteArray.ushort(at: Int): Int = (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8)
    private fun ByteArray.uint(at: Int): Long =
        (0..3).fold(0L) { result, n -> result or ((this[at + n].toLong() and 255) shl (8 * n)) }
}
