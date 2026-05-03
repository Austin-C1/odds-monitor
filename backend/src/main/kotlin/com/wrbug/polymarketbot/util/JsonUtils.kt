package com.wrbug.polymarketbot.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

@Component
class JsonUtils(
    private val injectedGson: Gson
) {

    @PostConstruct
    fun init() {
        _gson = injectedGson
    }

    fun parseStringArray(jsonString: String?): List<String> {
        return jsonString?.parseStringArray() ?: emptyList()
    }
}

@Volatile
private var _gson: Gson? = null

val gson: Gson
    get() = _gson ?: GsonBuilder().setLenient().create().also { _gson = it }

// ============================================================================
// ============================================================================

/**
 *
 *
 * @example
 * ```kotlin
 * val obj = MyDataClass(name = "test", value = 123)
 * val json = obj.toJson()  // {"name":"test","value":123}
 * ```
 */
fun Any?.toJson(): String {
    return if (this == null) {
        ""
    } else {
        try {
            gson.toJson(this)
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 *
 *
 * @example
 * ```kotlin
 * val json = "{\"name\":\"test\",\"value\":123}"
 * val obj = json.fromJson<MyDataClass>()  // MyDataClass(name="test", value=123)
 *
 *
 * val json = "[\"a\", \"b\", \"c\"]"
 * val list = json.fromJson<List<String>>()  // ["a", "b", "c"]
 * ```
 */
inline fun <reified T> String?.fromJson(): T? {
    if (this.isNullOrBlank()) {
        return null
    }

    return try {
        val typeToken = object : TypeToken<T>() {}
        gson.fromJson(this, typeToken.type)
    } catch (e: Exception) {
        null
    }
}

/**
 *
 *
 * @example
 * ```kotlin
 * val jsonElement: JsonElement = ...
 * val obj = jsonElement.fromJson<MyDataClass>()
 * ```
 */
inline fun <reified T> JsonElement?.fromJson(): T? {
    if (this == null) {
        return null
    }

    return try {
        val typeToken = object : TypeToken<T>() {}
        gson.fromJson(this, typeToken.type)
    } catch (e: Exception) {
        null
    }
}

/**
 *
 *
 * @example
 * ```kotlin
 * val json = "[\"Yes\", \"No\"]"
 * val list = json.parseStringArray()  // ["Yes", "No"]
 * ```
 */
fun String?.parseStringArray(): List<String> {
    if (this.isNullOrBlank()) {
        return emptyList()
    }

    return try {
        val listType = object : TypeToken<List<String>>() {}.type
        gson.fromJson<List<String>>(this, listType) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

