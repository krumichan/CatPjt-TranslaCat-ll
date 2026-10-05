package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.writing.api.curatedWritingRoutes
import jp.co.translacat.languagelearning.features.writing.api.curatedWritingPlanRoutes
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingPlanLimits
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.CuratedWritingPlanStore
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingManifestCodec
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingApprovalCodec
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.CuratedWritingStore
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.CuratedWritingFeedbackWorker
import jp.co.translacat.languagelearning.shared.ai.HttpModelExecution
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner

/** 명시적인 활성화와 안전한 QA DB가 없으면 신규 후보팩을 사용자 경로에 연결하지 않는다. */
internal suspend fun Application.configureCuratedWriting() {
    fun setting(key: String): String? = environment.config.propertyOrNull(key)?.getString()
    if (setting("writing.curated.enabled")?.toBooleanStrictOrNull() != true) return
    val qaOnly = setting("writing.curated.qaOnly")?.toBooleanStrictOrNull() == true
    val database = dependencies.resolve<DatabaseSettings>()
    check(database.enabled && loadInternalApiSettings().enabled) { "Curated Writing에는 DB와 내부 인증이 필요합니다." }

    // 미승인 후보의 QA 공개는 전용 catalog와 loopback 바인딩이 모두 확인된 실행에만 허용한다.
    if (qaOnly) {
        check(database.expectedCatalog.startsWith("translacat_curated_qa_")) { "QA 전용 DB가 아닙니다." }
        check(Regex("^jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/").containsMatchIn(database.jdbcUrl)) {
            "QA DB는 loopback이어야 합니다."
        }
        check(setting("ktor.deployment.host") == "127.0.0.1") { "QA 서비스는 loopback에만 바인딩합니다." }
    }
    val source = checkNotNull(javaClass.classLoader.getResourceAsStream("writing/curated-candidates.json")) {
        "Curated Writing manifest가 없습니다."
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val manifest = CuratedWritingManifestCodec.parse(source)
    val approvalSource = checkNotNull(javaClass.classLoader.getResourceAsStream("writing/curated-approvals.json")) {
        "Curated Writing approval manifest가 없습니다."
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val approvals = CuratedWritingApprovalCodec.parse(approvalSource, manifest)
    val transactions = dependencies.resolve<JdbcTransactionRunner>()
    val store = CuratedWritingStore(transactions, manifest, approvals = approvals,
        approvalManifestHash = CuratedWritingManifestCodec.sha256(approvalSource))
    val settings = dependencies.resolve<SettingsServiceOperations>()
    store.importManifest()
    // DI resolve는 suspend 단계에서 마치고 routing 구성에서는 이미 생성된 worker만 연결한다.
    val feedbackEnabled = setting("writing.curated.feedback.enabled")?.toBooleanStrictOrNull() == true
    check(!feedbackEnabled || (qaOnly && setting("writing.curated.variableN.enabled")?.toBooleanStrictOrNull() == true)) {
        "개인화 피드백은 QA 전용 가변 계획에서만 허용합니다."
    }
    val feedback = if (feedbackEnabled) CuratedWritingFeedbackWorker(
        transactions, store, manifest.releaseId, dependencies.resolve<HttpModelExecution>(),
    ) else null
    routing {
        curatedWritingRoutes(store, settings, qaOnly)
        // 기존 flag만으로 가변 목표 경로를 노출하지 않는다. 명시적 opt-in 설정에서만 설치한다.
        if (setting("writing.curated.variableN.enabled")?.toBooleanStrictOrNull() == true) {
            val limits = CuratedWritingPlanLimits(
                maxTargetItemCount = setting("writing.curated.variableN.maxTargetItemCount")?.toInt() ?: 100,
                workerBatchSize = setting("writing.curated.variableN.workerBatchSize")?.toInt() ?: 20,
                pageSize = setting("writing.curated.variableN.pageSize")?.toInt() ?: 10,
            )
            // 유료 개인화 피드백은 QA 전용 명시 플래그와 기존 서비스 모델 연결이 함께 있을 때만 사용한다.
            curatedWritingPlanRoutes(CuratedWritingPlanStore(
                transactions, store, manifest.releaseId, limits, feedback = feedback), settings, qaOnly)
        }
    }
}
