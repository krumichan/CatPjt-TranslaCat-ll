package jp.co.translacat.languagelearning.support

import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.DatabaseTargetGuard
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

internal data class LocalScratchMysql(
    val name: String,
    private val url: String,
    private val username: String,
    private val password: String,
) {
    fun connect(): Connection = DriverManager.getConnection(url, username, password)

    fun settings(poolSize: Int = 4) = DatabaseSettings(
        enabled = true,
        jdbcUrl = url,
        username = username,
        password = password,
        expectedCatalog = name,
        maximumPoolSize = poolSize,
        minimumIdle = 0,
    )

    override fun toString(): String = "LocalScratchMysql(name=$name, credentials=<redacted>)"

    companion object {
        /** 호출 중 생성에 성공한 무작위 DB만 finally에서 정리한다. 기존 DB 이름은 입력받지 않는다. */
        fun use(action: (LocalScratchMysql) -> Unit) {
            val serverUrl = requireNotNull(System.getenv("LL_TEST_MYSQL_URL")) { "LL_TEST_MYSQL_* 환경변수가 필요합니다." }
            val username = requireNotNull(System.getenv("LL_TEST_MYSQL_USERNAME"))
            val password = requireNotNull(System.getenv("LL_TEST_MYSQL_PASSWORD"))
            require(serverUrl.startsWith("jdbc:mysql://"))
            val uri = URI(serverUrl.removePrefix("jdbc:"))
            require(uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")) { "로컬 MySQL만 허용합니다." }
            require(uri.rawUserInfo == null && uri.rawFragment == null)
            require(uri.path.isNullOrEmpty() || uri.path == "/") { "기존 DB가 없는 서버 URL만 허용합니다." }
            val name = "translacat_ll_it_" + UUID.randomUUID().toString().replace("-", "")
            check(Regex("translacat_ll_it_[0-9a-f]{32}").matches(name))
            val url = serverUrl.substringBefore('?').trimEnd('/') + "/" + name + (uri.rawQuery?.let { "?$it" } ?: "")
            DatabaseTargetGuard.requireMatchingUrl(url, name)
            DriverManager.getConnection(serverUrl, username, password).use { admin ->
                admin.createStatement().use {
                    it.executeUpdate("CREATE DATABASE `$name` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
                }
                var original: Throwable? = null
                try {
                    action(LocalScratchMysql(name, url, username, password))
                } catch (failure: Throwable) {
                    original = failure
                    throw failure
                } finally {
                    try {
                        admin.createStatement().use { it.executeUpdate("DROP DATABASE `$name`") }
                    } catch (cleanup: Throwable) {
                        if (original != null) original.addSuppressed(cleanup) else throw cleanup
                    }
                }
            }
        }
    }
}
