package jp.co.translacat.languagelearning.features.leveltest.infrastructure.ai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.*

/** 응답 크기와 전체 제한 시간을 강제한다. 리다이렉트로 AI 인증 헤더를 다른 서버에 전달하지 않는다. */
internal class BoundedLevelHttpTransport(
    baseUrl: String,
    private val timeout: Duration,
    private val maximumBytes: Int,
) : AutoCloseable {
    private val base: URI = URI.create(if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/")
    private val client: HttpClient

    init {
        require(
            base.scheme in setOf("http", "https") &&
                base.host != null &&
                base.rawUserInfo == null &&
                base.rawQuery == null &&
                base.rawFragment == null &&
                !timeout.isZero &&
                !timeout.isNegative &&
                maximumBytes >= 1,
        ) {
            "AI URL 또는 HTTP 제한 설정을 확인해 주세요."
        }
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    internal data class Response(
        val status: Int,
        val body: ByteArray,
        val contentType: String,
    )

    @Throws(IOException::class, InterruptedException::class)
    fun request(
        method: String,
        relativePath: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?,
    ): Response {
        // 상대 경로는 서버 코드에서만 구성한다. 원본 AI 응답의 절대 URL은 절대로 요청하지 않는다.
        require(!relativePath.startsWith('/') && ':' !in relativePath && ".." !in relativePath) {
            "AI 상대 경로가 올바르지 않습니다."
        }
        val target = base.resolve(relativePath)
        require(base.host == target.host && base.port == target.port && base.scheme == target.scheme) {
            "AI 대상 서버를 변경할 수 없습니다."
        }

        val builder = HttpRequest.newBuilder(target).timeout(timeout)
        headers.forEach(builder::header)
        if (contentType != null) builder.header("Content-Type", contentType)
        builder.method(
            method,
            body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody(),
        )

        val future = client.sendAsync(builder.build()) { LimitedBody(maximumBytes) }
        try {
            val response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            return Response(
                status = response.statusCode(),
                body = response.body(),
                contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream"),
            )
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw IOException("AI 응답 제한 시간을 초과했습니다.")
        } catch (_: ExecutionException) {
            throw IOException("AI 전송 또는 응답 읽기에 실패했습니다.")
        } catch (error: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw error
        }
    }

    private class LimitedBody(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val bytes = ByteArrayOutputStream()
        private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<ByteArray> = result

        override fun onSubscribe(value: Flow.Subscription) {
            if (subscription != null) {
                value.cancel()
                return
            }
            subscription = value
            result.whenComplete { _, _ ->
                if (result.isCancelled) value.cancel()
            }
            value.request(1)
        }

        override fun onNext(buffers: List<ByteBuffer>) {
            val activeSubscription = subscription ?: return
            for (buffer in buffers) {
                if (bytes.size().toLong() + buffer.remaining() > limit) {
                    activeSubscription.cancel()
                    result.completeExceptionally(IOException("AI 응답 크기 제한 초과"))
                    return
                }
                val part = ByteArray(buffer.remaining())
                buffer.get(part)
                bytes.write(part)
            }
            activeSubscription.request(1)
        }

        override fun onError(error: Throwable) {
            result.completeExceptionally(error)
        }

        override fun onComplete() {
            result.complete(bytes.toByteArray())
        }
    }

    override fun close() {
        client.shutdownNow()
    }
}
