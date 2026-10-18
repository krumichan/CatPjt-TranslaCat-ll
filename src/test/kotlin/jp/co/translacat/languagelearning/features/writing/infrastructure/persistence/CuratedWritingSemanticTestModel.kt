package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingManifest
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingManifestCodec
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackPolicy
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV13Policy
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** 합성 모델은 단계 연결·저장 계약만 검사한다. 언어 의미 품질을 입증하는 응답이 아니다. */
internal object CuratedWritingSemanticTestModel {
    const val answer = "今週金曜日の午後5時までに申請書を提出すれば、来週月曜日に結果をメールで受け取れます。"

    fun manifest(): CuratedWritingManifest {
        val resource = checkNotNull(javaClass.getResourceAsStream("/writing/curated-candidates.json"))
        val original = resource.bufferedReader(Charsets.UTF_8).use { CuratedWritingManifestCodec.parse(it.readText()) }
        return original.copy(items = listOf(original.items.single { it.id == "ko-ja-translation-b3-01" }))
    }

    fun verifyQaCasesIfRequested() {
        val sourcePath = System.getenv("LL_QA_SEMANTIC_CASES") ?: return
        val cases = Json.parseToJsonElement(Files.readString(Path.of(sourcePath))).jsonObject.getValue("cases").jsonArray
        val report = JsonArray(cases.map { raw ->
            val case = raw.jsonObject
            val item = case.getValue("manifest").jsonObject.getValue("items").jsonArray.single().jsonObject
            val public = buildJsonObject {
                put("originText", item.getValue("prompt"))
                listOf("providedFacts", "requiredIntents", "responseConstraints").forEach { put(it, item.getValue(it)) }
                put("taskRequirements", item.getValue("checklist"))
            }
            val context = CuratedWritingFeedbackPolicy.Context(WritingType.valueOf(item.getValue("writingType").jsonPrimitive.content), public, "ko", "ja")
            val requirements = CuratedWritingFeedbackPolicy.requirements(item.getValue("checklist").jsonArray)
            val answer = case.getValue("answer").jsonPrimitive.content
            val plan = requireNotNull(CuratedWritingFeedbackV13Policy.prepare(context, requirements, CuratedWritingFeedbackPolicy.segments(answer))) {
                "Frozen QA case ${case.getValue("id")} has no authored meaning contract"
            }
            buildJsonObject {
                put("caseId", case.getValue("id")); put("meaningContractHash", plan.contract.sha256())
                put("extractionInput", Json.parseToJsonElement(CuratedWritingFeedbackV13Policy.extractionPrompt(plan, answer)))
            }
        })
        // 명시한 QA에서만 합성 공개 입력을 내보낸다. 기존 증거는 덮어쓰지 않는다.
        System.getenv("LL_QA_SEMANTIC_CASE_REPORT")?.let {
            val path = Path.of(it)
            if (!Files.exists(path)) Files.writeString(path, report.toString(), StandardOpenOption.CREATE_NEW)
        }
    }

    fun response(command: ModelExecutionCommand, invalidEvidence: Boolean = false, invalidComposition: Boolean = false): JsonObject {
        val input = Json.parseToJsonElement(command.messages.single().content).jsonObject
        val sources = input.getValue("answerSources").jsonArray.map { it.jsonObject }
        val ids = JsonArray(sources.map { it.getValue("id") })
        return when (command.schemaName) {
            "writing_observed_claims" -> buildJsonObject {
                put("observations", buildJsonArray { add(buildJsonObject {
                    put("sourceIds", ids)
                    put("actor", "申請者"); put("action", "提出と結果の受け取り")
                    put("target", "申請書"); put("condition", "締切までに提出")
                    put("recipient", "NONE"); put("kind", "EVENT"); put("modality", "HYPOTHETICAL")
                }) })
            }
            "writing_atom_evidence" -> {
                val contract = input.getValue("taskMeaningContract").jsonObject
                val atomIds = contract.getValue("requirements").jsonArray.flatMap {
                    it.jsonObject.getValue("atoms").jsonArray.map { atom -> atom.jsonObject.getValue("id").jsonPrimitive.content }
                }
                buildJsonObject {
                    put("atoms", buildJsonObject { atomIds.forEach { id -> put(id, buildJsonObject {
                        put("state", "SUPPORT")
                        put("sourceIds", if (invalidEvidence) buildJsonArray { add("FORGED_SOURCE") } else ids)
                        listOf("actorMatches", "actionMatches", "targetMatches", "conditionMatches").forEach { put(it, true) }
                        put("recipient", "NONE"); put("kind", "EVENT"); put("modality", "HYPOTHETICAL")
                        put("semanticReading", "締切までに提出した場合に月曜日に結果をメールで受け取る意味です。")
                    }) } })
                    put("facts", buildJsonObject {
                        contract.getValue("facts").jsonArray.forEach { factValue ->
                            val fact = factValue.jsonObject
                            val expected = fact.getValue("expectedValue").jsonPrimitive.content
                            val tokens = fact.getValue("normalizedAlternatives").jsonObject.filterValues {
                                it.jsonPrimitive.content == expected
                            }.keys.sortedByDescending(String::length)
                            val token = tokens.first { candidate -> sources.any { it.getValue("text").jsonPrimitive.content.contains(candidate) } }
                            val source = sources.first { it.getValue("text").jsonPrimitive.content.contains(token) }
                            put(fact.getValue("key").jsonPrimitive.content, buildJsonObject {
                                put("sourceId", source.getValue("id")); put("valueToken", token)
                                put("slotRelation", "MATCHED_EVENT")
                            })
                        }
                    })
                    put("languageFormErrors", buildJsonArray {})
                }
            }
            "writing_local_corrections" -> buildJsonObject {
                put("necessaryCorrections", buildJsonArray {
                    if (invalidComposition) add(buildJsonObject {
                        put("requirementId", "C3"); put("segmentId", "S1")
                        put("patch", buildJsonObject { put("original", "受け取れます"); put("replacement", "受け取ります") })
                        put("issueType", "LANGUAGE_FORM"); put("sourceFactId", ""); put("sourceAuthorityId", "")
                        put("formReason", "INFLECTION")
                        put("learningText", "過去の文脈に合わせて書き換えます。")
                    })
                })
                put("optionalAlternatives", buildJsonArray {}); put("uncertainties", buildJsonArray {})
            }
            "writing_local_correction_repair" -> buildJsonObject {
                put("additionalNecessaryCorrections", buildJsonArray {})
                put("additionalOptionalAlternatives", buildJsonArray {})
                put("additionalUncertainties", buildJsonArray {})
            }
            else -> error("Unexpected synthetic stage ${command.schemaName}")
        }
    }
}
