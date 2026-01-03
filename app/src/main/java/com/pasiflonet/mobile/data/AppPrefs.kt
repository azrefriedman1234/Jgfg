package com.pasiflonet.mobile.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pasiflonet_prefs")

class AppPrefs(private val ctx: Context) {

    private object Keys {
        val API_ID = intPreferencesKey("api_id")
        val API_HASH = stringPreferencesKey("api_hash")
        val PHONE = stringPreferencesKey("phone")
        val WATERMARK_URI = stringPreferencesKey("watermark_uri")
        val TARGET_CHAT_ID = longPreferencesKey("target_chat_id")
        val LOGGED_IN = booleanPreferencesKey("logged_in")
    }

    val apiIdFlow: Flow<Int> = ctx.dataStore.data.map { it[Keys.API_ID] ?: 0 }
    val apiHashFlow: Flow<String> = ctx.dataStore.data.map { it[Keys.API_HASH] ?: "" }
    val phoneFlow: Flow<String> = ctx.dataStore.data.map { it[Keys.PHONE] ?: "" }
    val watermarkUriFlow: Flow<String> = ctx.dataStore.data.map { it[Keys.WATERMARK_URI] ?: "" }
    val targetChatIdFlow: Flow<Long> = ctx.dataStore.data.map { it[Keys.TARGET_CHAT_ID] ?: 0L }
    val loggedInFlow: Flow<Boolean> = ctx.dataStore.data.map { it[Keys.LOGGED_IN] ?: false }

    suspend fun saveApi(apiId: Int, apiHash: String, phone: String) {
        ctx.dataStore.edit {
            it[Keys.API_ID] = apiId
            it[Keys.API_HASH] = apiHash.trim()
            it[Keys.PHONE] = phone.trim()
        }
    }

    suspend fun saveWatermark(uri: Uri?) {
        ctx.dataStore.edit {
            it[Keys.WATERMARK_URI] = uri?.toString() ?: ""
        }
    }

    suspend fun saveTargetChatId(chatId: Long) {
        ctx.dataStore.edit { it[Keys.TARGET_CHAT_ID] = chatId }
    }

    suspend fun setLoggedIn(loggedIn: Boolean) {
        ctx.dataStore.edit { it[Keys.LOGGED_IN] = loggedIn }
    }
}
