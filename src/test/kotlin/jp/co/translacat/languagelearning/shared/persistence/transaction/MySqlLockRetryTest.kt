package jp.co.translacat.languagelearning.shared.persistence.transaction

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MySqlLockRetryTest {
    @Test
    fun `MySQL 데드락과 잠금 대기 실패만 재시도 대상으로 분류한다`() {
        assertTrue(MySqlLockRetry.isRetryable(SQLException("deadlock", "40001", 1213)))
        assertTrue(MySqlLockRetry.isRetryable(SQLException("lock timeout", "HY000", 1205)))
    }

    @Test
    fun `래핑된 원인과 연결된 SQLException도 확인한다`() {
        val nested = IllegalStateException(SQLException("deadlock", "40001", 1213))
        assertTrue(MySqlLockRetry.isRetryable(nested))
        val outer = SQLException("outer")
        outer.setNextException(SQLException("timeout", "HY000", 1205))
        assertTrue(MySqlLockRetry.isRetryable(outer))
    }

    @Test
    fun `중복키 FK 통신오류와 일반오류는 재시도하지 않는다`() {
        for (failure in listOf(
            SQLException("duplicate", "23000", 1062), SQLException("FK", "23000", 1452),
            SQLException("connection", "08S01", 0), SQLException("unknown", "HY000", 0),
            IllegalStateException("application error"),
        )) assertFalse(MySqlLockRetry.isRetryable(failure))
    }

    @Test
    fun `벤더 코드나 SQLSTATE 한 가지만 맞는 오류는 재시도하지 않는다`() {
        assertFalse(MySqlLockRetry.isRetryable(SQLException("other", "40001", 999)))
        assertFalse(MySqlLockRetry.isRetryable(SQLException("other", "08S01", 1213)))
    }

    @Test
    fun `통신 실패가 함께 있으면 잠금 오류가 있어도 재시도하지 않는다`() {
        val failure = SQLException("lock", "40001", 1213)
        failure.setNextException(SQLException("connection", "08007", 0))
        assertFalse(MySqlLockRetry.isRetryable(failure))
    }

    @Test
    fun `순환하는 예외 원인에서도 무한루프에 빠지지 않는다`() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)
        assertFalse(MySqlLockRetry.isRetryable(first))
    }
}
