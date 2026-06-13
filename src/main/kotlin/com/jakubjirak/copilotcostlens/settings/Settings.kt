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
    @JvmField var estimationEnabled: Boolean = true,
    @JvmField var charsPerToken: Int = 4,
    @JvmField var statusBarEnabled: Boolean = true,
    @JvmField var extraStorageRoots: List<String> = emptyList(),
    @JvmField var starredRepos: List<String> = emptyList(),
    @JvmField var creditAlerts: List<Int> = emptyList(),
    /** project name -> member repo identifiers (JSON in a single string for simplicity). */
    @JvmField var projectGroupsJson: String = "{}",
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
