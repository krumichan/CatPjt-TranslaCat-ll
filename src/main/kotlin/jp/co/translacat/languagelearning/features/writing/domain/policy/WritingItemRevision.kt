package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingItem
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*

/** BE DailyWritingItemRevisionService의 필드 순서와 UTF-8 길이 prefix를 그대로 사용한다. */
internal object WritingItemRevision {
    fun of(item: WritingItem): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            item.order.toString(), item.difficulty.name, item.originText, item.keywordsJson,
            item.focusMetricsJson, item.focusReason, item.providedFactsJson,
            item.requiredIntentsJson, item.responseConstraintsJson,
        ).forEach { value ->
            if (value == null) digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(-1).array())
            else {
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
