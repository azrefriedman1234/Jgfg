package com.pasiflonet.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.ds by preferencesDataStore(name = "pasiflonet_prefs")

class AppPrefs(private val ctx: Context) {

    companion object {
        private val KEY_API_ID = intPreferencesKey("api_id")
        private val KEY_API_HASH = stringPreferencesKey("api_hash")
        private val KEY_PHONE = stringPreferencesKey("phone")
        private val KEY_TARGET_USERNAME = stringPreferencesKey("target_username") // כולל @
        private val KEY_WATERMARK_URI = stringPreferencesKey("watermark_uri")     // content://...
        private val KEY_LOGGED_IN = booleanPreferencesKey("logged_in")
    }

    val apiIdFlow: Flow<Int> = ctx.ds.data.map { it[KEY_API_ID] ?: 0 }
    val apiHashFlow: Flow<String> = ctx.ds.data.map { it[KEY_API_HASH] ?: "" }
    val phoneFlow: Flow<String> = ctx.ds.data.map { it[KEY_PHONE] ?: "" }
    val targetUsernameFlow: Flow<String> = ctx.ds.data.map { it[KEY_TARGET_USERNAME] ?: "" }
    val watermarkFlow: Flow<String> = ctx.ds.data.map { it[KEY_WATERMARK_URI] ?: "" }
    val loggedInFlow: Flow<Boolean> = ctx.ds.data.map { it[KEY_LOGGED_IN] ?: false }

    suspend fun saveApiId(v: Int) = ctx.ds.updateData { p -> p.toMutablePreferences().apply { this[KEY_API_ID] = v } }
    suspend fun saveApiHash(v: String) = ctx.ds.updateData { p -> p.toMutablePreferences().apply { this[KEY_API_HASH] = v.trim() } }
    suspend fun savePhone(v: String) = ctx.ds.updateData { p -> p.toMutablePreferences().apply { this[KEY_PHONE] = v.trim() } }

    suspend fun saveTargetUsername(v: String) {
        val s = v.trim()
        val normalized = if (s.isNotEmpty() && !s.startsWith("@")) "@$s" else s
        ctx.ds.updateData { p -> p.toMutablePreferences().apply { this[KEY_TARGET_USERNAME] = normalized } }
    }

    suspend fun saveWatermark(uri: String) = ctx.ds.updateData { p -> p.toMutablePreferences().apply { this[KEY_WATERMARK_URI] = uri } }
    suspend fun setLoggedIn(v: Boolean) = ctx.ds.updateData { p -> p.toMutablePreferences().apply { this[KEY_LOGGED_IN] = v } }

    // helper getters (מה שחסר לך בקומפילציה)
    suspend fun getTargetUsername(): String = targetUsernameFlow.map { it }.let { flow ->
        kotlinx.coroutines.flow.first(flow)
    }

    suspend fun getWatermark(): String = watermarkFlow.map { it }.let { flow ->
        kotlinx.coroutines.flow.first(flow)
    }
}
