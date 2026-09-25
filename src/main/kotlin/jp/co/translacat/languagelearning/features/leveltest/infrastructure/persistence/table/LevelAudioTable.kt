package jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** V007의 매핑이다. 테이블 생성과 변경은 Flyway만 수행한다. */
internal object LevelAudioTable : Table("language_learning_level_test_audio") {
    val key = varchar("object_key", 100)
    val ownerUserId = long("owner_user_id").nullable()
    val purpose = varchar("purpose", 20)
    val contentType = varchar("content_type", 100)
    val tokenHash = varchar("upload_token_hash", 64).nullable()
    val uploadUntil = datetime("upload_until").nullable()
    val checksum = varchar("checksum_sha256", 64).nullable()
    val sizeBytes = long("size_bytes")
    val status = varchar("status", 20)
    val retentionUntil = datetime("retention_until").nullable()
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(key)
}
