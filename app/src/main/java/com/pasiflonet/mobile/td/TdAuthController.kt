package com.pasiflonet.mobile.td

import android.content.Context
import android.util.Log
import com.pasiflonet.mobile.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class TdAuthController(
    private val ctx: Context,
    private val prefs: AppPrefs,
    private val scope: CoroutineScope
) {
    private val tag = "TdAuthController"

    fun start() {
        TdLibManager.ensureClient()
        scope.launch(Dispatchers.IO) {
            TdLibManager.send(TdApi.GetAuthorizationState()) { }
        }
    }

    fun setTdLibParameters(apiId: Int, apiHash: String) {
        val filesDir = ctx.filesDir.absolutePath

        // בגרסה שלך אין TdlibParameters, לכן משתמשים ב-SetTdlibParameters עצמו וממלאים שדות.
        val req = TdApi.SetTdlibParameters().apply {
            databaseDirectory = "$filesDir/tdlib"
            filesDirectory = "$filesDir/tdfiles"
            databaseEncryptionKey = ByteArray(0)

            // שדות נפוצים ברוב הגרסאות:
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false

            this.apiId = apiId
            this.apiHash = apiHash

            systemLanguageCode = "he"
            deviceModel = android.os.Build.MODEL ?: "Android"
            systemVersion = android.os.Build.VERSION.RELEASE ?: "0"
            applicationVersion = "1.0"
            enableStorageOptimizer = true
        }

        TdLibManager.send(req) { obj ->
            Log.i(tag, "SetTdlibParameters result: ${obj.constructor}")
        }
    }

    fun setPhoneNumber(phone: String) {
        val req = TdApi.SetAuthenticationPhoneNumber().apply {
            this.phoneNumber = phone
        }
        TdLibManager.send(req) { obj ->
            Log.i(tag, "Set phone result: ${obj.constructor}")
        }
    }

    fun checkCode(code: String) {
        val req = TdApi.CheckAuthenticationCode().apply {
            this.code = code
        }
        TdLibManager.send(req) { obj ->
            Log.i(tag, "Check code result: ${obj.constructor}")
        }
    }

    fun checkPassword(password: String) {
        val req = TdApi.CheckAuthenticationPassword().apply {
            this.password = password
        }
        TdLibManager.send(req) { obj ->
            Log.i(tag, "Check password result: ${obj.constructor}")
        }
    }

    fun logout() {
        TdLibManager.send(TdApi.LogOut()) { }
    }
}
