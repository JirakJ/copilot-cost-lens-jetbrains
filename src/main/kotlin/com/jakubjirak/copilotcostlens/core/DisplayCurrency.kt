package com.jakubjirak.copilotcostlens.core

import java.util.Locale

/** Display currency for dashboard, status bar and summaries. USD keeps rate 1. */
data class DisplayCurrency(val code: String, val rate: Double)

/** Defensive parsing of the currency settings — bad values fall back to USD/1. */
fun sanitizeCurrency(codeRaw: String?, rateRaw: Double): DisplayCurrency {
    val code = codeRaw?.trim()?.takeIf { Regex("^[A-Za-z]{3}$").matches(it) }?.uppercase() ?: "USD"
    if (code == "USD") return DisplayCurrency("USD", 1.0)
    val rate = if (rateRaw.isFinite() && rateRaw > 0) rateRaw else 1.0
    return DisplayCurrency(code, rate)
}

/** Format a USD amount in the display currency: `$12.34` or `123.45 CZK`. */
fun money(usd: Double, currency: DisplayCurrency): String =
    if (currency.code == "USD") "$" + "%.2f".format(Locale.ROOT, usd)
    else "%.2f %s".format(Locale.ROOT, usd * currency.rate, currency.code)
