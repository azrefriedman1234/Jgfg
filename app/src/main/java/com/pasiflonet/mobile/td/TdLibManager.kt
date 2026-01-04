package com.pasiflonet.mobile.td

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList

object TdLibManager {

    private const val TAG = "TdLibManager"

    private lateinit var appCtx: Context
    private var client: Client? = null

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState

    private val updateListeners = CopyOnWriteArrayList<(TdApi.Object) -> Unit>()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun ensureClient() {
        if (client != null) return

        val updatesHandler = Client.ResultHandler { obj ->
            try {
                if (obj is TdApi.UpdateAuthorizationState) {
                    _authState.value = obj.authorizationState
                }
                for (l in updateListeners) {
                    try { l(obj) } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                Log.e(TAG, "updatesHandler crash", t)
            }
        }

        val exceptionHandler = Client.ExceptionHandler { e ->
            Log.e(TAG, "TDLib exception", e)
        }

        client = Client.create(updatesHandler, exceptionHandler, exceptionHandler)
        Log.d(TAG, "Client created")
    }

    fun addUpdateListener(l: (TdApi.Object) -> Unit) {
        updateListeners.add(l)
    }

    fun removeUpdateListener(l: (TdApi.Object) -> Unit) {
        updateListeners.remove(l)
    }

    fun send(fn: TdApi.Function<out TdApi.Object>, cb: (TdApi.Object) -> Unit) {
        ensureClient()
        val c = client ?: return
        c.send(fn, Client.ResultHandler { obj ->
            try { cb(obj) } catch (_: Throwable) {}
        })
    }

    fun send(fn: TdApi.Function<out TdApi.Object>) {
        send(fn) { }
    }
}
