package jp.co.translacat.languagelearning.shared.persistence

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** An accidental-target guard, not a replacement for database account permissions. */
internal object DatabaseTargetGuard {
    private val catalogPattern = Regex("[a-z][a-z0-9_]{0,63}")
    private val forbiddenCatalogs = setOf(
        "translacat", "mysql", "sys", "information_schema", "performance_schema",
    )

    fun requireAllowedCatalog(catalog: String) {
        require(catalogPattern.matches(catalog) && catalog !in forbiddenCatalogs) {
            "database.expectedCatalog must be a dedicated lower-case LL database, not Core/system databases."
        }
    }

    fun requireMatchingUrl(jdbcUrl: String, expectedCatalog: String) {
        requireAllowedCatalog(expectedCatalog)
        require(jdbcUrl.startsWith("jdbc:mysql://")) {
            "database.jdbcUrl must use a single-host jdbc:mysql:// URL."
        }
        val uri = try {
            URI(jdbcUrl.removePrefix("jdbc:"))
        } catch (_: Exception) {
            // Never include the URL: it may contain credentials in query parameters.
            throw IllegalArgumentException("database.jdbcUrl is not a valid MySQL JDBC URL.")
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "database.jdbcUrl must specify one host without embedded credentials or fragments."
        }
        require(uri.path == "/$expectedCatalog") {
            "database.jdbcUrl catalog must match database.expectedCatalog. No database is created automatically."
        }
        // These properties can change the session catalog or introduce side effects before the guard.
        val disallowedOptions = setOf("createdatabaseifnotexist", "sessionvariables", "user", "password")
        val queryKeys = try {
            uri.rawQuery.orEmpty().split('&').map {
                URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8).lowercase()
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("database.jdbcUrl contains an invalid option encoding.")
        }
        require(queryKeys.none { it in disallowedOptions }) {
            "Configure credentials separately; automatic database creation and sessionVariables are not allowed."
        }
    }

    fun requireSelectedCatalog(actualCatalog: String?, expectedCatalog: String) {
        requireAllowedCatalog(expectedCatalog)
        check(actualCatalog == expectedCatalog) {
            "The connected database does not match database.expectedCatalog; migrations were not started."
        }
    }
}
