package jp.co.translacat.languagelearning.features.accountdeletion

import jp.co.translacat.languagelearning.features.accountdeletion.application.*
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingExecutionCache
import jp.co.translacat.languagelearning.shared.security.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.*
import java.util.*
import kotlin.test.*

internal class DeletionFixtureStore : AccountDeletionStore {
    var operation: String? = null
    var prepared = 0
    var active = 0
    var finished = false
    var ended = 0
    var uncertain = 0
    val pending = mutableListOf<AccountDeletionObject>()
    override suspend fun ready() = true
    override suspend fun prepare(userId: Long, operationId: String, requestedAt: Instant): List<String> {
        if (operation != null && operation != operationId) throw AccountDeletionFailure("ACCOUNT_DELETION_CONFLICT", 409)
        operation = operationId
        prepared++
        return listOf("test-session-1")
    }
    override suspend fun activeWrites(userId: Long) = active
    override suspend fun objects(userId: Long, operationId: String) = pending.toList()
    override suspend fun objectDeleted(userId: Long, operationId: String, value: AccountDeletionObject) { pending.remove(value) }
    override suspend fun finish(userId: Long, operationId: String): AccountDeletionResponse {
        finished = pending.isEmpty() && active == 0
        return AccountDeletionResponse(operationId, userId, if (finished) "COMPLETED" else "PENDING", pending.size + active)
    }
    override suspend fun beginAudioWrite(userId: Long, value: AccountDeletionObject) { active++ }
    override suspend fun endAudioWrite(userId: Long, value: AccountDeletionObject) { active--; ended++ }
    override suspend fun uncertainAudioWrite(userId: Long, value: AccountDeletionObject) { uncertain++ }
}

class AccountDeletionServiceTest {
    private val instant = Instant.parse("2026-10-23T03:00:00Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)
    private fun request() = AccountDeletionRequest(UUID.randomUUID().toString(), instant.toString())
    private fun audio() = AccountDeletionObject("SPEAKING", "${UUID.randomUUID()}.audio")

    @Test
    fun `실제 객체가 모두 삭제된 뒤에만 완료를 반환하고 메모리도 무효화한다`(): Unit = runBlocking {
        // 준비
        val store = DeletionFixtureStore().also { it.pending += audio() }
        val removed = mutableListOf<AccountDeletionObject>()
        val memory = mutableListOf<Long>()
        val service = AccountDeletionService(store, { removed += it }, { user, _ -> memory += user }, clock)

        // 실행
        val result = service.delete(71, request())

        // 검증
        assertEquals("COMPLETED", result.status)
        assertEquals(0, result.pendingObjects)
        assertEquals(1, removed.size)
        assertEquals(listOf(71L), memory)
    }

    @Test
    fun `저장소 실패는 pending으로 남고 같은 operation 재호출에서 정리한다`(): Unit = runBlocking {
        // 준비
        val store = DeletionFixtureStore().also { it.pending += audio() }
        var fail = true
        val service = AccountDeletionService(store, { if (fail) error("synthetic") }, clock = clock)
        val request = request()

        // 실행과 검증: 첫 실패는 완료하지 않는다.
        assertEquals("PENDING", service.delete(71, request).status)
        assertFalse(store.finished)
        fail = false

        // 실행과 검증: 응답 유실·재시작과 같은 재호출은 기존 키만 마저 지운다.
        assertEquals("COMPLETED", service.delete(71, request).status)
        assertEquals(2, store.prepared)
    }

    @Test
    fun `freeze 이전 파일 쓰기가 진행 중이면 삭제를 앞질러 실행하지 않는다`(): Unit = runBlocking {
        // 준비
        val store = DeletionFixtureStore().also { it.pending += audio(); it.active = 1 }
        var calls = 0
        val service = AccountDeletionService(store, { calls++ }, clock = clock)
        val request = request()

        // 실행
        assertEquals("PENDING", service.delete(71, request).status)

        // 검증
        assertEquals(0, calls)
        store.active = 0
        assertEquals("COMPLETED", service.delete(71, request).status)
        assertEquals(1, calls)
    }

    @Test
    fun `잘못된 식별자와 시각은 tombstone 접수 전 거부한다`(): Unit = runBlocking {
        // 준비
        val store = DeletionFixtureStore()
        val service = AccountDeletionService(store, {}, clock = clock)

        // 실행과 검증
        for (bad in listOf(
            request().copy(operationId = "../target"),
            request().copy(requestedAtUtc = "bad"),
            request().copy(requestedAtUtc = instant.plusSeconds(6).toString()),
            request().copy(requestedAtUtc = "1900-01-01T00:00:00Z"),
        )) assertFailsWith<AccountDeletionFailure> { service.delete(71, bad) }
        assertEquals(0, store.prepared)
    }

    @Test
    fun `전송 시작 뒤 취소는 미확정 intent로 남고 공용 파일은 사용자 intent가 없다`(): Unit = runBlocking {
        // 준비
        val store = DeletionFixtureStore()
        val writes = AccountAudioWrites(store)
        val key = audio().key

        // 실행
        assertFailsWith<CancellationException> { writes.write(71, "SPEAKING", key) { throw CancellationException() } }
        writes.write(null, "LEVEL_TEST", "${UUID.randomUUID()}.wav") { Unit }

        // 검증
        assertEquals(1, store.active)
        assertEquals(0, store.ended)
        assertEquals(1, store.uncertain)
    }

    @Test
    fun `개인 삭제 객체 키는 다른 저장소 경로나 파일명을 허용하지 않는다`() {
        // 준비와 실행, 검증
        for ((kind, key) in listOf("OTHER" to "x", "SPEAKING" to "../x.audio", "LEVEL_TEST" to "/x.wav")) {
            assertFailsWith<IllegalArgumentException> { AccountDeletionObject(kind, key) }
        }
    }

    @Test
    fun `세션 캐시 무효화는 다른 세션을 보존하고 늦은 결과 재삽입을 거부한다`(): Unit = runBlocking {
        // 준비
        val cache = SpeakingExecutionCache()
        val deleted = "deleted-${UUID.randomUUID()}"
        val other = "other-${UUID.randomUUID()}"
        cache.execute("one", deleted) { buildJsonObject { put("private", "first") } }
        cache.execute("two", other) { buildJsonObject { put("private", "other") } }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val late = async { runCatching { cache.execute("late", deleted) {
            entered.complete(Unit); release.await(); buildJsonObject { put("private", "late") }
        } } }
        entered.await()

        // 실행
        SpeakingExecutionCache.discardSessions(setOf(deleted))
        release.complete(Unit)

        // 검증
        assertTrue(late.await().isFailure)
        assertFails { cache.execute("one", deleted) { error("must not call") } }
        assertTrue(cache.execute("two", other) { error("must be cached") }.second)
    }

    @Test
    fun `다른 노드의 owner 삭제는 성공 캐시 반환과 Provider 늦은 완료를 모두 거부한다`(): Unit = runBlocking {
        // 준비: 로컬 discard 이벤트 없이 DB owner 검사만 false로 바뀐다.
        var active = true
        val session = "remote-${UUID.randomUUID()}"
        val cache = SpeakingExecutionCache(sessionOwnerCheck = { active })
        cache.execute("first", session) { buildJsonObject { put("private", "cached") } }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val late = async { runCatching { cache.execute("late", session) {
            entered.complete(Unit)
            release.await()
            buildJsonObject { put("private", "late") }
        } } }
        entered.await()

        // 실행
        active = false
        release.complete(Unit)

        // 검증
        assertTrue(late.await().isFailure)
        assertFails { cache.execute("first", session) { error("must not call") } }
    }

    @Test
    fun `전용 scope와 subject 및 120초 이하 TTL만 삭제 principal이 된다`() {
        // 준비
        val settings = InternalApiSettings(enabled = true, secretBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        fun validate(sub: String? = "71", scope: List<String>? = listOf("accounts:delete"),
                     roles: List<String>? = null, seconds: Long = 120) =
            AccountDeletionClaimsPolicy.validate(sub, scope, roles, instant, instant.plusSeconds(seconds), settings, instant)

        // 실행과 검증
        assertEquals(71L, validate()?.userId)
        assertNull(validate(sub = "071"))
        assertNull(validate(sub = "9223372036854775808"))
        assertNull(validate(scope = listOf("settings:read")))
        assertNull(validate(scope = listOf("accounts:delete", "accounts:delete")))
        assertNull(validate(roles = listOf("ADMIN")))
        assertNull(validate(seconds = 121))
        assertNull(validate(seconds = 0))
    }
}
