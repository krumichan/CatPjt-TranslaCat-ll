package jp.co.translacat.languagelearning.features.listening.application

import jp.co.translacat.languagelearning.features.listening.domain.model.*
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.LocalDateTime

/** 외부 DTO의 상태·필드와 정답 공개 시점을 유지한다. 내부 저장 JSON을 그대로 노출하지 않는다. */
internal class ListeningReadService(
    private val work: ListeningUnitOfWork, private val settings: SettingsServiceOperations,
) {
    private val json = Json { encodeDefaults = true }
    private val terminal = setOf("EVALUATED", "NOT_EVALUABLE", "SKIPPED")

    suspend fun set(userId: Long, id: Long): JsonObject {
        val policy = settings.listeningPolicy()
        return work.read { setView(set(userId, id) ?: missing("LANGUAGE_LEARNING_DAILY_SET_NOT_FOUND"), policy) }
    }

    suspend fun session(userId: Long, id: Long): JsonObject = work.read {
        val session = session(userId, id) ?: missing()
        sessionView(session, checkNotNull(set(userId, session.setId)))
    }

    suspend fun sessionForAttempt(userId: Long, id: Long): Long = work.read {
        sessions(userId).firstOrNull { it.attempts.any { value -> value.id == id } }?.id ?: missing(
            "LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND",
        )
    }

    suspend fun sessionForResponse(userId: Long, id: Long): Long = work.read {
        sessions(userId).firstOrNull { it.attempts.any { value -> value.tasks.any { task -> task.id == id } } }?.id
            ?: missing("LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND")
    }

    suspend fun attempt(userId: Long, id: Long): JsonObject = work.read {
        val session = sessions(userId).firstOrNull { it.attempts.any { value -> value.id == id } } ?: missing(
            "LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND",
        )
        attemptView(userId, session.attempts.single { it.id == id })
    }

    suspend fun task(userId: Long, attemptId: Long, type: ListeningTaskType): JsonObject = work.read {
        val session = sessions(userId).firstOrNull { it.attempts.any { value -> value.id == attemptId } } ?: missing(
            "LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND",
        )
        taskView(userId, session.attempts.single { it.id == attemptId }.tasks.single { it.taskType == type })
    }

    suspend fun item(userId: Long, sessionId: Long, itemId: Long): JsonObject = work.read {
        val session = session(userId, sessionId) ?: missing("LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND")
        val attempt = session.attempts.filter { it.itemId == itemId }.maxByOrNull { it.attemptNo } ?: missing(
            "LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND",
        )
        val set = checkNotNull(set(userId, session.setId))
        val item = set.items.single { it.id == itemId }
        val reveal = attempt.answerRevealed || attempt.status in terminal
        val keywords = set.request.getValue("setContext").jsonObject.getValue("selectedKeywords").jsonArray

        // 최종 시도 또는 명시적 정답 공개 전에는 원문·의미·객관식 정답·요약 기준점을 숨긴다.
        buildJsonObject {
            putId("sessionId", sessionId)
            putId("itemId", itemId)
            put("itemIndex", item.index)
            put("status", item.status)
            put("playable", playable(userId, item))
            put(
                "referenceAudioPath",
                "/api/v1/language-learning/listening/items/${LearningPublicId.encode(itemId)}/audio",
            )
            put("audioDurationMs", item.audioDurationMs)
            put(
                "topicHint",
                keywords.map { it.jsonObject }.firstOrNull { it["type"]?.jsonPrimitive?.content == "TOPIC" }
                    ?.get("text")?.jsonPrimitive?.content ?: "Daily Listening",
            )
            put(
                "keywordHints",
                JsonArray(
                    item.content.array("targetKeywords")
                        .map { it.jsonPrimitive.content }
                        .filter(String::isNotBlank)
                        .distinct()
                        .take(5)
                        .map(::JsonPrimitive),
                ),
            )
            put("question", item.content["question"] ?: JsonNull)
            put("options", item.content["options"] ?: JsonArray(emptyList()))
            put("comprehensionFocus", item.content["comprehensionFocus"] ?: JsonNull)
            put("correctOptionKey", if (reveal) item.content["correctOptionKey"] ?: JsonNull else JsonNull)
            put(
                "summaryKeyPoints",
                if (reveal) item.content["summaryKeyPoints"] ?: JsonArray(emptyList()) else JsonArray(emptyList()),
            )
            put("sourceText", if (reveal) item.content.getValue("sourceText") else JsonNull)
            put("referenceMeanings", if (reveal) item.content.getValue("referenceMeanings") else JsonArray(emptyList()))
            put("attempt", attemptView(userId, attempt))
        }
    }

    suspend fun todayStatuses(userId: Long): JsonArray {
        val setting = settings.userSnapshot(userId)
        val language = setting.result.settings.learningLanguage ?: missing("LISTENING_SETTING_REQUIRED")
        return work.read {
            val sets = sets(userId, setting.learningDate).filter { it.learningLanguage == language }
            val sessions = sessions(userId)
            JsonArray(
                listOf("DICTATION", "COMPREHENSION", "SUMMARY").map { mode ->
                    val set = sets.firstOrNull { it.learningMode == mode }
                    val session = set?.let { source -> sessions.firstOrNull { it.setId == source.id } }
                    val official = session?.attempts.orEmpty().filter { it.purpose == "OFFICIAL" }
                    buildJsonObject {
                        put("learningMode", mode)
                        putId("dailySetId", set?.id)
                        putId("latestSessionId", session?.id)
                        put("status", set?.status)
                        put("latestSessionStatus", session?.status)
                        put("completedItemCount", set?.completedItemCount ?: 0)
                        put("evaluatedItemCount", session?.evaluatedItemCount ?: 0)
                        put("submittedItemCount", official.count { it.status !in setOf("READY", "IN_PROGRESS") })
                        put("terminalItemCount", official.count { it.status in terminal })
                        put("answerRevealedItemCount", official.count { it.answerRevealed })
                        put("physicalItemCount", set?.items?.size ?: 0)
                        put(
                            "readyItemCount",
                            set?.let { activeItems(it).count { value -> value.status == "READY" } } ?: 0,
                        )
                        put("targetItemCount", set?.targetItemCount ?: 0)
                        put("completed", set?.status == "COMPLETED" || session?.status == "COMPLETED")
                        put("failureReason", set?.generationFailure)
                        put("generationInProgress", set?.let { hasActiveJob(userId, it.id, "GENERATE") } ?: false)
                    }
                },
            )
        }
    }

    suspend fun result(userId: Long, sessionId: Long): JsonObject = work.read {
        val session = session(userId, sessionId) ?: missing()
        val official = session.attempts.filter { it.purpose == "OFFICIAL" }
        val scores = official.filter { it.coverage >= 1 }.mapNotNull { it.overallScore }
        buildJsonObject {
            putId("sessionId", sessionId)
            put("status", session.status)
            put("learnedItemCount", session.completedItemCount)
            put("evaluatedItemCount", session.evaluatedItemCount)
            put("averageScore", scores.takeIf { it.isNotEmpty() }?.average())
            put("coverage", official.takeIf { it.isNotEmpty() }?.map { it.coverage }?.average() ?: 0.0)
            put("attempts", JsonArray(ordered(session).map { attemptView(userId, it) }))
        }
    }

    suspend fun referenceAudio(userId: Long, itemId: Long): ListeningAudioState = work.read {
        val set = sets(userId).firstOrNull { it.items.any { item -> item.id == itemId } } ?: missing(
            "LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND",
        )
        val item = set.items.single { it.id == itemId }
        if (!playable(userId, item)) expired()
        checkNotNull(audio(userId, checkNotNull(item.audioId)))
    }

    suspend fun responseAudio(userId: Long, responseId: Long): ListeningAudioState = work.read {
        val response = sessions(userId).flatMap { it.attempts }.flatMap { it.tasks }.firstOrNull { it.id == responseId }
            ?: invalidUserAudio()
        val value = response.audioId?.let { audio(userId, it) } ?: invalidUserAudio()
        if (value.deletedAt != null || value.bytes == null || value.retentionUntil < nowUtc) invalidUserAudio()
        value
    }

    suspend fun uploadView(userId: Long, attemptId: Long): JsonObject = work.read {
        val response = sessions(userId).flatMap { it.attempts }.singleOrNull { it.id == attemptId }
            ?.tasks?.single { it.taskType == ListeningTaskType.REPEAT_AFTER_AUDIO } ?: missing(
            "LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND",
        )
        val audio = response.audioId?.let { audio(userId, it) } ?: invalidUserAudio()
        buildJsonObject {
            putId("taskResponseId", response.id)
            put("durationMs", response.audioDurationMs)
            put("rerecordCount", response.rerecordCount)
            put("retentionUntil", audio.retentionUntil.toString())
        }
    }

    suspend fun reportView(userId: Long, responseId: Long, report: ListeningReportState): JsonObject = work.read {
        val response =
            sessions(userId).flatMap { it.attempts }.flatMap { it.tasks }.singleOrNull { it.id == responseId }
                ?: missing("LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND")
        buildJsonObject {
            putId("reportId", report.id)
            putId("taskResponseId", responseId)
            put("status", "OPEN")
            put("consentToRetainAudio", report.consentToRetainAudio)
            put(
                "audioRetentionUntil",
                if (report.consentToRetainAudio) response.audioId?.let {
                    audio(
                        userId, it,
                    )?.retentionUntil?.toString()
                } else null,
            )
        }
    }

    suspend fun revealView(userId: Long, attemptId: Long): JsonObject = work.read {
        val session =
            sessions(userId).firstOrNull { it.attempts.any { attempt -> attempt.id == attemptId } } ?: missing(
                "LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND",
            )
        val attempt = session.attempts.single { it.id == attemptId }
        check(attempt.answerRevealed)
        val item = checkNotNull(set(userId, session.setId)).items.single { it.id == attempt.itemId }
        buildJsonObject {
            putId("attemptId", attemptId)
            put("sourceText", item.content.getValue("sourceText"))
            put("referenceMeanings", item.content.getValue("referenceMeanings"))
            put("excludedFromProgress", true)
            put("excludedFromProfile", true)
        }
    }

    suspend fun history(userId: Long, sessionId: Long): JsonObject = work.read {
        val session = session(userId, sessionId) ?: missing()
        val set = checkNotNull(set(userId, session.setId))

        // 이력 화면도 학습 화면과 동일한 정답 공개 조건을 적용한다.
        buildJsonObject {
            put("session", sessionView(session, set))
            put(
                "attempts",
                JsonArray(
                    ordered(session).map { attempt ->
                        val item = set.items.single { it.id == attempt.itemId }
                        val reveal = attempt.answerRevealed || attempt.status in terminal
                        buildJsonObject {
                            putId("itemId", item.id)
                            put("itemIndex", item.index)
                            put("sourceText", if (reveal) item.content.getValue("sourceText") else JsonNull)
                            put(
                                "referenceMeanings",
                                if (reveal) item.content.getValue("referenceMeanings") else JsonArray(emptyList()),
                            )
                            put("referenceAudio", availability(item.audioId?.let { audio(userId, it) }))
                            put("attempt", attemptView(userId, attempt))
                        }
                    },
                ),
            )
        }
    }

    suspend fun report(userId: Long, from: LocalDate, to: LocalDate, task: ListeningTaskType?): JsonObject {
        val language = settings.userSnapshot(userId).result.settings.learningLanguage
        return work.read {
            val sets = sets(userId).associateBy { it.id }
            val sessions = sessions(userId)

            // 이력은 모든 언어의 학습일을, 추세는 현재 언어의 실제 평가일을 기준으로 조회한다.
            buildJsonObject {
                put(
                    "sets",
                    JsonArray(
                        sets.values.filter { LocalDate.parse(it.learningDate) in from..to }.map { set ->
                            buildJsonObject {
                                putId("setId", set.id)
                                put("learningLanguage", set.learningLanguage)
                                put("learningDate", set.learningDate)
                                put("completedItemCount", set.completedItemCount)
                                put("targetItemCount", set.targetItemCount)
                            }
                        },
                    ),
                )
                put(
                    "evaluations",
                    JsonArray(
                        evaluations(sessions, sets, language, from, to, null).map { fact ->
                            buildJsonObject {
                                putId("attemptId", fact.attemptId)
                                put("taskType", fact.task.name)
                                put("confidence", fact.record.result.confidence)
                                put("score", fact.record.result.score)
                                put("evaluatedAt", fact.record.evaluatedAt)
                                put("metrics", json.encodeToJsonElement(fact.record.result.metrics))
                            }
                        },
                    ),
                )
                put(
                    "history",
                    JsonArray(
                        sessions.filter { session ->
                            LocalDate.parse(sets.getValue(session.setId).learningDate) in from..to &&
                                (task == null || session.attempts.any { attempt -> attempt.tasks.any { it.taskType == task && it.status != "NOT_SELECTED" } })
                        }.sortedByDescending { it.startedAt }.map { session ->
                            val official = session.attempts.filter { it.purpose == "OFFICIAL" }
                            buildJsonObject {
                                put("activityId", "LISTENING:${LearningPublicId.encode(session.id)}")
                                put("source", "LISTENING")
                                put("learningDate", sets.getValue(session.setId).learningDate)
                                put("title", "Daily Listening")
                                put("topic", JsonNull)
                                put("durationSeconds", session.actualDurationMs / 1000)
                                put(
                                    "overallScore",
                                    official.mapNotNull { it.overallScore }.takeIf { it.isNotEmpty() }?.average(),
                                )
                                put("completionStatus", session.status)
                                put(
                                    "evaluationStatus",
                                    if (official.all { it.status in terminal }) "COMPLETED" else "PENDING",
                                )
                            }
                        },
                    ),
                )
            }
        }
    }

    suspend fun dashboard(userId: Long, from: LocalDate?, to: LocalDate?, task: ListeningTaskType?): JsonObject {
        val snapshot = settings.userSnapshot(userId)
        val end = to ?: snapshot.learningDate
        val start = from ?: end.minusDays(29)
        val language = snapshot.result.settings.learningLanguage ?: missing("LISTENING_SETTING_REQUIRED")
        return work.read {
            val facts = evaluations(sessions(userId), sets(userId).associateBy { it.id }, language, start, end, task)
            val grouped = facts.filter { it.record.result.score != null }.groupBy { it.date to it.task }

            // 기존 지표 집계와 추천 순서를 그대로 사용하고 표현 DTO만 구성한다.
            buildJsonObject {
                put("learningLanguage", language)
                put("from", start.toString())
                put("to", end.toString())
                put(
                    "metrics",
                    JsonArray(
                        ListeningProfiles.profiles(metricHistory(userId, language), task).map { profile ->
                            buildJsonObject {
                                put("metric", profile.metric)
                                put("score", profile.score)
                                put("sampleCount", profile.sampleCount)
                                put("confidence", profile.confidence)
                                put("weaknessState", profile.weaknessState)
                                put("growthActive", profile.growthActive)
                                put("growthDelta", profile.recentDelta)
                            }
                        },
                    ),
                )
                put(
                    "taskTrends",
                    JsonArray(
                        grouped.entries.sortedWith(compareBy({ it.key.first }, { it.key.second.ordinal }))
                            .map { (key, values) ->
                                buildJsonObject {
                                    put("taskType", key.second.name)
                                    put("date", key.first.toString())
                                    put("averageScore", values.map { checkNotNull(it.record.result.score) }.average())
                                    put("sampleCount", values.size)
                                }
                            },
                    ),
                )
                put(
                    "recommendations",
                    JsonArray(
                        recommendations(userId, language).filter { it.status == "ACTIVE" }
                            .sortedWith(
                                compareBy<ListeningRecommendationState> { it.priority }.thenByDescending { it.createdAt },
                            )
                            .take(2)
                            .map { recommendation ->
                                buildJsonObject {
                                    putId("recommendationId", recommendation.id)
                                    put("targetMetric", recommendation.targetMetric)
                                    put("recommendedActivity", recommendation.recommendedActivity)
                                    put("recommendedTask", recommendation.recommendedTask)
                                    put("reason", recommendation.reason)
                                    put("ctaLabel", recommendation.ctaLabel)
                                    put("priority", recommendation.priority)
                                    put("status", recommendation.status)
                                    put("expiresAt", recommendation.expiresAt)
                                }
                            },
                    ),
                )
            }
        }
    }

    suspend fun metricTrends(
        userId: Long, language: String, from: LocalDate, to: LocalDate, task: ListeningTaskType?,
    ): JsonArray = work.read {
        val facts = evaluations(sessions(userId), sets(userId).associateBy { it.id }, language, from, to, task)
        val grouped = facts.flatMap { fact ->
            fact.record.result.metrics.filter { it.type.isNotBlank() && it.score != null }.map { metric ->
                Triple(fact.date, fact.task, metric.type.trim().uppercase()) to checkNotNull(metric.score)
            }
        }.groupBy({ it.first }, { it.second })
        JsonArray(
            grouped.entries.sortedWith(compareBy({ it.key.first }, { it.key.second.ordinal }, { it.key.third }))
                .map { (key, scores) ->
                    buildJsonObject {
                        put("taskType", key.second.name)
                        put("metric", key.third)
                        put("date", key.first.toString())
                        put("averageScore", scores.average())
                        put("sampleCount", scores.size)
                    }
                },
        )
    }

    suspend fun dismiss(userId: Long, recommendationId: Long) {
        work.write(userId) {
            val recommendation = recommendation(userId, recommendationId) ?: throw NoSuchElementException()
            saveRecommendation(recommendation.copy(status = "DISMISSED", dismissedAt = nowUtc.toString()))
        }
    }

    private fun evaluations(
        sessions: List<ListeningSessionState>, sets: Map<Long, ListeningSetState>, language: String?,
        from: LocalDate, to: LocalDate, task: ListeningTaskType?,
    ): List<EvaluationFact> = sessions.flatMap { session ->
        if (sets.getValue(session.setId).learningLanguage != language) return@flatMap emptyList()
        session.attempts.filter { it.purpose == "OFFICIAL" && !it.answerRevealed }.flatMap { attempt ->
            attempt.tasks.filter { task == null || it.taskType == task }.flatMap { response ->
                // 체크포인트 이전 JSON에는 마지막 평가 시각만 존재한다. 기록된 시각만 사용한다.
                val records = response.evaluationRecords.ifEmpty {
                    response.evaluation?.let { result ->
                        response.evaluatedAt?.let {
                            listOf(
                                ListeningEvaluationRecord(response.id, it, result),
                            )
                        }
                    }.orEmpty()
                }
                records.filter { it.result.evaluable && LocalDateTime.parse(it.evaluatedAt).toLocalDate() in from..to }
                    .map { EvaluationFact(attempt.id, response.taskType, it) }
            }
        }
    }.sortedBy { it.record.evaluatedAt }

    private data class EvaluationFact(
        val attemptId: Long, val task: ListeningTaskType, val record: ListeningEvaluationRecord,
    ) {
        val date: LocalDate get() = LocalDateTime.parse(record.evaluatedAt).toLocalDate()
    }

    suspend fun policy(): JsonObject {
        val value = settings.listeningPolicy()
        return buildJsonObject {
            put("enabled", value.enabled)
            put("defaultItemCount", value.defaultItemCount)
            put("minItemCount", value.minItemCount)
            put("maxItemCount", value.maxItemCount)
            put("hardItemLimit", value.hardItemLimit)
            put("resumeHours", value.resumeHours)
            put("referenceAudioRetentionDays", value.referenceAudioRetentionDays)
            put("userAudioRetentionDays", value.userAudioRetentionDays)
            put("reportedAudioRetentionDays", value.reportedAudioRetentionDays)
            put("automaticRetryLimit", value.automaticRetryLimit)
            put("manualRetryLimit", value.manualRetryLimit)
            put("practiceAttemptLimit", value.practiceAttemptLimit)
            put("profilePolicyVersion", value.profilePolicyVersion)
            put("modelConfigVersion", value.modelConfigVersion)
            put("referenceTtsRegenerationEnabled", value.referenceTtsRegenerationEnabled)
        }
    }

    private fun ListeningTransaction.setView(set: ListeningSetState, policy: ListeningPolicy) = buildJsonObject {
        putId("dailySetId", set.id)
        put("learningDate", set.learningDate)
        put("originLanguage", set.originLanguage)
        put("learningLanguage", set.learningLanguage)
        put("learningMode", set.learningMode)
        put("difficulty", set.difficulty)
        put("status", set.status)
        put("targetItemCount", set.targetItemCount)
        put("physicalItemCount", set.items.size)
        put("readyItemCount", activeItems(set).count { it.status == "READY" })
        put("completedItemCount", set.completedItemCount)
        put("failureReason", set.generationFailure)
        put("generationInProgress", hasActiveJob(set.userId, set.id, "GENERATE"))
        put(
            "items",
            JsonArray(
                activeItems(set).map { item ->
                    buildJsonObject {
                        val duration = item.content["durationDemand"] as? JsonObject
                        putId("itemId", item.id)
                        put("itemIndex", item.index)
                        put("replacementSequence", item.replacementSequence)
                        put("status", item.status)
                        put("playable", playable(set.userId, item))
                        put("audioDurationMs", item.audioDurationMs)
                        put(
                            "ttsRetryAllowed",
                            item.status == "NOT_EVALUABLE" && item.ttsRetries < policy.manualRetryLimit && item.errorCode !in setOf(
                                "AUDIO_TOO_SHORT", "AUDIO_TOO_LONG",
                            ),
                        )
                        put(
                            "durationValidationStatus",
                            when {
                                duration == null -> "LEGACY_UNVALIDATED"; item.status == "READY" -> "VALIDATED"; item.status == "NOT_EVALUABLE" -> "FAILED"; else -> "PENDING"
                            },
                        )
                        put("durationPolicyVersion", duration?.get("policyVersion") ?: JsonNull)
                    }
                },
            ),
        )
    }

    private fun ListeningTransaction.sessionView(session: ListeningSessionState, set: ListeningSetState) =
        buildJsonObject {
            putId("sessionId", session.id)
            putId("dailySetId", set.id)
            put("status", session.status)
            put("selectedTaskTypes", json.encodeToJsonElement(session.selectedTaskTypes))
            put("completedItemCount", session.completedItemCount)
            put("evaluatedItemCount", session.evaluatedItemCount)
            put("actualDurationMs", session.actualDurationMs)
            put("startedAt", session.startedAt)
            put("lastActivityAt", session.lastActivityAt)
            put("resumableUntil", session.resumableUntil)
            put("attempts", JsonArray(ordered(session).map { attemptView(session.userId, it) }))
            put("dailySetStatus", set.status)
            put("targetItemCount", set.targetItemCount)
            put(
                "attachedItemCount",
                session.attempts.filter { it.purpose == "OFFICIAL" }.map { it.itemIndex }.distinct().size,
            )
            put("generationFailureMessage", set.generationFailure)
            put("pendingItemCount", activeItems(set).count { it.status == "TTS_PENDING" })
            put("generationInProgress", hasActiveJob(session.userId, set.id, "GENERATE"))
        }

    private fun ListeningTransaction.attemptView(userId: Long, attempt: ListeningAttemptState) = buildJsonObject {
        putId("attemptId", attempt.id)
        putId("itemId", attempt.itemId)
        put("attemptNo", attempt.attemptNo)
        put("evaluationPurpose", attempt.purpose)
        put("status", attempt.status)
        put("answerRevealed", attempt.answerRevealed)
        put("contentOverallScore", attempt.contentOverallScore)
        put("listeningIndependenceScore", attempt.listeningIndependenceScore)
        put("overallScore", attempt.overallScore)
        put(
            "playbackSummary",
            buildJsonObject {
                put("normalPlaybackCount", attempt.playbackEvents.values.count { it == "NORMAL" })
                put("slowPlaybackCount", attempt.playbackEvents.values.count { it == "SLOW" })
                put("policyVersion", "listening-independence")
            },
        )
        put("evaluatedTaskCount", attempt.evaluatedTaskCount)
        put("coverage", attempt.coverage)
        put("errorCode", attempt.tasks.firstOrNull { it.evaluationErrorCode != null }?.evaluationErrorCode)
        put("tasks", JsonArray(attempt.tasks.sortedBy { it.taskType.name }.map { taskView(userId, it) }))
        put("itemIndex", attempt.itemIndex)
    }

    private fun ListeningTransaction.taskView(userId: Long, task: ListeningResponseState) = buildJsonObject {
        val audio = task.audioId?.let { audio(userId, it) }
        putId("taskResponseId", task.id)
        put("taskType", task.taskType.name)
        put("status", if (task.status == "PENDING") "READY" else task.status)
        put("answerText", task.answerText)
        put(
            "audioUploaded",
            audio?.let { it.bytes != null && it.deletedAt == null && it.retentionUntil >= nowUtc } ?: false,
        )
        put("audioDurationMs", task.audioDurationMs)
        put("audioAvailability", availability(audio))
        put("rerecordCount", task.rerecordCount)
        put(
            "assistanceLevel",
            when {
                task.assistanceUsage.any { it.type == "SHOW_ANSWER" } -> "GUIDED"
                task.assistanceUsage.any { it.type in setOf("TOPIC_HINT", "KEYWORD_HINT") } -> "ASSISTED"
                else -> "INDEPENDENT"
            },
        )
        put("assistanceUsage", json.encodeToJsonElement(task.assistanceUsage))
        put("evaluationErrorCode", task.evaluationErrorCode)
        put(
            "evaluation",
            task.evaluation?.let { result ->
                buildJsonObject {
                    putId("evaluationId", task.evaluationRecords.lastOrNull()?.id ?: task.id)
                    put("taskType", task.taskType.name)
                    put("evaluable", result.evaluable)
                    put("score", result.score)
                    put("confidence", result.confidence)
                    put("reasonCode", result.reasonCode)
                    put("metrics", json.encodeToJsonElement(result.metrics))
                    put("strengths", json.encodeToJsonElement(result.strengths))
                    put("improvements", json.encodeToJsonElement(result.improvements))
                    put("recommendedAnswers", json.encodeToJsonElement(result.recommendedInterpretations))
                    put("evaluatedAt", task.evaluatedAt)
                }
            } ?: JsonNull,
        )
    }

    private fun ListeningTransaction.availability(audio: ListeningAudioState?) = buildJsonObject {
        val expired = audio?.let { it.deletedAt != null || it.retentionUntil < nowUtc } ?: false
        put("available", audio?.bytes != null && !expired)
        put("expired", expired)
        put("retentionUntil", audio?.retentionUntil?.toString())
        put("deletedAt", audio?.deletedAt?.toString())
    }

    private fun ListeningTransaction.playable(userId: Long, item: ListeningItemState) = item.status == "READY" &&
        item.audioId?.let {
            audio(
                userId, it,
            )?.let { value -> value.bytes != null && value.deletedAt == null && value.retentionUntil >= nowUtc }
        } == true

    private fun activeItems(set: ListeningSetState) =
        set.items.sortedBy { it.replacementSequence }.groupBy { it.index }.values.map { items ->
            items.fold(
                items.first(),
            ) { previous, item -> if (item.status == "READY" || previous.status != "READY") item else previous }
        }.sortedBy { it.index }

    private fun ordered(session: ListeningSessionState) =
        session.attempts.sortedWith(compareBy({ it.itemIndex }, { it.attemptNo }))

    private fun JsonObject.array(key: String) = (this[key] as? JsonArray).orEmpty()
    private fun JsonObjectBuilder.putId(name: String, id: Long?) = put(name, id?.let(LearningPublicId::encode))
    private fun missing(code: String = "SESSION_NOT_FOUND"): Nothing =
        throw LearningBusinessException(code, "Listening 학습 기록을 찾을 수 없습니다.")

    private fun invalidUserAudio(): Nothing =
        throw LearningBusinessException("LISTENING_AUDIO_INVALID", "Listening 사용자 Audio를 찾을 수 없거나 보관 기간이 종료되었습니다.")

    private fun expired(): Nothing =
        throw LearningBusinessException("LANGUAGE_LEARNING_REVIEW_EXPIRED", "Listening Audio 보관 기간이 종료되었습니다.")
}
