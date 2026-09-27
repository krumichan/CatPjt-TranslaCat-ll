package jp.co.translacat.languagelearning.features.listening.domain.policy

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.round

internal data class ListeningDurationDemand(val minimum: Double, val maximum: Double) {
    fun payload() = buildJsonObject {
        put("minSeconds", minimum)
        put("maxSeconds", maximum)
        put("policyVersion", "listening-audio-duration-v1")
        put("playbackSpeed", "NORMAL")
    }
}

internal object ListeningDuration {
    fun demand(request: JsonObject): ListeningDurationDemand {
        val range = when (request.getValue("setContext").jsonObject.getValue("difficulty").jsonPrimitive.content) {
            "EASY" -> 5.0 to 12.0
            "MY_LEVEL" -> 8.0 to 20.0
            "CHALLENGE" -> 15.0 to 30.0
            else -> throw ListeningProtocolFailure("INVALID_REQUEST")
        }
        val constraints = request["constraints"] as? JsonObject
        val minimum =
            maxOf(range.first, (constraints?.get("audioSecondsMin") as? JsonPrimitive)?.doubleOrNull ?: range.first)
        val maximum =
            minOf(range.second, (constraints?.get("audioSecondsMax") as? JsonPrimitive)?.doubleOrNull ?: range.second)
        if (minimum > maximum) throw ListeningProtocolFailure("INVALID_REQUEST")
        return ListeningDurationDemand(minimum, maximum)
    }

    fun correctionFloor(request: JsonObject): Int? {
        val correction = request["durationCorrection"] as? JsonObject ?: return null
        val previous = correction.getValue("previousMeasuredSeconds").jsonPrimitive.double
        val minimum = demand(request).minimum
        if (previous <= 0 || previous >= minimum) return null
        val source = correction.getValue("previousSourceText").jsonPrimitive.content.trim()
        val length = source.codePointCount(0, source.length)
        return maxOf(length + 1, ceil(length * minimum / previous).toInt())
    }

    fun guidance(request: JsonObject): JsonObject {
        val demand = demand(request)
        val target = (demand.minimum + demand.maximum) / 2
        val result = buildJsonObject {
            put("durationDemand", demand.payload())
            put("interiorTargetSeconds", target)
            put("estimatedAudioSecondsIsDiagnosticOnly", true)
            put("acceptanceAuthority", "decoded NORMAL audio frames, not text length or estimated seconds")
            correctionFloor(request)?.let { minimum ->
                put(
                    "durationCorrectionPlanning",
                    buildJsonObject {
                        put("direction", "EXPAND_MEANINGFUL_CONTENT")
                        put("minimumSourceCharacters", minimum)
                        put("basis", "previous measured NORMAL audio rate and unchanged minimum duration")
                        put("notAudioAcceptance", true)
                    },
                )
            }
        }.toMutableMap()
        val language = request.getValue("userContext").jsonObject.getValue("learningLanguage").jsonPrimitive.content
        val voice = (request["referenceVoice"] as? JsonObject)?.get("voiceKey")?.jsonPrimitive?.content
        if (language.lowercase().substringBefore('-') == "ja" && voice in setOf("marin", "Kore")) {
            val observed = listOf(28 / 5.5, 29 / 6.35, 36 / 5.3)
            val mean = if (voice == "marin") observed.average() else 5.894
            result["empiricalPlanningReference"] = buildJsonObject {
                put("language", "ja")
                put("provider", if (voice == "marin") "openai" else "gemini")
                put("voice", voice)
                if (voice == "marin") put("model", "gpt-4o-mini-tts-2025-12-15")
                put("playbackSpeed", "NORMAL")
                put("sampleCount", if (voice == "marin") 3 else 30)
                put(
                    "observedCharactersPerSecond",
                    buildJsonObject {
                        put("min", if (voice == "marin") round(observed.min() * 1000) / 1000 else 4.813)
                        put("max", if (voice == "marin") round(observed.max() * 1000) / 1000 else 6.665)
                        put("mean", round(mean * 1000) / 1000)
                    },
                )
                put("approximateTargetCharacters", round(target * mean).toInt())
                put(
                    "scope",
                    if (voice == "marin") "small planning sample only; decoded NORMAL WAV remains the duration authority"
                    else "planning reference only; other languages/providers/voices are not calibrated",
                )
            }
        }
        return JsonObject(result)
    }

    /** 실측 WAV frame으로만 길이를 판정하며 Provider가 신고한 duration은 사용하지 않는다. */
    fun measuredSeconds(bytes: ByteArray, contentType: String): Double {
        if (contentType.substringBefore(';').lowercase() !in setOf(
                "audio/wav", "audio/x-wav", "audio/vnd.wave", "wav", "wave",
            )
        )
            throw ListeningProtocolFailure("AUDIO_DECODE_FAILED")
        try {
            require(
                bytes.size >= 44 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                    bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE",
            )
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 12
            var channels = 0
            var rate = 0
            var width = 0
            var audioSize: Int? = null
            while (offset + 8 <= bytes.size) {
                val chunk = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
                val size = buffer.getInt(offset + 4)
                require(size >= 0 && offset.toLong() + 8 + size <= bytes.size)
                if (chunk == "fmt ") {
                    require(size >= 16 && buffer.getShort(offset + 8).toInt() == 1)
                    channels = buffer.getShort(offset + 10).toInt()
                    rate = buffer.getInt(offset + 12)
                    width = buffer.getShort(offset + 22).toInt() / 8
                } else if (chunk == "data") audioSize = size
                offset += 8 + size + size % 2
            }
            require(channels in 1..2 && rate in 8000..96000 && width in 1..4)
            val size = checkNotNull(audioSize)
            require(size > 0 && size % (channels * width) == 0)
            return size.toDouble() / (channels * width * rate)
        } catch (_: Exception) {
            throw ListeningProtocolFailure("AUDIO_DECODE_FAILED")
        }
    }

    fun validate(seconds: Double, demand: ListeningDurationDemand) {
        if (seconds < demand.minimum) throw ListeningProtocolFailure("AUDIO_TOO_SHORT")
        if (seconds > demand.maximum) throw ListeningProtocolFailure("AUDIO_TOO_LONG")
    }
}
