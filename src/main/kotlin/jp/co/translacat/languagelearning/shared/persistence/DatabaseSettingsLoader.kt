package jp.co.translacat.languagelearning.shared.persistence

import io.ktor.server.application.Application

fun Application.loadDatabaseSettings(): DatabaseSettings {
    val config = environment.config

    return DatabaseSettings(
        enabled = config.property("database.enabled").getString().toBooleanStrict(),

        jdbcUrl = config.property("database.jdbcUrl").getString(),
        username = config.property("database.username").getString(),
        password = config.property("database.password").getString(),

        maximumPoolSize = config.property("database.maximumPoolSize").getString().toInt(),
        minimumIdle = config.property("database.minimumIdle").getString().toInt(),
        connectionTimeoutMs = config.property("database.connectionTimeoutMs").getString().toLong(),
    )
}