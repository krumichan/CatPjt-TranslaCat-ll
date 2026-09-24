package jp.co.translacat.languagelearning.support

import jp.co.translacat.languagelearning.features.resultjournal.domain.model.IncomingLearningResult
import jp.co.translacat.languagelearning.features.resultjournal.domain.model.ResultKind
import java.util.*

internal object ResultJournalFixtures {
    const val SOURCE = "4b8abfea-a0d1-4458-95e8-66cb5e68d9a0"
    const val PAYLOAD =
        """{"resultKind":"SCORED_EVALUATION","answerId":123,"response":{"scores":{"overall":80,"meaning":80,"grammar":80,"vocabulary":80,"naturalness":80,"expression":80}}}"""

    fun event(sequence: Long = 1, userId: Long = 123, payload: String = PAYLOAD) = IncomingLearningResult(
        1, SOURCE, UUID.randomUUID().toString(), userId, sequence, ResultKind.WRITING_SCORED,
        "123", "2026-09-24T03:00:00.123456Z", payload, IncomingLearningResult.hash(payload),
    )
}
