package com.pasiflonet.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.td.TdLibManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var btnClearTemp: Button
    private lateinit var btnExit: Button

    private lateinit var adapter: MessagesAdapter
    private val startSec: Int by lazy { (System.currentTimeMillis() / 1000L).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        TdLibManager.init(this)
        TdLibManager.ensureClient()
        TdLibManager.send(TdApi.GetAuthorizationState()) { }

        recycler = findViewById(R.id.recycler)
        btnClearTemp = findViewById(R.id.btnClearTemp)
        btnExit = findViewById(R.id.btnExit)

        adapter = MessagesAdapter { m ->
            DetailsActivity.start(this, m.chatId, m.msgId, m.text)
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        btnExit.setOnClickListener { finish() }

        btnClearTemp.setOnClickListener {
            // בכוונה: לא נוגעים ב־tdlib DB כדי שלא ינתק אותך
            // כאן אתה יכול למחוק רק קבצי temp של האפליקציה שלך
        }

        lifecycleScope.launch {
            TdLibManager.authState.collectLatest { st ->
                if (st == null) return@collectLatest
                if (st.constructor != TdApi.AuthorizationStateReady.CONSTRUCTOR) {
                    // אם אין READY — הולכים ללוגין
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }
            }
        }

        lifecycleScope.launch {
            TdLibManager.updates.collectLatest { obj ->
                if (obj == null) return@collectLatest
                if (obj.constructor != TdApi.UpdateNewMessage.CONSTRUCTOR) return@collectLatest

                val up = obj as TdApi.UpdateNewMessage
                val msg = up.message ?: return@collectLatest

                // רק הודעות שנכנסו אחרי פתיחת האפליקציה
                if (msg.date < startSec) return@collectLatest

                val text = extractText(msg)
                if (text.isBlank()) return@collectLatest

                val ui = UiMsg(
                    chatId = msg.chatId,
                    msgId = msg.id,
                    dateSec = msg.date,
                    from = "src",
                    text = text
                )
                adapter.prepend(ui)
            }
        }
    }

    private fun extractText(m: TdApi.Message): String {
        val c = m.content ?: return ""
        return when (c.constructor) {
            TdApi.MessageText.CONSTRUCTOR -> (c as TdApi.MessageText).text?.text ?: ""
            TdApi.MessagePhoto.CONSTRUCTOR -> (c as TdApi.MessagePhoto).caption?.text ?: "📷 Photo"
            TdApi.MessageVideo.CONSTRUCTOR -> (c as TdApi.MessageVideo).caption?.text ?: "🎬 Video"
            TdApi.MessageDocument.CONSTRUCTOR -> (c as TdApi.MessageDocument).caption?.text ?: "📎 Document"
            else -> ""
        }
    }
}
