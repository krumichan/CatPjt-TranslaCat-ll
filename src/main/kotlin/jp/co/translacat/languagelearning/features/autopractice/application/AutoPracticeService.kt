package jp.co.translacat.languagelearning.features.autopractice.application

import jp.co.translacat.languagelearning.features.autopractice.domain.*
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettings
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** 기존 학습 경로가 가진 저장·실행·판정 규칙을 유지하는 자동 생성 입구다. */
internal interface AutoPracticePublisher {
    /** 네이티브 세트를 준비하며 같은 작업은 항상 같은 저장 원본을 반환한다. */
    suspend fun enqueue(owner: Long, job: AutoPracticeJob): String

    /** 화면 조회는 저장된 준비 상태만 읽는다. */
    suspend fun state(owner: Long, job: AutoPracticeJob): String
}

/** 설정 저장과 스케줄러는 같은 활성 플랜 및 관리자 한도를 사용한다. */
internal class AutoPracticeService(
    private val store: AutoPracticeStore,
    private val settings: SettingsServiceOperations,
    private val publishers: Map<AutoPracticeArea, AutoPracticePublisher>,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** 현재 언어의 최근 준비 분량을 기존 학습 상태에서 조회한다. 이월분의 배정일은 변경하지 않는다. */
    suspend fun workspace(owner: Long): AutoPracticeWorkspace {
        val user = settings.userSnapshot(owner).result.settings
        val admin = settings.adminPolicy()
        val listeningCap = listeningCap()
        val today = clock.instant().atZone(ZoneId.of(user.timezone)).toLocalDate()
        val view = store.read {
            val assignments = if (user.configured) assignments(
                owner, checkNotNull(user.originLanguage), checkNotNull(user.learningLanguage),
                today.minusDays((admin.reviewAvailableDays - 1).coerceAtLeast(2).toLong()), today,
            ) else emptyList()
            AutoPracticeWorkspace(
                user.timezone, plans(owner), effectiveLimits(limits(), listeningCap),
                admin.maxDailySentenceCount, publishers.keys, assignments,
            )
        }
        val jobs = view.jobs.map { job ->
            val publisher = publishers[job.area]
            if (job.resourceId == null || publisher == null) job else job.copy(state = publisher.state(owner, job))
        }
        return view.copy(jobs = jobs)
    }

    /** 활성화는 사용자 잠금 안에서 기존 활성 플랜과 교체한다. */
    suspend fun save(owner: Long, id: String?, input: AutoPracticePlanInput): AutoPracticePlan {
        val user = settings.userSnapshot(owner).result.settings
        val origin = user.originLanguage ?: invalid("모국어를 먼저 설정해 주세요.")
        val learning = user.learningLanguage ?: invalid("학습 언어를 먼저 설정해 주세요.")
        val cap = settings.adminPolicy().maxDailySentenceCount
        val listeningCap = listeningCap()
        val now = utc(clock.instant())
        return store.writeOwner(owner, now) {
            AutoPracticePolicy.validatePlan(input, effectiveLimits(limits(), listeningCap), cap)
            if (input.areas.any { (area, quantity) -> quantity.total > 0 && area !in publishers }) {
                invalid("이 환경에서 자동 생성할 수 없는 유형이 포함되어 있습니다.")
            }

            val previous = id?.let { plan(owner, it) ?: invalid("학습 플랜을 찾을 수 없습니다.") }
            if (previous != null && input.revision != previous.plan.revision) {
                invalid("학습 플랜이 변경되었습니다. 새로고침 후 다시 시도해 주세요.")
            }
            if (previous != null && !pair(previous.plan).matches(origin, learning)) {
                invalid("현재 학습 언어쌍의 학습 플랜만 수정할 수 있습니다.")
            }
            val value = AutoPracticePlan(
                id ?: UUID.randomUUID().toString(), (previous?.plan?.revision ?: 0L) + 1L, input.name.trim(),
                input.studyTime, input.areas.filterValues { it.total > 0 }, origin, learning, input.enabled,
            )

            // 새 활성 플랜을 쓰기 전에 현재 활성 슬롯을 비우고 기존 준비 분량은 그대로 둔다.
            if (value.enabled) {
                active(owner)?.takeUnless { it.plan.id == value.id }?.let { current ->
                    writePlan(owner, paused(current.plan, "REPLACED"), null, null, update = true)
                }
            }
            val activatedAt = if (value.enabled) previous?.activatedAt ?: now else null
            writePlan(owner, value, activatedAt, now.takeIf { value.enabled }, update = previous != null)
            value
        }
    }

    /** 플랜이 삭제돼도 저장된 학습 원본과 일일 배정 식별자가 남는다. */
    suspend fun delete(owner: Long, id: String) {
        store.writeOwner(owner, utc(clock.instant())) {
            if (!removePlan(owner, id)) invalid("학습 플랜을 찾을 수 없습니다.")
        }
    }

    suspend fun limits(): Map<AutoPracticeArea, Int> = store.read { limits() }

    /** 권한을 확인한 관리 요청에서 열두 유형의 값을 한 트랜잭션으로 저장한다. */
    suspend fun updateLimits(values: Map<AutoPracticeArea, Int>): Map<AutoPracticeArea, Int> {
        AutoPracticePolicy.validateLimits(values)
        return store.write { saveLimits(values); limits() }
    }

    /** 제한된 수의 사용자와 작업만 처리하고 한 소유자의 실패로 다른 사용자 실행을 막지 않는다. */
    suspend fun tick(onFailure: (Exception) -> Unit) {
        val now = clock.instant()
        val utcNow = utc(now)
        val duePlans = store.read { due(utcNow) }
        val writingCap = settings.adminPolicy().maxDailySentenceCount
        val listeningCap = listeningCap()
        for (plan in duePlans) {
            try {
                schedule(plan, now, writingCap, listeningCap)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // 기록은 남기되 실패한 앞 플랜이 due 목록을 계속 차지하지 않도록 다음 확인을 늦춘다.
                onFailure(failure)
                store.write { defer(plan.userId, plan.plan.id, utcNow.plusMinutes(5)) }
            }
        }
        for (job in store.read { pending(utcNow) }) {
            try {
                dispatch(job, writingCap, listeningCap)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
            }
        }
    }

    /** 사용자 시각으로 배정일을 판단하고 자정 전에는 아직 오지 않은 답안을 추정하지 않는다. */
    private suspend fun schedule(selected: AutoPracticeSavedPlan, now: Instant, writingCap: Int, listeningCap: Int) {
        val owner = selected.userId
        val user = settings.userSnapshot(owner).result.settings
        val zone = ZoneId.of(user.timezone)
        val today = now.atZone(zone).toLocalDate()
        val utcNow = utc(now)
        store.writeOwner(owner, utcNow) {
            val current = active(owner)?.takeIf {
                it.plan.id == selected.plan.id && it.plan.revision == selected.plan.revision
            } ?: return@writeOwner
            val plan = current.plan
            if (!pair(plan).matches(user.originLanguage, user.learningLanguage)) {
                writePlan(owner, paused(plan, "LANGUAGE_CHANGED"), null, null, true)
                return@writeOwner
            }

            val first = placement(owner, plan.originLanguage, plan.learningLanguage)?.atOffset(ZoneOffset.UTC)
                ?.atZoneSameInstant(zone)?.toLocalDate()
            val answered = submissions(
                owner, plan.originLanguage, plan.learningLanguage,
                utc(today.minusDays(2).atStartOfDay(zone).toInstant()), utcNow.plusNanos(1_000),
            ).mapTo(mutableSetOf()) { it.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate() }
            val activated = checkNotNull(current.activatedAt).atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate()

            // 이월분은 native 원본에 둔다. 이틀 동안 답안이 없을 때 현재 플랜만 끈다.
            if (AutoPracticePolicy.shouldPause(today, activated, first, answered)) {
                writePlan(owner, paused(plan, "INACTIVITY"), null, null, true)
                return@writeOwner
            }
            val time = LocalTime.parse(plan.studyTime)
            if (AutoPracticePolicy.withinLimits(plan.areas, effectiveLimits(limits(), listeningCap), writingCap)) {
                AutoPracticePolicy.dueDates(now, zone, time, first, answered).filter { !changesLanguages(user, it) }
                    .forEach { date -> reserve(owner, plan, date, utcNow) }
            }

            val midnight = today.plusDays(1).atStartOfDay(zone).toInstant()
            val todayWindow = AutoPracticePolicy.preparationStartsOn(today, time, zone)
            val tomorrowWindow = AutoPracticePolicy.preparationStartsOn(today.plusDays(1), time, zone)
            val watchLateSubmission = if (tomorrowWindow <= now) now.plusSeconds(60) else tomorrowWindow
            val next = listOf(midnight, todayWindow, watchLateSubmission).filter { it > now }.min()
            defer(owner, plan.id, utc(next))
        }
    }

    /** 작업 예약 뒤 플랜·날짜·관리자 한도를 다시 확인하고, 모델 호출이 있는 영역 작업과 잠금을 분리한다. */
    private suspend fun dispatch(queued: AutoPracticeQueuedJob, writingCap: Int, listeningCap: Int) {
        val job = queued.job
        val owner = queued.userId
        val now = utc(clock.instant())
        val user = settings.userSnapshot(owner).result.settings
        val date = LocalDate.parse(job.learningDate)
        val lease = UUID.randomUUID().toString()
        val allowed = store.writeOwner(owner, now) {
            val selected = active(owner)?.plan
            if (!claim(queued, now, lease)) return@writeOwner false
            val reason = when {
                selected == null || selected.id != job.planId || selected.revision != job.planRevision -> "PLAN_CHANGED"
                !pair(selected).matches(user.originLanguage, user.learningLanguage) -> "LANGUAGE_CHANGED"
                changesLanguages(user, date) -> "LANGUAGE_CHANGED"
                date.isBefore(now.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of(user.timezone)).toLocalDate()) -> "DAY_EXPIRED"
                queued.attempts >= 3 -> "RETRY_LIMIT"
                !AutoPracticePolicy.withinLimits(selected.areas, effectiveLimits(limits(), listeningCap), writingCap) ->
                    "LIMIT_CHANGED"
                job.area !in publishers -> "AREA_UNAVAILABLE"
                else -> null
            }
            if (reason == null) true else {
                finish(queued, lease, "BLOCKED", now, null, reason)
                false
            }
        }
        if (!allowed) return

        try {
            val resource = publishers.getValue(job.area).enqueue(owner, job)
            store.write { finish(queued, lease, "QUEUED", now, resource, null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val code = (failure as? LearningBusinessException)?.code ?: "PREPARATION_FAILED"
            val state = if (queued.attempts >= 2) "FAILED" else "PENDING"
            store.write { finish(queued, lease, state, now.plusMinutes(2), null, code) }
        }
    }

    // Listening 세트의 기존 운영 상한도 함께 확인한다. 준비하지 않는 환경에는 조회가 필요 없다.
    private suspend fun listeningCap(): Int {
        if (publishers.keys.none { it.name.startsWith("LISTENING_") }) return 0
        val policy = settings.listeningPolicy()
        return if (policy.enabled) minOf(policy.maxItemCount, policy.hardItemLimit) else 0
    }

    private fun effectiveLimits(configured: Map<AutoPracticeArea, Int>, listeningCap: Int) =
        configured.mapValues { (area, maximum) ->
            if (area.name.startsWith("LISTENING_")) minOf(maximum, listeningCap) else maximum
        }

    // 아직 승격되지 않은 다른 언어쌍에 이전 설정으로 미래 분량을 만들지 않는다.
    private fun changesLanguages(user: UserSettings, date: LocalDate): Boolean =
        user.pendingEffectiveDate?.let { date >= it } == true &&
            (user.pendingOriginLanguage?.let { it != user.originLanguage } == true ||
                user.pendingLearningLanguage?.let { it != user.learningLanguage } == true)

    private fun utc(now: Instant) = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
    private fun pair(plan: AutoPracticePlan) = ConfiguredLanguagePair(plan.originLanguage, plan.learningLanguage)
    private fun paused(plan: AutoPracticePlan, reason: String) =
        plan.copy(enabled = false, revision = plan.revision + 1, pauseReason = reason)

    private fun invalid(message: String): Nothing =
        throw LearningBusinessException("LANGUAGE_LEARNING_SETTING_INVALID", message)
}
