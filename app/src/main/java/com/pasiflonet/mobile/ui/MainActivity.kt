package com.pasiflonet.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.databinding.ActivityMainBinding
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.td.TdMessageMapper
import com.pasiflonet.mobile.td.TdMediaDownloader
import com.pasiflonet.mobile.util.TempCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: MessagesAdapter
    private val chatIsChannel = ConcurrentHashMap<Long, Boolean>()
    private val started = AtomicBoolean(false)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val ok = res.values.all { it }
            Snackbar.make(
                b.root,
                if (ok) "✅ ניתנה הרשאה למדיה" else "⚠️ בלי הרשאה למדיה חלק מהפעולות לא יעבדו",
                Snackbar.LENGTH_LONG
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // בקשת הרשאה מיד, לפני ניווטים
        requestMediaPermissionsIfNeeded()

        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()
        TdLibManager.send(TdApi.GetAuthorizationState()) { }

        adapter = MessagesAdapter { row ->
            try {
                val i = Intent(this, DetailsActivity::class.java).apply {
                    putExtra("src_chat_id", row.chatId)
                    putExtra("src_message_id", row.messageId)
                    putExtra("src_text", row.text)
                    putExtra("src_type", row.typeLabel)
                }
                startActivity(i)
            } catch (t: Throwable) {
                Toast.makeText(this, "קריסה נמנעה בפרטים: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.btnExit.setOnClickListener { finishAffinity() }

        // אצלך הכפתור נקרא btnClearTemp
        b.btnClearTemp.setOnClickListener {
            val n = TempCleaner.clean(applicationContext)
            Snackbar.make(b.root, "✅ נמחקו קבצים זמניים: $n", Snackbar.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            TdLibManager.authState.collectLatest { st ->
                if (st == null) return@collectLatest

                if (st.constructor == TdApi.AuthorizationStateReady.CONSTRUCTOR) {
                    if (started.compareAndSet(false, true)) {

                        adapter.clearAll()
observeTdUpdates()
                    }
                } else {
                    if (started.compareAndSet(false, true)) {
                        startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                        finish()
                    }
                }
            }
        }
    }

    private fun requestMediaPermissionsIfNeeded() {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun
{
        lifecycleScope.launch(Dispatchers.IO) {
            TdLibManager.send(TdApi.GetChats(TdApi.ChatListMain(), 100)) { obj ->
                if (obj.constructor != TdApi.Chats.CONSTRUCTOR) return@send
                val chats = (obj as TdApi.Chats).chatIds ?: return@send
                for (chatId in chats) {
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
                            addMessageRow(chatId, msgs[0])
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

            val row = TdMessageMapper.mapToRow(chatId, msg, thumbPath, null)
            runOnUiThread {
                adapter.prepend(row)
                adapter.trimTo(100)
                b.recycler.scrollToPosition(0)
            }
        }
    }
}
