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
