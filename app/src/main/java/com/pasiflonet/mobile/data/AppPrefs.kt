package com.pasiflonet.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "pasiflonet_prefs")

object AppPrefs {

    // ====== init for legacy no-ctx calls ======
    @Volatile private var appCtx: Context? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun requireCtx(): Context =
        appCtx ?: error("AppPrefs.init(context) was not called (call it in Application.onCreate)")

    // ====== keys ======
    private val KEY_API_ID = intPreferencesKey("api_id")
    private val KEY_API_HASH = stringPreferencesKey("api_hash")
    private val KEY_PHONE = stringPreferencesKey("phone")
    private val KEY_TARGET_USERNAME = stringPreferencesKey("target_username") // עם @
    private val KEY_WATERMARK_URI = stringPreferencesKey("watermark_uri")
    private val KEY_LOGGED_IN = booleanPreferencesKey("logged_in")

    // ====== legacy-style invoke wrapper: AppPrefs(this).saveApiId(...) ======
    operator fun invoke(ctx: Context): Bound = Bound(ctx)

    class Bound(private val ctx: Context) {
        fun saveApiId(v: Int) = AppPrefs.saveApiId(ctx, v)
        fun saveApiHash(v: String) = AppPrefs.saveApiHash(ctx, v)
        fun savePhone(v: String) = AppPrefs.savePhone(ctx, v)
        fun saveTargetUsername(v: String) = AppPrefs.saveTargetUsername(ctx, v)
        fun saveWatermark(v: String) = AppPrefs.saveWatermark(ctx, v)
        fun setLoggedIn(v: Boolean) = AppPrefs.setLoggedIn(ctx, v)

        fun getApiId(): Int = AppPrefs.getApiId(ctx)
        fun getApiHash(): String = AppPrefs.getApiHash(ctx)
        fun getPhone(): String = AppPrefs.getPhone(ctx)
        fun getTargetUsername(): String = AppPrefs.getTargetUsername(ctx)
        fun getWatermark(): String = AppPrefs.getWatermark(ctx)
        fun isLoggedIn(): Boolean = AppPrefs.isLoggedIn(ctx)

        val loggedInFlow: Flow<Boolean> get() = AppPrefs.loggedInFlow(ctx)
        val apiIdFlow: Flow<String> get() = AppPrefs.apiIdFlow(ctx) // String כדי למנוע setText(Int) ambiguity
        val apiHashFlow: Flow<String> get() = AppPrefs.apiHashFlow(ctx)
        val phoneFlow: Flow<String> get() = AppPrefs.phoneFlow(ctx)
        val targetUsernameFlow: Flow<String> get() = AppPrefs.targetUsernameFlow(ctx)
        val watermarkFlow: Flow<String> get() = AppPrefs.watermarkFlow(ctx)
    }

    // ====== flows (ctx) ======
    fun loggedInFlow(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[KEY_LOGGED_IN] ?: false }.distinctUntilChanged()

    fun apiIdFlow(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { (it[KEY_API_ID] ?: 0).toString() }.distinctUntilChanged()

    fun apiHashFlow(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[KEY_API_HASH] ?: "" }.distinctUntilChanged()

    fun phoneFlow(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[KEY_PHONE] ?: "" }.distinctUntilChanged()

    fun targetUsernameFlow(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[KEY_TARGET_USERNAME] ?: "" }.distinctUntilChanged()

    fun watermarkFlow(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[KEY_WATERMARK_URI] ?: "" }.distinctUntilChanged()

    // ====== flows (legacy no-ctx) ======
    val loggedInFlow: Flow<Boolean> get() = loggedInFlow(requireCtx())
    val apiIdFlow: Flow<String> get() = apiIdFlow(requireCtx())
    val apiHashFlow: Flow<String> get() = apiHashFlow(requireCtx())
    val phoneFlow: Flow<String> get() = phoneFlow(requireCtx())
    val targetUsernameFlow: Flow<String> get() = targetUsernameFlow(requireCtx())
    val watermarkFlow: Flow<String> get() = watermarkFlow(requireCtx())

    // ====== SAVE (ctx) ======
    fun saveApiId(ctx: Context, v: Int) = put(ctx, KEY_API_ID, v)
    fun saveApiHash(ctx: Context, v: String) = put(ctx, KEY_API_HASH, v)
    fun savePhone(ctx: Context, v: String) = put(ctx, KEY_PHONE, v)
    fun saveTargetUsername(ctx: Context, v: String) = put(ctx, KEY_TARGET_USERNAME, v.trim())
    fun saveWatermark(ctx: Context, v: String) = put(ctx, KEY_WATERMARK_URI, v)
    fun setLoggedIn(ctx: Context, v: Boolean) = put(ctx, KEY_LOGGED_IN, v)

    // ====== SAVE (legacy no-ctx) ======
    fun saveApiId(v: Int) = saveApiId(requireCtx(), v)
    fun saveApiHash(v: String) = saveApiHash(requireCtx(), v)
    fun savePhone(v: String) = savePhone(requireCtx(), v)
    fun saveTargetUsername(v: String) = saveTargetUsername(requireCtx(), v)
    fun saveWatermark(v: String) = saveWatermark(requireCtx(), v)
    fun setLoggedIn(v: Boolean) = setLoggedIn(requireCtx(), v)

    // ====== GET (ctx) ======
    fun getApiId(ctx: Context): Int = get(ctx, KEY_API_ID) ?: 0
    fun getApiHash(ctx: Context): String = get(ctx, KEY_API_HASH) ?: ""
    fun getPhone(ctx: Context): String = get(ctx, KEY_PHONE) ?: ""
    fun getTargetUsername(ctx: Context): String = get(ctx, KEY_TARGET_USERNAME) ?: ""
    fun getWatermark(ctx: Context): String = get(ctx, KEY_WATERMARK_URI) ?: ""
    fun isLoggedIn(ctx: Context): Boolean = get(ctx, KEY_LOGGED_IN) ?: false

    // ====== GET (legacy no-ctx) ======
    fun getApiId(): Int = getApiId(requireCtx())
    fun getApiHash(): String = getApiHash(requireCtx())
    fun getPhone(): String = getPhone(requireCtx())
    fun getTargetUsername(): String = getTargetUsername(requireCtx())
    fun getWatermark(): String = getWatermark(requireCtx())
    fun isLoggedIn(): Boolean = isLoggedIn(requireCtx())

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
