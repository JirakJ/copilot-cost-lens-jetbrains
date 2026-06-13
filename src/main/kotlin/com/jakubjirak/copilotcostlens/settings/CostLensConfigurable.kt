package com.jakubjirak.copilotcostlens.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty

private val PLANS = listOf("business", "businessPromo", "enterprise", "enterprisePromo", "custom")

class CostLensConfigurable : BoundConfigurable("Copilot Cost Lens") {
    private val settings = CostLensSettings.getInstance()

    override fun createPanel(): DialogPanel {
        val s = settings.data.copy()
        return panel {
            group("Plan & Budget") {
                row("Plan:") {
                    comboBox(PLANS).bindItem(s::plan.toNullableProperty())
                }
                row("Included AI Credits / month (custom plan):") {
                    intTextField().bindIntText(s::includedCreditsPerMonth)
                }
                row("Warn at percent:") {
                    intTextField().bindIntText(s::warnAtPercent)
                }
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
            onApply { settings.mutate { s } }
            onReset { /* fields rebind from settings on next open */ }
            onIsModified { s != settings.data }
        }
    }
}
