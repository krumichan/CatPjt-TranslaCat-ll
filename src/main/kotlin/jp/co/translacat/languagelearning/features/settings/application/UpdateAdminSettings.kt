package jp.co.translacat.languagelearning.features.settings.application

import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettingsChange
import jp.co.translacat.languagelearning.features.settings.domain.policy.AdminSettingsPolicy

/** 어댑터가 ADMIN을 검증한 후 호출한다. 변경과 before/after 감사 기록은 한 트랜잭션이다. */
internal class UpdateAdminSettings(private val unitOfWork: AdminSettingsUnitOfWork) {
    suspend fun execute(adminUserId: Long, change: AdminSettingsChange): AdminSettings {
        require(adminUserId > 0) { "관리자 식별자는 양수여야 합니다." }
        return unitOfWork.execute {
            val before = settings.loadForUpdate()
            val next = AdminSettingsPolicy.change(before, change)
            val saved = if (next == before) before else settings.save(next, adminUserId, nowUtc)
            // BE와 같이 값이 같거나 빈 PATCH여도 관리자의 변경 시도를 기록한다.
            settings.appendAudit(adminUserId, before, saved, nowUtc)
            saved
        }
    }
}
