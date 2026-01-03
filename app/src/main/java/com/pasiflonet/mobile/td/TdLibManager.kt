package com.pasiflonet.mobile.td

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object TdLibManager {

    private const val TAG = "TdLibManager"

    private lateinit var appCtx: Context
    private var client: Client? = null
    private val clientId = AtomicLong(1)
    private val handlers = ConcurrentHashMap<Long, (TdApi.Object) -> Unit>()

    private val _authState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authState: StateFlow<TdApi.AuthorizationState?> = _authState

    private val _updates = MutableStateFlow<TdApi.Object?>(null)
    val updates: StateFlow<TdApi.Object?> = _updates

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        try {
            // TDLib logs can be enabled if needed
            Client.setLogVerbosityLevel(1)
        } catch (_: Throwable) {}
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
        when (obj.constructor) {
            TdApi.UpdateAuthorizationState.CONSTRUCTOR -> {
                val st = (obj as TdApi.UpdateAuthorizationState).authorizationState
                _authState.value = st
            }
        }
        _updates.value = obj
    }

    fun send(query: TdApi.Function, cb: (TdApi.Object) -> Unit) {
        val id = clientId.getAndIncrement()
        handlers[id] = cb
        getClient().send(query) { obj ->
            try {
                cb(obj)
            } catch (t: Throwable) {
                Log.e(TAG, "Callback error", t)
            } finally {
                handlers.remove(id)
            }
        }
    }

    fun close() {
        try {
            client?.close()
        } catch (_: Throwable) {}
        client = null
    }
}
