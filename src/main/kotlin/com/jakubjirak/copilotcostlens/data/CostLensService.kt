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

    /** Fire a balloon at most once per month when usage crosses a configured threshold. */
    private fun checkAlerts() {
        val s = CostLensSettings.getInstance().data
        val included = if (s.plan == "custom") s.includedCreditsPerMonth
        else com.jakubjirak.copilotcostlens.pricing.PLAN_CREDITS[s.plan] ?: 1900
        val report = com.jakubjirak.copilotcostlens.core.buildMonthReport(
            store.events, com.jakubjirak.copilotcostlens.core.currentMonthKey(), included,
        )
        val props = com.intellij.ide.util.PropertiesComponent.getInstance()
        fun onceThisMonth(key: String): Boolean {
            val full = "ccl.alert.${report.month}.$key"
            if (props.getBoolean(full, false)) return false
            props.setValue(full, true)
            return true
        }
        for (threshold in s.creditAlerts.filter { it > 0 }) {
            if (report.copilotCredits >= threshold && onceThisMonth("cr$threshold")) {
                notify("Copilot usage crossed ${"%,d".format(threshold)} AI Credits this month " +
                    "(${"%,d".format(report.copilotCredits.toLong())} used, \$${"%.2f".format(report.copilotUsd)}).")
            }
        }
        if (s.monthlyBudgetUsd > 0 && report.totalUsd >= s.monthlyBudgetUsd * s.warnAtPercent / 100.0 &&
            onceThisMonth("budget")
        ) {
            notify("You have used \$${"%.2f".format(report.totalUsd)} of your \$${"%.2f".format(s.monthlyBudgetUsd)} budget this month.")
        }
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

    private fun buildConfig(): StoreConfig {
        val s = CostLensSettings.getInstance().data
        return StoreConfig(
            extraStorageRoots = s.extraStorageRoots,
            claudeCodeEnabled = s.claudeCodeEnabled,
            copilotCliEnabled = s.copilotCliEnabled,
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
