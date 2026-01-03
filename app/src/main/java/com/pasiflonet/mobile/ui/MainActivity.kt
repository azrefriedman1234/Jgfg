package com.pasiflonet.mobile.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.databinding.ActivityMainBinding
import com.pasiflonet.mobile.model.MessageRow
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.td.TdMessageMapper
import com.pasiflonet.mobile.td.TdMediaDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.drinkless.td.libcore.telegram.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private lateinit var adapter: MessagesAdapter

    private val chatIsChannel = ConcurrentHashMap<Long, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = AppPrefs(this)

        adapter = MessagesAdapter(
            onDetails = { row ->
                val i = Intent(this, DetailsActivity::class.java)
                i.putExtra(DetailsActivity.EXTRA_ROW_JSON, com.pasiflonet.mobile.util.JsonUtil.toJson(row))
                startActivity(i)
            }
        )

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.btnExit.setOnClickListener { finishAffinity() }

        b.btnClearTemp.setOnClickListener {
            val dir = cacheDir
            dir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".mp4") || f.name.endsWith(".jpg") || f.name.endsWith(".png") || f.name.endsWith(".tmp"))) {
                    f.delete()
                }
            }
            Snackbar.make(b.root, "✅ זמניים נוקו", Snackbar.LENGTH_SHORT).show()
        }

        TdLibManager.ensureClient()
        seedRecentMessages()
        observeTdUpdates()
    }

    private fun seedRecentMessages() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Fetch up to 100 chats, then last message for channel chats
            TdLibManager.send(TdApi.GetChats(null, 100)) { obj ->
                if (obj.constructor != TdApi.Chats.CONSTRUCTOR) return@send
                val chats = (obj as TdApi.Chats).chatIds
                chats?.forEach { chatId ->
                    TdLibManager.send(TdApi.GetChat(chatId)) { chatObj ->
                        if (chatObj.constructor != TdApi.Chat.CONSTRUCTOR) return@send
                        val chat = chatObj as TdApi.Chat
                        val isChannel = TdMessageMapper.isChannelChat(chat)
                        chatIsChannel[chatId] = isChannel
                        if (!isChannel) return@send

                        TdLibManager.send(TdApi.GetChatHistory(chatId, 0, 0, 1, false)) { histObj ->
                            if (histObj.constructor != TdApi.Messages.CONSTRUCTOR) return@send
                            val msgs = (histObj as TdApi.Messages).messages ?: return@send
                            if (msgs.isEmpty()) return@send
                            val m = msgs[0]
                            addMessageRow(chatId, m)
                        }
                    }
                }
            }
        }
    }

    private fun observeTdUpdates() {
        lifecycleScope.launch {
            TdLibManager.updates.collectLatest { obj ->
                if (obj == null) return@collectLatest
                if (obj.constructor == TdApi.UpdateNewMessage.CONSTRUCTOR) {
                    val up = obj as TdApi.UpdateNewMessage
                    val msg = up.message ?: return@collectLatest
                    val chatId = msg.chatId

                    val isChannel = chatIsChannel[chatId]
                    if (isChannel == null) {
                        // lazy fetch chat type
                        TdLibManager.send(TdApi.GetChat(chatId)) { chatObj ->
                            if (chatObj.constructor != TdApi.Chat.CONSTRUCTOR) return@send
                            val chat = chatObj as TdApi.Chat
                            val ch = TdMessageMapper.isChannelChat(chat)
                            chatIsChannel[chatId] = ch
                            if (ch) addMessageRow(chatId, msg)
                        }
                    } else if (isChannel) {
                        addMessageRow(chatId, msg)
                    }
                }
            }
        }
    }

    private fun addMessageRow(chatId: Long, msg: TdApi.Message) {
        lifecycleScope.launch(Dispatchers.IO) {
            val thumbId = TdMessageMapper.getThumbFileId(msg.content)
            val thumbPath = if (thumbId != null) {
                TdMediaDownloader.downloadFile(thumbId, priority = 8, synchronous = true)
            } else null

            val row = TdMessageMapper.mapToRow(chatId, msg, thumbPath)
            runOnUiThread {
                adapter.prepend(row)
                // keep to 100
                adapter.trimTo(100)
                b.recycler.scrollToPosition(0)
            }
        }
    }
}
