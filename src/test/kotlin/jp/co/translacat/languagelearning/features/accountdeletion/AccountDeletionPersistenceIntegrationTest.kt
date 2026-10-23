package jp.co.translacat.languagelearning.features.accountdeletion

import jp.co.translacat.languagelearning.features.accountdeletion.application.*
import jp.co.translacat.languagelearning.features.accountdeletion.infrastructure.ExposedAccountDeletionStore
import jp.co.translacat.languagelearning.features.learner.domain.exception.LearnerUnavailableException
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.features.leveltest.infrastructure.storage.LocalLevelTestAudioStore
import jp.co.translacat.languagelearning.features.speaking.infrastructure.LocalSpeakingAudioStore
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.*
import java.sql.Connection
import java.nio.file.Files
import java.nio.file.Path
import java.time.*
import java.util.*
import kotlin.test.*

/** 운영 catalog를 입력받지 않는 기존 LocalScratchMysql의 무작위 신규 DB만 사용한다. */
class AccountDeletionPersistenceIntegrationTest {
    private val now = Instant.parse("2026-10-23T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `FK 전체 개인 그래프의 실제 바이트와 행을 지우고 다른 소유자와 정적 자료는 보존한다`() = LocalScratchMysql.use { scratch ->
        DatabaseFactory(scratch.settings()).use { factory ->
            runBlocking {
                // 준비: migration FK 그래프에서 독립적으로 발견한 각 개인 테이블에 A/B 합성 행을 넣는다.
                val fixture = scratch.connect().use { connection -> LearningDeletionFixture(connection).also { it.seed(73001); it.seed(73002) } }
                val work = JdbcTransactionRunner(factory.database, 2)
                val store = ExposedAccountDeletionStore(work, clock)
                val deleted = mutableSetOf<AccountDeletionObject>()
                val files = deletionAudioFixture(scratch.connect().use { connection ->
                    fixture.audio(connection, 73001) + fixture.audio(connection, 73002)
                })
                val level = LocalLevelTestAudioStore(files.resolve("level"))
                val speaking = LocalSpeakingAudioStore(files.resolve("speaking"))
                val service = AccountDeletionService(store, {
                    if (it.kind == "LEVEL_TEST") level.delete(it.key) else speaking.delete(it.key)
                    deleted += it
                }, clock = clock)
                val request = AccountDeletionRequest(UUID.randomUUID().toString(), now.toString())
                assertTrue(store.ready())

                // 실행: pending 객체를 실제 삭제 성공으로 응답하는 합성 저장소를 주입한다.
                val result = service.delete(73001, request)

                // 검증: 독립 schema 그래프의 모든 개인 테이블과 B의 원문을 비교한다.
                assertEquals("COMPLETED", result.status)
                scratch.connect().use { connection ->
                    fixture.assertErased(connection, 73001)
                    fixture.assertPreserved(connection, 73002)
                    fixture.audio(connection, 73002).forEach { target ->
                        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(files.resolve(if (target.kind == "LEVEL_TEST") "level" else "speaking").resolve(target.key)))
                    }
                    assertEquals("DELETED", connection.query("SELECT status FROM language_learning_learner WHERE user_id=73001").single().single())
                    assertEquals("ACTIVE", connection.query("SELECT status FROM language_learning_learner WHERE user_id=73002").single().single())
                }
                assertTrue(deleted.any { it.kind == "LEVEL_TEST" })
                assertTrue(deleted.any { it.kind == "SPEAKING" })
                deleted.forEach { target -> assertFalse(Files.exists(files.resolve(if (target.kind == "LEVEL_TEST") "level" else "speaking").resolve(target.key))) }
                assertEquals(result, service.delete(73001, request))
                assertFailsWith<LearnerUnavailableException> { work.write {
                    ExposedLearnerRepository {}.ensureAndLock(73001, LocalDateTime.ofInstant(now, ZoneOffset.UTC)).requireActive()
                } }
                assertFailsWith<AccountDeletionFailure> { service.delete(73001, request.copy(operationId = UUID.randomUUID().toString())) }
            }
        }
    }

    @Test
    fun `다른 사용자가 참조한 개인 생성 catalog는 원문을 소거하고 고정된 타인 문항은 보존한다`() = LocalScratchMysql.use { scratch ->
        DatabaseFactory(scratch.settings()).use { factory -> runBlocking {
            // 준비: B의 고정 문항은 A의 생성 catalog를 참조하되 자신의 스냅샷 본문을 가진다.
            val fixture = scratch.connect().use { connection -> LearningDeletionFixture(connection).also {
                it.seed(76001); it.seed(76002); it.shareCatalog(connection, 76001, 76002)
            } }
            val store = ExposedAccountDeletionStore(JdbcTransactionRunner(factory.database, 2), clock)
            val service = AccountDeletionService(store, {}, clock = clock)

            // 실행
            assertEquals("COMPLETED", service.delete(76001, AccountDeletionRequest(UUID.randomUUID().toString(), now.toString())).status)

            // 검증: FK를 보존하면서 개인 catalog 내용은 지우며 B의 frozen 원문/답안은 바꾸지 않는다.
            scratch.connect().use { connection ->
                fixture.assertErased(connection, 76001)
                fixture.assertPreserved(connection, 76002)
                assertEquals(listOf(listOf("RETIRED", "{}", "deleted-owner", "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a")), connection.query(
                    "SELECT review_status,content_json,topic_key,content_hash FROM language_learning_curated_writing_catalog WHERE id='gen-fixture-76001'"))
            }
            assertFalse(store.isUserAvailable(76001))
            assertTrue(store.isUserAvailable(76002))
            assertTrue(store.isUserAvailable(76003))
            assertFalse(store.isSessionActive("76001"))
            assertTrue(store.isSessionActive("76002"))
        } }
    }

    @Test
    fun `파일 삭제 실패와 재시작은 pending을 유지하고 업로드 종료 후 같은 키만 회수한다`() = LocalScratchMysql.use { scratch ->
        val operation = UUID.randomUUID().toString()
        val key = AccountDeletionObject("SPEAKING", "${UUID.randomUUID()}.audio")
        DatabaseFactory(scratch.settings()).use { factory -> runBlocking {
            // 준비
            val store = ExposedAccountDeletionStore(JdbcTransactionRunner(factory.database, 2), clock)
            store.beginAudioWrite(74001, key)
            val service = AccountDeletionService(store, { error("synthetic-storage-failure") }, clock = clock)

            // 실행과 검증: freeze 전에 시작한 쓰기가 있으면 아직 완료/파일 삭제를 허용하지 않는다.
            val response = service.delete(74001, AccountDeletionRequest(operation, now.toString()))
            assertEquals("PENDING", response.status)
            store.endAudioWrite(74001, key)
            assertEquals("PENDING", service.delete(74001, AccountDeletionRequest(operation, now.toString())).status)
            assertFailsWith<LearnerUnavailableException> { store.beginAudioWrite(74001, key.copy(key = "${UUID.randomUUID()}.audio")) }
        } }
        DatabaseFactory(scratch.settings()).use { factory -> runBlocking {
            // 실행: 새 pool/서비스에서도 같은 영속 operation과 키를 복구한다.
            val removed = mutableListOf<AccountDeletionObject>()
            val store = ExposedAccountDeletionStore(JdbcTransactionRunner(factory.database, 2), clock)
            val service = AccountDeletionService(store, { removed += it }, clock = clock)
            val response = service.delete(74001, AccountDeletionRequest(operation, now.toString()))

            // 검증
            assertEquals("COMPLETED", response.status)
            assertEquals(listOf(key), removed)
        } }
    }

    @Test
    fun `진행 중 쓰기 취소와 동일 키 경합은 타 소유자 파일을 바꾸지 않는다`() = LocalScratchMysql.use { scratch ->
        DatabaseFactory(scratch.settings()).use { factory -> runBlocking {
            // 준비
            val store = ExposedAccountDeletionStore(JdbcTransactionRunner(factory.database, 2), clock)
            val target = AccountDeletionObject("LEVEL_TEST", "${UUID.randomUUID()}.wav")
            store.beginAudioWrite(75001, target)

            // 실행과 검증
            assertFailsWith<AccountDeletionFailure> { store.beginAudioWrite(75001, target) }
            assertFailsWith<AccountDeletionFailure> { store.beginAudioWrite(75002, target) }
            store.endAudioWrite(75001, target)
            val writes = AccountAudioWrites(store)
            assertFailsWith<CancellationException> { writes.write(75001, target.kind, target.key) { throw CancellationException() } }
            assertEquals(1, store.activeWrites(75001))
            val later = ExposedAccountDeletionStore(JdbcTransactionRunner(factory.database, 2), Clock.offset(clock, Duration.ofDays(30)))
            assertEquals(1, later.activeWrites(75001))
            var deletes = 0
            val service = AccountDeletionService(later, { deletes++ }, clock = Clock.offset(clock, Duration.ofDays(30)))
            assertEquals("PENDING", service.delete(75001, AccountDeletionRequest(UUID.randomUUID().toString(), now.toString())).status)
            assertEquals(0, deletes)
        } }
    }
}

/** FK/NOT NULL/CHECK 정의로 합성 행을 만든다. 삭제 구현의 고정 테이블 목록을 재사용하지 않는다. */
private class LearningDeletionFixture(private val initial: Connection) {
    private data class Column(val name: String, val type: String, val nullable: Boolean, val default: String?, val extra: String, val maximum: Int?, val definition: String)
    private data class Foreign(val column: String, val parent: String, val parentColumn: String)
    private val tables = initial.query("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'")
        .map { it.single()!! }.filter { it != "flyway_schema_history" }.toSet()
    private val columns = tables.associateWith { table -> initial.query(
        "SELECT COLUMN_NAME,DATA_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,CHARACTER_MAXIMUM_LENGTH,COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION", table,
    ).map { Column(it[0]!!, it[1]!!, it[2] == "YES", it[3], it[4]!!, it[5]?.toIntOrNull(), it[6]!!) } }
    private val foreign = tables.associateWith { table -> initial.query(
        "SELECT COLUMN_NAME,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND REFERENCED_TABLE_NAME IS NOT NULL", table,
    ).map { Foreign(it[0]!!, it[1]!!, it[2]!!) } }
    private val owned = run {
        val set = columns.filterValues { list -> list.any { it.name in setOf("user_id", "owner_user_id") } }.keys.toMutableSet()
        do {
            val before = set.size
            foreign.filterValues { list -> list.any { it.parent in set } }.keys.forEach(set::add)
        } while (set.size != before)
        set - setOf("language_learning_learner", "language_learning_account_deletion", "language_learning_account_deletion_object", "language_learning_audio_write")
    }
    private val rows = mutableMapOf<Pair<String, Long>, Map<String, Any?>>()
    private val original = mutableMapOf<Pair<String, Long>, List<List<String?>>>()

    fun seed(user: Long) {
        assertEquals(56, owned.size, "FK graph coverage changed; update the deletion contract and fixture deliberately")
        insert("language_learning_learner", user)
        owned.sorted().forEach { insert(it, user) }
        owned.forEach { table -> original[table to user] = select(initial, table, rows.getValue(table to user)) }
    }

    private fun insert(table: String, user: Long): Map<String, Any?> {
        rows[table to user]?.let { return it }
        val row = linkedMapOf<String, Any?>()
        val checks = initial.query("SELECT c.CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS c JOIN information_schema.TABLE_CONSTRAINTS t " +
            "ON t.CONSTRAINT_SCHEMA=c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME=c.CONSTRAINT_NAME WHERE t.TABLE_SCHEMA=DATABASE() AND t.TABLE_NAME=?", table)
            .joinToString(" ") { it.single().orEmpty() }
        val required = columns.getValue(table).filter { !it.nullable && it.default == null || it.name in setOf("user_id", "owner_user_id", "id", "object_key") }
        for (column in required) {
            val link = foreign.getValue(table).firstOrNull { it.column == column.name && it.parent != table }
            row[column.name] = when {
                column.name in setOf("user_id", "owner_user_id") -> user
                link != null -> insert(link.parent, user)[link.parentColumn]
                table == "language_learning_curated_writing_generation" && column.name == "catalog_id" -> insert("language_learning_curated_writing_catalog", user)["id"]
                column.name == "object_key" -> UUID.nameUUIDFromBytes("$table:$user".toByteArray()).toString() + if ("level_test" in table) ".wav" else ".audio"
                column.name == "id" && column.type in setOf("varchar", "char") -> if ("catalog" in table) "gen-fixture-$user" else UUID.nameUUIDFromBytes("$table:$user".toByteArray()).toString()
                column.name == "id" -> user
                column.name == "user_id" -> user
                column.type in setOf("datetime", "timestamp") -> "2026-10-23 03:00:00.000000"
                column.type == "date" -> "2026-10-23"
                column.type in setOf("json", "text", "longtext", "mediumtext") -> if (column.name.contains("json")) "{}" else "private fixture $user"
                column.type.contains("blob") -> byteArrayOf(1, 2, 3)
                column.type in setOf("bigint", "int", "tinyint", "smallint", "double", "decimal", "float") -> 1
                else -> checkedString(column.name, checks) ?: when {
                    column.name in setOf("status", "review_status") -> if (table == "language_learning_learner") "ACTIVE" else "READY"
                    column.name == "writing_type" -> "TRANSLATION"
                    column.name.endsWith("hash") || column.name.contains("sha256") -> "a".repeat(64)
                    column.type == "enum" -> Regex("'([^']*)'").find(column.definition)!!.groupValues[1]
                    else -> "f$user"
                }.let { value -> column.maximum?.let(value::take) ?: value }
            }
        }
        // 여러 OR 분기를 가진 정책 CHECK는 독립 문자열 추론 대신 지원되는 fixed5 조합을 명시한다.
        if (table == "language_learning_curated_writing_set") {
            row["policy_version"] = "curated-writing-v1"
            row["result_policy"] = "REFERENCE_ONLY"
            row["status"] = "READY"
            row["target_item_count"] = 5
            row["planned_slots_json"] = null
        }

        // nullable 소유자와 객체 키도 실제 삭제 대상으로 남긴다.
        if (columns.getValue(table).any { it.name == "owner_user_id" }) row["owner_user_id"] = user
        if (table == "language_learning_listening_audio") row["audio_bytes"] = byteArrayOf(1, 2, 3)
        val sql = "INSERT INTO `$table` (${row.keys.joinToString(",") { "`$it`" }}) VALUES (${row.keys.joinToString(",") { "?" }})"
        initial.prepareStatement(sql).use { statement ->
            row.values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            try { statement.executeUpdate() } catch (failure: Exception) { throw AssertionError("Fixture table $table failed: ${failure.message}", failure) }
        }
        rows[table to user] = row
        return row
    }

    private fun checkedString(column: String, checks: String): String? {
        // MySQL 8.4의 CHECK_CLAUSE는 따옴표를 SQL escape로 돌려준다. 실제 허용 문자열만 읽는다.
        val clause = checks.replace("\\'", "'")
        val key = "`?${Regex.escape(column)}`?"
        val equality = Regex("$key\\s*=\\s*(?:_[A-Za-z0-9]+)?'([^']*)'", RegexOption.IGNORE_CASE).find(clause)
        if (equality != null) return equality.groupValues[1]
        val list = Regex("$key\\s+in\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE).find(clause)?.groupValues?.get(1) ?: return null
        return Regex("'([^']*)'").find(list)?.groupValues?.get(1)
    }

    private fun select(connection: Connection, table: String, row: Map<String, Any?>): List<List<String?>> {
        val primary = connection.query("SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION", table)
            .map { it.single()!! }
        return connection.query("SELECT * FROM `$table` WHERE " + primary.joinToString(" AND ") { "`$it`=?" }, *primary.map { row[it] }.toTypedArray())
    }

    fun assertErased(connection: Connection, user: Long) {
        owned.forEach { table -> assertTrue(select(connection, table, rows.getValue(table to user)).isEmpty(), "$table A data survived") }
    }

    fun assertPreserved(connection: Connection, user: Long) {
        owned.forEach { table -> assertEquals(original.getValue(table to user), select(connection, table, rows.getValue(table to user)), "$table B changed") }
    }

    fun audio(connection: Connection, user: Long): List<AccountDeletionObject> =
        connection.query("SELECT object_key FROM language_learning_level_test_audio WHERE owner_user_id=?", user)
            .map { AccountDeletionObject("LEVEL_TEST", it.single()!!) } +
            connection.query("SELECT a.object_key FROM language_learning_speaking_audio a JOIN language_learning_speaking_session s ON s.id=a.session_id WHERE s.user_id=?", user)
                .map { AccountDeletionObject("SPEAKING", it.single()!!) }

    fun shareCatalog(connection: Connection, owner: Long, other: Long) {
        connection.prepareStatement("UPDATE language_learning_curated_writing_catalog SET content_json='{" +
            "\"private\":\"owner@example.test\"}',topic_key='private-owner' WHERE id=?").use {
            it.setString(1, "gen-fixture-$owner"); it.executeUpdate()
        }
        connection.prepareStatement("UPDATE language_learning_curated_writing_item SET catalog_id=?,public_json='{" +
            "\"text\":\"other-owned frozen study\"}' WHERE user_id=?").use {
            it.setString(1, "gen-fixture-$owner"); it.setLong(2, other); it.executeUpdate()
        }
        owned.forEach { table -> original[table to other] = select(connection, table, rows.getValue(table to other)) }
    }
}

private fun deletionAudioFixture(targets: List<AccountDeletionObject>): Path {
    val parent = Path.of(requireNotNull(System.getenv("LL_DELETION_TEST_OUTPUT_DIR"))).toAbsolutePath().normalize()
    require(Files.isDirectory(parent))
    val root = Files.createTempDirectory(parent, "audio-fixture-")
    targets.forEach { target ->
        val directory = Files.createDirectories(root.resolve(if (target.kind == "LEVEL_TEST") "level" else "speaking"))
        Files.write(directory.resolve(target.key), byteArrayOf(1, 2, 3))
    }
    return root
}

private fun Connection.query(sql: String, vararg values: Any?): List<List<String?>> = prepareStatement(sql).use { statement ->
    values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
    statement.executeQuery().use { result -> buildList {
        while (result.next()) add((1..result.metaData.columnCount).map(result::getString))
    } }
}
