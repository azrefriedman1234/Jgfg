package com.pasiflonet.mobile.data

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {
    private const val PREF = "pasiflonet_prefs"

    private const val K_LOGGED_IN = "logged_in"
    private const val K_API_ID = "api_id"
    private const val K_API_HASH = "api_hash"
    private const val K_PHONE = "phone"
    private const val K_TARGET_USERNAME = "target_username"
    private const val K_WATERMARK_PATH = "watermark_path"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isLoggedIn(ctx: Context): Boolean = sp(ctx).getBoolean(K_LOGGED_IN, false)
    fun setLoggedIn(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_LOGGED_IN, v).apply()

    fun getApiId(ctx: Context): Int = sp(ctx).getInt(K_API_ID, 0)
    fun setApiId(ctx: Context, v: Int) = sp(ctx).edit().putInt(K_API_ID, v).apply()

    fun getApiHash(ctx: Context): String = sp(ctx).getString(K_API_HASH, "") ?: ""
    fun setApiHash(ctx: Context, v: String) = sp(ctx).edit().putString(K_API_HASH, v).apply()

    fun getPhone(ctx: Context): String = sp(ctx).getString(K_PHONE, "") ?: ""
    fun setPhone(ctx: Context, v: String) = sp(ctx).edit().putString(K_PHONE, v).apply()

    fun getTargetUsername(ctx: Context): String = sp(ctx).getString(K_TARGET_USERNAME, "") ?: ""
    fun setTargetUsername(ctx: Context, v: String) = sp(ctx).edit().putString(K_TARGET_USERNAME, v).apply()

    fun getWatermark(ctx: Context): String = sp(ctx).getString(K_WATERMARK_PATH, "") ?: ""
    fun setWatermark(ctx: Context, v: String) = sp(ctx).edit().putString(K_WATERMARK_PATH, v).apply()
}
