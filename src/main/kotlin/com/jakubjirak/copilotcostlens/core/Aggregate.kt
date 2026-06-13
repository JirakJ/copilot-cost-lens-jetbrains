package com.jakubjirak.copilotcostlens.core

import com.jakubjirak.copilotcostlens.model.CostSource
import com.jakubjirak.copilotcostlens.model.RepoRef
import com.jakubjirak.copilotcostlens.model.UsageEvent
import com.jakubjirak.copilotcostlens.pricing.creditsToUsd
import java.util.Calendar
import java.util.GregorianCalendar

const val ALL_TIME = "all"

// --- DTOs — field names match exactly what the webview dashboard reads ------

data class ModelSummary(
    val model: String,
    var credits: Double = 0.0,
    var usd: Double = 0.0,
    var requestCount: Int = 0,
    var inputTokens: Long = 0,
    var outputTokens: Long = 0,
    var cachedTokens: Long = 0,
    var cacheWriteTokens: Long = 0,
)

data class ProviderSummary(val provider: String, var credits: Double = 0.0, var usd: Double = 0.0, var requestCount: Int = 0)

data class DayPoint(val day: String, var credits: Double = 0.0, var usd: Double = 0.0)

data class MonthPoint(val month: String, var credits: Double = 0.0, var usd: Double = 0.0)

data class SessionSummary(
    val sessionId: String,
    val provider: String,
    var credits: Double = 0.0,
    var usd: Double = 0.0,
    var requestCount: Int = 0,
    val models: MutableList<String> = mutableListOf(),
    var lastTimestamp: Long = 0,
)

data class RepoSummary(
    val repo: RepoRef,
    var credits: Double = 0.0,
    var usd: Double = 0.0,
    var inputTokens: Long = 0,
    var outputTokens: Long = 0,
    var cachedTokens: Long = 0,
    var cacheWriteTokens: Long = 0,
    var requestCount: Int = 0,
    var sessionCount: Int = 0,
    var models: List<ModelSummary> = emptyList(),
    var providers: List<String> = emptyList(),
    var lastActivity: Long = 0,
    var hasEstimates: Boolean = false,
)

data class GroupSummary(
    val name: String,
    val repos: List<RepoSummary>,
    var credits: Double = 0.0,
    var usd: Double = 0.0,
    var inputTokens: Long = 0,
    var outputTokens: Long = 0,
    var cachedTokens: Long = 0,
    var cacheWriteTokens: Long = 0,
    var requestCount: Int = 0,
    var sessionCount: Int = 0,
    var models: List<ModelSummary> = emptyList(),
    var hasEstimates: Boolean = false,
)

data class MonthReport(
    val month: String,
    val totalCredits: Double,
    val totalUsd: Double,
    val copilotCredits: Double,
    val copilotUsd: Double,
    val includedCredits: Int,
    val usedPercent: Double,
    val forecastCredits: Double,
    val forecastUsd: Double,
    val prevMonth: String?,
    val prevMonthUsd: Double?,
    val allowanceExhaustion: String?,
    val monthsSeries: List<MonthPoint>,
    val heatmap: List<DayPoint>,
    val repos: List<RepoSummary>,
    val groups: List<GroupSummary>,
    val models: List<ModelSummary>,
    val providers: List<ProviderSummary>,
    val days: List<DayPoint>,
    val requestCount: Int,
    val sessionCount: Int,
    val hasEstimates: Boolean,
)

data class RepoDetail(
    val summary: RepoSummary,
    val days: List<DayPoint>,
    val providers: List<ProviderSummary>,
    val topSessions: List<SessionSummary>,
    val firstActivity: Long,
    val month: String,
)

data class GroupDetail(val group: GroupSummary, val days: List<DayPoint>, val providers: List<ProviderSummary>, val month: String)

// --- date helpers (local time) ---------------------------------------------

private fun cal(ts: Long) = GregorianCalendar().apply { timeInMillis = ts }

fun monthKey(ts: Long): String {
    val c = cal(ts)
    return "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
}

fun dayKey(ts: Long): String {
    val c = cal(ts)
    return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

fun currentMonthKey(now: Long = System.currentTimeMillis()) = monthKey(now)

fun availableMonths(events: List<UsageEvent>, now: Long = System.currentTimeMillis()): List<String> {
    val months = sortedSetOf(currentMonthKey(now))
    events.forEach { months += monthKey(it.timestamp) }
    return months.toList().sortedDescending()
}

// --- builders ---------------------------------------------------------------

private fun accModel(map: LinkedHashMap<String, ModelSummary>, e: UsageEvent) {
    val m = map.getOrPut(e.model) { ModelSummary(e.model) }
    m.credits += e.credits
    m.usd = creditsToUsd(m.credits)
    m.requestCount++
    m.inputTokens += e.inputTokens
    m.outputTokens += e.outputTokens
    m.cachedTokens += e.cachedTokens
    m.cacheWriteTokens += e.cacheWriteTokens
}

fun buildMonthReport(
    events: List<UsageEvent>,
    month: String,
    includedCredits: Int,
    groups: Map<String, List<String>> = emptyMap(),
    now: Long = System.currentTimeMillis(),
): MonthReport {
    val inMonth = if (month == ALL_TIME) events else events.filter { monthKey(it.timestamp) == month }

    val repoMap = LinkedHashMap<String, MutableList<UsageEvent>>()
    val modelMap = LinkedHashMap<String, ModelSummary>()
    val providerMap = LinkedHashMap<String, ProviderSummary>()
    val dayMap = LinkedHashMap<String, DayPoint>()
    val sessions = HashSet<String>()
    var totalCredits = 0.0
    var copilotCredits = 0.0
    var hasEstimates = false

    for (e in inMonth) {
        totalCredits += e.credits
        if (e.provider.id != "claude-code") copilotCredits += e.credits
        sessions += e.sessionId
        if (e.costSource == CostSource.ESTIMATED) hasEstimates = true
        repoMap.getOrPut(e.repo.name) { mutableListOf() } += e
        accModel(modelMap, e)
        val p = providerMap.getOrPut(e.provider.id) { ProviderSummary(e.provider.id) }
        p.credits += e.credits; p.usd = creditsToUsd(p.credits); p.requestCount++
        val d = dayMap.getOrPut(dayKey(e.timestamp)) { DayPoint(dayKey(e.timestamp)) }
        d.credits += e.credits; d.usd = creditsToUsd(d.credits)
    }

    val repos = repoMap.values.map { summarizeRepo(it) }.sortedByDescending { it.credits }
    val included = if (month == ALL_TIME) 0 else includedCredits

    var prevMonth: String? = null
    var prevMonthUsd: Double? = null
    if (month != ALL_TIME) {
        prevMonth = previousMonthKey(month)
        prevMonthUsd = creditsToUsd(events.filter { monthKey(it.timestamp) == prevMonth }.sumOf { it.credits })
    }

    val days = dayMap.values.sortedBy { it.day }
    return MonthReport(
        month = month,
        totalCredits = totalCredits,
        totalUsd = creditsToUsd(totalCredits),
        copilotCredits = copilotCredits,
        copilotUsd = creditsToUsd(copilotCredits),
        includedCredits = included,
        usedPercent = if (included > 0) copilotCredits / included * 100 else 0.0,
        forecastCredits = forecast(month, totalCredits, days, now),
        forecastUsd = creditsToUsd(forecast(month, totalCredits, days, now)),
        prevMonth = prevMonth,
        prevMonthUsd = prevMonthUsd,
        allowanceExhaustion = allowanceExhaustion(month, copilotCredits, included, now),
        monthsSeries = buildMonthsSeries(events),
        heatmap = buildHeatmap(events, now),
        repos = repos,
        groups = buildGroupSummaries(repos, groups),
        models = modelMap.values.sortedByDescending { it.credits },
        providers = providerMap.values.sortedByDescending { it.credits },
        days = days,
        requestCount = inMonth.size,
        sessionCount = sessions.size,
        hasEstimates = hasEstimates,
    )
}

private fun summarizeRepo(events: List<UsageEvent>): RepoSummary {
    val s = RepoSummary(events.first().repo)
    val models = LinkedHashMap<String, ModelSummary>()
    val providers = sortedSetOf<String>()
    val sessions = HashSet<String>()
    for (e in events) {
        s.credits += e.credits
        s.inputTokens += e.inputTokens
        s.outputTokens += e.outputTokens
        s.cachedTokens += e.cachedTokens
        s.cacheWriteTokens += e.cacheWriteTokens
        sessions += e.sessionId
        providers += e.provider.id
        s.lastActivity = maxOf(s.lastActivity, e.timestamp)
        if (e.costSource == CostSource.ESTIMATED) s.hasEstimates = true
        accModel(models, e)
    }
    s.usd = creditsToUsd(s.credits)
    s.requestCount = events.size
    s.sessionCount = sessions.size
    s.models = models.values.sortedByDescending { it.credits }
    s.providers = providers.toList()
    return s
}

fun previousMonthKey(month: String): String {
    val (y, m) = month.split("-").map { it.toInt() }
    val c = GregorianCalendar(y, m - 1, 1)
    c.add(Calendar.MONTH, -1)
    return "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
}

private fun buildMonthsSeries(events: List<UsageEvent>): List<MonthPoint> {
    val map = LinkedHashMap<String, MonthPoint>()
    for (e in events) {
        val p = map.getOrPut(monthKey(e.timestamp)) { MonthPoint(monthKey(e.timestamp)) }
        p.credits += e.credits; p.usd = creditsToUsd(p.credits)
    }
    return map.values.sortedBy { it.month }
}

fun buildHeatmap(events: List<UsageEvent>, now: Long = System.currentTimeMillis(), weeks: Int = 26): List<DayPoint> {
    val days = weeks * 7
    val totals = HashMap<String, Double>()
    val c = cal(now)
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    c.add(Calendar.DAY_OF_MONTH, -(days - 1))
    val start = c.timeInMillis
    for (e in events) if (e.timestamp >= start) {
        val k = dayKey(e.timestamp)
        totals[k] = (totals[k] ?: 0.0) + e.credits
    }
    val out = ArrayList<DayPoint>(days)
    val cur = GregorianCalendar().apply { timeInMillis = start }
    repeat(days) {
        val k = dayKey(cur.timeInMillis)
        val credits = totals[k] ?: 0.0
        out += DayPoint(k, credits, creditsToUsd(credits))
        cur.add(Calendar.DAY_OF_MONTH, 1)
    }
    return out
}

private fun forecast(month: String, totalCredits: Double, days: List<DayPoint>, now: Long): Double {
    if (month == ALL_TIME || month != currentMonthKey(now) || days.isEmpty()) return totalCredits
    val c = cal(now)
    val daysInMonth = c.getActualMaximum(Calendar.DAY_OF_MONTH)
    val elapsed = maxOf(1, c.get(Calendar.DAY_OF_MONTH))
    return totalCredits / elapsed * daysInMonth
}

private fun allowanceExhaustion(month: String, copilotCredits: Double, included: Int, now: Long): String? {
    if (month != currentMonthKey(now) || included <= 0 || copilotCredits <= 0) return null
    val c = cal(now)
    val daysInMonth = c.getActualMaximum(Calendar.DAY_OF_MONTH)
    val elapsed = maxOf(1, c.get(Calendar.DAY_OF_MONTH))
    val pace = copilotCredits / elapsed
    val exhaustDay = included / pace
    if (exhaustDay > daysInMonth) return null
    val target = GregorianCalendar(c.get(Calendar.YEAR), c.get(Calendar.MONTH), maxOf(1, Math.ceil(exhaustDay).toInt()))
    return dayKey(target.timeInMillis)
}

private fun repoMatches(repo: RepoSummary, member: String): Boolean {
    val t = member.trim().lowercase()
    if (t.isEmpty()) return false
    return listOfNotNull(repo.repo.name, repo.repo.remoteSlug, repo.repo.folderPath?.substringAfterLast('/'))
        .any { it.lowercase() == t }
}

fun buildGroupSummaries(repos: List<RepoSummary>, groups: Map<String, List<String>>): List<GroupSummary> {
    val out = mutableListOf<GroupSummary>()
    for ((name, members) in groups) {
        val matched = repos.filter { r -> members.any { repoMatches(r, it) } }
        if (matched.isEmpty()) continue
        val models = LinkedHashMap<String, ModelSummary>()
        val g = GroupSummary(name, matched)
        for (r in matched) {
            g.credits += r.credits
            g.inputTokens += r.inputTokens; g.outputTokens += r.outputTokens
            g.cachedTokens += r.cachedTokens; g.cacheWriteTokens += r.cacheWriteTokens
            g.requestCount += r.requestCount; g.sessionCount += r.sessionCount
            if (r.hasEstimates) g.hasEstimates = true
            for (m in r.models) {
                val e = models.getOrPut(m.model) { ModelSummary(m.model) }
                e.credits += m.credits; e.usd = creditsToUsd(e.credits); e.requestCount += m.requestCount
                e.inputTokens += m.inputTokens; e.outputTokens += m.outputTokens
                e.cachedTokens += m.cachedTokens; e.cacheWriteTokens += m.cacheWriteTokens
            }
        }
        g.usd = creditsToUsd(g.credits)
        g.models = models.values.sortedByDescending { it.credits }
        out += g
    }
    return out.sortedByDescending { it.credits }
}

fun buildRepoDetail(events: List<UsageEvent>, repoName: String, month: String): RepoDetail? {
    val filtered = events.filter { it.repo.name == repoName && (month == ALL_TIME || monthKey(it.timestamp) == month) }
    if (filtered.isEmpty()) return null
    val dayMap = LinkedHashMap<String, DayPoint>()
    val providerMap = LinkedHashMap<String, ProviderSummary>()
    val sessionMap = LinkedHashMap<String, SessionSummary>()
    var firstActivity = Long.MAX_VALUE
    for (e in filtered) {
        firstActivity = minOf(firstActivity, e.timestamp)
        val d = dayMap.getOrPut(dayKey(e.timestamp)) { DayPoint(dayKey(e.timestamp)) }
        d.credits += e.credits; d.usd = creditsToUsd(d.credits)
        val p = providerMap.getOrPut(e.provider.id) { ProviderSummary(e.provider.id) }
        p.credits += e.credits; p.usd = creditsToUsd(p.credits); p.requestCount++
        val s = sessionMap.getOrPut(e.sessionId) { SessionSummary(e.sessionId, e.provider.id) }
        s.credits += e.credits; s.usd = creditsToUsd(s.credits); s.requestCount++
        s.lastTimestamp = maxOf(s.lastTimestamp, e.timestamp)
        if (e.model !in s.models) s.models += e.model
    }
    return RepoDetail(
        summary = summarizeRepo(filtered),
        days = dayMap.values.sortedBy { it.day },
        providers = providerMap.values.sortedByDescending { it.credits },
        topSessions = sessionMap.values.sortedByDescending { it.credits }.take(5),
        firstActivity = firstActivity,
        month = month,
    )
}

fun buildGroupDetail(events: List<UsageEvent>, name: String, members: List<String>, month: String): GroupDetail? {
    val report = buildMonthReport(events, month, 0, mapOf(name to members))
    val group = report.groups.firstOrNull { it.name == name } ?: return null
    val memberNames = group.repos.mapTo(HashSet()) { it.repo.name }
    val inScope = events.filter { it.repo.name in memberNames && (month == ALL_TIME || monthKey(it.timestamp) == month) }
    val dayMap = LinkedHashMap<String, DayPoint>()
    val providerMap = LinkedHashMap<String, ProviderSummary>()
    for (e in inScope) {
        val d = dayMap.getOrPut(dayKey(e.timestamp)) { DayPoint(dayKey(e.timestamp)) }
        d.credits += e.credits; d.usd = creditsToUsd(d.credits)
        val p = providerMap.getOrPut(e.provider.id) { ProviderSummary(e.provider.id) }
        p.credits += e.credits; p.usd = creditsToUsd(p.credits); p.requestCount++
    }
    return GroupDetail(group, dayMap.values.sortedBy { it.day }, providerMap.values.sortedByDescending { it.credits }, month)
}
