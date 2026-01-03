package com.pasiflonet.mobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.data.AppPrefs

class SettingsActivity : AppCompatActivity() {

    private lateinit var etTarget: TextInputEditText
    private lateinit var ivWatermark: ImageView
    private lateinit var btnPick: Button
    private lateinit var btnClear: Button

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {
            // לפעמים המכשיר לא נותן persist; עדיין נשמור את ה-URI ונקווה שיישמר
        }

        AppPrefs.setWatermark(this, uri.toString())
        ivWatermark.setImageURI(uri)
        Snackbar.make(etTarget, "✅ סימן מים נשמר", Snackbar.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etTarget = findViewById(R.id.etTargetUsername)
        ivWatermark = findViewById(R.id.ivWatermark)
        btnPick = findViewById(R.id.btnPickWatermark)
        btnClear = findViewById(R.id.btnClearWatermark)

        // טען ערכים
        etTarget.setText(AppPrefs.getTargetUsername(this))
        val wm = AppPrefs.getWatermark(this).trim()
        if (wm.isNotBlank()) {
            runCatching { ivWatermark.setImageURI(Uri.parse(wm)) }
        }

        // שמירה אוטומטית של יעד
        etTarget.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val t = s?.toString().orEmpty().trim()
                if (t.isBlank()) {
                    AppPrefs.setTargetUsername(this@SettingsActivity, "")
                } else {
                    AppPrefs.setTargetUsername(this@SettingsActivity, t)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnPick.setOnClickListener {
            pickImage.launch(arrayOf("image/*"))
        }

        btnClear.setOnClickListener {
            AppPrefs.setWatermark(this, "")
            ivWatermark.setImageDrawable(null)
            Snackbar.make(etTarget, "✅ סימן מים נמחק", Snackbar.LENGTH_SHORT).show()
        }
    }
}
