package com.jakubjirak.copilotcostlens.ui

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.jakubjirak.copilotcostlens.core.ALL_TIME
import com.jakubjirak.copilotcostlens.core.ReceiptData
import com.jakubjirak.copilotcostlens.core.ReceiptModelLine
import com.jakubjirak.copilotcostlens.core.availableMonths
import com.jakubjirak.copilotcostlens.core.buildGroupDetail
import com.jakubjirak.copilotcostlens.core.buildMonthReport
import com.jakubjirak.copilotcostlens.core.buildReceiptPdf
import com.jakubjirak.copilotcostlens.core.buildRepoDetail
import com.jakubjirak.copilotcostlens.core.monthKey
import com.jakubjirak.copilotcostlens.core.toCsv
import com.jakubjirak.copilotcostlens.data.CostLensService
import com.jakubjirak.copilotcostlens.pricing.PLAN_CREDITS
import com.jakubjirak.copilotcostlens.settings.CostLensSettings
import com.jakubjirak.copilotcostlens.settings.CostLensState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.io.File
import javax.swing.JPanel
import javax.swing.UIManager

class DashboardPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val gson: Gson = GsonBuilder().create()
    private val service = CostLensService.getInstance()
    private val store get() = service.store
    private var browser: JBCefBrowser? = null
    private var jsQuery: JBCefJSQuery? = null

    private var selectedMonth: String? = null
    private var selectedRepo: String? = null
    private var selectedGroup: String? = null
    @Volatile private var ready = false

    private val unsubscribe: () -> Unit

    init {
        if (JBCefApp.isSupported()) initBrowser() else fallback()
        // shared service already scans on its own; repaint when it has data
        unsubscribe = service.addListener { ApplicationManager.getApplication().invokeLater { if (ready) postData() } }
        service.refresh()
    }

    private fun fallback() {
        add(
            JBLabel("<html><center><h2>Copilot Cost Lens</h2><p>JCEF is not available in this IDE build.</p></center></html>")
                as Component,
            BorderLayout.CENTER,
        )
    }

    private fun initBrowser() {
        val b = JBCefBrowser()
        browser = b
        Disposer.register(this, b)
        val query = JBCefJSQuery.create(b as com.intellij.ui.jcef.JBCefBrowserBase)
        jsQuery = query
        query.addHandler { raw ->
            ApplicationManager.getApplication().invokeLater { handleMessage(raw) }
            null
        }
        // Push data when the page finishes loading — the cefQuery bridge and the
        // webview's message listener are both ready by then, so we don't depend
        // on the webview's own 'ready' handshake (which can race the bridge).
        b.jbCefClient.addLoadHandler(
            object : org.cef.handler.CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: org.cef.browser.CefBrowser?, frame: org.cef.browser.CefFrame?, httpStatusCode: Int) {
                    if (frame?.isMain != false) {
                        ready = true
                        ApplicationManager.getApplication().invokeLater { postData() }
                    }
                }
            },
            b.cefBrowser,
        )
        b.loadHTML(buildHtml(query))
        add(b.component as Component, BorderLayout.CENTER)
    }

    // --- config ---------------------------------------------------------------

    private fun settings(): CostLensState = CostLensSettings.getInstance().data

    private fun includedCredits(): Int {
        val s = settings()
        return if (s.plan == "custom") s.includedCreditsPerMonth else PLAN_CREDITS[s.plan] ?: 1900
    }

    private fun projectGroups(): Map<String, List<String>> = try {
        val obj = JsonParser.parseString(settings().projectGroupsJson).asJsonObject
        obj.entrySet().associate { (k, v) -> k to v.asJsonArray.map { it.asString } }
    } catch (_: Exception) {
        emptyMap()
    }

    fun refresh() = service.refresh()

    // --- messages from the webview --------------------------------------------

    private fun currentMonth(): String {
        val months = availableMonths(store.events)
        val sel = selectedMonth
        return if (sel == ALL_TIME || (sel != null && months.contains(sel))) sel else months.firstOrNull() ?: ALL_TIME
    }

    private fun handleMessage(raw: String) {
        val msg = try { JsonParser.parseString(raw).asJsonObject } catch (_: Exception) { return }
        when (msg.get("type")?.asString) {
            "ready" -> { ready = true; postData() }
            "refresh" -> refresh()
            "selectMonth" -> { selectedMonth = msg.str("month"); selectedRepo = null; selectedGroup = null; postData() }
            "selectRepo" -> { selectedRepo = msg.str("repo"); selectedGroup = null; postData() }
            "selectGroup" -> { selectedGroup = msg.str("group"); selectedRepo = null; postData() }
            "setAllowance" -> setAllowance(msg)
            "toggleStar" -> { msg.str("repo")?.let { toggleStar(it) }; postData() }
            "saveGroup" -> saveGroup(msg)
            "deleteGroup" -> { msg.str("group")?.let { deleteGroup(it) }; selectedGroup = null; postData() }
            "openRepo" -> msg.str("path")?.let { openFolder(it) }
            "exportReceipt" -> exportReceipt(msg)
            "openSettings" -> com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, "Copilot Cost Lens")
            "export" -> exportData(msg.str("format") ?: "csv")
            "exportInvoice" -> Unit
        }
    }

    private fun exportData(format: String) {
        val month = currentMonth()
        val events = if (month == ALL_TIME) store.events else store.events.filter { monthKey(it.timestamp) == month }
        if (events.isEmpty()) {
            Messages.showInfoMessage(project, "No usage data to export yet.", "Copilot Cost Lens")
            return
        }
        val ext = if (format == "json") "json" else "csv"
        val content = if (format == "json") gson.toJson(events) else toCsv(events)
        val period = if (month == ALL_TIME) "all-time" else month
        val descriptor = FileSaverDescriptor("Export Usage", "Save the usage export", ext)
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save("copilot-usage-$period.$ext") ?: return
        wrapper.file.writeText(content)
        Messages.showInfoMessage(project, "Exported ${events.size} records to ${wrapper.file.absolutePath}", "Copilot Cost Lens")
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun setAllowance(msg: JsonObject) {
        val v = msg.get("value") ?: return
        val credits = when {
            v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asInt
            else -> Messages.showInputDialog(project, "Included AI Credits per month", "Monthly Copilot Allowance", null)
                ?.trim()?.toIntOrNull() ?: return
        }
        CostLensSettings.getInstance().mutate { it.copy(plan = "custom", includedCreditsPerMonth = credits) }
        postData()
    }

    private fun toggleStar(repo: String) = CostLensSettings.getInstance().mutate { s ->
        val cur = s.starredRepos
        s.copy(starredRepos = if (cur.any { it.equals(repo, true) }) cur.filterNot { it.equals(repo, true) } else cur + repo)
    }

    private fun saveGroup(msg: JsonObject) {
        val name = msg.str("name") ?: return
        val members = msg.getAsJsonArray("members")?.map { it.asString } ?: return
        if (members.isEmpty()) return
        val original = msg.str("originalName")
        val groups = projectGroups().toMutableMap()
        if (original != null && original != name) groups.remove(original)
        val claimed = members.map { it.lowercase() }.toSet()
        for ((g, ms) in groups.toMap()) if (g != name) groups[g] = ms.filterNot { it.lowercase() in claimed }
        groups[name] = members
        persistGroups(groups)
        selectedGroup = name; selectedRepo = null; postData()
    }

    private fun deleteGroup(name: String) {
        val groups = projectGroups().toMutableMap()
        groups.remove(name)
        persistGroups(groups)
    }

    private fun persistGroups(groups: Map<String, List<String>>) {
        val json = gson.toJson(groups)
        CostLensSettings.getInstance().mutate { it.copy(projectGroupsJson = json) }
    }

    private fun openFolder(path: String) {
        val dir = File(path)
        if (dir.isDirectory) {
            com.intellij.ide.impl.ProjectUtil.openOrImport(dir.toPath(), project, false)
        }
    }

    private fun exportReceipt(msg: JsonObject) {
        val month = currentMonth()
        val groupName = msg.str("group")
        val repoName = msg.str("repo")
        val data: ReceiptData? = when {
            groupName != null -> projectGroups()[groupName]?.let { members ->
                buildGroupDetail(store.events, groupName, members, month)?.let { d ->
                    receiptFrom(d.group.name, d.group.let { g ->
                        Triple(g.models.map { m -> ReceiptModelLine(m.model, m.requestCount, m.credits, m.usd, m.inputTokens, m.outputTokens, m.cachedTokens, m.cacheWriteTokens) }, listOf(g.inputTokens, g.outputTokens, g.cachedTokens, g.cacheWriteTokens, g.sessionCount.toLong()), Triple(g.credits, g.usd, g.hasEstimates)) },
                        month, d.group.repos.map { it.repo.name to it.usd }, d.providers.map { it.provider to it.usd })
                }
            }
            repoName != null -> buildRepoDetail(store.events, repoName, month)?.let { d ->
                val s = d.summary
                receiptFrom(s.repo.name, Triple(s.models.map { m -> ReceiptModelLine(m.model, m.requestCount, m.credits, m.usd, m.inputTokens, m.outputTokens, m.cachedTokens, m.cacheWriteTokens) }, listOf(s.inputTokens, s.outputTokens, s.cachedTokens, s.cacheWriteTokens, s.sessionCount.toLong()), Triple(s.credits, s.usd, s.hasEstimates)),
                    month, emptyList(), d.providers.map { it.provider to it.usd })
            }
            else -> null
        }
        if (data == null) {
            Messages.showInfoMessage(project, "No usage data for this period.", "Copilot Cost Lens")
            return
        }
        val safe = (groupName ?: repoName ?: "receipt").replace(Regex("[^a-zA-Z0-9._-]+"), "-")
        val descriptor = FileSaverDescriptor("Export Receipt", "Save the receipt PDF", "pdf")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save("receipt-$safe-${if (month == ALL_TIME) "all-time" else month}.pdf") ?: return
        wrapper.file.writeBytes(buildReceiptPdf(data))
        Messages.showInfoMessage(project, "Receipt saved to ${wrapper.file.absolutePath}", "Copilot Cost Lens")
    }

    private fun receiptFrom(
        title: String,
        body: Triple<List<ReceiptModelLine>, List<Long>, Triple<Double, Double, Boolean>>,
        month: String,
        repoLines: List<Pair<String, Double>>,
        providers: List<Pair<String, Double>>,
    ): ReceiptData {
        val (models, tokens, totals) = body
        return ReceiptData(
            title = title, period = month, models = models, repoLines = repoLines,
            inputTokens = tokens[0], outputTokens = tokens[1], cachedTokens = tokens[2],
            cacheWriteTokens = tokens[3], sessionCount = tokens[4].toInt(),
            providers = providers.map { providerName(it.first) to it.second },
            totalCredits = totals.first, totalUsd = totals.second, hasEstimates = totals.third,
        )
    }

    private fun providerName(id: String) = when (id) {
        "copilot" -> "Copilot"; "copilot-cli" -> "Copilot CLI"; "claude-code" -> "Claude Code"; else -> id
    }

    // --- push data to the webview ---------------------------------------------

    private fun postData() {
        val b = browser ?: return
        val month = currentMonth()
        val events = store.events
        val groups = projectGroups()
        val report = buildMonthReport(events, month, includedCredits(), groups)
        val detail = selectedRepo?.let { buildRepoDetail(events, it, month) }
        if (selectedRepo != null && detail == null) selectedRepo = null
        val groupDetail = selectedGroup?.let { g -> groups[g]?.let { buildGroupDetail(events, g, it, month) } }
        if (selectedGroup != null && groupDetail == null) selectedGroup = null

        val allRepos = buildMonthReport(events, ALL_TIME, 0).repos.map { mapOf("name" to it.repo.name, "usd" to it.usd) }
        val payload = mapOf(
            "type" to "data",
            "report" to report,
            "months" to availableMonths(events),
            "selectedMonth" to month,
            "detail" to detail,
            "groupDetail" to groupDetail,
            "allRepos" to allRepos,
            "groupsConfig" to groups,
            "starred" to settings().starredRepos,
            "stats" to store.stats,
        )
        val json = gson.toJson(payload)
        b.cefBrowser.executeJavaScript("window.postMessage($json, '*')", b.cefBrowser.url, 0)
    }

    // --- html with theme + bridge ---------------------------------------------

    private fun buildHtml(query: JBCefJSQuery): String {
        val template = javaClass.getResourceAsStream("/webview/dashboard.html")!!
            .bufferedReader().use { it.readText() }
        val theme = themeStyle()
        val bridge = """
            <script>
              window.acquireVsCodeApi = function () {
                return {
                  postMessage: function (m) { ${query.inject("JSON.stringify(m)")} },
                  getState: function () { return null; },
                  setState: function () {},
                };
              };
            </script>
        """.trimIndent()
        return template
            .replace("__CCL_S__", gson.toJson(localizedStrings()))
            .replace("</head>", "$theme$bridge</head>")
    }

    /** English UI strings, overlaid with the IDE-language translation when available. */
    private fun localizedStrings(): Map<String, String> {
        fun res(name: String) = javaClass.getResourceAsStream("/webview/$name")?.bufferedReader()?.use { it.readText() }
        val type = object : com.google.gson.reflect.TypeToken<LinkedHashMap<String, String>>() {}.type
        val en: LinkedHashMap<String, String> = gson.fromJson(res("strings.en.json"), type) ?: LinkedHashMap()
        val lang = java.util.Locale.getDefault().language
        if (lang in setOf("cs", "de", "ja")) {
            val bundle: Map<String, String> = gson.fromJson(res("bundle.$lang.json"), type) ?: emptyMap()
            for ((k, v) in en.toMap()) en[k] = bundle[v] ?: v
        }
        return en
    }

    private fun hex(c: Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    private fun color(key: String, fallback: Color): Color = UIManager.getColor(key) ?: fallback

    private fun blend(a: Color, b: Color, t: Double): Color = Color(
        (a.red + (b.red - a.red) * t).toInt().coerceIn(0, 255),
        (a.green + (b.green - a.green) * t).toInt().coerceIn(0, 255),
        (a.blue + (b.blue - a.blue) * t).toInt().coerceIn(0, 255),
    )

    private fun luminance(c: Color): Double = (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) / 255.0

    private fun themeStyle(): String {
        // The dashboard HTML was designed for a high-contrast content surface.
        // IDE named colors (esp. the muddy tool-window grays) often fail WCAG,
        // so we use proven dark/light content palettes and only borrow the IDE
        // accent. `dark` is decided by the actual IDE background luminance.
        val ideBg = color("ToolWindow.background", color("Panel.background", JBColor.background()))
        val dark = luminance(ideBg) < 0.5

        val bg: Color; val fg: Color; val muted: Color; val card: Color; val border: Color
        if (dark) {
            bg = Color(0x1e, 0x22, 0x27); fg = Color(0xe6, 0xea, 0xf0)
            muted = Color(0x9d, 0xa7, 0xb3); card = Color(0x29, 0x2e, 0x36); border = Color(0x3a, 0x41, 0x4b)
        } else {
            bg = Color(0xff, 0xff, 0xff); fg = Color(0x1f, 0x23, 0x28)
            muted = Color(0x44, 0x4c, 0x54); card = Color(0xf6, 0xf8, 0xfa); border = Color(0xd0, 0xd7, 0xde)
        }
        val accent = color("Link.activeForeground", JBColor(Color(0x1f6feb), Color(0x4daafc)))
        val button = color("Button.default.startBackground", accent)
        return """
            <style>
            :root {
              --vscode-editor-background: ${hex(bg)};
              --vscode-editor-foreground: ${hex(fg)};
              --vscode-descriptionForeground: ${hex(muted)};
              --vscode-editorWidget-background: ${hex(card)};
              --vscode-widget-border: ${hex(border)};
              --vscode-charts-blue: ${if (dark) "#4daafc" else "#1f6feb"}; --vscode-charts-purple: ${if (dark) "#c191e0" else "#8250df"};
              --vscode-charts-green: ${if (dark) "#89d185" else "#1a7f37"}; --vscode-charts-orange: #e0883a;
              --vscode-charts-yellow: #d4a72c; --vscode-charts-red: #f14c4c;
              --vscode-font-family: -apple-system, "Segoe UI", system-ui, sans-serif;
              --vscode-dropdown-background: ${hex(card)}; --vscode-dropdown-foreground: ${hex(fg)};
              --vscode-input-background: ${hex(card)}; --vscode-input-foreground: ${hex(fg)};
              --vscode-input-border: ${hex(border)};
              --vscode-button-background: ${hex(button)}; --vscode-button-foreground: #ffffff;
            }
            body { color: var(--vscode-editor-foreground); background: var(--vscode-editor-background); }
            </style>
        """.trimIndent()
    }

    override fun dispose() {
        unsubscribe()
        jsQuery?.let { runCatching { Disposer.dispose(it) } }
    }
}
