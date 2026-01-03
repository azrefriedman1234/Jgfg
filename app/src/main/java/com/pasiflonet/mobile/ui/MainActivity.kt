package com.pasiflonet.mobile.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.databinding.ActivityMainBinding
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.ui.adapters.MessageAdapter
import com.pasiflonet.mobile.ui.adapters.MessageRow
import com.pasiflonet.mobile.util.TdExtract
import com.pasiflonet.mobile.util.TempCleaner
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: MessageAdapter

    private val startUnix: Int by lazy { (System.currentTimeMillis() / 1000L).toInt() }

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()

        // הרשאות מדיה פעם ראשונה (Android 13+)
        requestMediaPermissionsIfNeeded()

        // אם כבר התחברת בעבר – לא להציג Login
        if (AppPrefs.isLoggedIn(this)) {
            // ממשיכים לטבלה
        } else {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        adapter = MessageAdapter { row ->
            val it = Intent(this, DetailsActivity::class.java)
            it.putExtra("chatId", row.chatId)
            it.putExtra("msgId", row.msgId)
            it.putExtra("text", row.text)
            it.putExtra("type", row.type)
            it.putExtra("dateUnix", row.dateUnix)
            it.putExtra("miniThumbB64", row.miniThumbB64)
            it.putExtra("localPath", row.localPath)
                        it.putExtra("src_chat_id", it.chatId)
            it.putExtra("src_message_id", it.id)
startActivity(it)
        }

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        // ניקוי TEMP בלי למחוק התחברות
        b.btnClearTemp.setOnClickListener {
            val n = TempCleaner.clean(applicationContext)
            Snackbar.make(b.root, "✅ נמחקו קבצים זמניים: $n", Snackbar.LENGTH_SHORT).show()
        }

        b.btnExit.setOnClickListener { finish() }

        // רק הודעות חדשות מרגע פתיחה
        lifecycleScope.launch {
            TdLibManager.updates.collectLatest { obj ->
                if (obj == null) return@collectLatest
                if (obj.constructor != TdApi.UpdateNewMessage.CONSTRUCTOR) return@collectLatest
                val up = obj as TdApi.UpdateNewMessage
                val m = up.message ?: return@collectLatest
                if (m.date < startUnix) return@collectLatest // רק חדש

                val (text0, type) = TdExtract.textAndType(m)
                val text = text0.replace("\n", " ").trim()

                val row = MessageRow(
                    msgId = m.id,
                    chatId = m.chatId,
                    dateUnix = m.date,
                    text = if (text.length > 140) text.take(140) + "…" else text,
                    type = type,
                    miniThumbB64 = TdExtract.miniThumbB64(m),
                    localPath = TdExtract.localPathIfAny(m)
                )
                adapter.addTop(row)
                b.recycler.scrollToPosition(0)
            }
        }

        // עדכון סטטוס התחברות לפי TDLib
        lifecycleScope.launch {
            TdLibManager.authState.collectLatest { st ->
                if (st != null && st.constructor == TdApi.AuthorizationStateReady.CONSTRUCTOR) {
                    AppPrefs.setLoggedIn(this@MainActivity, true)
                }
            }
        }
    }

    private fun requestMediaPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            reqPerms.launch(arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO
            ))
        } else {
            reqPerms.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }
}
