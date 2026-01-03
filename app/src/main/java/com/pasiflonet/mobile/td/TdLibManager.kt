package com.pasiflonet.mobile.td

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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

    // IMPORTANT: Updates must be event-queue, not StateFlow (StateFlow loses rapid events)
    private val _updatesFlow = MutableSharedFlow<TdApi.Object>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val updatesFlow: SharedFlow<TdApi.Object> = _updatesFlow

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
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

        // emit all updates as events
        val ok = _updatesFlow.tryEmit(obj)
        if (!ok) {
            // overflowed; we drop oldest. keep silent or log if you want:
            // Log.w(TAG, "updatesFlow overflow; dropping")
        }
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
