package com.pasiflonet.mobile.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.data.AppPrefs

class SettingsActivity : AppCompatActivity() {

    private val pickWatermarkLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            // Persist permission so it survives reboot
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Throwable) { }
            AppPrefs.setWatermark(this, uri.toString())
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etTarget = findViewById<TextInputEditText>(R.id.etTargetUsername)
        val tvCurrent = findViewById<TextView>(R.id.tvCurrentWatermark)

        etTarget.setText(AppPrefs.getTargetUsername(this))

        // Auto-save target username (no save button)
        etTarget.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val t = s?.toString().orEmpty().trim()
                if (t.isBlank()) {
                    AppPrefs.setTargetUsername(this@SettingsActivity, "")
                } else {
                    AppPrefs.setTargetUsername(this@SettingsActivity, if (t.startsWith("@")) t else "@$t")
                }
            }
        })

        findViewById<Button>(R.id.btnPickWatermark).setOnClickListener {
            pickWatermarkLauncher.launch(arrayOf("image/*"))
        }

        findViewById<Button>(R.id.btnClearWatermark).setOnClickListener {
            AppPrefs.setWatermark(this, "")
            refresh()
        }

        fun refreshText() {
            val wm = AppPrefs.getWatermark(this).trim()
            tvCurrent.text = if (wm.isBlank()) "נוכחי: (אין)" else "נוכחי: $wm"
        }

        fun refreshAll() {
            refreshText()
        }

        refreshAll()
    }

    private fun refresh() {
        val tvCurrent = findViewById<TextView>(R.id.tvCurrentWatermark)
        val wm = AppPrefs.getWatermark(this).trim()
        tvCurrent.text = if (wm.isBlank()) "נוכחי: (אין)" else "נוכחי: $wm"
    }
}
