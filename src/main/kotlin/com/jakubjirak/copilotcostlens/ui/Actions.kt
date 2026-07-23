package com.jakubjirak.copilotcostlens.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.jakubjirak.copilotcostlens.core.MonthReport
import com.jakubjirak.copilotcostlens.core.buildMonthReport
import com.jakubjirak.copilotcostlens.core.currentMonthKey
import com.jakubjirak.copilotcostlens.core.sanitizeCurrency
import com.jakubjirak.copilotcostlens.core.summaryCsv
import com.jakubjirak.copilotcostlens.core.summaryMarkdown
import com.jakubjirak.copilotcostlens.data.CostLensService
import com.jakubjirak.copilotcostlens.settings.CostLensSettings
import java.awt.datatransfer.StringSelection

private fun currentMonthReport(): MonthReport =
    buildMonthReport(CostLensService.getInstance().visibleEvents, currentMonthKey(), 0)

/** Puts the current month — per-repository costs, shares and totals — on the clipboard as Markdown. */
class CopySummaryAction : AnAction("Copilot Cost Lens: Copy Summary as Markdown"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val s = CostLensSettings.getInstance().data
        val report = currentMonthReport()
        if (report.repos.isEmpty()) {
            Messages.showInfoMessage(e.project, "No usage data to summarize yet.", "Copilot Cost Lens")
            return
        }
        val md = summaryMarkdown(report, sanitizeCurrency(s.displayCurrency, s.usdExchangeRate))
        CopyPasteManager.getInstance().setContents(StringSelection(md))
        Messages.showInfoMessage(e.project, "Summary copied to clipboard.", "Copilot Cost Lens")
    }
}

/** Saves the per-repository aggregate of the current month as a pivot-ready CSV. */
class ExportSummaryCsvAction : AnAction("Copilot Cost Lens: Export Summary CSV"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val report = currentMonthReport()
        if (report.repos.isEmpty()) {
            Messages.showInfoMessage(e.project, "No usage data to export yet.", "Copilot Cost Lens")
            return
        }
        val descriptor = FileSaverDescriptor("Export Summary", "Save the per-repository summary", "csv")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, e.project)
            .save("ai-summary-${report.month}.csv") ?: return
        wrapper.file.writeText(summaryCsv(report))
        Messages.showInfoMessage(e.project, "Summary exported to ${wrapper.file.absolutePath}", "Copilot Cost Lens")
    }
}
