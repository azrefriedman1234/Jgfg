package com.pasiflonet.mobile.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pasiflonet.mobile.R
import java.net.URLEncoder

class TranslateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SOURCE_TEXT = "src_text"
        const val EXTRA_TRANSLATED_TEXT = "translated_text"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translate)

        val et = findViewById<EditText>(R.id.etSource)
        val wv = findViewById<WebView>(R.id.webView)

        val src = intent.getStringExtra(EXTRA_SOURCE_TEXT).orEmpty()
        et.setText(src)

        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.webViewClient = WebViewClient()

        fun load() {
            val text = et.text?.toString().orEmpty().trim()
            if (text.isBlank()) {
                Toast.makeText(this, "אין טקסט", Toast.LENGTH_SHORT).show()
                return
            }
            val enc = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.google.com/?sl=auto&tl=iw&text=$enc&op=translate"
            wv.loadUrl(url)
        }

        findViewById<View>(R.id.btnLoad).setOnClickListener { load() }

        findViewById<View>(R.id.btnPasteBack).setOnClickListener {
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val txt = clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (txt.isBlank()) {
                Toast.makeText(this, "הלוח ריק. העתק את התרגום מהדף ואז לחץ שוב.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            setResult(RESULT_OK, Intent().putExtra(EXTRA_TRANSLATED_TEXT, txt))
            finish()
        }

        findViewById<View>(R.id.btnCloseTranslate).setOnClickListener { finish() }

        load()
        Toast.makeText(this, "העתק את התרגום (לחיצה ארוכה) ואז 'הדבק חזרה'", Toast.LENGTH_LONG).show()
    }
}
