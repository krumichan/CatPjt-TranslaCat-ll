package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingFailure
import java.security.MessageDigest

internal data class SpeakingAudioResponse(val bytes: ByteArray, val contentType: String)

internal class SpeakingAudioService(private val work: SpeakingUnitOfWork, private val storage: SpeakingAudioStore) {
    suspend fun get(userId: Long, sessionId: Long, turnId: Long?, role: String): SpeakingAudioResponse {
        require(role in setOf("USER", "ASSISTANT", "OPENING"))
        val audio = work.read {
            val session = records.session(userId, sessionId) ?: throw SpeakingFailure("SESSION_NOT_FOUND")
            if (!session.openingReady) throw SpeakingFailure("SESSION_NOT_FOUND")
            if (turnId != null && records.turn(sessionId, turnId) == null) throw SpeakingFailure("TURN_NOT_FOUND")
            records.audio(sessionId, turnId, role) ?: throw SpeakingFailure("INVALID_AUDIO")
        }

        // 권한 확인 후 업무 소유 저장소만 읽고 저장 당시 길이·hash와 일치하는 파일을 반환한다.
        val bytes = storage.load(audio.objectKey)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (bytes.size.toLong() != audio.byteLength || digest != audio.sha256) throw SpeakingFailure("INVALID_AUDIO")
        return SpeakingAudioResponse(bytes, audio.contentType)
    }
}
