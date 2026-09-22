package com.nexus.aichat.core.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * One JSON configuration for the whole engine.
 *
 * `isLenient` + `ignoreUnknownKeys` are not optional here: providers add fields without warning
 * (OpenAI added `reasoning_content`, OpenRouter adds `generation_id`), and half of the self-hosted
 * OpenAI-compatible servers emit slightly invalid JSON (trailing commas, unquoted keys, `NaN`).
 */
object NexusJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
        prettyPrint = false
        coerceInputValues = true
    }
}

/** Safe navigation helpers - providers omit fields far too often for `!!` to be acceptable. */
object JsonX {

    fun obj(element: JsonElement?): JsonObject? = element as? JsonObject

    fun array(element: JsonElement?): JsonArray? = element as? JsonArray

    fun str(element: JsonElement?, key: String): String? {
        val primitive = obj(element)?.get(key) as? JsonPrimitive ?: return null
        return if (primitive is JsonNull) null else primitive.content
    }

    fun int(element: JsonElement?, key: String): Int? =
        obj(element)?.get(key)?.let { (it as? JsonPrimitive)?.intOrNull }

    fun dbl(element: JsonElement?, key: String): Double? =
        obj(element)?.get(key)?.let { (it as? JsonPrimitive)?.doubleOrNull }

    fun bool(element: JsonElement?, key: String): Boolean? =
        obj(element)?.get(key)?.let { (it as? JsonPrimitive)?.booleanOrNull }

    fun nested(element: JsonElement?, vararg path: String): JsonElement? {
        var current: JsonElement? = element
        for (key in path) {
            current = obj(current)?.get(key) ?: return null
        }
        return current
    }

}

/** Escapes a JSON string value. Used by the raw-REST body templater and the inlined tool schema. */
fun String.jsonEscape(): String = buildString(length + 8) {
    this@jsonEscape.forEach { ch ->
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
}

/** Wraps a value in a JSON string literal. */
fun String.jsonString(): String = "\"${jsonEscape()}\""
