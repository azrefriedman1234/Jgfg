package com.pasiflonet.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "pasiflonet_prefs")

object AppPrefs {

    private val KEY_API_ID = intPreferencesKey("api_id")
    private val KEY_API_HASH = stringPreferencesKey("api_hash")
    private val KEY_PHONE = stringPreferencesKey("phone")
    private val KEY_TARGET_USERNAME = stringPreferencesKey("target_username") // עם @
    private val KEY_WATERMARK_URI = stringPreferencesKey("watermark_uri")
    private val KEY_LOGGED_IN = booleanPreferencesKey("logged_in")

    // ====== SAVE (blocking) ======
    fun saveApiId(ctx: Context, v: Int) = put(ctx, KEY_API_ID, v)
    fun saveApiHash(ctx: Context, v: String) = put(ctx, KEY_API_HASH, v)
    fun savePhone(ctx: Context, v: String) = put(ctx, KEY_PHONE, v)
    fun saveTargetUsername(ctx: Context, v: String) = put(ctx, KEY_TARGET_USERNAME, v.trim())
    fun saveWatermark(ctx: Context, v: String) = put(ctx, KEY_WATERMARK_URI, v)
    fun setLoggedIn(ctx: Context, v: Boolean) = put(ctx, KEY_LOGGED_IN, v)

    // ====== GET (blocking) ======
    fun getApiId(ctx: Context): Int = get(ctx, KEY_API_ID) ?: 0
    fun getApiHash(ctx: Context): String = get(ctx, KEY_API_HASH) ?: ""
    fun getPhone(ctx: Context): String = get(ctx, KEY_PHONE) ?: ""
    fun getTargetUsername(ctx: Context): String = get(ctx, KEY_TARGET_USERNAME) ?: ""
    fun getWatermark(ctx: Context): String = get(ctx, KEY_WATERMARK_URI) ?: ""
    fun isLoggedIn(ctx: Context): Boolean = get(ctx, KEY_LOGGED_IN) ?: false

    // ====== internals ======
    private fun <T> put(ctx: Context, key: Preferences.Key<T>, value: T) {
        runBlocking {
            ctx.dataStore.edit { it[key] = value }
        }
    }

    private fun <T> get(ctx: Context, key: Preferences.Key<T>): T? {
        return runBlocking {
            ctx.dataStore.data.first()[key]
        }
    }
}
