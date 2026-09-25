package jp.co.translacat.languagelearning.features.growth.application

import jp.co.translacat.languagelearning.features.growth.domain.exception.GrowthConflict
import jp.co.translacat.languagelearning.features.growth.domain.model.*
import jp.co.translacat.languagelearning.features.growth.domain.policy.GrowthPolicy
import jp.co.translacat.languagelearning.support.MemoryGrowthUnitOfWork
import kotlinx.coroutines.*
import java.time.*
import kotlin.test.*

class GrowthProjectionTest {
    private val now = LocalDateTime.parse("2026-09-25T10:00:00")
    private val day = now.toLocalDate()
    private val source = "1ae93ac7-179b-4700-9edb-eb001461f033"
    private fun change(score: Double = 80.0, date: LocalDate = day, difficulty: String = "NORMAL", keys: List<String> = listOf("work")) = GrowthChange.WritingScored(date, difficulty, List(5) { score }, mapOf("STRENGTH" to listOf(" clear ")), keys)
    private fun event(sequence: Long = 1, user: Long = 123, key: String = "EVAL:1", change: GrowthChange = change(), suffix: String = "a") = GrowthEvent(source, "event-$sequence-$user-$suffix", user, sequence, now.toString()+"Z", "payload-$suffix", "envelope-$sequence-$user-$suffix", listOf(GrowthOperation(key, "op-$key-$suffix", change)), now)
    private fun baseline(work: MemoryGrowthUnitOfWork, date: LocalDate = day) { ApplyLevelBaseline(work.state).execute(123, "completion-1", 83.0, date, now.minusMinutes(8), now) }
    private fun activity(status: String = "EVALUATING", source: String = "SPEAKING", ref: String = "51") = GrowthActivity(userId=123,source=source,referenceId=ref,learningDate=day,title="연습",durationSeconds=60,status=status,startedAt=now.minusMinutes(1),completedAt=now,createdAt=now,updatedAt=now)

    @Test fun `Writing 점수 signal mastery와 수신 이력을 한 번에 반영한다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork(); baseline(work); val e=event(); val accept=AcceptGrowthBatch(work,source)
        assertEquals("APPLIED",accept.execute(e).outcome); assertEquals("DUPLICATE",accept.execute(e).outcome)
        assertEquals(1,work.state.profile(123)!!.evaluationCount); assertEquals(80.0,work.state.mastery(123,"work")!!.score)
        assertEquals(1,work.state.signal(123,"STRENGTH","clear")!!.occurrenceCount)
        assertEquals(1,work.state.receipts.size); assertEquals(1L,work.state.sequences[source to 123L])
        assertEquals(1,work.state.activities.size) // 원본 Writing은 Activity를 별도로 만들지 않는다.
    } }
    @Test fun `한 batch의 뒤 명령이 실패하면 앞 점수와 inbox도 모두 롤백한다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork(); baseline(work);val e=event(); val invalid=GrowthOperation("bad","bad",GrowthChange.SignalsTouched("INVALID", listOf("a")))
        assertFailsWith<IllegalArgumentException>{AcceptGrowthBatch(work,source).execute(e.copy(operations=e.operations+invalid))}
        assertEquals(0,work.state.profile(123)!!.evaluationCount); assertTrue(work.state.masteries.isEmpty());assertTrue(work.state.receipts.isEmpty())
        assertEquals("APPLIED",AcceptGrowthBatch(work,source).execute(e).outcome)
    } }
    @Test fun `응답 유실 뒤 동시 재전달은 정확히 한 번만 계산한다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork(); baseline(work);val accept=AcceptGrowthBatch(work,source);val e=event()
        val outcomes=(1..20).map { async(Dispatchers.Default) { accept.execute(e).outcome } }.awaitAll()
        assertEquals(1,outcomes.count {it=="APPLIED"});assertEquals(19,outcomes.count {it=="DUPLICATE"});assertEquals(1,work.state.profile(123)!!.evaluationCount)
    } }
    @Test fun `다른 envelope라도 같은 평가 operation은 중복 집계하지 않는다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork();val accept=AcceptGrowthBatch(work,source);val e=event();accept.execute(e)
        accept.execute(e.copy(eventId="next",sequence=2,envelopeHash="next"))
        assertEquals(1,work.state.profile(123)!!.evaluationCount);assertEquals(2L,work.state.sequences[source to 123L])
    } }
    @Test fun `같은 operation ID의 다른 내용은 충돌이며 순번을 소비하지 않는다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork();val accept=AcceptGrowthBatch(work,source);accept.execute(event())
        assertEquals("GROWTH_OPERATION_CONFLICT",assertFailsWith<GrowthConflict>{accept.execute(event(2,suffix="changed"))}.code)
        assertEquals(1L,work.state.sequences[source to 123L]);assertEquals(1,work.state.profile(123)!!.evaluationCount)
    } }
    @Test fun `같은 event ID로 사용자나 본문을 바꾸면 충돌한다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork();val accept=AcceptGrowthBatch(work,source);val e=event();accept.execute(e)
        assertFailsWith<GrowthConflict>{accept.execute(e.copy(envelopeHash="different"))}
        assertFailsWith<GrowthConflict>{accept.execute(e.copy(userId=456,envelopeHash="other-user"))}
        assertNull(work.state.profile(456))
    } }
    @Test fun `역순 및 다른 source 이벤트는 앞 결과를 건너뛰지 않는다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork();val accept=AcceptGrowthBatch(work,source)
        assertFailsWith<GrowthConflict>{accept.execute(event(2))};assertFailsWith<GrowthConflict>{accept.execute(event().copy(sourceInstanceId="wrong"))}
        assertTrue(work.state.profiles.isEmpty());assertTrue(work.state.receipts.isEmpty())
    } }
    @Test fun `다른 사용자의 동일 operation key는 독립적이다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork();val accept=AcceptGrowthBatch(work,source);accept.execute(event());accept.execute(event(user=456))
        assertEquals(1,work.state.profile(123)!!.evaluationCount);assertEquals(1,work.state.profile(456)!!.evaluationCount)
    } }
    @Test fun `미커밋 preview는 점수를 보여줘도 영속 상태를 바꾸지 않는다`() {
        val work=MemoryGrowthUnitOfWork();baseline(work);val view=GrowthPreview(work.state);GrowthProjector(view).apply(123,change(),now)
        assertEquals(1,view.profile(123)!!.evaluationCount);assertEquals(0,work.state.profile(123)!!.evaluationCount)
        assertEquals(1,view.masteries(123,null,30).size);assertTrue(work.state.masteries.isEmpty());assertTrue(work.state.sequences.isEmpty())
    }
    @Test fun `보정 기간은 여섯째 날까지 유지하고 일곱째 날부터 ACTIVE다`() {
        val work=MemoryGrowthUnitOfWork();baseline(work);val p=work.state.profile(123)!!
        assertEquals("CALIBRATING",GrowthPolicy.prepare(p,day.plusDays(6),now).state)
        val active=GrowthPolicy.prepare(p,day.plusDays(7),now);assertEquals("ACTIVE",active.state);assertEquals(day.plusDays(7),active.calibrationCompletedDate)
    }
    @Test fun `기존 Core의 가중 누적 80 다음 20은 62다`() {
        val work=MemoryGrowthUnitOfWork();baseline(work);val first=GrowthPolicy.applyScores(work.state.profile(123)!!,change(),now)
        val second=GrowthPolicy.applyScores(first,change(20.0,day.plusDays(7)),now)
        assertEquals(62.0,second.meaningScore);assertEquals(62.0,second.grammarScore);assertEquals(2,second.evaluationCount);assertEquals(0.1,second.confidence)
        assertEquals("declining",second.trend)
    }
    @Test fun `세 난이도 성과는 독립적이고 평가 confidence는 1에서 멈춘다`() {
        var p=GrowthProfile(userId=123,createdAt=now,updatedAt=now)
        for ((index,d) in listOf("REVIEW","NORMAL","CHALLENGE").withIndex()) p=GrowthPolicy.applyScores(p,change(60.0+index*10,difficulty=d),now)
        assertEquals(60.0,p.reviewPerformance);assertEquals(70.0,p.normalPerformance);assertEquals(80.0,p.challengePerformance)
        repeat(30){p=GrowthPolicy.applyScores(p,change(),now)};assertEquals(1.0,p.confidence)
    }
    @Test fun `키워드 없는 평가는 기본 키워드나 선택 이력을 만들지 않는다`() {
        val work=MemoryGrowthUnitOfWork();GrowthProjector(work.state).apply(123,change(keys=emptyList()),now);assertTrue(work.state.masteries.isEmpty())
    }
    @Test fun `선택과 평가 횟수는 분리되고 첫 평가가 기본 50과 blend되지 않는다`() {
        val work=MemoryGrowthUnitOfWork();val p=GrowthProjector(work.state)
        p.apply(123,GrowthChange.KeywordsSelected(day,listOf("x")),now);assertEquals(50.0,work.state.mastery(123,"x")!!.score)
        p.apply(123,change(80.0,keys=listOf("x")),now)
        val m=work.state.mastery(123,"x")!!;assertEquals(80.0,m.score);assertEquals(1,m.selectedCount);assertEquals(1,m.evaluationCount);assertEquals(day,m.lastSelectedDate)
    }
    @Test fun `동일 signal과 keyword의 업무상 반복은 한 전달 안에서도 원본대로 누적한다`() {
        val work=MemoryGrowthUnitOfWork();val p=GrowthProjector(work.state)
        p.apply(123,GrowthChange.SignalsTouched("WEAKNESS",listOf(" x ","x"," ")),now);assertEquals(2,work.state.signal(123,"WEAKNESS","x")!!.occurrenceCount)
        p.apply(123,GrowthChange.KeywordsSelected(day,listOf("x","x")),now);assertEquals(2,work.state.mastery(123,"x")!!.selectedCount)
    }
    @Test fun `레벨 테스트 결과 재조회는 완료 Activity나 보정 시작일을 다시 만들지 않는다`() {
        val work=MemoryGrowthUnitOfWork();baseline(work);GrowthProjector(work.state).apply(123,GrowthChange.LearningPrepared(day.plusDays(8)),now.plusDays(8))
        baseline(work);assertEquals("ACTIVE",work.state.profile(123)!!.state);assertEquals(day,work.state.profile(123)!!.calibrationStartedDate);assertEquals(1,work.state.activities.size)
    }
    @Test fun `새 레벨 테스트는 skill signal mastery를 유지하며 기준점만 갱신한다`() {
        val work=MemoryGrowthUnitOfWork();baseline(work);GrowthProjector(work.state).apply(123,change(),now)
        ApplyLevelBaseline(work.state).execute(123,"completion-2",91.0,day.plusDays(1),now.plusDays(1),now.plusDays(1).plusMinutes(3))
        assertEquals(80.0,work.state.profile(123)!!.meaningScore);assertEquals(91.0,work.state.profile(123)!!.baseLevelScore)
        assertEquals(1,work.state.mastery(123,"work")!!.evaluationCount);assertEquals(2,work.state.activities.size)
    }
    @Test fun `레벨 테스트 기준점 충돌과 오래된 완료는 거부한다`() {
        val work=MemoryGrowthUnitOfWork();baseline(work);val p=ApplyLevelBaseline(work.state)
        assertFailsWith<GrowthConflict>{p.execute(123,"completion-1",1.0,day,now.minusMinutes(8),now)}
        assertFailsWith<GrowthConflict>{p.execute(123,"old",90.0,day,now.minusHours(3),now.minusHours(1))}
    }
    @Test fun `Speaking 공식 결과의 activity metric evidence는 함께 반영된다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork();val accept=AcceptGrowthBatch(work,source)
        val metric=GrowthMetric("FLUENCY","EVALUATED",75.0,0.9,null)
        val fact=GrowthChange.SpeakingScored(activity("EVALUATED").copy(overallScore=80.0,evaluationConfidence=0.9),listOf(metric),true,0.8,listOf(EvidenceFact("FLUENCY"," hesitation ","weakness",0.9,"연결 연습")))
        val e=event(change=fact);accept.execute(e);accept.execute(e)
        val a=work.state.activity(123,"SPEAKING","51")!!;assertEquals(listOf(metric),work.state.metrics(a.id))
        val evidence=work.state.evidence(123,"SPEAKING","hesitation","WEAKNESS")!!;assertEquals(1,evidence.evidenceCount);assertEquals(0.8,evidence.weightedEvidence)
        assertNull(work.state.profile(123)) // Speaking evidence는 Writing 점수를 바꾸지 않는다.
    } }
    @Test fun `근거 부족 Speaking은 공식 metric과 evidence를 만들지 않는다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork();val fact=GrowthChange.SpeakingScored(activity("INSUFFICIENT_EVIDENCE"),listOf(GrowthMetric("FLUENCY","EVALUATED",70.0,0.2,null)),false,0.0,listOf(EvidenceFact("FLUENCY","x",null,0.2,null)))
        AcceptGrowthBatch(work,source).execute(event(change=fact));assertTrue(work.state.metricRows.isEmpty());assertTrue(work.state.evidence.isEmpty())
        assertEquals("INSUFFICIENT_EVIDENCE",work.state.activity(123,"SPEAKING","51")!!.status)
    } }
    @Test fun `코칭은 완료 Activity만 만들며 점수 Profile과 분리된다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork();val fact=GrowthChange.ActivityRecorded(activity("COMPLETED").copy(metadataJson="{\"resultKind\":\"SESSION_COACHING\"}"),null)
        AcceptGrowthBatch(work,source).execute(event(change=fact));assertNull(work.state.profile(123));assertTrue(work.state.evidence.isEmpty());assertTrue(work.state.metricRows.isEmpty())
    } }
    @Test fun `확정 Activity를 늦은 상태 이벤트가 되돌리면 batch를 거부한다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork();val accept=AcceptGrowthBatch(work,source)
        accept.execute(event(change=GrowthChange.ActivityRecorded(activity("EVALUATED").copy(overallScore=80.0,evaluationConfidence=0.9),null)))
        assertFailsWith<GrowthConflict>{accept.execute(event(2,key="STATE:2",change=GrowthChange.ActivityRecorded(activity(),null)))}
        assertEquals("EVALUATED",work.state.activity(123,"SPEAKING","51")!!.status);assertEquals(1L,work.state.sequences[source to 123L])
    } }
    @Test fun `Activity 소유자와 고정 식별 정보는 변경할 수 없다`() {
        val work=MemoryGrowthUnitOfWork();val p=GrowthProjector(work.state);p.apply(123,GrowthChange.ActivityRecorded(activity(),null),now)
        assertFailsWith<IllegalArgumentException>{p.apply(456,GrowthChange.ActivityRecorded(activity(),null),now)}
        assertFailsWith<GrowthConflict>{p.apply(123,GrowthChange.ActivityRecorded(activity().copy(startedAt=now),null),now)}
    }
    @Test fun `evidence confidence는 근거 횟수 평균이고 weight는 합산한다`() {
        val e=GrowthEvidence(123,"SPEAKING","FLUENCY","x","WEAKNESS",1,0.8,0.9,"old",now,now,now)
        val n=GrowthPolicy.touched(e,EvidenceFact(null,"x",null,0.5," "),0.6,now.plusSeconds(1))
        assertEquals(2,n.evidenceCount);assertEquals(1.4,n.weightedEvidence,0.000001);assertEquals(0.7,n.averageConfidence,0.000001);assertEquals("old",n.recommendedFocus);assertEquals("FLUENCY",n.metricType)
    }
    @Test fun `비활성 learner는 수신 이력도 증가시키지 않는다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork();work.state.inactive.add(123)
        assertFailsWith<jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException>{AcceptGrowthBatch(work,source).execute(event())}
        assertTrue(work.state.receipts.isEmpty())
    } }
    @Test fun `watermark 조회는 미반영 상태에서 값 대신 명시적 pending 오류를 낸다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork();val q=QueryGrowth(work,source)
        val failure=assertFailsWith<jp.co.translacat.languagelearning.features.growth.domain.exception.GrowthPending>{q.snapshot(123,source,1,emptyList(),null)}
        assertEquals(1L,failure.required);assertEquals(0L,failure.applied)
        AcceptGrowthBatch(work,source).execute(event());assertEquals(1L,q.snapshot(123,source,1,emptyList(),null).sequence)
    } }
    @Test fun `query preview는 이미 커밋된 semantic operation을 두 번 적용하지 않는다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork();val e=event();AcceptGrowthBatch(work,source).execute(e)
        val view=QueryGrowth(work,source).snapshot(123,source,1,e.operations,null)
        assertTrue(view.preview);assertEquals(1,view.profile!!.evaluationCount);assertEquals(1,work.state.profile(123)!!.evaluationCount)
    } }
    @Test fun `activity 조회는 사용자 날짜 source 및 커서 경계를 유지한다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork()
        repeat(27){n -> work.state.saveActivity(activity("COMPLETED",ref=n.toString())) }
        work.state.saveActivity(activity("COMPLETED",ref="other").copy(userId=456))
        val q=QueryGrowth(work,source);val first=q.activities(123,source,0,"SPEAKING",day,day,0)
        assertEquals(25,first.activities.size);val second=q.activities(123,source,0,"SPEAKING",day,day,first.nextAfterId!!)
        assertEquals(2,second.activities.size);assertNull(second.nextAfterId)
        assertTrue(q.activities(123,source,0,"READING",day,day,0).activities.isEmpty())
    } }
    @Test fun `Core DB 시간의 일 마이크로초 정밀도 차이는 상태 전이를 막지 않는다`() {
        val work=MemoryGrowthUnitOfWork();val p=GrowthProjector(work.state);p.apply(123,GrowthChange.ActivityRecorded(activity(),null),now)
        p.apply(123,GrowthChange.ActivityRecorded(activity("EVALUATION_FAILED").copy(startedAt=activity().startedAt.plusNanos(1000)),null),now)
        assertEquals("EVALUATION_FAILED",work.state.activity(123,"SPEAKING","51")!!.status)
        assertEquals(activity().startedAt,work.state.activity(123,"SPEAKING","51")!!.startedAt)
    }

    @Test fun `native 레벨 완료도 활동 pagination revision을 바꾼다`() { runBlocking {
        val work=MemoryGrowthUnitOfWork();val q=QueryGrowth(work,source)
        val before=q.activities(123,source,0,null,day,day,0)
        baseline(work)
        val after=q.activities(123,source,0,null,day,day,0)
        assertEquals(before.sequence,after.sequence);assertNotEquals(before.projectionRevision,after.projectionRevision)
    } }
}
