package com.jakubjirak.copilotcostlens.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

data class CostLensState(
    @JvmField var plan: String = "business",
    @JvmField var includedCreditsPerMonth: Int = 1900,
    @JvmField var monthlyBudgetUsd: Double = 0.0,
    @JvmField var warnAtPercent: Int = 80,
    @JvmField var claudeCodeEnabled: Boolean = true,
    @JvmField var copilotCliEnabled: Boolean = true,
    @JvmField var codexEnabled: Boolean = true,
    @JvmField var jetbrainsCopilotEnabled: Boolean = false,
    @JvmField var estimationEnabled: Boolean = true,
    @JvmField var charsPerToken: Int = 4,
    @JvmField var statusBarEnabled: Boolean = true,
    /** What the status bar shows: "spend", "remaining" or "today". */
    @JvmField var statusBarMode: String = "spend",
    /** ISO 4217 display currency; amounts are converted with [usdExchangeRate]. */
    @JvmField var displayCurrency: String = "USD",
    @JvmField var usdExchangeRate: Double = 1.0,
    @JvmField var extraStorageRoots: List<String> = emptyList(),
    @JvmField var starredRepos: List<String> = emptyList(),
    /** Repositories hidden from the dashboard; raw exports and alerts still count them. */
    @JvmField var hiddenRepos: List<String> = emptyList(),
    @JvmField var creditAlerts: List<Int> = emptyList(),
    /** Warn when a single session crosses this USD total (0 = off). */
    @JvmField var sessionCostAlertUsd: Double = 0.0,
    /** project name -> member repo identifiers (JSON in a single string for simplicity). */
    @JvmField var projectGroupsJson: String = "{}",
    /** original repo name -> display name (JSON map). */
    @JvmField var repoAliasesJson: String = "{}",
    /** project name -> monthly USD budget (JSON map). */
    @JvmField var projectBudgetsJson: String = "{}",
    @JvmField var documentLanguage: String = "en",
)

@Service(Service.Level.APP)
@State(name = "CopilotCostLens", storages = [Storage("copilot-cost-lens.xml")])
class CostLensSettings : SerializablePersistentStateComponent<CostLensState>(CostLensState()) {

    val data: CostLensState get() = state

    fun mutate(transform: (CostLensState) -> CostLensState) {
        updateState(transform)
    }

    companion object {
        fun getInstance(): CostLensSettings = service()
    }
}
