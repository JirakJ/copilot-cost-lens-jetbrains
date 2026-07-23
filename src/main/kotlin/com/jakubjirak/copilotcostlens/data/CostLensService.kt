package com.jakubjirak.copilotcostlens.data

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.Alarm
import com.jakubjirak.copilotcostlens.model.UsageEvent
import com.jakubjirak.copilotcostlens.settings.CostLensSettings
import java.util.concurrent.CopyOnWriteArraySet

private const val AUTO_REFRESH_MS = 60_000

/**
 * App-wide owner of the [UsageStore]: one scan, one auto-refresh timer, shared
 * by the dashboard tool window and the status-bar widget (so they don't each
 * rescan thousands of files). Listeners are notified on the EDT after a scan.
 */
@Service(Service.Level.APP)
class CostLensService : Disposable {
    private val log = Logger.getInstance(CostLensService::class.java)
    val store = UsageStore(buildConfig())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        refresh()
        scheduleAuto()
    }

    /** Subscribe to post-scan updates; returns a removal function to call on dispose. */
    fun addListener(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** Re-read settings and rescan (call when settings change). */
    fun reconfigure() {
        store.updateConfig(buildConfig())
        refresh()
    }

    fun refresh() {
        store.updateConfig(buildConfig())
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                store.refresh { fire() }
            } catch (e: Throwable) {
                log.warn("Cost Lens scan failed", e)
            }
        }
    }

    private fun fire() = ApplicationManager.getApplication().invokeLater {
        for (l in listeners) l()
        checkAlerts()
    }

    /**
     * Alert notifications after each scan. Budget/credit/session alerts always
     * see the full spend — hidden repositories included, so totals never lie.
     */
    private fun checkAlerts() {
        val s = CostLensSettings.getInstance().data
        val included = if (s.plan == "custom") s.includedCreditsPerMonth
        else com.jakubjirak.copilotcostlens.pricing.PLAN_CREDITS[s.plan] ?: 1900
        val report = com.jakubjirak.copilotcostlens.core.buildMonthReport(
            store.events, com.jakubjirak.copilotcostlens.core.currentMonthKey(), included, jsonToGroups(s.projectGroupsJson),
        )
        val props = com.intellij.ide.util.PropertiesComponent.getInstance()
        val today = java.time.LocalDate.now().toString()
        fun onceThisMonth(key: String): Boolean {
            val full = "ccl.alert.${report.month}.$key"
            if (props.getBoolean(full, false)) return false
            props.setValue(full, true)
            return true
        }
        fun onceToday(key: String): Boolean {
            val full = "ccl.alert.${report.month}.$key"
            if (props.getValue(full) == today) return false
            props.setValue(full, today)
            return true
        }
        for (threshold in s.creditAlerts.filter { it > 0 }) {
            if (report.copilotCredits >= threshold && onceThisMonth("cr$threshold")) {
                notify("Copilot usage crossed ${"%,d".format(threshold)} AI Credits this month " +
                    "(${"%,d".format(report.copilotCredits.toLong())} used, \$${"%.2f".format(report.copilotUsd)}).")
            }
        }
        if (s.monthlyBudgetUsd > 0 && report.totalUsd >= s.monthlyBudgetUsd * s.warnAtPercent / 100.0 &&
            onceToday("budget")
        ) {
            notify("You have used \$${"%.2f".format(report.totalUsd)} of your \$${"%.2f".format(s.monthlyBudgetUsd)} budget this month.")
        }
        checkProjectBudgets(s, report, ::onceToday)
        checkSessionAlerts(s, props)
    }

    /** Per-project budget warnings: at most once per day per project. */
    private fun checkProjectBudgets(
        s: com.jakubjirak.copilotcostlens.settings.CostLensState,
        report: com.jakubjirak.copilotcostlens.core.MonthReport,
        onceToday: (String) -> Boolean,
    ) {
        val budgets = jsonToBudgets(s.projectBudgetsJson)
        for (group in report.groups) {
            val budget = budgets[group.name] ?: continue
            if (group.usd < budget * s.warnAtPercent / 100.0) continue
            if (!onceToday("project-${group.name}")) continue
            notify("Project ${group.name} has used \$${"%.2f".format(group.usd)} of its \$${"%.2f".format(budget)} budget.")
        }
    }

    /**
     * Warn once per session when a single session's total cost crosses the
     * configured USD threshold — catches runaway agent sessions early.
     */
    private fun checkSessionAlerts(
        s: com.jakubjirak.copilotcostlens.settings.CostLensState,
        props: com.intellij.ide.util.PropertiesComponent,
    ) {
        if (s.sessionCostAlertUsd <= 0) return
        val key = "ccl.sessionAlerts"
        val notified = (props.getValue(key) ?: "").split('\n').filter { it.isNotEmpty() }.toMutableSet()
        val offenders = com.jakubjirak.copilotcostlens.core.sessionCosts(store.events)
            .filter { it.usd >= s.sessionCostAlertUsd && it.sessionId !in notified }
        if (offenders.isEmpty()) return
        offenders.forEach { notified += it.sessionId }
        props.setValue(key, notified.toList().takeLast(1000).joinToString("\n"))
        val top = offenders.maxBy { it.usd }
        notify(
            if (offenders.size == 1) "A session in ${top.repoName} has reached \$${"%.2f".format(top.usd)}."
            else "${offenders.size} sessions crossed \$${"%.2f".format(s.sessionCostAlertUsd)} — " +
                "the largest in ${top.repoName} at \$${"%.2f".format(top.usd)}.",
        )
    }

    private fun notify(message: String) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Copilot Cost Lens")
            .createNotification("Copilot Cost Lens", message, com.intellij.notification.NotificationType.WARNING)
            .notify(null)
    }

    private fun scheduleAuto() {
        if (alarm.isDisposed) return
        alarm.addRequest({ try { refresh() } finally { scheduleAuto() } }, AUTO_REFRESH_MS)
    }

    val events: List<UsageEvent> get() = store.events

    /**
     * Events minus hidden repositories — what the dashboard and status bar
     * show. Raw exports and budget alerts intentionally keep the full set.
     */
    val visibleEvents: List<UsageEvent>
        get() {
            val hidden = CostLensSettings.getInstance().data.hiddenRepos.mapTo(HashSet()) { it.lowercase() }
            if (hidden.isEmpty()) return store.events
            return store.events.filter { it.repo.name.lowercase() !in hidden }
        }

    private fun buildConfig(): StoreConfig {
        val s = CostLensSettings.getInstance().data
        return StoreConfig(
            extraStorageRoots = s.extraStorageRoots,
            repoAliases = jsonToAliases(s.repoAliasesJson),
            claudeCodeEnabled = s.claudeCodeEnabled,
            copilotCliEnabled = s.copilotCliEnabled,
            codexEnabled = s.codexEnabled,
            jetbrainsCopilotEnabled = s.jetbrainsCopilotEnabled,
            estimationEnabled = s.estimationEnabled,
            charsPerToken = s.charsPerToken,
        )
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(): CostLensService = service()
    }
}

// --- JSON-string settings maps, parsed defensively --------------------------

fun jsonToGroups(json: String): Map<String, List<String>> = try {
    com.google.gson.JsonParser.parseString(json).asJsonObject.entrySet().associate { (k, v) ->
        k to v.asJsonArray.mapNotNull { runCatching { it.asString }.getOrNull() }
    }
} catch (_: Exception) {
    emptyMap()
}

fun jsonToAliases(json: String): Map<String, String> = try {
    com.google.gson.JsonParser.parseString(json).asJsonObject.entrySet()
        .mapNotNull { (k, v) ->
            val alias = runCatching { v.asString }.getOrNull()?.trim()
            if (k.isNotBlank() && !alias.isNullOrEmpty()) k.trim() to alias else null
        }.toMap()
} catch (_: Exception) {
    emptyMap()
}

fun jsonToBudgets(json: String): Map<String, Double> = try {
    com.google.gson.JsonParser.parseString(json).asJsonObject.entrySet()
        .mapNotNull { (k, v) ->
            val budget = runCatching { v.asDouble }.getOrNull()
            if (k.isNotBlank() && budget != null && budget.isFinite() && budget > 0) k.trim() to budget else null
        }.toMap()
} catch (_: Exception) {
    emptyMap()
}
