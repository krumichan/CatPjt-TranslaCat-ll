package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.levelInvalid
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.levelNotFound
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelAudio
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelAudioBytes
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelReferenceAudio
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelAudioPolicy
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

internal class LevelAudioService(
    private val work: LevelTestUnitOfWork,
    private val storage: LevelTestAudioStore,
    private val callbackOrigin: String,
) {
    suspend fun reserveReference(userId: Long?): LevelAudioUpload {
        val key = UUID.randomUUID().toString() + ".wav"
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        work.write(userId) {
            records.saveAudio(
                LevelAudio(
                    key, userId, "REFERENCE", "audio/wav",
                    tokenHash = LevelTestRules.sha256(token.toByteArray(Charsets.US_ASCII)),
                    uploadUntil = nowUtc.plusMinutes(10), createdAt = nowUtc,
                ),
            )
        }
        return LevelAudioUpload(
            "${callbackOrigin.trimEnd('/')}/internal/v1/language-learning/level-test/audio-uploads/$key?token=$token",
            key,
        )
    }

    /** 업로드 URL 자체가 단기 capability다. 토큰 원문/URL 전체는 로그에 남기지 않는다. */
    suspend fun upload(key: String, token: String?, bytes: ByteArray, mime: String) {
        val expected = work.read { records.audio(key) } ?: levelNotFound()
        val digest = LevelTestRules.sha256((token ?: "").toByteArray(Charsets.UTF_8))
        val allowed = work.read {
            expected.status != "DELETED" && expected.purpose == "REFERENCE" && expected.uploadUntil != null && expected.uploadUntil > nowUtc &&
                expected.tokenHash != null && MessageDigest.isEqual(
                expected.tokenHash.toByteArray(), digest.toByteArray(),
            )
        }
        if (!allowed) throw LevelTestException("LEVEL_TEST_UPLOAD_FORBIDDEN", 403, "업로드 권한이 없거나 만료되었습니다.")
        val normalized = LevelAudioPolicy.validate(bytes, mime)
        if (normalized !in setOf("audio/wav", "audio/x-wav", "audio/vnd.wave")) throw LevelTestException(
            "LEVEL_TEST_AUDIO_INVALID", 400, "참고 음성은 WAV여야 합니다.",
        )
        val checksum = LevelTestRules.sha256(bytes)
        // 파일 키는 무작위 예약별로 불변이다. 같은 토큰의 동시 업로드도 다른 바이트로 바꿀 수 없다.
        storage.store(key, bytes)
        work.write(expected.ownerUserId) {
            val current = records.audio(key) ?: levelNotFound()
            if (current.status == "DELETED" || current.uploadUntil == null || current.uploadUntil <= nowUtc) throw LevelTestException(
                "LEVEL_TEST_UPLOAD_FORBIDDEN", 403, "업로드 권한이 만료되었습니다.",
            )
            if (current.status == "STORED" && current.checksum != checksum) levelInvalid("이미 저장된 참고 음성을 변경할 수 없습니다.")
            records.saveAudio(
                current.copy(
                    status = "STORED", checksum = checksum, sizeBytes = bytes.size.toLong(), contentType = "audio/wav",
                ),
            )
        }
    }

    suspend fun verify(reference: LevelReferenceAudio, upload: LevelAudioUpload) {
        if (reference.objectKey != upload.objectKey || reference.contentType != upload.contentType) invalidReference()
        val stored = work.read { records.audio(upload.objectKey) } ?: invalidReference()
        if (stored.status != "STORED" || stored.checksum == null || stored.sizeBytes !in 1..LevelTestRules.MAX_AUDIO_BYTES.toLong()) invalidReference()
        if (reference.checksumSha256 != null && !reference.checksumSha256.equals(
                stored.checksum, ignoreCase = true,
            )
        ) invalidReference()
        if (reference.durationMs != null && reference.durationMs <= 0) invalidReference()
        val bytes = storage.load(stored.key)
        if (LevelTestRules.sha256(bytes) != stored.checksum) invalidReference()
    }

    suspend fun healthy(key: String): Boolean {
        val row = work.read { records.audio(key) } ?: return false
        if (row.status != "STORED" || row.retentionUntil?.let { expiry -> work.read { expiry <= nowUtc } } == true) return false
        return try {
            LevelTestRules.sha256(storage.load(key)) == row.checksum
        } catch (failure: LevelTestException) {
            if (failure.code == "LANGUAGE_LEARNING_LEVEL_TEST_NOT_FOUND") false else throw failure
        }
    }

    suspend fun storeAnswer(userId: Long, bytes: ByteArray, mime: String): LevelAudio {
        val type = LevelAudioPolicy.validate(bytes, mime)
        val key = UUID.randomUUID().toString() + ".audio"
        storage.store(key, bytes)
        return try {
            work.write(userId) {
                LevelAudio(
                    key, userId, "ANSWER", type, checksum = LevelTestRules.sha256(bytes),
                    sizeBytes = bytes.size.toLong(), status = "STORED",
                    retentionUntil = nowUtc.plusDays(LevelTestRules.AUDIO_RETENTION_DAYS), createdAt = nowUtc,
                ).also(records::saveAudio)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { storage.delete(key) } }; throw failure
        }
    }

    suspend fun storeModel(userId: Long, audio: LevelAudioBytes): LevelAudio {
        val type = LevelAudioPolicy.validate(audio.bytes, audio.contentType)
        val key = UUID.randomUUID().toString() + ".audio"
        storage.store(key, audio.bytes)
        return try {
            work.write(userId) {
                LevelAudio(
                    key, userId, "MODEL", type, checksum = LevelTestRules.sha256(audio.bytes),
                    sizeBytes = audio.bytes.size.toLong(), status = "STORED", createdAt = nowUtc,
                ).also(records::saveAudio)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { storage.delete(key) } }; throw failure
        }
    }

    suspend fun load(key: String): LevelAudioBytes {
        val row = work.read {
            records.audio(key)
                ?.takeIf { it.status == "STORED" && (it.retentionUntil == null || it.retentionUntil > nowUtc) }
        } ?: levelNotFound()
        val bytes = storage.load(row.key)
        if (LevelTestRules.sha256(bytes) != row.checksum) throw LevelTestException(
            "LEVEL_TEST_AUDIO_INVALID", 503, "저장된 음성의 무결성을 확인할 수 없습니다.",
        )
        return LevelAudioBytes(bytes, row.contentType)
    }

    suspend fun abandon(key: String) {
        if (work.read { records.audio(key) } == null) return
        // 실패 직후 바이트를 지우기보다 만료 대상으로 표시하여 재시작 후에도 정리가 가능하게 한다.
        work.write(null) { records.audio(key)?.let { records.saveAudio(it.copy(retentionUntil = nowUtc)) } }
    }

    suspend fun sweep() {
        val expired = work.read { records.expiredAudio(nowUtc, 100) }
        for (row in expired) {
            storage.delete(row.key)
            work.write(null) {
                records.audio(row.key)
                    ?.let { records.saveAudio(it.copy(status = "DELETED", tokenHash = null)) }
            }
        }
    }

    private fun invalidReference(): Nothing =
        throw LevelTestException("AI_TTS_FAILED", 502, "AI 참고 음성 업로드와 체크섬 검증에 실패했습니다.")
}
