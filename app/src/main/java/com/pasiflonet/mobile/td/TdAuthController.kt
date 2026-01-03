package com.pasiflonet.mobile.td

import android.content.Context
import android.util.Log
import com.pasiflonet.mobile.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.td.libcore.telegram.TdApi

class TdAuthController(
    private val ctx: Context,
    private val prefs: AppPrefs,
    private val scope: CoroutineScope
) {
    private val tag = "TdAuthController"
    private var lastPhone: String = ""
    private var lastApiId: Int = 0
    private var lastApiHash: String = ""

    fun start() {
        TdLibManager.ensureClient()
        scope.launch(Dispatchers.IO) {
            lastApiId = prefs.apiIdFlow.first()
            lastApiHash = prefs.apiHashFlow.first()
            lastPhone = prefs.phoneFlow.first()
            TdLibManager.send(TdApi.GetAuthorizationState()) { }
        }
    }

    fun setTdLibParameters(apiId: Int, apiHash: String) {
        lastApiId = apiId
        lastApiHash = apiHash
        val filesDir = ctx.filesDir.absolutePath
        val params = TdApi.TdlibParameters().apply {
            databaseDirectory = "$filesDir/tdlib"
            useMessageDatabase = true
            useSecretChats = false
            apiId = apiId
            apiHash = apiHash
            systemLanguageCode = "he"
            deviceModel = android.os.Build.MODEL ?: "Android"
            systemVersion = android.os.Build.VERSION.RELEASE ?: "0"
            applicationVersion = "1.0"
            enableStorageOptimizer = true
        }
        TdLibManager.send(TdApi.SetTdlibParameters(params)) { obj ->
            Log.i(tag, "SetTdlibParameters result: ${obj.constructor}")
        }
    }

    fun setPhoneNumber(phone: String) {
        lastPhone = phone
        TdLibManager.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { obj ->
            Log.i(tag, "Set phone result: ${obj.constructor}")
        }
    }

    fun checkCode(code: String) {
        TdLibManager.send(TdApi.CheckAuthenticationCode(code)) { obj ->
            Log.i(tag, "Check code result: ${obj.constructor}")
        }
    }

    fun checkPassword(password: String) {
        TdLibManager.send(TdApi.CheckAuthenticationPassword(password)) { obj ->
            Log.i(tag, "Check password result: ${obj.constructor}")
        }
    }

    fun logout() {
        TdLibManager.send(TdApi.LogOut()) { }
    }
}
