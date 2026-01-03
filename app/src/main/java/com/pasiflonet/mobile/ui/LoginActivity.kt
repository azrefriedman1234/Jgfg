package com.pasiflonet.mobile.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.td.TdLibManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class LoginActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etApiId: EditText
    private lateinit var etApiHash: EditText
    private lateinit var etPhone: EditText
    private lateinit var etTargetUsername: EditText
    private lateinit var etCode: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnSendCode: Button
    private lateinit var btnLogin: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        TdLibManager.init(this)
        TdLibManager.ensureClient()
        TdLibManager.send(TdApi.GetAuthorizationState()) { }

        tvStatus = findViewById(R.id.tvStatus)
        etApiId = findViewById(R.id.etApiId)
        etApiHash = findViewById(R.id.etApiHash)
        etPhone = findViewById(R.id.etPhone)
        etTargetUsername = findViewById(R.id.etTargetUsername)
        etCode = findViewById(R.id.etCode)
        etPassword = findViewById(R.id.etPassword)
        btnSendCode = findViewById(R.id.btnSendCode)
        btnLogin = findViewById(R.id.btnLogin)

        // טוען שמור
        etApiId.setText(AppPrefs.getApiId(this).toString())
        etApiHash.setText(AppPrefs.getApiHash(this))
        etPhone.setText(AppPrefs.getPhone(this))
        etTargetUsername.setText(AppPrefs.getTargetUsername(this))

        btnSendCode.setOnClickListener {
            saveInputs()
            ensureTdlibParamsIfNeeded()
            sendPhone()
        }

        btnLogin.setOnClickListener {
            saveInputs()
            val code = etCode.text?.toString().orEmpty().trim()
            val pass = etPassword.text?.toString().orEmpty()

            if (code.isNotBlank()) {
                TdLibManager.send(TdApi.CheckAuthenticationCode(code)) { obj ->
                    runOnUiThread {
                        Snackbar.make(tvStatus, "נשלח קוד, תשובה: ${obj.javaClass.simpleName}", Snackbar.LENGTH_SHORT).show()
                    }
                }
                return@setOnClickListener
            }

            if (pass.isNotBlank()) {
                TdLibManager.send(TdApi.CheckAuthenticationPassword(pass)) { obj ->
                    runOnUiThread {
                        Snackbar.make(tvStatus, "נשלחה סיסמה, תשובה: ${obj.javaClass.simpleName}", Snackbar.LENGTH_SHORT).show()
                    }
                }
                return@setOnClickListener
            }

            Snackbar.make(tvStatus, "תכניס קוד אימות או סיסמת 2FA", Snackbar.LENGTH_SHORT).show()
        }

        // מעקב סטטוס התחברות
        lifecycleScope.launch {
            TdLibManager.authState.collectLatest { st ->
                if (st == null) return@collectLatest
                runOnUiThread { tvStatus.text = "סטטוס: ${st.javaClass.simpleName}" }

                when (st.constructor) {
                    TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> ensureTdlibParamsIfNeeded()
                    TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> { /* מחכים ללחיצה של המשתמש */ }
                    TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> { /* המשתמש מכניס קוד */ }
                    TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> { /* המשתמש מכניס 2FA */ }
                    TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                        AppPrefs.setLoggedIn(this@LoginActivity, true)
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        finish()
                    }
                }
            }
        }
    }

    private fun saveInputs() {
        val apiId = etApiId.text?.toString().orEmpty().trim().toIntOrNull() ?: 0
        val apiHash = etApiHash.text?.toString().orEmpty().trim()
        val phone = etPhone.text?.toString().orEmpty().trim()
        val target = etTargetUsername.text?.toString().orEmpty().trim()

        AppPrefs.setApiId(this, apiId)
        AppPrefs.setApiHash(this, apiHash)
        AppPrefs.setPhone(this, phone)
        AppPrefs.setTargetUsername(this, target)
    }

    private fun ensureTdlibParamsIfNeeded() {
        val apiId = AppPrefs.getApiId(this)
        val apiHash = AppPrefs.getApiHash(this)

        if (apiId <= 0 || apiHash.isBlank()) {
            Snackbar.make(tvStatus, "חסר API ID / API HASH", Snackbar.LENGTH_SHORT).show()
            return
        }

        val dbDir = applicationContext.getDir("tdlib", MODE_PRIVATE).absolutePath
        val filesDir = applicationContext.filesDir.absolutePath
        val key = ByteArray(0)

        val fn = TdApi.SetTdlibParameters(
            false,
            dbDir,
            filesDir,
            key,
            true,
            true,
            true,
            true,
            apiId,
            apiHash,
            "en",
            Build.MODEL ?: "Android",
            Build.VERSION.RELEASE ?: "0",
            "1.0"
        )

        TdLibManager.send(fn) { }
    }

    private fun sendPhone() {
        val phone = AppPrefs.getPhone(this)
        if (phone.isBlank()) {
            Snackbar.make(tvStatus, "חסר טלפון", Snackbar.LENGTH_SHORT).show()
            return
        }
        TdLibManager.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { obj ->
            runOnUiThread {
                Snackbar.make(tvStatus, "✅ קוד אימות נשלח (אם לא חסום): ${obj.javaClass.simpleName}", Snackbar.LENGTH_SHORT).show()
            }
        }
    }
}
