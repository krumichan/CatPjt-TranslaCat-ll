package jp.co.translacat.languagelearning.features.leveltest.infrastructure.ai

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundedLevelHttpTransportTest {
    @Test
    fun `sends post and key without changing body`() {
        val seen = AtomicReference<String>()
        val requestBody = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/base/generate") { exchange ->
            seen.set(exchange.requestHeaders.getFirst("X-API-KEY"))
            requestBody.set(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.write("{}".toByteArray())
            exchange.close()
        }
        server.start()
        try {
            BoundedLevelHttpTransport(
                "http://127.0.0.1:${server.address.port}/base",
                Duration.ofSeconds(3),
                1024,
            ).use { transport ->
                val response = transport.request(
                    "POST",
                    "generate",
                    mapOf("X-API-KEY" to "local-test-only"),
                    "{\"x\":1}".toByteArray(),
                    "application/json",
                )
                assertEquals(200, response.status)
                assertEquals("local-test-only", seen.get())
                assertEquals("{\"x\":1}", requestBody.get())
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `refuses large response without reading it all`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/large") { exchange ->
            exchange.sendResponseHeaders(200, 8192)
            try {
                exchange.responseBody.write(ByteArray(8192))
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            BoundedLevelHttpTransport(
                "http://127.0.0.1:${server.address.port}", Duration.ofSeconds(3), 128,
            ).use { transport ->
                assertFailsWith<IOException> { transport.request("GET", "large", emptyMap(), null, null) }
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `never follows redirect with api key`() {
        val count = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.set("Location", "/secret")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/secret") { exchange ->
            count.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            BoundedLevelHttpTransport(
                "http://127.0.0.1:${server.address.port}", Duration.ofSeconds(3), 128,
            ).use { transport ->
                assertEquals(302, transport.request("GET", "redirect", mapOf("X-API-KEY" to "test"), null, null).status)
                assertEquals(0, count.get())
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejects absolute or parent path`() {
        BoundedLevelHttpTransport("http://localhost:8080", Duration.ofSeconds(1), 128).use { transport ->
            listOf("https://other.example/", "../secret", "/absolute").forEach { path ->
                assertFailsWith<IllegalArgumentException> {
                    transport.request("GET", path, emptyMap(), null, null)
                }
            }
        }
    }

    @Test
    fun `total request timeout includes waiting for body`() {
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/slow") { exchange ->
            try {
                Thread.sleep(600)
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.write("{}".toByteArray())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                exchange.close()
            }
        }
        server.start()
        val before = System.nanoTime()
        try {
            BoundedLevelHttpTransport(
                "http://127.0.0.1:${server.address.port}", Duration.ofMillis(100), 128,
            ).use { transport ->
                assertFailsWith<IOException> { transport.request("GET", "slow", emptyMap(), null, null) }
                assertTrue(Duration.ofNanos(System.nanoTime() - before).toMillis() < 2000)
            }
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
