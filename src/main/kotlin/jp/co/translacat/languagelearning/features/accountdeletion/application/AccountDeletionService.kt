package jp.co.translacat.languagelearning.features.accountdeletion.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Serializable
internal data class AccountDeletionRequest(val operationId: String, val requestedAtUtc: String)

@Serializable
internal data class AccountDeletionResponse(
    val operationId: String, val userId: Long, val status: String, val pendingObjects: Int,
)

internal class AccountDeletionFailure(val code: String, val status: Int) : RuntimeException(code)

internal data class AccountDeletionObject(val kind: String, val key: String) {
    init {
        require(kind in setOf("LEVEL_TEST", "SPEAKING"))
        require(Regex(if (kind == "LEVEL_TEST") "[0-9a-f-]{36}\\.(wav|audio)" else "[0-9a-f-]{36}\\.audio").matches(key))
    }
}

internal interface AccountDeletionStore {
    suspend fun ready(): Boolean
    suspend fun isSessionActive(sessionId: String): Boolean = false
    suspend fun isUserAvailable(userId: Long): Boolean = false
    suspend fun prepare(userId: Long, operationId: String, requestedAt: Instant): List<String>
    suspend fun activeWrites(userId: Long): Int
    suspend fun objects(userId: Long, operationId: String): List<AccountDeletionObject>
    suspend fun objectDeleted(userId: Long, operationId: String, value: AccountDeletionObject)
    suspend fun finish(userId: Long, operationId: String): AccountDeletionResponse
    suspend fun beginAudioWrite(userId: Long, value: AccountDeletionObject)
    suspend fun endAudioWrite(userId: Long, value: AccountDeletionObject)
    suspend fun uncertainAudioWrite(userId: Long, value: AccountDeletionObject)
}

/** 기존 오디오 쓰기 전에 owner 행 잠금으로 intent를 기록한다. 실제 I/O는 트랜잭션 밖에서 수행한다. */
internal class AccountAudioWrites(private val store: AccountDeletionStore) {
    suspend fun <T> write(userId: Long?, kind: String, key: String, operation: suspend () -> T): T {
        if (userId == null) return operation() // 공용 LevelTest pool 오디오는 사용자 삭제 대상이 아니다.
        val target = AccountDeletionObject(kind, key)
        store.beginAudioWrite(userId, target)
        var started = false
        try {
            // 시간 제한은 요청 수명만 제한한다. 만료를 외부 PUT 미실행 증거로 해석하지 않는다.
            val value = withTimeout(90_000) { started = true; operation() }
            withContext(NonCancellable) { store.endAudioWrite(userId, target) }
            return value
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                if (!started) store.endAudioWrite(userId, target)
                else runCatching { store.uncertainAudioWrite(userId, target) }
            }
            // 전송 이후 취소·timeout·오류는 운영 검토가 필요한 intent로 남긴다. 개인 키는 로그에 쓰지 않는다.
            throw failure
        }
    }
}

internal class AccountDeletionService(
    private val store: AccountDeletionStore,
    private val deleteObject: suspend (AccountDeletionObject) -> Unit,
    private val clearMemory: (Long, List<String>) -> Unit = { _, _ -> },
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun ready(): Boolean = try {
        store.ready()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    suspend fun delete(userId: Long, request: AccountDeletionRequest): AccountDeletionResponse {
        // 멱등 식별자와 시각은 DB를 변경하기 전에 엄격히 확인한다. 과거 시각은 재시도를 위해 허용한다.
        val id = runCatching { UUID.fromString(request.operationId) }.getOrNull()
        if (userId <= 0 || id?.toString() != request.operationId) invalid()
        val requested = runCatching { Instant.parse(request.requestedAtUtc) }.getOrNull() ?: invalid()
        if (requested.isBefore(Instant.EPOCH) || requested.isAfter(clock.instant().plusSeconds(5))) invalid()
        val sessions = store.prepare(userId, request.operationId, requested)
        clearMemory(userId, sessions)

        // freeze 전에 시작한 파일 쓰기가 끝나기 전에는 객체를 지우거나 완료를 반환하지 않는다.
        if (store.activeWrites(userId) == 0) {
            withTimeoutOrNull(15_000) {
                for (target in store.objects(userId, request.operationId)) {
                    try {
                        deleteObject(target)
                        store.objectDeleted(userId, request.operationId, target)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 객체 키·공급자 오류 원문을 로그에 노출하지 않고 영속 pending으로 다음 재호출에 맡긴다.
                    }
                }
            }
        }
        return store.finish(userId, request.operationId)
    }

    private fun invalid(): Nothing = throw AccountDeletionFailure("ACCOUNT_DELETION_INVALID", 400)
}
