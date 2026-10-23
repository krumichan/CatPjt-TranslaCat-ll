package jp.co.translacat.languagelearning.features.speaking.execution

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingFailure
import java.util.WeakHashMap

/** 원본의 성공 응답 TTL 600초·1000개와 동시 중복 요청 병합을 보존한다. */
internal class SpeakingExecutionCache(
    private val nanoTime: () -> Long = System::nanoTime,
    private val ttlNanos: Long = 600_000_000_000,
    private val maximum: Int = 1000,
    private val sessionOwnerCheck: suspend (String) -> Boolean = { true },
) {
    private data class Value(val created: Long, val body: JsonObject, val session: String?)
    private class Pending(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val monitor = Any()
    private val items = LinkedHashMap<String, Value>(16, 0.75f, true)
    private val pending = mutableMapOf<String, Pending>()
    private val retiredSessions = mutableSetOf<String>()

    init { synchronized(instances) { instances[this] = Unit } }

    companion object {
        private val instances = WeakHashMap<SpeakingExecutionCache, Unit>()

        /** 업무 세션 ID만 받아 원문을 찾아내지 않고 현재 프로세스의 성공 캐시를 지운다. */
        fun discardSessions(sessions: Set<String>) {
            val caches = synchronized(instances) { instances.keys.toList() }
            caches.forEach { cache -> synchronized(cache.monitor) {
                cache.retiredSessions += sessions
                cache.items.entries.removeIf { it.value.session in sessions }
            } }
        }
    }

    suspend fun execute(key: String, session: String? = null, operation: suspend () -> JsonObject): Pair<JsonObject, Boolean> {
        requireOwnerActive(session)
        // 같은 키만 직렬화하며 다른 세션의 모델 호출은 서로 기다리지 않는다.
        val slot = synchronized(monitor) { pending.getOrPut(key) { Pending() }.also { it.users++ } }
        try {
            return slot.mutex.withLock {
                val cached = synchronized(monitor) {
                    if (session != null && session in retiredSessions) throw SpeakingFailure("SESSION_NOT_FOUND")
                    val now = nanoTime()
                    items.entries.removeIf { now - it.value.created > ttlNanos }
                    items[key]?.body
                }
                if (cached != null) {
                    // 다른 LL 프로세스에서 접수된 tombstone도 캐시 응답 직전에 DB로 확인한다.
                    requireOwnerActive(session)
                    return@withLock cached to true
                }

                // 실패와 취소를 캐시하지 않는다. 성공 결과만 원래 만료·LRU 정책으로 보관한다.
                val value = operation()
                requireOwnerActive(session)
                synchronized(monitor) {
                    // 삭제 이전에 시작한 Provider 응답도 캐시로 다시 살아나지 않는다.
                    if (session != null && session in retiredSessions) throw SpeakingFailure("SESSION_NOT_FOUND")
                    items[key] = Value(nanoTime(), value, session)
                    while (items.size > maximum) items.remove(items.keys.first())
                }
                value to false
            }
        } finally {
            synchronized(monitor) {
                slot.users--
                if (slot.users == 0) pending.remove(key, slot)
            }
        }
    }

    private suspend fun requireOwnerActive(session: String?) {
        if (session != null && !sessionOwnerCheck(session)) {
            discardSessions(setOf(session))
            throw SpeakingFailure("SESSION_NOT_FOUND")
        }
    }
}
