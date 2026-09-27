package jp.co.translacat.languagelearning.features.speaking.execution

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** 원본의 성공 응답 TTL 600초·1000개와 동시 중복 요청 병합을 보존한다. */
internal class SpeakingExecutionCache(
    private val nanoTime: () -> Long = System::nanoTime,
    private val ttlNanos: Long = 600_000_000_000,
    private val maximum: Int = 1000,
) {
    private data class Value(val created: Long, val body: JsonObject)
    private class Pending(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val monitor = Any()
    private val items = LinkedHashMap<String, Value>(16, 0.75f, true)
    private val pending = mutableMapOf<String, Pending>()

    suspend fun execute(key: String, operation: suspend () -> JsonObject): Pair<JsonObject, Boolean> {
        // 같은 키만 직렬화하며 다른 세션의 모델 호출은 서로 기다리지 않는다.
        val slot = synchronized(monitor) { pending.getOrPut(key) { Pending() }.also { it.users++ } }
        try {
            return slot.mutex.withLock {
                val cached = synchronized(monitor) {
                    val now = nanoTime()
                    items.entries.removeIf { now - it.value.created > ttlNanos }
                    items[key]?.body
                }
                if (cached != null) return@withLock cached to true

                // 실패와 취소를 캐시하지 않는다. 성공 결과만 원래 만료·LRU 정책으로 보관한다.
                val value = operation()
                synchronized(monitor) {
                    items[key] = Value(nanoTime(), value)
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
}
