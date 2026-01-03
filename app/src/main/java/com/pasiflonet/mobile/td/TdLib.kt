package com.pasiflonet.mobile.td

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

object TdLib {
    private const val TAG = "TdLib"
    private var client: Client? = null

    private val _authStateFlow = MutableSharedFlow<TdApi.AuthorizationState>(replay = 1, extraBufferCapacity = 64)
    val authStateFlow = _authStateFlow.asSharedFlow()

    fun init(ctx: Context) {
        if (client != null) return
        try { Client.setLogVerbosityLevel(1) } catch (_: Throwable) {}

        client = Client.create(
            { obj -> onUpdate(obj) },
            { ex -> Log.e(TAG, "TDLib fatal", ex) },
            { ex -> Log.e(TAG, "TDLib error", ex) }
        )
    }

    private fun onUpdate(obj: TdApi.Object) {
        if (obj.constructor == TdApi.UpdateAuthorizationState.CONSTRUCTOR) {
            val st = (obj as TdApi.UpdateAuthorizationState).authorizationState
            _authStateFlow.tryEmit(st)
        }
    }

    fun send(q: TdApi.Function<out TdApi.Object>, cb: (TdApi.Object) -> Unit) {
        val c = client ?: return
        c.send(q) { obj -> cb(obj) }
    }
}
