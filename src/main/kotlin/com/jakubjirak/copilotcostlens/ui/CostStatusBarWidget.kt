package com.jakubjirak.copilotcostlens.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import com.jakubjirak.copilotcostlens.core.MonthReport
import com.jakubjirak.copilotcostlens.core.buildMonthReport
import com.jakubjirak.copilotcostlens.core.currentMonthKey
import com.jakubjirak.copilotcostlens.core.money
import com.jakubjirak.copilotcostlens.core.sanitizeCurrency
import com.jakubjirak.copilotcostlens.core.sparkline
import com.jakubjirak.copilotcostlens.core.todayUsd
import com.jakubjirak.copilotcostlens.data.CostLensService
import com.jakubjirak.copilotcostlens.pricing.PLAN_CREDITS
import com.jakubjirak.copilotcostlens.settings.CostLensSettings
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

    private fun report(): MonthReport {
        val s = CostLensSettings.getInstance().data
        val included = if (s.plan == "custom") s.includedCreditsPerMonth else PLAN_CREDITS[s.plan] ?: 1900
        return buildMonthReport(service.visibleEvents, currentMonthKey(), included)
    }

    override fun getText(): String {
        val s = CostLensSettings.getInstance().data
        val cur = sanitizeCurrency(s.displayCurrency, s.usdExchangeRate)
        val r = report()
        val spark = sparkline(r).let { if (it.isEmpty()) "" else " $it" }
        return when {
            s.statusBarMode == "today" -> "${money(todayUsd(r), cur)} today$spark"
            // remaining mode needs an allowance to count down from; fall back to spend
            s.statusBarMode == "remaining" && r.includedCredits > 0 ->
                "${formatCredits(r.includedCredits - r.copilotCredits)} cr left$spark"
            else -> "Copilot ${money(r.totalUsd, cur)}$spark"
        }
    }

    override fun getTooltipText(): String {
        val s = CostLensSettings.getInstance().data
        val cur = sanitizeCurrency(s.displayCurrency, s.usdExchangeRate)
        val r = report()
        val names = mapOf(
            "copilot" to "Copilot", "copilot-cli" to "Copilot CLI",
            "claude-code" to "Claude Code", "codex" to "ChatGPT Codex",
        )
        val split = r.providers.joinToString(" · ") { "${names[it.provider] ?: it.provider} ${money(it.usd, cur)}" }
        return "Copilot Cost Lens — ${r.month}: ${money(r.totalUsd, cur)}" +
            "\nToday: ${money(todayUsd(r), cur)}" +
            (if (split.isNotEmpty()) "\n$split" else "") + "\nClick to open the dashboard"
    }

    override fun getAlignment() = java.awt.Component.CENTER_ALIGNMENT

    override fun getClickConsumer() = Consumer<MouseEvent> {
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("Copilot Cost Lens")?.activate(null)
    }
}

private fun formatCredits(credits: Double): String =
    if (credits >= 100) "%,d".format(Math.round(credits)) else "%.1f".format(java.util.Locale.ROOT, credits)

class CostStatusBarWidgetFactory : StatusBarWidgetFactory, DumbAware {
    override fun getId() = WIDGET_ID
    override fun getDisplayName() = "Copilot Cost Lens"
    override fun isAvailable(project: Project) = CostLensSettings.getInstance().data.statusBarEnabled
    override fun createWidget(project: Project): StatusBarWidget = CostStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
    override fun canBeEnabledOn(statusBar: StatusBar) = true
}
