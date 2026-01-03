package com.pasiflonet.mobile.util

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object JsonUtil {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    inline fun <reified T> toJson(value: T): String = json.encodeToString(value)
    inline fun <reified T> fromJson(str: String): T = json.decodeFromString(str)
}
