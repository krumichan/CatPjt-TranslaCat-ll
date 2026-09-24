package jp.co.translacat.languagelearning.shared.persistence.transaction

import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.SQLException
import kotlin.time.Duration.Companion.milliseconds

/**
 * 명시적인 DB 하나에 대한 짧은 JDBC 트랜잭션만 실행한다.
 * 내부 블록은 DB 작업만 허용하며, 네트워크 호출과 중첩 트랜잭션 실행을 넣지 않는다.
 */
internal class JdbcTransactionRunner(
    private val database: Database,
    maximumConcurrency: Int,
    private val maximumLockAttempts: Int = 3,
    private val queryTimeoutSeconds: Int = 10,
) {
    init {
        require(maximumConcurrency > 0)
        require(maximumLockAttempts in 1..3)
        require(queryTimeoutSeconds > 0)
    }

    // 공유 IO 풀의 실행 동시성만 제한한다. 별도 executor의 종료 관리는 필요 없다.
    private val dispatcher = Dispatchers.IO.limitedParallelism(maximumConcurrency)

    /** 수동 조회도 명시적인 DB/실행 컨텍스트를 사용하며, 기존 트랜잭션에 중첩하지 않는다. */
    suspend fun <T> read(block: () -> T): T {
        check(TransactionManager.currentOrNull() == null) { "실행 중인 트랜잭션에 읽기 작업을 중첩할 수 없습니다." }
        return withContext(dispatcher) {
            val context = currentCoroutineContext()
            check(TransactionManager.currentOrNull() == null) { "JDBC 실행 스레드에 트랜잭션이 남아 있습니다." }
            transaction(db = database, readOnly = true) {
                maxAttempts = 1
                queryTimeout = queryTimeoutSeconds
                context.ensureActive()
                val result = block()
                context.ensureActive()
                result
            }
        }
    }

    suspend fun <T> write(block: () -> T): T {
        check(TransactionManager.currentOrNull() == null) {
            "이미 실행 중인 JDBC 트랜잭션 안에서 새 UnitOfWork를 열 수 없습니다."
        }
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return withContext(dispatcher) {
                    val context = currentCoroutineContext()
                    check(TransactionManager.currentOrNull() == null) {
                        "JDBC 실행 스레드에 예상하지 못한 트랜잭션이 남아 있습니다."
                    }
                    transaction(db = database, readOnly = false) {
                        // Exposed의 광범위한 SQL 재시도를 끄고, 아래에서 잠금 실패만 제한적으로 재시도한다.
                        maxAttempts = 1
                        queryTimeout = queryTimeoutSeconds
                        context.ensureActive()
                        val result = block()
                        // JDBC 실행 중 취소된 요청은 가능한 한 commit 전에 감지한다.
                        context.ensureActive()
                        result
                    }
                }
            } catch (failure: SQLException) {
                attempt += 1
                if (attempt >= maximumLockAttempts || !MySqlLockRetry.isRetryable(failure)) throw failure
                // 이미 rollback/connection 반환이 끝난 뒤 대기한다.
                delay((25L * attempt).milliseconds)
            }
        }
    }
}
