package com.pasiflonet.mobile.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.databinding.ActivityMainBinding
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.td.TdMessageMapper
import com.pasiflonet.mobile.util.TempCleaner
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: MessagesAdapter
    private val started = AtomicBoolean(false)
    private val chatIsChannel = ConcurrentHashMap<Long, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()
        TdLibManager.send(TdApi.GetAuthorizationState()) { }

        adapter = MessagesAdapter { row ->
            try {
                
            val i = android.content.Intent(this, DetailsActivity::class.java).apply {
                putExtra("src_chat_id", row.chatId)
                putExtra("src_message_id", row.messageId)
                putExtra("src_text", (row.text).toString())
                putExtra("src_type", (row.typeLabel).toString())
            }
            startActivity(i)
    
            } catch (t: Throwable) {
                android.widget.Toast.makeText(this, "שגיאה בפרטים: " + (t.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
            }
        }

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        try {
            b.btnExit.setOnClickListener { finishAffinity() }
        } catch (_: Throwable) {}

        try {
            b.btnClearTemp.setOnClickListener {
                val n = TempCleaner.clean(applicationContext)
                Snackbar.make(b.root, "✅ נמחקו קבצים זמניים: $n", Snackbar.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {}

        lifecycleScope.launch {
            TdLibManager.authState.collectLatest { st ->
                if (st == null) return@collectLatest

                if (st.constructor == TdApi.AuthorizationStateReady.CONSTRUCTOR) {
                    if (started.compareAndSet(false, true)) {
                        adapter.clearAll() // חשוב: מתחילים נקי — רק חדש אחרי פתיחה
                        observeOnlyNewMessages()
                    }
                } else {
                    if (started.compareAndSet(false, true)) {
                        startActivity(android.content.Intent(this@MainActivity, LoginActivity::class.java))
                        finish()
                    }
                }
            }
        }
    }

    private fun observeOnlyNewMessages() {
        lifecycleScope.launch {
            TdLibManager.updates.collectLatest { obj ->
                if (obj == null) return@collectLatest
                if (obj.constructor != TdApi.UpdateNewMessage.CONSTRUCTOR) return@collectLatest

                val up = obj as TdApi.UpdateNewMessage
                val msg = up.message ?: return@collectLatest
                val chatId = msg.chatId

                val isChannel = chatIsChannel[chatId]
                if (isChannel == null) {
                    TdLibManager.send(TdApi.GetChat(chatId)) { chatObj ->
                        if (chatObj == null || chatObj.constructor != TdApi.Chat.CONSTRUCTOR) return@send
                        val chat = chatObj as TdApi.Chat
                        val ch = TdMessageMapper.isChannelChat(chat)
                        chatIsChannel[chatId] = ch
                        if (ch) addRow(chatId, msg)
                    }
                } else if (isChannel) {
                    addRow(chatId, msg)
                }
            }
        }
    }

    private fun addRow(chatId: Long, msg: TdApi.Message) {
        val row = TdMessageMapper.mapToRow(chatId, msg, null, null)
        runOnUiThread {
            adapter.prepend(row)      // חדש למעלה
            adapter.trimTo(100)       // מוחק הכי ישן
            b.recycler.scrollToPosition(0)
        }
    }
}
