package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.api.GrowthWire
import jp.co.translacat.languagelearning.features.growth.application.*
import jp.co.translacat.languagelearning.features.growth.domain.exception.*
import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.*
import jp.co.translacat.languagelearning.support.GrowthFixtures as F
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.flywaydb.core.Flyway
import java.time.*
import java.util.UUID
import kotlin.test.*

/** 실서버/AI 없이, 이 테스트가 생성한 무작위 로컬 DB만 사용한다. */
class GrowthPersistenceIntegrationTest {
    private val clock=Clock.fixed(Instant.parse("2026-09-25T10:01:00Z"),ZoneOffset.UTC)
    private fun work(factory:DatabaseFactory)=ExposedGrowthUnitOfWork(JdbcTransactionRunner(factory.database,4),clock)
    private fun number(db:LocalScratchMysql,sql:String):Long = db.connect().use{c->c.createStatement().use{s->s.executeQuery(sql).use{r->check(r.next());r.getLong(1)}}}
    private fun sql(db:LocalScratchMysql,statement:String) {db.connect().use{it.createStatement().use{s->s.executeUpdate(statement)}}}
    private fun event(operations:JsonArray,sequence:Long=1,id:String=UUID.randomUUID().toString()):JsonObject {
        val payload=buildJsonObject{put("operations",operations)}.toString()
        return JsonObject(F.envelope()+mapOf("eventId" to JsonPrimitive(id),"sequence" to JsonPrimitive(sequence),"payloadJson" to JsonPrimitive(payload),"payloadSha256" to JsonPrimitive(GrowthWire.hash(payload))))
    }
    private fun operations()=Json.parseToJsonElement(F.envelope().getValue("payloadJson").jsonPrimitive.content).jsonObject.getValue("operations").jsonArray

    @Test fun `V007의 완료 기준점과 설정을 보존하고 V008에서 로컬 성장 기준점을 만든다`() {
        LocalScratchMysql.use {db->
            val s=db.settings()
            Flyway.configure().dataSource(s.jdbcUrl,s.username,s.password).locations("classpath:db/migration").schemas(db.name).defaultSchema(db.name)
                .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).target("007").load().migrate()
            sql(db,"INSERT INTO language_learning_learner(user_id,status,identity_version,created_at,updated_at) VALUES(123,'ACTIVE',17,'2026-09-25 10:00:00','2026-09-25 10:00:00')")
            sql(db,"UPDATE language_learning_admin_setting SET daily_keyword_max_count=6 WHERE id='DEFAULT'")
            val id="aa9287bb-3f91-4ac4-ab79-ac43b1e0ea88"
            sql(db,"INSERT INTO language_learning_level_test_session(id,session_uid,user_id,session_type,status,origin_language,learning_language,timezone,current_question_number,current_complexity_band,base_level_score,proficiency_band,domain_scores_json,started_at,last_activity_at,completed_at,completed_date,idempotency_key) VALUES(1,'$id',123,'INITIAL','COMPLETED','ko','ja','Asia/Tokyo',20,4,83,'UPPER_INTERMEDIATE','{}','2026-09-25 09:00:00','2026-09-25 10:00:00','2026-09-25 10:00:00','2026-09-25','seed-1')")
            sql(db,"INSERT INTO language_learning_level_test_baseline(user_id,session_id,completion_id,session_type,base_level_score,proficiency_band,completed_date,started_at,completed_at) VALUES(123,1,'$id','INITIAL',83,'UPPER_INTERMEDIATE','2026-09-25','2026-09-25 09:00:00','2026-09-25 10:00:00')")
            DatabaseFactory(s).use{f->
                assertEquals(1,f.migrationReport.migrationsExecuted);assertEquals(CurrentSchema.VERSION,f.migrationReport.schemaVersion.toInt())
                assertEquals(CurrentSchema.TABLE_COUNT,number(db,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"))
                assertEquals(17,number(db,"SELECT identity_version FROM language_learning_learner WHERE user_id=123").toInt())
                assertEquals(6,number(db,"SELECT daily_keyword_max_count FROM language_learning_admin_setting WHERE id='DEFAULT'").toInt())
                runBlocking {
                    work(f).read {
                        val p=checkNotNull(records.profile(123));assertEquals(83.0,p.baseLevelScore);assertEquals(id,p.baselineCompletionId);assertEquals("CALIBRATING",p.state)
                        assertEquals(3600,records.activity(123,"LEVEL_TEST","LL_LEVEL_TEST:$id")!!.durationSeconds.toInt())
                    }
                    work(f).write(123){ ApplyLevelBaseline(records).execute(123,id,83.0,LocalDate.parse("2026-09-25"),LocalDateTime.parse("2026-09-25T09:00:00"),LocalDateTime.parse("2026-09-25T10:00:00")) }
                }
                assertEquals(1L,number(db,"SELECT COUNT(*) FROM language_learning_activity"));assertEquals(0L,number(db,"SELECT COUNT(*) FROM language_learning_growth_receipt"))
            }
            DatabaseFactory(s).use{assertEquals(0,it.migrationReport.migrationsExecuted)}
        }
    }
    @Test fun `서로 다른 pool의 동일 이벤트 동시 전달은 선택 횟수를 한 번만 늘린다`() {
        LocalScratchMysql.use{db->DatabaseFactory(db.settings()).use{a->DatabaseFactory(db.settings()).use{b->runBlocking{
            val event=GrowthWire.event(F.selectedEnvelope());val left=AcceptGrowthBatch(work(a),F.SOURCE);val right=AcceptGrowthBatch(work(b),F.SOURCE)
            val results=withTimeout(30_000){(1..16).map{n->async{(if(n%2==0)left else right).execute(event).outcome}}.awaitAll()}
            assertEquals(1,results.count{it=="APPLIED"});assertEquals(15,results.count{it=="DUPLICATE"})
            assertEquals(1L,number(db,"SELECT selected_count FROM language_learning_keyword_mastery WHERE user_id=123"))
            assertEquals(1L,number(db,"SELECT COUNT(*) FROM language_learning_growth_receipt"))
        }}}}
    }
    @Test fun `점수 signal mastery와 공식 Speaking metric evidence는 한 batch에서 저장된다`() {
        LocalScratchMysql.use{db->DatabaseFactory(db.settings()).use{f->runBlocking{
            val w=work(f);w.write(123){ApplyLevelBaseline(records).execute(123,"aa9287bb-3f91-4ac4-ab79-ac43b1e0ea88",83.0,LocalDate.parse("2026-09-25"),LocalDateTime.parse("2026-09-25T09:00:00"),LocalDateTime.parse("2026-09-25T10:00:00"))}
            assertEquals("APPLIED",AcceptGrowthBatch(w,F.SOURCE).execute(GrowthWire.event(F.envelope())).outcome)
            w.read{assertEquals(1,records.profile(123)!!.evaluationCount);assertEquals(80.0,records.profile(123)!!.meaningScore);assertEquals(1,records.mastery(123,"여행")!!.selectedCount)}
            assertEquals(1L,number(db,"SELECT COUNT(*) FROM language_learning_metric_history"))
            assertEquals(1L,number(db,"SELECT COUNT(*) FROM language_learning_profile_evidence"))
            assertEquals(6L,number(db,"SELECT COUNT(*) FROM language_learning_growth_operation"))
        }}}
    }
    @Test fun `batch 뒤의 업무 실패가 앞선 mastery와 learner inbox를 모두 롤백한다`() {
        LocalScratchMysql.use{db->DatabaseFactory(db.settings()).use{f->runBlocking{
            val original=operations();val bad=event(JsonArray(listOf(original[3],original[0])))
            assertFailsWith<GrowthConflict>{AcceptGrowthBatch(work(f),F.SOURCE).execute(GrowthWire.event(bad))}
            for(table in listOf("keyword_mastery","growth_receipt","growth_operation","growth_stream","learner")) assertEquals(0L,number(db,"SELECT COUNT(*) FROM language_learning_$table"),table)
        }}}
    }
    @Test fun `새 envelope에 중복 평가가 들어와도 업무 중복 반영은 없다`() {
        LocalScratchMysql.use{db->DatabaseFactory(db.settings()).use{f->runBlocking{
            val accept=AcceptGrowthBatch(work(f),F.SOURCE)
            accept.execute(GrowthWire.event(F.selectedEnvelope()))
            accept.execute(GrowthWire.event(F.selectedEnvelope(2,UUID.randomUUID().toString())))
            assertEquals(1L,number(db,"SELECT selected_count FROM language_learning_keyword_mastery WHERE user_id=123"))
            assertEquals(2L,number(db,"SELECT COUNT(*) FROM language_learning_growth_receipt"));assertEquals(1L,number(db,"SELECT COUNT(*) FROM language_learning_growth_operation"))
            assertFailsWith<GrowthConflict>{accept.execute(GrowthWire.event(F.selectedEnvelope(4,UUID.randomUUID().toString())))}
            assertEquals(2L,number(db,"SELECT last_sequence FROM language_learning_growth_stream WHERE user_id=123"))
        }}}
    }
    @Test fun `같은 semantic key의 다른 본문은 성장과 순번을 바꾸지 않는다`() {
        LocalScratchMysql.use{db->DatabaseFactory(db.settings()).use{f->runBlocking{
            val accept=AcceptGrowthBatch(work(f),F.SOURCE);accept.execute(GrowthWire.event(F.selectedEnvelope()))
            val op=operations()[3].jsonObject;val p=JsonObject(op.getValue("payload").jsonObject+mapOf("canonicalKeys" to JsonArray(listOf(JsonPrimitive("다른 키")))))
            assertFailsWith<GrowthConflict>{accept.execute(GrowthWire.event(event(JsonArray(listOf(JsonObject(op+mapOf("payload" to p)))),2)))}
            assertEquals(1L,number(db,"SELECT COUNT(*) FROM language_learning_keyword_mastery"));assertEquals(1L,number(db,"SELECT last_sequence FROM language_learning_growth_stream WHERE user_id=123"))
        }}}
    }
    @Test fun `미반영 watermark와 preview 조회는 DB를 변경하지 않는다`() {
        LocalScratchMysql.use{db->DatabaseFactory(db.settings()).use{f->runBlocking{
            val w=work(f);val query=QueryGrowth(w,F.SOURCE,clock);val op=GrowthWire.event(F.selectedEnvelope()).operations
            assertFailsWith<GrowthPending>{query.snapshot(123,F.SOURCE,1,emptyList(),null)}
            val view=query.snapshot(123,F.SOURCE,0,op,null);assertEquals(1,view.masteries.single().selectedCount)
            assertEquals(0L,number(db,"SELECT COUNT(*) FROM language_learning_keyword_mastery"));assertEquals(0L,number(db,"SELECT COUNT(*) FROM language_learning_learner"))
        }}}
    }
    @Test fun `비활성 learner를 재활성화하거나 조회로 우회하지 않는다`() {
        LocalScratchMysql.use{db->DatabaseFactory(db.settings()).use{f->
            sql(db,"INSERT INTO language_learning_learner VALUES(123,'DISABLED',4,'2026-09-25 10:00:00','2026-09-25 10:00:00')")
            runBlocking{
                assertFailsWith<LearnerUnavailableException>{AcceptGrowthBatch(work(f),F.SOURCE).execute(GrowthWire.event(F.selectedEnvelope()))}
                assertFailsWith<LearnerUnavailableException>{QueryGrowth(work(f),F.SOURCE,clock).snapshot(123,F.SOURCE,0,emptyList(),null)}
            }
            assertEquals(4L,number(db,"SELECT identity_version FROM language_learning_learner WHERE user_id=123"));assertEquals(0L,number(db,"SELECT COUNT(*) FROM language_learning_growth_receipt"))
        }}
    }
    @Test fun `repository는 트랜잭션 바깥으로 탈출할 수 없다`() {
        LocalScratchMysql.use{db->DatabaseFactory(db.settings()).use{f->
            var escaped:GrowthRepository?=null
            runBlocking{work(f).read{escaped=records}}
            assertFailsWith<IllegalStateException>{escaped!!.profile(123)}
        }}
    }
}
