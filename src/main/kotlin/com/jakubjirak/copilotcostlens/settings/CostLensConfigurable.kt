package com.jakubjirak.copilotcostlens.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty

private val PLANS = listOf("business", "businessPromo", "enterprise", "enterprisePromo", "custom")

private fun intsToText(list: List<Int>) = list.joinToString(", ")
private fun textToInts(text: String) = text.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
private fun listToText(list: List<String>) = list.joinToString(", ")
private fun textToList(text: String) = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

class CostLensConfigurable : BoundConfigurable("Copilot Cost Lens") {
    private val settings = CostLensSettings.getInstance()

    override fun createPanel(): DialogPanel {
        val s = settings.data.copy()
        // list/decimal-valued settings edited as text, converted on apply
        val alertsText = Holder(intsToText(s.creditAlerts))
        val rootsText = Holder(listToText(s.extraStorageRoots))
        val hiddenText = Holder(listToText(s.hiddenRepos))
        val sessionAlertText = Holder(if (s.sessionCostAlertUsd > 0) s.sessionCostAlertUsd.toString() else "0")
        val rateText = Holder(s.usdExchangeRate.toString())
        return panel {
            group("Plan & Budget") {
                row("Plan:") {
                    comboBox(PLANS).bindItem(s::plan.toNullableProperty())
                }
                row("Included AI Credits / month (custom plan):") {
                    intTextField().bindIntText(s::includedCreditsPerMonth)
                }
                row("Monthly budget (USD, 0 = off):") {
                    intTextField().bindIntText({ s.monthlyBudgetUsd.toInt() }, { s.monthlyBudgetUsd = it.toDouble() })
                }
                row("Warn at percent:") {
                    intTextField().bindIntText(s::warnAtPercent)
                }
                row("Credit alerts (comma-separated AIC):") {
                    textField().columns(24).bindText(alertsText::value)
                }.comment("Notifies once per month when month-to-date Copilot usage crosses a threshold, e.g. 2500, 5000.")
                row("Session cost alert (USD, 0 = off):") {
                    textField().columns(10).bindText(sessionAlertText::value)
                }.comment("Warns the moment a single session — say, an agent left unattended — crosses this dollar total.")
                row("Per-project budgets (JSON, name → USD):") {
                    textField().columns(40).bindText(s::projectBudgetsJson)
                }.comment("""E.g. {"MyProduct": 50} — warned once per day when a project crosses the warn percent of its budget.""")
            }
            group("Sources") {
                row { checkBox("Include GitHub Copilot CLI usage").bindSelected(s::copilotCliEnabled) }
                row { checkBox("Include Claude Code usage").bindSelected(s::claudeCodeEnabled) }
                row {
                    checkBox("Include ChatGPT Codex usage").bindSelected(s::codexEnabled)
                }.comment("Reads ~/.codex/sessions rollout logs. Codex spend never counts against the Copilot allowance gauge.")
                row {
                    checkBox("Include JetBrains Copilot usage (estimated)").bindSelected(s::jetbrainsCopilotEnabled)
                }.comment(
                    "The JetBrains Copilot plugin does not store token counts locally, so this " +
                        "source estimates cost from chat content and is always marked ~est.",
                )
                row { checkBox("Estimate sessions without exact token counts").bindSelected(s::estimationEnabled) }
                row("Characters per token (estimates only):") {
                    intTextField().bindIntText(s::charsPerToken)
                }
            }
            group("Display") {
                row { checkBox("Show month-to-date spend in the status bar").bindSelected(s::statusBarEnabled) }
                row("Status bar shows:") {
                    comboBox(listOf("spend", "remaining", "today")).bindItem(s::statusBarMode.toNullableProperty())
                }.comment("\"remaining\" counts down the AI credits you have left; \"today\" shows today's spend.")
                row("Display currency (ISO code):") {
                    textField().columns(6).bindText(s::displayCurrency)
                }
                row("Units per 1 USD (manual rate):") {
                    textField().columns(10).bindText(rateText::value)
                }.comment(
                    "The rate is set manually — the plugin never touches the network. " +
                        "Internal accounting and PDF receipts stay in USD.",
                )
                row("Hidden repositories (comma-separated):") {
                    textField().columns(40).bindText(hiddenText::value)
                }.comment("Hidden from the dashboard, status bar and receipts; raw CSV/JSON exports and budget alerts still count them.")
            }
            group("Advanced") {
                row("Extra storage roots (comma-separated paths):") {
                    textField().columns(40).bindText(rootsText::value)
                }.comment("Additional VS Code 'workspaceStorage' folders to scan; standard locations are detected automatically.")
            }
            onApply {
                s.creditAlerts = textToInts(alertsText.value)
                s.extraStorageRoots = textToList(rootsText.value)
                s.hiddenRepos = textToList(hiddenText.value)
                s.sessionCostAlertUsd = sessionAlertText.value.trim().toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0
                s.usdExchangeRate = rateText.value.trim().toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
                settings.mutate { s }
                com.jakubjirak.copilotcostlens.data.CostLensService.getInstance().reconfigure()
            }
            onIsModified {
                s != settings.data ||
                    alertsText.value != intsToText(settings.data.creditAlerts) ||
                    rootsText.value != listToText(settings.data.extraStorageRoots) ||
                    hiddenText.value != listToText(settings.data.hiddenRepos) ||
                    sessionAlertText.value.trim().toDoubleOrNull() != settings.data.sessionCostAlertUsd ||
                    rateText.value.trim().toDoubleOrNull() != settings.data.usdExchangeRate
            }
        }
    }

    private class Holder(var value: String)
}
