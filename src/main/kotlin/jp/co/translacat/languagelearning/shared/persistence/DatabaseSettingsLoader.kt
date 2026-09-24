package jp.co.translacat.languagelearning.shared.persistence

import io.ktor.server.application.Application

fun Application.loadDatabaseSettings(): DatabaseSettings {
    val config = environment.config

    val enabled = config.property("database.enabled").getString().toBooleanStrict()

    if (!enabled) {
        return DatabaseSettings(
            enabled = false,
            jdbcUrl = "",
            username = "",
            password = "",
        )
    }

    return DatabaseSettings(
        enabled = true,

        jdbcUrl = config.property("database.jdbcUrl").getString(),
        username = config.property("database.username").getString(),
        password = config.property("database.password").getString(),

        maximumPoolSize = config.propertyOrNull("database.maximumPoolSize")?.getString()?.toInt()?: 10,
        minimumIdle = config.propertyOrNull("database.minimumIdle")?.getString()?.toInt()?: 2,
        connectionTimeoutMs = config.propertyOrNull("database.connectionTimeoutMs")?.getString()?.toLong()?: 5_000,
    )
}