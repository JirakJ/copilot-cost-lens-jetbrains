package com.jakubjirak.copilotcostlens.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import com.jakubjirak.copilotcostlens.core.buildMonthReport
import com.jakubjirak.copilotcostlens.core.currentMonthKey
import com.jakubjirak.copilotcostlens.data.CostLensService
import java.awt.event.MouseEvent

private const val WIDGET_ID = "CopilotCostLens.StatusBar"

/** Always-on month-to-date Copilot spend in the IDE status bar; click opens the dashboard. */
class CostStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null
    private val service = CostLensService.getInstance()
    private var unsubscribe: (() -> Unit)? = null

    override fun ID() = WIDGET_ID
    override fun getPresentation() = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        unsubscribe = service.addListener { statusBar.updateWidget(WIDGET_ID) }
    }

    override fun dispose() {
        unsubscribe?.invoke()
        unsubscribe = null
        statusBar = null
    }

    override fun getText(): String {
        val r = buildMonthReport(service.events, currentMonthKey(), 0)
        return if (r.totalUsd <= 0) "Copilot \$0" else "Copilot \$${"%.2f".format(r.totalUsd)}"
    }

    override fun getTooltipText(): String {
        val r = buildMonthReport(service.events, currentMonthKey(), 0)
        val names = mapOf("copilot" to "Copilot", "copilot-cli" to "Copilot CLI", "claude-code" to "Claude Code")
        val split = r.providers.joinToString(" · ") { "${names[it.provider] ?: it.provider} \$${"%.2f".format(it.usd)}" }
        return "Copilot Cost Lens — ${r.month}: \$${"%.2f".format(r.totalUsd)}" +
            (if (split.isNotEmpty()) "\n$split" else "") + "\nClick to open the dashboard"
    }

    override fun getAlignment() = java.awt.Component.CENTER_ALIGNMENT

    override fun getClickConsumer() = Consumer<MouseEvent> {
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("Copilot Cost Lens")?.activate(null)
    }
}

class CostStatusBarWidgetFactory : StatusBarWidgetFactory, DumbAware {
    override fun getId() = WIDGET_ID
    override fun getDisplayName() = "Copilot Cost Lens"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project): StatusBarWidget = CostStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
    override fun canBeEnabledOn(statusBar: StatusBar) = true
}
