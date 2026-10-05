package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*
import java.time.Instant

/** 별도 소유자 기록의 정확한 문항 버전과 hash만 승인으로 인정한다. */
internal data class CuratedWritingApproval(
    val id: String, val version: Int, val contentHash: String, val releaseId: String,
    val approver: String, val approvedAt: Instant,
)

internal object CuratedWritingApprovalCodec {
    fun parse(source: String, manifest: CuratedWritingManifest): Map<Pair<String, Int>, CuratedWritingApproval> {
        val root = Json.parseToJsonElement(source).jsonObject
        require(root.keys == setOf("releaseId", "approvals")) { "WRITING_APPROVAL_SCHEMA_INVALID" }
        require(root.getValue("releaseId").jsonPrimitive.content == manifest.releaseId) {
            "WRITING_APPROVAL_RELEASE_MISMATCH"
        }
        val catalog = manifest.items.associateBy { it.id to it.version }
        val approvals = root.getValue("approvals").jsonArray.map { raw ->
            val value = raw.jsonObject
            require(value.keys == setOf("id", "version", "contentHash", "releaseId", "approver", "approvedAt")) {
                "WRITING_APPROVAL_SCHEMA_INVALID"
            }
            val approval = CuratedWritingApproval(
                value.getValue("id").jsonPrimitive.content,
                value.getValue("version").jsonPrimitive.int,
                value.getValue("contentHash").jsonPrimitive.content,
                value.getValue("releaseId").jsonPrimitive.content,
                value.getValue("approver").jsonPrimitive.content,
                Instant.parse(value.getValue("approvedAt").jsonPrimitive.content),
            )
            require(approval.approver.isNotBlank() && approval.releaseId == manifest.releaseId &&
                approval.contentHash == catalog[approval.id to approval.version]?.contentHash) {
                "WRITING_APPROVAL_CONTENT_MISMATCH"
            }
            approval
        }
        require(approvals.distinctBy { it.id to it.version }.size == approvals.size) {
            "WRITING_APPROVAL_DUPLICATE"
        }
        return approvals.associateBy { it.id to it.version }
    }
}
