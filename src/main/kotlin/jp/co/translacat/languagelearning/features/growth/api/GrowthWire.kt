package jp.co.translacat.languagelearning.features.growth.api

import jp.co.translacat.languagelearning.features.growth.domain.model.*
import jp.co.translacat.languagelearning.features.growth.domain.policy.GrowthPolicy
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** 외부 AI DTO를 복제하지 않는다. 성장 데이터에 필요한 값만 엄격하게 해석한다. */
internal object GrowthWire {
    const val MAX_BODY_BYTES = 2_105_344
    const val MAX_OPERATIONS = 256
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun uuid(value: String): String { require(UUID.fromString(value).toString() == value); return value }
    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { JsonPrimitive(it.key).toString() + ":" + canonical(it.value) }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }

    fun event(json: JsonObject): GrowthEvent {
        val f = Fields(json, setOf("schemaVersion", "sourceInstanceId", "eventId", "userId", "sequence", "occurredAt", "payloadJson", "payloadSha256"))
        require(f.long("schemaVersion") == 1L)
        val source = uuid(f.text("sourceInstanceId", 36))
        val id = uuid(f.text("eventId", 36))
        val userId = f.long("userId").also { require(it > 0) }
        val sequence = f.long("sequence").also { require(it > 0) }
        val occurred = f.text("occurredAt", 40)
        val instant = Instant.parse(occurred)
        require(instant.nano % 1000 == 0)
        val at = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
        val payload = f.text("payloadJson", 1_048_576)
        val sha = f.text("payloadSha256", 64)
        require(Regex("[a-f0-9]{64}").matches(sha) && hash(payload) == sha)
        val body = Fields(Json.parseToJsonElement(payload).jsonObject, setOf("operations"))
        val operations = body.array("operations", MAX_OPERATIONS).map { operation(it.jsonObject, userId, at) }
        require(operations.isNotEmpty() && operations.map { it.key }.distinct().size == operations.size)
        val envelopeHash = hash(listOf("1", source, id, userId.toString(), sequence.toString(), occurred, sha).joinToString("\n"))
        return GrowthEvent(source, id, userId, sequence, occurred, sha, envelopeHash, operations, at)
    }

    fun operation(json: JsonObject, userId: Long, now: LocalDateTime): GrowthOperation {
        val f = Fields(json, setOf("key", "kind", "payload"))
        val key = f.text("key", 160).also { require(Regex("[A-Za-z0-9_.:-]{1,160}").matches(it)) }
        val kind = f.text("kind", 40)
        val p = f.obj("payload")
        val change: GrowthChange = when (kind) {
            "LEARNING_PREPARED" -> {
                val v = Fields(p, setOf("learningDate"))
                GrowthChange.LearningPrepared(v.date("learningDate"))
            }
            "WRITING_SCORED" -> {
                val v = Fields(p, setOf("learningDate", "difficulty", "scores", "signals", "canonicalKeys"))
                val scores = v.array("scores", 5).map { number(it, 0.0, 100.0) }
                require(scores.size == 5)
                val difficulty = v.text("difficulty", 20).also { require(it in setOf("REVIEW", "NORMAL", "CHALLENGE")) }
                val signals = v.obj("signals").mapValues { (_, values) -> strings(values, 100, 4096) }
                require(signals.keys.all { it in GrowthPolicy.signalTypes })
                GrowthChange.WritingScored(v.date("learningDate"), difficulty, scores, signals, v.strings("canonicalKeys", 100, 200))
            }
            "SIGNALS_TOUCHED" -> {
                val v = Fields(p, setOf("type", "values"))
                val type = v.text("type", 40).also { require(it in GrowthPolicy.signalTypes) }
                GrowthChange.SignalsTouched(type, v.strings("values", 100, 4096))
            }
            "KEYWORDS_SELECTED" -> {
                val v = Fields(p, setOf("learningDate", "canonicalKeys"))
                GrowthChange.KeywordsSelected(v.date("learningDate"), v.strings("canonicalKeys", 100, 200))
            }
            "ACTIVITY_RECORDED" -> {
                val v = Fields(p, setOf("activity", "metrics"))
                val metrics = v.optionalArray("metrics", 10)
                require(metrics == null || metrics.isEmpty()) // 공식 metric은 SPEAKING_SCORED에만 결합한다.
                GrowthChange.ActivityRecorded(activity(v.obj("activity"), userId, now), null)
            }
            "SPEAKING_SCORED" -> {
                val v = Fields(p, setOf("resultKind", "activity", "metrics", "formal", "activityWeight", "evidence"))
                require(v.text("resultKind", 40) == "SCORED_EVALUATION")
                val activity = activity(v.obj("activity"), userId, now)
                require(activity.source == "SPEAKING")
                val formal = v.boolean("formal")
                require(activity.status == if (formal) "EVALUATED" else "INSUFFICIENT_EVIDENCE")
                val metrics = v.array("metrics", 10).map { metric(it.jsonObject) }
                require(metrics.map { it.metricType }.distinct().size == metrics.size)
                GrowthChange.SpeakingScored(activity, metrics, formal, v.number("activityWeight", 0.0, 1.0),
                    v.array("evidence", 100).map { item ->
                        val e = Fields(item.jsonObject, setOf("metricType", "patternKey", "direction", "confidence", "recommendedFocus"))
                        val metric = e.optionalText("metricType", 40).also { require(it == null || it in GrowthPolicy.metricTypes) }
                        EvidenceFact(metric, e.text("patternKey", 4096), e.optionalText("direction", 30),
                            e.number("confidence", 0.0, 1.0), e.optionalText("recommendedFocus", 1000))
                    })
            }
            else -> throw IllegalArgumentException("지원하지 않는 성장 명령입니다.")
        }
        return GrowthOperation(key, hash(kind + "\n" + canonical(p)), change)
    }

    private fun activity(json: JsonObject, userId: Long, now: LocalDateTime): GrowthActivity {
        val f = Fields(json, setOf("source", "referenceId", "learningDate", "title", "durationSeconds", "status", "overallScore", "evaluationConfidence", "startedAt", "completedAt", "metadataJson"))
        val source = f.text("source", 30).also { require(it in GrowthPolicy.sources && it != "LEVEL_TEST") }
        val status = f.text("status", 40).also { require(it in GrowthPolicy.statuses) }
        val score = f.optionalNumber("overallScore", 0.0, 100.0)
        val confidence = f.optionalNumber("evaluationConfidence", 0.0, 1.0)
        require(status != "EVALUATED" || (score != null && confidence != null))
        require(status != "INSUFFICIENT_EVIDENCE" || score == null)
        val metadata = f.text("metadataJson", 32768)
        val obj = Json.parseToJsonElement(metadata).jsonObject
        require(obj["resultKind"]?.jsonPrimitive?.content != "SESSION_COACHING" ||
            (status == "COMPLETED" && score == null && confidence == null))
        return GrowthActivity(userId = userId, source = source, referenceId = f.text("referenceId", 100),
            learningDate = f.date("learningDate"), title = f.text("title", 300),
            durationSeconds = f.long("durationSeconds").also { require(it >= 0) }, status = status,
            overallScore = score, evaluationConfidence = confidence, startedAt = f.time("startedAt"),
            completedAt = f.optionalText("completedAt", 40)?.let { LocalDateTime.parse(it).truncatedTo(java.time.temporal.ChronoUnit.MICROS) },
            metadataJson = metadata, createdAt = now, updatedAt = now)
    }

    private fun metric(json: JsonObject): GrowthMetric {
        val f = Fields(json, setOf("metricType", "state", "score", "confidence", "notEvaluableReason"))
        val type = f.text("metricType", 40).also { require(it in GrowthPolicy.metricTypes) }
        val state = f.text("state", 30).also { require(it in setOf("EVALUATED", "NOT_EVALUABLE")) }
        return GrowthMetric(type, state, f.optionalNumber("score", 0.0, 100.0), f.optionalNumber("confidence", 0.0, 1.0), f.optionalText("notEvaluableReason", 1000))
    }

    fun strings(value: JsonElement, limit: Int, length: Int): List<String> {
        val array = value as? JsonArray ?: throw IllegalArgumentException("배열이 필요합니다.")
        require(array.size <= limit)
        return array.map { text(it, length) }
    }
    fun text(value: JsonElement, max: Int): String {
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("문자열이 필요합니다.")
        require(primitive.isString)
        return primitive.content.also { require(it.isNotEmpty() && it.length <= max) }
    }
    fun number(value: JsonElement, min: Double, max: Double): Double {
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("숫자가 필요합니다.")
        require(!primitive.isString)
        return (primitive.doubleOrNull ?: throw IllegalArgumentException("숫자가 필요합니다.")).also { require(it.isFinite() && it in min..max) }
    }

    internal class Fields(private val value: JsonObject, allowed: Set<String>) {
        init { require(value.keys.all { it in allowed }) { "지원하지 않는 필드입니다." } }
        fun text(key: String, max: Int) = GrowthWire.text(value.getValue(key), max)
        fun optionalText(key: String, max: Int) = value[key]?.takeUnless { it is JsonNull }?.let { GrowthWire.text(it, max) }
        fun long(key: String): Long {
            val primitive = value.getValue(key).jsonPrimitive
            require(!primitive.isString)
            return primitive.longOrNull ?: throw IllegalArgumentException("정수가 필요합니다.")
        }
        fun boolean(key: String): Boolean { val p = value.getValue(key).jsonPrimitive; require(!p.isString); return p.boolean }
        fun number(key: String, min: Double, max: Double) = GrowthWire.number(value.getValue(key), min, max)
        fun optionalNumber(key: String, min: Double, max: Double) = value[key]?.takeUnless { it is JsonNull }?.let { GrowthWire.number(it, min, max) }
        fun date(key: String): LocalDate = LocalDate.parse(text(key, 10))
        fun time(key: String): LocalDateTime = LocalDateTime.parse(text(key, 40)).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        fun obj(key: String): JsonObject = value.getValue(key).jsonObject
        fun array(key: String, max: Int): JsonArray = value.getValue(key).jsonArray.also { require(it.size <= max) }
        fun optionalArray(key: String, max: Int): JsonArray? = value[key]?.takeUnless { it is JsonNull }?.jsonArray?.also { require(it.size <= max) }
        fun strings(key: String, limit: Int, length: Int) = GrowthWire.strings(value.getValue(key), limit, length)
    }
}
