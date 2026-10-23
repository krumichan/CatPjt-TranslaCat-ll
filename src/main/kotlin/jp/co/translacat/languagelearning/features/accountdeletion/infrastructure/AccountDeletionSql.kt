package jp.co.translacat.languagelearning.features.accountdeletion.infrastructure

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import java.sql.ResultSet

/** 호출자는 JdbcTransactionRunner의 소유 블록 안에서만 사용한다. 모든 외부 값은 bind한다. */
internal object AccountDeletionSql {
    private fun connection(): Connection = TransactionManager.current().connection.connection as Connection

    fun update(sql: String, vararg values: Any?): Int = connection().prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }

    fun <T> query(sql: String, vararg values: Any?, map: (ResultSet) -> T): List<T> =
        connection().prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result -> buildList { while (result.next()) add(map(result)) } }
        }

    fun count(sql: String, vararg values: Any?): Int = query(sql, *values) { it.getInt(1) }.single()
}
