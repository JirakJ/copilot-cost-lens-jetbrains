package com.jakubjirak.copilotcostlens.core

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Receipt-style PDF for a repository or project — thermal-printer look,
 * hand-written PDF 1.4 with the built-in Courier core fonts (no dependencies).
 * Core fonts cover WinAnsi only, so text is transliterated.
 */
data class ReceiptModelLine(
    val model: String,
    val requestCount: Int,
    val credits: Double,
    val usd: Double,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
)

data class ReceiptData(
    val title: String,
    val period: String,
    val models: List<ReceiptModelLine>,
    val repoLines: List<Pair<String, Double>>,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val cacheWriteTokens: Long,
    val sessionCount: Int,
    val providers: List<Pair<String, Double>>,
    val totalCredits: Double,
    val totalUsd: Double,
    val hasEstimates: Boolean,
)

private const val PAGE_W = 240
private const val MARGIN = 14
private const val LINE_H = 11
private const val COLS = (PAGE_W - 2 * MARGIN) / 6

private data class Line(val text: String, val bold: Boolean = false, val size: Int = 10, val center: Boolean = false)

fun buildReceiptPdf(data: ReceiptData): ByteArray {
    val lines = layout(data)
    val pageH = MARGIN * 2 + lines.size * LINE_H + 10
    return renderPdf(lines, pageH)
}

private fun usd(v: Double) = "$" + "%.2f".format(v)
private fun fmtNum(v: Double): String {
    val rounded = if (v >= 100) Math.round(v).toDouble() else Math.round(v * 10) / 10.0
    return "%,d".format(rounded.toLong()).let { if (v < 100 && rounded % 1.0 != 0.0) "%.1f".format(rounded) else it }
}
private fun fit(s: String, w: Int) = if (s.length > w) s.take(maxOf(1, w - 1)) + "~" else s
private fun pad(left: String, right: String, w: Int): String {
    val space = maxOf(1, w - left.length - right.length)
    return left + " ".repeat(space) + right
}

private fun layout(d: ReceiptData): List<Line> {
    val out = mutableListOf<Line>()
    fun rule() = out.add(Line("-".repeat(COLS)))
    fun dbl() = out.add(Line("=".repeat(COLS)))
    fun kv(l: String, r: String) = out.add(Line(pad(l, r, COLS)))
    fun blank() = out.add(Line(""))

    out += Line("COPILOT COST LENS", bold = true, size = 12, center = true)
    out += Line("* RECEIPT *", center = true)
    blank(); dbl()
    kv("Project", fit(d.title, COLS - 8))
    kv("Period", if (d.period == "all") "all time" else d.period)
    kv("Issued", java.time.LocalDate.now().toString())
    dbl(); blank()

    for (m in d.models) {
        out += Line(fit(m.model, COLS), bold = true)
        kv("  requests", "${m.requestCount}x")
        kv("  Credits", fmtNum(m.credits))
        kv("  USD", usd(m.usd))
        val total = m.inputTokens + m.outputTokens + m.cachedTokens + m.cacheWriteTokens
        if (total > 0) kv("  ~\$/1M", usd(m.usd / total * 1_000_000))
        blank()
    }

    if (d.repoLines.isNotEmpty()) {
        rule(); out += Line("Breakdown by repository", bold = true)
        for ((name, amt) in d.repoLines) kv("  " + fit(name, COLS - 12), usd(amt))
        blank()
    }

    rule()
    kv("Tokens in", fmtNum(d.inputTokens.toDouble()))
    kv("Tokens out", fmtNum(d.outputTokens.toDouble()))
    kv("Cache read", fmtNum(d.cachedTokens.toDouble()))
    kv("Cache write", fmtNum(d.cacheWriteTokens.toDouble()))
    kv("Sessions", d.sessionCount.toString())
    rule()

    if (d.providers.size > 1) {
        out += Line("Subtotal by source", bold = true)
        for ((p, amt) in d.providers) kv("  $p", usd(amt))
        rule()
    }

    blank()
    val totalCols = ((PAGE_W - 2 * MARGIN) / (12 * 0.6)).toInt()
    out += Line(pad("TOTAL", usd(d.totalUsd), totalCols), bold = true, size = 12)
    kv("Credits", fmtNum(d.totalCredits))
    blank(); dbl()
    if (d.hasEstimates) {
        blank()
        out += Line("~ Items marked ~ are estimates based on content length.", size = 8)
    }
    blank()
    out += Line("*** THANK YOU FOR YOUR TOKENS ***", center = true, size = 8)
    return out
}

fun toWinAnsi(text: String): String {
    val map = mapOf(
        'á' to "a", 'č' to "c", 'ď' to "d", 'é' to "e", 'ě' to "e", 'í' to "i", 'ň' to "n", 'ó' to "o",
        'ř' to "r", 'š' to "s", 'ť' to "t", 'ú' to "u", 'ů' to "u", 'ý' to "y", 'ž' to "z",
        '—' to "-", '–' to "-", '·' to "*", '×' to "x", '…' to "...",
    )
    val sb = StringBuilder()
    for (ch in text) {
        when {
            map.containsKey(ch) -> sb.append(map[ch])
            ch.code <= 0xFF -> sb.append(ch)
            else -> sb.append('?')
        }
    }
    return sb.toString()
}

private fun escapePdf(t: String) = t.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

private fun deflate(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    deflater.setInput(data); deflater.finish()
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
    deflater.end()
    return out.toByteArray()
}

private fun renderPdf(lines: List<Line>, pageH: Int): ByteArray {
    val content = StringBuilder()
    var y = pageH - MARGIN - LINE_H
    for (line in lines) {
        val size = line.size
        val font = if (line.bold) "/F2" else "/F1"
        val text = toWinAnsi(line.text)
        if (text.trim().isNotEmpty()) {
            val charW = size * 0.6
            val x = if (line.center) maxOf(MARGIN.toDouble(), (PAGE_W - text.length * charW) / 2) else MARGIN.toDouble()
            content.append("BT $font $size Tf 1 0 0 1 ${"%.1f".format(x)} ${"%.1f".format(y.toDouble())} Tm (${escapePdf(text)}) Tj ET\n")
        }
        y -= LINE_H
    }
    val stream = deflate(content.toString().toByteArray(Charsets.ISO_8859_1))

    val objects = mutableListOf<Pair<String, ByteArray?>>()
    objects += "<< /Type /Catalog /Pages 2 0 R >>" to null
    objects += "<< /Type /Pages /Kids [3 0 R] /Count 1 >>" to null
    objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $PAGE_W $pageH] /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>" to null
    objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>" to null
    objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Courier-Bold /Encoding /WinAnsiEncoding >>" to null
    objects += "<< /Length ${stream.size} /Filter /FlateDecode >>\nstream\n" to stream

    val out = ByteArrayOutputStream()
    fun write(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
    write("%PDF-1.4\n%âãÏÓ\n")
    val offsets = mutableListOf<Int>()
    objects.forEachIndexed { i, (body, binary) ->
        offsets += out.size()
        write("${i + 1} 0 obj\n$body")
        if (binary != null) { out.write(binary); write("\nendstream") }
        write("\nendobj\n")
    }
    val xrefStart = out.size()
    write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
    for (off in offsets) write("%010d 00000 n \n".format(off))
    write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefStart\n%%EOF\n")
    return out.toByteArray()
}
