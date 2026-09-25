package jp.co.translacat.languagelearning.shared.persistence.transaction

import java.sql.SQLException
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.collections.ArrayDeque

/** 데드락/잠금 대기 실패만 재시도한다. 통신 실패와 불명확한 commit 결과는 재시도하지 않는다. */
internal object MySqlLockRetry {
    fun isRetryable(failure: Throwable): Boolean {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val remaining = ArrayDeque<Throwable>()
        remaining.add(failure)
        var lockFailure = false
        while (remaining.isNotEmpty()) {
            val current = remaining.removeFirst()
            if (!visited.add(current)) continue
            if (current is SQLException) {
                // 예외 체인에 통신/commit 불명확성이 섞여 있으면 잠금 오류보다 우선해 재시도를 막는다.
                if (current.sqlState.orEmpty().startsWith("08") || current.errorCode in setOf(2006, 2013)) return false
                if (current.errorCode == 1213 && current.sqlState == "40001") lockFailure = true
                if (current.errorCode == 1205 && current.sqlState == "HY000") lockFailure = true
                current.nextException?.let { remaining.add(it) }
            }
            current.cause?.let { remaining.add(it) }
        }
        return lockFailure
    }
}
