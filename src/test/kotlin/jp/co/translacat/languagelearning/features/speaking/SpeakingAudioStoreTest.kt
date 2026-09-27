package jp.co.translacat.languagelearning.features.speaking

import com.sun.net.httpserver.HttpServer
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingFailure
import jp.co.translacat.languagelearning.features.speaking.infrastructure.LocalSpeakingAudioStore
import jp.co.translacat.languagelearning.features.speaking.infrastructure.S3SpeakingAudioStore
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SpeakingAudioStoreTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun `로컬 음성은 기존 객체를 덮어쓰지 않고 지정된 UUID 경계만 접근한다`() = runBlocking {
        // 준비
        val store = LocalSpeakingAudioStore(directory.root.toPath())
        val key = "${UUID.randomUUID()}.audio"
        val original = byteArrayOf(1, 2, 3)

        // 실행
        store.put(key, original, "audio/wav")
        assertFails { store.put(key, byteArrayOf(4), "audio/wav") }
        assertFailsWith<IllegalArgumentException> { store.load("../outside.audio") }

        // 검증
        assertContentEquals(original, store.load(key))
        store.delete(key)
        store.delete(key)
        assertFailsWith<SpeakingFailure> { store.load(key) }
        Unit
    }

    @Test
    fun `S3 호환 저장은 실제 로컬 HTTP와 정적 합성 자격증명만 사용한다`() = runBlocking {
        // 준비: 테스트에서 소유한 loopback 서버만 만들며 실제 AWS에는 접근하지 않는다.
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val objects = ConcurrentHashMap<String, ByteArray>()
        val calls = AtomicInteger()
        val headers = ConcurrentHashMap<String, String>()
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            val path = exchange.requestURI.path
            val auth = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            if (!path.startsWith("/synthetic-bucket/language-learning/speaking-ll/") || !auth.contains(
                    "Credential=synthetic-access/",
                )
            ) {
                exchange.sendResponseHeaders(403, -1)
            } else when (exchange.requestMethod) {
                "PUT" -> {
                    val incoming = exchange.requestBody.use { it.readBytes() }
                    val bytes =
                        if (exchange.requestHeaders.getFirst("Content-Encoding").orEmpty().contains("aws-chunked"))
                            decodeChunks(incoming) else incoming
                    if (exchange.requestHeaders.getFirst("If-None-Match") != "*" || objects.putIfAbsent(
                            path, bytes,
                        ) != null
                    ) {
                        exchange.sendResponseHeaders(412, -1)
                    } else {
                        headers["type"] = exchange.requestHeaders.getFirst("Content-Type")
                        headers["cache"] = exchange.requestHeaders.getFirst("Cache-Control")
                        exchange.responseHeaders.add("ETag", "\"synthetic\"")
                        exchange.sendResponseHeaders(200, -1)
                    }
                }

                "GET" -> {
                    val bytes = objects[path]
                    if (bytes == null) exchange.sendResponseHeaders(404, -1)
                    else {
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                }

                "DELETE" -> {
                    objects.remove(path); exchange.sendResponseHeaders(204, -1)
                }

                else -> exchange.sendResponseHeaders(405, -1)
            }
            exchange.close()
        }
        server.start()
        try {
            S3SpeakingAudioStore(
                URI("http://127.0.0.1:${server.address.port}"), "us-east-1", "synthetic-bucket",
                "synthetic-access", "synthetic-secret",
            ).use { store ->
                val key = "${UUID.randomUUID()}.audio"
                val bytes = ByteArray(128) { it.toByte() }

                // 실행
                store.put(key, bytes, "audio/wav")
                val loaded = store.load(key)
                assertFailsWith<SpeakingFailure> { store.put(key, byteArrayOf(4), "audio/wav") }
                store.delete(key)
                store.delete(key)

                // 검증: SDK HTTP가 LL prefix·정적 credential·MIME·불변 쓰기를 모두 거쳤다.
                assertContentEquals(bytes, loaded)
                assertEquals("audio/wav", headers["type"])
                assertEquals("private, no-store", headers["cache"])
                assertTrue(objects.isEmpty())
                assertEquals(5, calls.get())
            }
        } finally {
            server.stop(0)
        }
    }

    private fun decodeChunks(bytes: ByteArray): ByteArray {
        val result = ByteArrayOutputStream()
        var position = 0
        while (position < bytes.size) {
            var end = position
            while (end + 1 < bytes.size && !(bytes[end] == 13.toByte() && bytes[end + 1] == 10.toByte())) end++
            val size = bytes.copyOfRange(position, end).toString(Charsets.US_ASCII).substringBefore(';').toInt(16)
            position = end + 2
            if (size == 0) break
            result.write(bytes, position, size)
            position += size + 2
        }
        return result.toByteArray()
    }
}
