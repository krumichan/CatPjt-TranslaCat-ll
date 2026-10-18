package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingManifest
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingManifestCodec
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.serialization.json.*

/** 실제 DB/HTTP의 호출·실패 경계 전용 합성 Provider다. 언어 의미의 실모델 gold가 아니다. */
internal class CuratedWritingSemanticV14TestModel(
    private val coreMode: CoreMode = CoreMode.VALID,
    private val optionalMode: OptionalMode = OptionalMode.EMPTY,
    private val invalidRange: Boolean = false,
    private val rejectCoreVerification: Boolean = false,
) : ModelExecutionPort {
    enum class CoreMode { VALID, REPAIR_ONCE, FAIL, EXTRA_TASK, DUPLICATED_ENDING }
    enum class OptionalMode { EMPTY, VALID, REJECT, DUPLICATE, THROW, MODEL_CHANGED }
    val commands = mutableListOf<ModelExecutionCommand>()
    var beforeReturn: (suspend (ModelExecutionCommand) -> Unit)? = null

    override suspend fun execute(command: ModelExecutionCommand): ModelExecutionResult {
        commands += command
        CuratedWritingServerRepairQaExport.commandIfRequested(command)
        val input = Json.parseToJsonElement(command.messages.single().content).jsonObject
        val output = when (command.schemaName) {
            "writing_observed_claims" -> CuratedWritingSemanticTestModel.response(command)
            "writing_atom_evidence_v14" -> evidence(input)
            "writing_server_repairs", "writing_server_repair_remaining" -> core(input, command.schemaName == "writing_server_repair_remaining")
            "writing_core_verification" -> coreVerification(requireNotNull(command.responseSchema))
            "writing_optional_proposals" -> proposals(input)
            "writing_optional_verification" -> verification(input)
            else -> error("Unexpected V14 synthetic stage ${command.schemaName}")
        }
        beforeReturn?.invoke(command)
        val resultModel = if (optionalMode == OptionalMode.MODEL_CHANGED && command.schemaName == "writing_optional_proposals") {
            "gpt-5-other-unapproved"
        } else {
            "gpt-5-mini-2025-08-07"
        }
        return ModelExecutionResult(output, 10, 5, "openai", resultModel)
    }

    private fun evidence(input: JsonObject): JsonObject {
        val sources = input.getValue("answerSources").jsonArray.map { it.jsonObject }
        val ids = JsonArray(sources.map { it.getValue("id") })
        val contract = input.getValue("taskMeaningContract").jsonObject
        fun edge() = buildJsonObject {
            put("state", "SUPPORT"); put("sourceIds", ids)
            listOf("actorMatches", "actionMatches", "targetMatches", "conditionMatches").forEach { put(it, true) }
            put("recipient", "TASK_PARTICIPANT"); put("kind", "EVENT"); put("modality", "AFFIRMED")
            put("semanticReading", "合成の原文に表現された要求の意味を参照しています。")
        }
        val segments = input.getValue("learnerSegments").jsonArray.map { it.jsonObject }
        return buildJsonObject {
            put("sourceRevisionHash", input.getValue("sourceRevisionHash"))
            put("atoms", buildJsonObject {
                contract.getValue("requirements").jsonArray.forEach { requirement ->
                    requirement.jsonObject.getValue("atoms").jsonArray.forEach { raw ->
                        val atom = raw.jsonObject
                        val alternatives = atom.getValue("alternatives").jsonArray
                        put(atom.string("id"), if (alternatives.isEmpty()) edge() else buildJsonObject {
                            put("alternatives", buildJsonObject { alternatives.indices.forEach { put("V${it + 1}", edge()) } })
                        })
                    }
                }
            })
            put("facts", buildJsonObject {
                contract.getValue("facts").jsonArray.forEach { raw ->
                    val fact = raw.jsonObject
                    val candidates = fact.getValue("normalizedAlternatives").jsonObject.keys.sortedByDescending(String::length)
                    val token = candidates.first { candidate -> sources.any { it.string("text").contains(candidate) } }
                    val source = sources.first { it.string("text").contains(token) }
                    put(fact.string("key"), buildJsonObject {
                        put("sourceId", source.getValue("id")); put("valueToken", token); put("slotRelation", "MATCHED_EVENT")
                    })
                }
            })
            put("languageFormErrors", buildJsonArray {
                segments.forEach { segment ->
                    for ((core, reason) in listOf("いただけるます" to "INFLECTION", "受け取りますです" to "SYNTAX",
                        "るまし" to "INFLECTION")) {
                        val start = segment.string("text").indexOf(core)
                        if (start >= 0) add(buildJsonObject {
                            put("segmentId", segment.getValue("id")); put("start", start)
                            put("end", if (invalidRange) segment.string("text").length + 1 else start + core.length)
                            put("formReason", reason)
                        })
                    }
                }
                if (invalidRange && segments.none { it.string("text").contains("いただけるます") || it.string("text").contains("受け取りますです") }) {
                    add(buildJsonObject {
                        put("segmentId", segments.first().getValue("id")); put("start", 0)
                        put("end", segments.first().string("text").length + 1); put("formReason", "SYNTAX")
                    })
                }
            })
        }
    }

    private fun core(input: JsonObject, repairing: Boolean): JsonObject {
        val tasks = input.getValue("repairTasks").jsonArray.map { it.jsonObject }
        return buildJsonObject {
            put("repairPlanHash", input.getValue("repairPlanHash"))
            put("repairs", buildJsonObject {
                tasks.forEachIndexed { index, task ->
                    val original = task.string("original")
                    val segment = input.getValue("learnerSegments").jsonArray.single {
                        it.jsonObject.string("id") == task.string("segmentId")
                    }.jsonObject.string("text")
                    val fail = coreMode == CoreMode.FAIL || coreMode == CoreMode.REPAIR_ONCE && !repairing && index == tasks.lastIndex
                    put(task.string("id"), buildJsonObject {
                        val replacement = if (fail) original else original.replace("いただけるます", "いただけます")
                            .replace("受け取りますです", "受け取ります").replace("図書館に置いたままです", "家に持ち帰りました")
                            .replace("るまし", if (coreMode == CoreMode.DUPLICATED_ENDING) "りました" else "りまし")
                        put("fullPatchedSegment", segment.replaceRange(task.getValue("start").jsonPrimitive.int,
                            task.getValue("end").jsonPrimitive.int, replacement))
                        put("learningText", "独立して確認された活用または構文の誤りだけを、この位置で直します。")
                    })
                }
                if (coreMode == CoreMode.EXTRA_TASK) put("R999", buildJsonObject {
                    put("fullPatchedSegment", "余分な文です。"); put("learningText", "許可されていない作業です。")
                })
            })
        }
    }

    private fun proposals(input: JsonObject): JsonObject {
        if (optionalMode == OptionalMode.THROW) throw ModelExecutionFailure("QA_OPTIONAL_FAILURE", 503, false)
        val target = input.getValue("targets").jsonArray.first().jsonObject
        val original = target.string("original")
        val replacement = when {
            original.contains("電子メール") -> original.replace("電子メール", "メール")
            original.contains("午後5時") -> original.replace("午後5時", "17時")
            original.contains("仕事の後に") -> original.replace("仕事の後に", "仕事後に")
            else -> original.replace("することができます", "できます")
        }
        val row = buildJsonObject {
            put("targetId", target.getValue("targetId")); put("replacement", replacement)
        }
        return buildJsonObject {
            put("sourceRevisionHash", input.getValue("sourceRevisionHash")); put("coreHash", input.getValue("coreHash"))
            put("proposals", when (optionalMode) {
                OptionalMode.EMPTY -> JsonArray(emptyList())
                OptionalMode.DUPLICATE -> JsonArray(listOf(row, row))
                else -> JsonArray(listOf(row))
            })
        }
    }

    private fun coreVerification(schema: JsonObject): JsonElement {
        // 합성 긍정/부정 응답만 만든다. 실모델의 문법·의미 판정 근거로 사용하지 않는다.
        schema["enum"]?.jsonArray?.let { return it.first() }
        return when (schema.string("type")) {
            "object" -> JsonObject(schema.getValue("properties").jsonObject.mapValues { (name, raw) ->
                if (rejectCoreVerification && name.contains("grammar", ignoreCase = true)) JsonPrimitive(false)
                else coreVerification(raw.jsonObject)
            })
            "boolean" -> JsonPrimitive(true)
            "string" -> JsonPrimitive("合成の独立検証です。")
            else -> error("Unexpected synthetic core check type")
        }
    }

    private fun verification(input: JsonObject): JsonObject = buildJsonObject {
        put("sourceRevisionHash", input.getValue("sourceRevisionHash")); put("coreHash", input.getValue("coreHash"))
        put("proposalHash", input.getValue("proposalHash"))
        put("decisions", JsonArray(input.getValue("proposals").jsonArray.map { raw -> buildJsonObject {
            put("targetId", raw.jsonObject.getValue("targetId"))
            listOf("actorPreserved", "actionPreserved", "targetPreserved", "timePreserved", "negationPreserved",
                "conditionPreserved", "certaintyPreserved", "quantitiesPreserved", "noNewProposition", "grammarValid",
                "useful", "explanationAccurate").forEach { name -> put(name, optionalMode != OptionalMode.REJECT || name != "noNewProposition") }
            put("reason", "合成の独立比較結果です。")
        } }))
    }

    companion object {
        const val freeOneError = "明日の午後に商品を取り置きしていただけるますか。仕事の後に伺います。"
        const val freeTwoErrors = "明日の午後に商品を取り置きしていただけるますか。仕事の後に受け取りますです。"

        fun manifest(free: Boolean = false): CuratedWritingManifest {
            val resource = checkNotNull(javaClass.getResourceAsStream("/writing/curated-candidates.json"))
            val original = resource.bufferedReader(Charsets.UTF_8).use { CuratedWritingManifestCodec.parse(it.readText()) }
            val id = if (free) "ko-ja-free-b3-01" else "ko-ja-translation-b3-01"
            return original.copy(items = listOf(original.items.single { it.id == id }))
        }
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
