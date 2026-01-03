package com.pasiflonet.mobile.td

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

object TdLibManager {

    private const val TAG = "TdLibManager"

    @Suppress("unused")
    private lateinit var appCtx: Context

    private var client: Client? = null

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState

    private val _updates = MutableStateFlow<TdApi.Object?>(null)
    val updates: StateFlow<TdApi.Object?> = _updates

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        // לא משתמשים ב-Client.setLogVerbosityLevel (לא קיים אצלך)
        // אפשר לשנות Verbosity דרך TdApi.SetLogVerbosityLevel אם תרצה בהמשך.
    }

    fun ensureClient() {
        if (client != null) return

        client = Client.create(
            { obj -> handleUpdate(obj) },
            { ex -> Log.e(TAG, "TDLib fatal error", ex) },
            { ex -> Log.e(TAG, "TDLib error", ex) }
        )

        Log.i(TAG, "TDLib client created")
    }

    fun getClient(): Client {
        ensureClient()
        return client!!
    }

    private fun handleUpdate(obj: TdApi.Object) {
        if (obj.constructor == TdApi.UpdateAuthorizationState.CONSTRUCTOR) {
            val st = (obj as TdApi.UpdateAuthorizationState).authorizationState
            _authState.value = st
        }
        _updates.value = obj
    }

    fun send(query: TdApi.Function<out TdApi.Object>, cb: (TdApi.Object) -> Unit) {
        getClient().send(query) { obj ->
            try {
                cb(obj)
            } catch (t: Throwable) {
                Log.e(TAG, "Callback error", t)
            }
        }
    }
}
