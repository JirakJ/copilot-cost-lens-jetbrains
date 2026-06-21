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
        // list-valued settings edited as comma-separated text, converted on apply
        val alertsText = Holder(intsToText(s.creditAlerts))
        val rootsText = Holder(listToText(s.extraStorageRoots))
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
            }
            group("Sources") {
                row { checkBox("Include GitHub Copilot CLI usage").bindSelected(s::copilotCliEnabled) }
                row { checkBox("Include Claude Code usage").bindSelected(s::claudeCodeEnabled) }
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
            }
            group("Advanced") {
                row("Extra storage roots (comma-separated paths):") {
                    textField().columns(40).bindText(rootsText::value)
                }.comment("Additional VS Code 'workspaceStorage' folders to scan; standard locations are detected automatically.")
            }
            onApply {
                s.creditAlerts = textToInts(alertsText.value)
                s.extraStorageRoots = textToList(rootsText.value)
                settings.mutate { s }
                com.jakubjirak.copilotcostlens.data.CostLensService.getInstance().reconfigure()
            }
            onIsModified {
                s != settings.data ||
                    alertsText.value != intsToText(settings.data.creditAlerts) ||
                    rootsText.value != listToText(settings.data.extraStorageRoots)
            }
        }
    }

    private class Holder(var value: String)
}
