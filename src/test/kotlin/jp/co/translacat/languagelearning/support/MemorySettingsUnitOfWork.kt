package jp.co.translacat.languagelearning.support

import jp.co.translacat.languagelearning.features.learner.domain.model.Learner
import jp.co.translacat.languagelearning.features.learner.domain.repository.LearnerRepository
import jp.co.translacat.languagelearning.features.settings.application.SettingsTransaction
import jp.co.translacat.languagelearning.features.settings.application.SettingsUnitOfWork
import jp.co.translacat.languagelearning.features.settings.domain.model.*
import jp.co.translacat.languagelearning.features.settings.domain.repository.*
import java.time.LocalDateTime

/** 단위 테스트용이다. 실제 DB의 잠금/롤백은 별도 MySQL 테스트로 검증한다. */
internal class MemorySettingsUnitOfWork : SettingsUnitOfWork {
    var now = SettingsFixtures.now
    var policy = SettingsFixtures.policy()
    var learnerStatus = "ACTIVE"
    val learners = mutableSetOf<Long>()
    val rows = mutableMapOf<Long,UserSettings>()
    var saves = 0
    var failSave = false
    override suspend fun <T> execute(block: SettingsTransaction.() -> T): T {
        val oldRows=rows.toMap();val oldLearners=learners.toSet()
        try {
            return block(object : SettingsTransaction {
                override val nowUtc: LocalDateTime get() = now
                override val learners=object : LearnerRepository {
                    override fun ensureAndLock(userId:Long,nowUtc:LocalDateTime):Learner {
                        this@MemorySettingsUnitOfWork.learners += userId
                        return Learner(userId,learnerStatus,0,nowUtc,nowUtc)
                    }
                }
                override val policies=object : SettingsPolicyRepository {
                    override fun loadInitialPolicy() = policy
                }
                override val userSettings=object : UserSettingsRepository {
                    override fun findForUser(userId:Long) = rows[userId]
                    override fun create(settings:NewUserSettings):UserSettings = SettingsFixtures.user(settings.userId).copy(
                        dailySentenceCount=settings.dailySentenceCount,dailySpeakingGoalMinutes=settings.dailySpeakingGoalMinutes,
                        dailyListeningGoalCount=settings.dailyListeningGoalCount,createdAt=now,updatedAt=now,
                    ).also { rows[it.userId]=it }
                    override fun save(settings:UserSettings):UserSettings {
                        if(failSave) error("테스트 저장 실패")
                        saves++;rows[settings.userId]=settings;return settings
                    }
                }
            })
        } catch(failure:Throwable) {
            rows.clear();rows.putAll(oldRows);learners.clear();learners.addAll(oldLearners);throw failure
        }
    }
}
