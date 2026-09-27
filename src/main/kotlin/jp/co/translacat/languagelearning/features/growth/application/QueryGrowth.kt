package jp.co.translacat.languagelearning.features.growth.application

import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthActivityPage
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthSnapshot
import jp.co.translacat.languagelearning.features.growth.domain.policy.GrowthPolicy
import java.time.LocalDate

/** 현재 LL 트랜잭션에 커밋된 성장 자료만 조회한다. 조회는 학습자를 생성하지 않는다. */
internal class QueryGrowth(private val work: GrowthUnitOfWork) {
    suspend fun snapshot(userId: Long, keys: List<String>?): GrowthSnapshot {
        require(userId > 0 && (keys == null || keys.size <= 500))

        return work.read {
            requireActiveIfPresent(userId)
            GrowthSnapshot(
                userId, records.profile(userId), records.masteries(userId, keys, if (keys == null) 100 else 500),
                GrowthPolicy.signalTypes.associateWith { records.signals(userId, it, 100) },
            )
        }
    }

    suspend fun activities(
        userId: Long, source: String?, from: LocalDate, to: LocalDate, after: Long,
    ): GrowthActivityPage {
        require(userId > 0 && (source == null || source in GrowthPolicy.sources))
        require(!to.isBefore(from) && after >= 0)

        // 페이지와 버전을 같은 읽기 트랜잭션에서 구해 페이지 사이의 변경을 감지한다.
        return work.read {
            requireActiveIfPresent(userId)
            val revision = records.activityRevision(userId)
            val values = records.activities(userId, source, from, to, after, 26)
            val page = values.take(25)
            GrowthActivityPage(
                userId, page.map { it to records.metrics(it.id) },
                if (values.size > 25) page.last().id else null, revision.toString(),
            )
        }
    }
}
