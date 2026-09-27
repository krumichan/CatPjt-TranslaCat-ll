package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingFailure
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingSttReportRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.util.*

@Serializable
internal enum class SpeakingSttReportType { WRONG_TEXT, MISSING_TEXT, LANGUAGE_MISMATCH, OTHER }

@Serializable
internal data class SpeakingSttReportRequest(
    val reportType: SpeakingSttReportType? = null,
    val expectedText: String? = null,
    val audioAnalysisConsent: Boolean = false,
    val clientAudioMetadata: JsonObject? = null,
    val supportRequested: Boolean = false,
)

internal class SpeakingSttReportService(private val work: SpeakingUnitOfWork) {
    suspend fun create(
        userId: Long, sessionId: Long, turnId: Long, request: SpeakingSttReportRequest,
    ): SpeakingSttReportRecord = work.write(userId) {
        if (request.reportType == null) throw SpeakingFailure("STT_REPORT_NOT_FOUND")
        val session = records.session(userId, sessionId) ?: throw SpeakingFailure("SESSION_NOT_FOUND")
        val turn = records.turn(sessionId, turnId) ?: throw SpeakingFailure("TURN_NOT_FOUND")

        // 분석 동의가 있고 실제 보관 참조가 있는 경우에만 원본과 같은 신고 보관 기간을 연장한다.
        val audio = records.audio(sessionId, turnId, "USER")
        val retention = if (request.audioAnalysisConsent && audio != null)
            nowUtc.plusDays(session.snapshot.policy.reportedAudioRetentionDays.toLong()) else null
        if (audio != null && retention != null && audio.retentionUntil.isBefore(retention)) {
            records.saveAudio(audio.copy(retentionUntil = retention))
        }
        val reference = "STT-" + UUID.randomUUID().toString().replace("-", "").take(16).uppercase(Locale.ROOT)
        records.saveReport(
            SpeakingSttReportRecord(
                userId = userId, sessionId = sessionId, turnId = turnId,
                reference = reference, reportType = request.reportType.name,
                expectedText = request.expectedText?.trim()?.take(4000),
                audioAnalysisConsent = request.audioAnalysisConsent, audioRetentionUntil = retention,
                sttMetadata = turn.content.sttMetadata, clientMetadata = request.clientAudioMetadata ?: JsonNull,
                supportRequested = request.supportRequested,
                supportReference = if (request.supportRequested) "SUP-${reference.substring(4)}" else null,
                createdAt = nowUtc,
            ),
        )
    }

    suspend fun get(userId: Long, reportId: Long): SpeakingSttReportRecord = work.read {
        records.reportById(userId, reportId) ?: throw SpeakingFailure("STT_REPORT_NOT_FOUND")
    }

    suspend fun support(userId: Long, reportId: Long): SpeakingSttReportRecord = work.write(userId) {
        val report = records.reportById(userId, reportId) ?: throw SpeakingFailure("STT_REPORT_NOT_FOUND")
        if (report.supportRequested) report else records.saveReport(
            report.copy(
                supportRequested = true,
                supportReference = "SUP-${report.reference.substring(4)}",
            ),
        )
    }
}
