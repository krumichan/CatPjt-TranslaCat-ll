package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.config.*
import io.ktor.server.testing.*
import java.util.*
import kotlin.test.*

class InternalApiSettingsLoaderTest {
    @Test
    fun `기본값은 비활성이고 비밀키가 없어도 읽을 수 있다`() = testApplication {
        environment { config = MapApplicationConfig() }
        application { assertFalse(loadInternalApiSettings().enabled) }
        startApplication()
    }

    @Test
    fun `명시적으로 활성화했는데 키가 없으면 기동 단계에서 거부한다`() = testApplication {
        environment { config = MapApplicationConfig("internalApi.enabled" to "true") }
        application { assertFailsWith<IllegalArgumentException> { loadInternalApiSettings() } }
        startApplication()
    }

    @Test
    fun `설정값으로 수신자와 유효기간을 읽는다`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "internalApi.enabled" to "true",
                "internalApi.issuer" to "core-local",
                "internalApi.audience" to "ll-local",
                "internalApi.maxTtlSeconds" to "60",
                "internalApi.secretBase64" to Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
            )
        }
        application {
            val value = loadInternalApiSettings(); assertTrue(value.enabled); assertEquals("core-local", value.issuer)
            assertEquals("ll-local", value.audience); assertEquals(60L, value.maxTtlSeconds)
        }
        startApplication()
    }
}
