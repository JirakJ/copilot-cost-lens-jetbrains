package com.jakubjirak.copilotcostlens.sources

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** Stream a JSONL file, yielding each valid object. Malformed lines are skipped. */
fun forEachJsonLine(file: File, onObject: (JsonObject) -> Unit) {
    if (!file.isFile) return
    file.bufferedReader().useLines { lines ->
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                val el = JsonParser.parseString(trimmed)
                if (el.isJsonObject) onObject(el.asJsonObject)
            } catch (_: Exception) {
                // tolerate malformed lines — one bad record must not break a scan
            }
        }
    }
}

fun JsonObject.obj(key: String): JsonObject? =
    if (has(key) && get(key).isJsonObject) getAsJsonObject(key) else null

fun JsonObject.str(vararg keys: String): String? {
    for (k in keys) {
        val v = get(k) ?: continue
        if (v.isJsonPrimitive && v.asJsonPrimitive.isString) {
            val s = v.asString.trim()
            if (s.isNotEmpty()) return s
        }
    }
    return null
}

fun JsonObject.num(vararg keys: String): Long? {
    for (k in keys) {
        val v = get(k) ?: continue
        if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) return v.asLong
    }
    return null
}

fun JsonObject.dbl(key: String): Double? {
    val v = get(key) ?: return null
    return if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) v.asDouble else null
}

/** Total length of every string nested anywhere inside a JSON value (capped depth). */
fun totalTextLength(el: JsonElement?, depth: Int = 0): Long {
    if (el == null || depth > 12) return 0
    return when {
        el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString.length.toLong()
        el.isJsonArray -> el.asJsonArray.sumOf { totalTextLength(it, depth + 1) }
        el.isJsonObject -> el.asJsonObject.entrySet().sumOf { totalTextLength(it.value, depth + 1) }
        else -> 0
    }
}
