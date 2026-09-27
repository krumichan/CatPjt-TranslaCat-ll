package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingAudioRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.*

internal data class SpeakingAudioDeleteClaim(val userId: Long, val audio: SpeakingAudioRecord, val token: String)

internal class SpeakingAudioRetention(
    private val work: SpeakingUnitOfWork, private val store: SpeakingAudioStore,
    private val onFailure: (String) -> Unit,
) {
    suspend fun claim(userId: Long, audioId: Long): SpeakingAudioDeleteClaim? = work.write(userId) {
        val audio = records.audioById(userId, audioId) ?: return@write null
        if (audio.physicalDeletedAt != null || audio.deleteLeaseUntil?.isAfter(nowUtc) == true) return@write null
        if (audio.deletedAt == null && audio.retentionUntil.isAfter(nowUtc)) return@write null

        // 사용자 잠금 아래 신고 연장과 삭제를 직렬화하고, 선택된 삭제는 재시작 후에도 같은 객체로 제한한다.
        val token = UUID.randomUUID().toString()
        val claimed = records.saveAudio(
            audio.copy(
                deletedAt = audio.deletedAt ?: nowUtc,
                deleteClaimToken = token, deleteLeaseUntil = nowUtc.plusSeconds(60),
            ),
        )
        SpeakingAudioDeleteClaim(userId, claimed, token)
    }

    suspend fun complete(claim: SpeakingAudioDeleteClaim): Boolean = work.write(claim.userId) {
        val audio = records.audioById(claim.userId, claim.audio.id) ?: return@write false
        if (audio.deleteClaimToken != claim.token || audio.physicalDeletedAt != null) return@write false
        records.saveAudio(audio.copy(physicalDeletedAt = nowUtc, deleteClaimToken = null, deleteLeaseUntil = null))
        true
    }

    suspend fun release(claim: SpeakingAudioDeleteClaim): Boolean = work.write(claim.userId) {
        val audio = records.audioById(claim.userId, claim.audio.id) ?: return@write false
        if (audio.deleteClaimToken != claim.token || audio.physicalDeletedAt != null) return@write false
        records.saveAudio(audio.copy(deleteClaimToken = null, deleteLeaseUntil = nowUtc.plusSeconds(60)))
        true
    }

    suspend fun runOnce(pendingOnly: Boolean = false): Int {
        val due = work.read {
            records.dueAudio(nowUtc, 100, pendingOnly).mapNotNull { audio ->
                records.audioOwner(audio.id)?.let { it to audio.id }
            }
        }
        var completed = 0
        for ((userId, id) in due) {
            val claim = claim(userId, id) ?: continue
            try {
                // 파일/S3 I/O는 DB 밖에서 실행한다. 이미 없는 객체 삭제도 성공이므로 응답 유실을 재시도할 수 있다.
                store.delete(claim.audio.objectKey)
                if (complete(claim)) completed++
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { release(claim) }
                throw cancelled
            } catch (failure: Exception) {
                release(claim)
                onFailure(failure.javaClass.simpleName)
            }
        }
        return completed
    }
}
