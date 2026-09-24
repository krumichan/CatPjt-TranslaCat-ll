package jp.co.translacat.languagelearning.features.settings.domain.policy

import jp.co.translacat.languagelearning.features.settings.domain.model.AdminSettingsChange
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.support.SettingsFixtures as F
import kotlin.test.*

class AdminSettingsPolicyTest {
    @Test fun `기본 seed는 관리자 변경 검증을 통과한다`() {
        AdminSettingsPolicy.validate(F.admin())
        assertEquals(F.admin(),AdminSettingsPolicy.change(F.admin(),AdminSettingsChange()))
    }
    @Test fun `부분 업데이트는 누락된 필드를 보존한다`() {
        val result=AdminSettingsPolicy.change(F.admin(),AdminSettingsChange(defaultDailySentenceCount=7,aiEvaluationEnabled=false))
        assertEquals(7,result.defaultDailySentenceCount);assertFalse(result.aiEvaluationEnabled)
        assertEquals(F.admin().maxDailySentenceCount,result.maxDailySentenceCount)
    }
    @Test fun `범위와 기본값은 최종 조합으로 검증한다`() {
        assertFailsWith<LearningBusinessException> { AdminSettingsPolicy.change(F.admin(),AdminSettingsChange(minDailySentenceCount=10)) }
        val result=AdminSettingsPolicy.change(F.admin(),AdminSettingsChange(minDailySentenceCount=10,defaultDailySentenceCount=10))
        assertEquals(10,result.defaultDailySentenceCount)
    }
    @Test fun `모든 관리자 범위의 거부 경계를 검증한다`() {
        val cases=listOf(
            AdminSettingsChange(minDailySentenceCount=0),AdminSettingsChange(maxDailySentenceCount=101),
            AdminSettingsChange(dailyKeywordMaxCount=-1),AdminSettingsChange(dailyKeywordMaxCount=21),
            AdminSettingsChange(reviewAvailableDays=0),AdminSettingsChange(reviewAvailableDays=366),
            AdminSettingsChange(levelRecheckRecommendationDays=0),AdminSettingsChange(levelRecheckRecommendationDays=3651),
            AdminSettingsChange(minDailySpeakingGoalMinutes=0),AdminSettingsChange(maxDailySpeakingGoalMinutes=2),
            AdminSettingsChange(defaultDailySpeakingGoalMinutes=21),AdminSettingsChange(dailySpeakingHardLimitMinutes=19),
            AdminSettingsChange(dailySpeakingHardLimitMinutes=241),AdminSettingsChange(dailySpeakingSessionLimit=0),
            AdminSettingsChange(dailySpeakingSessionLimit=101),AdminSettingsChange(maxSessionMinutes=0),AdminSettingsChange(maxSessionMinutes=11),
            AdminSettingsChange(maxTurnsPerSession=0),AdminSettingsChange(maxTurnsPerSession=21),
            AdminSettingsChange(minValidAudioSeconds=0.09),AdminSettingsChange(minValidAudioSeconds=10.1),
            AdminSettingsChange(minValidAudioSeconds=Double.NaN),AdminSettingsChange(minValidAudioSeconds=Double.POSITIVE_INFINITY),
            AdminSettingsChange(maxTurnAudioSeconds=0),AdminSettingsChange(maxTurnAudioSeconds=61),
            AdminSettingsChange(maxAudioFileBytes=1023),AdminSettingsChange(maxAudioFileBytes=10L*1024*1024+1),
            AdminSettingsChange(rawAudioRetentionDays=0),AdminSettingsChange(rawAudioRetentionDays=366),
            AdminSettingsChange(reportedAudioRetentionDays=0),AdminSettingsChange(reportedAudioRetentionDays=366),
            AdminSettingsChange(automaticRetryLimitPerStage=-1),AdminSettingsChange(automaticRetryLimitPerStage=3),
            AdminSettingsChange(manualRetryLimitPerStage=-1),AdminSettingsChange(manualRetryLimitPerStage=2),
            AdminSettingsChange(sttTimeoutSeconds=0),AdminSettingsChange(ttsTimeoutSeconds=0),AdminSettingsChange(evaluationTimeoutSeconds=0),
            AdminSettingsChange(activeSessionResumeHours=0),AdminSettingsChange(activeSessionResumeHours=25),
            AdminSettingsChange(levelTestQuestionPoolTargetSize=99),AdminSettingsChange(levelTestQuestionPoolTargetSize=100001),
        )
        cases.forEach { assertEquals(UserSettingsPolicy.INVALID,assertFailsWith<LearningBusinessException> {
            AdminSettingsPolicy.change(F.admin(),it)
        }.code) }
    }
    @Test fun `nullable legacy 문제풀 설정은 BE처럼 해석한다`() {
        val legacy=F.admin().copy(levelTestQuestionPoolTargetSize=null,levelTestQuestionPoolReplenishmentEnabled=null)
        assertEquals(1000,legacy.resolvedQuestionPoolTarget());assertFalse(legacy.resolvedQuestionPoolReplenishment())
        val changed=AdminSettingsPolicy.change(legacy,AdminSettingsChange())
        assertEquals(1000,changed.levelTestQuestionPoolTargetSize);assertNull(changed.levelTestQuestionPoolReplenishmentEnabled)
    }
    @Test fun `원본에 상한이 없는 timeout에 임의 상한을 추가하지 않는다`() {
        val updated=AdminSettingsPolicy.change(F.admin(),AdminSettingsChange(sttTimeoutSeconds=Int.MAX_VALUE,ttsTimeoutSeconds=1,evaluationTimeoutSeconds=1))
        assertEquals(Int.MAX_VALUE,updated.sttTimeoutSeconds)
    }
    @Test fun `보관 기간과 오디오 길이 관계도 검증한다`() {
        assertFailsWith<LearningBusinessException> { AdminSettingsPolicy.change(F.admin(),AdminSettingsChange(rawAudioRetentionDays=100,reportedAudioRetentionDays=99)) }
        assertFailsWith<LearningBusinessException> { AdminSettingsPolicy.change(F.admin(),AdminSettingsChange(minValidAudioSeconds=10.0,maxTurnAudioSeconds=9)) }
    }
}
