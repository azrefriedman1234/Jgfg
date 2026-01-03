package com.pasiflonet.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.td.TdLibManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File

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

        if (AppPrefs.isLoggedIn(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(com.pasiflonet.mobile.R.layout.activity_login)

        tvStatus = findViewById(com.pasiflonet.mobile.R.id.tvStatus)
        etApiId = findViewById(com.pasiflonet.mobile.R.id.etApiId)
        etApiHash = findViewById(com.pasiflonet.mobile.R.id.etApiHash)
        etPhone = findViewById(com.pasiflonet.mobile.R.id.etPhone)
        etTargetUsername = findViewById(com.pasiflonet.mobile.R.id.etTargetUsername)
        etCode = findViewById(com.pasiflonet.mobile.R.id.etCode)
        etPassword = findViewById(com.pasiflonet.mobile.R.id.etPassword)
        btnSendCode = findViewById(com.pasiflonet.mobile.R.id.btnSendCode)
        btnLogin = findViewById(com.pasiflonet.mobile.R.id.btnLogin)

        // טעינת ערכים שמורים
        val apiIdSaved = AppPrefs.getApiId(this)
        if (apiIdSaved != 0) etApiId.setText(apiIdSaved.toString())
        etApiHash.setText(AppPrefs.getApiHash(this))
        etPhone.setText(AppPrefs.getPhone(this))
        etTargetUsername.setText(AppPrefs.getTargetUsername(this))

        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()

        // כל שינוי ביעד נשמר מיד
        etTargetUsername.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTarget()
        }

        btnSendCode.setOnClickListener {
            saveTarget()
            val apiId = etApiId.text?.toString()?.trim().orEmpty().toIntOrNull() ?: 0
            val apiHash = etApiHash.text?.toString()?.trim().orEmpty()
            val phone = etPhone.text?.toString()?.trim().orEmpty()

            if (apiId == 0 || apiHash.isBlank() || phone.isBlank()) {
                Snackbar.make(findViewById(com.pasiflonet.mobile.R.id.root), "❌ מלא API ID / API HASH / טלפון", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            AppPrefs.setApiId(this, apiId)
            AppPrefs.setApiHash(this, apiHash)
            AppPrefs.setPhone(this, phone)

            // SetTdlibParameters בצורה יציבה (בלי להיתקע על חתימות)
            val params = TdApi.TdlibParameters()
            val dbDir = File(filesDir, "tdlib").apply { mkdirs() }
            params.databaseDirectory = dbDir.absolutePath
            params.useMessageDatabase = true
            params.useFileDatabase = true
            params.useChatInfoDatabase = true
            params.useSecretChats = false
            params.apiId = apiId
            params.apiHash = apiHash
            params.systemLanguageCode = "en"
            params.deviceModel = android.os.Build.MODEL ?: "Android"
            params.systemVersion = android.os.Build.VERSION.RELEASE ?: "0"
            params.applicationVersion = "1.0"
            params.enableStorageOptimizer = true

            TdLibManager.send(TdApi.SetTdlibParameters(params)) { _ ->
                TdLibManager.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { r ->
                    runOnUiThread {
                        if (r is TdApi.Error) {
                            Snackbar.make(findViewById(com.pasiflonet.mobile.R.id.root), "❌ שליחת קוד נכשלה: ${r.message}", Snackbar.LENGTH_LONG).show()
                        } else {
                            Snackbar.make(findViewById(com.pasiflonet.mobile.R.id.root), "✅ קוד אימות נשלח", Snackbar.LENGTH_SHORT).show()
                            showCodeUi()
                        }
                    }
                }
            }
        }

        btnLogin.setOnClickListener {
            val code = etCode.text?.toString()?.trim().orEmpty()
            val pass = etPassword.text?.toString()?.trim().orEmpty()

            // אם מוצג password -> שולחים Password, אחרת Code
            if (etPassword.visibility == View.VISIBLE) {
                TdLibManager.send(TdApi.CheckAuthenticationPassword(pass)) { r ->
                    runOnUiThread {
                        if (r is TdApi.Error) {
                            Snackbar.make(findViewById(com.pasiflonet.mobile.R.id.root), "❌ 2FA נכשל: ${r.message}", Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                if (code.isBlank()) {
                    Snackbar.make(findViewById(com.pasiflonet.mobile.R.id.root), "❌ הזן קוד אימות", Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                TdLibManager.send(TdApi.CheckAuthenticationCode(code)) { r ->
                    runOnUiThread {
                        if (r is TdApi.Error) {
                            Snackbar.make(findViewById(com.pasiflonet.mobile.R.id.root), "❌ קוד שגוי: ${r.message}", Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        // מעקב מצב התחברות -> UI
        lifecycleScope.launch {
            TdLibManager.authState.collectLatest { st ->
                if (st == null) return@collectLatest
                runOnUiThread { renderState(st) }
            }
        }

        // בקשה לקבלת מצב התחברות
        TdLibManager.send(TdApi.GetAuthorizationState()) { }
    }

    private fun saveTarget() {
        val t = etTargetUsername.text?.toString()?.trim().orEmpty()
        if (t.isNotBlank()) {
            AppPrefs.setTargetUsername(this, if (t.startsWith("@")) t else "@$t")
        }
    }

    private fun showCodeUi() {
        etCode.visibility = View.VISIBLE
        btnLogin.visibility = View.VISIBLE
        btnSendCode.visibility = View.VISIBLE
    }

    private fun renderState(st: TdApi.AuthorizationState) {
        when (st) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                tvStatus.text = "סטטוס: מחכה לפרמטרים"
                btnLogin.visibility = View.GONE
                etCode.visibility = View.GONE
                etPassword.visibility = View.GONE
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                tvStatus.text = "סטטוס: מחכה לטלפון (לחץ 'שלח קוד')"
                btnLogin.visibility = View.GONE
                etCode.visibility = View.GONE
                etPassword.visibility = View.GONE
            }
            is TdApi.AuthorizationStateWaitCode -> {
                tvStatus.text = "סטטוס: מחכה לקוד אימות"
                showCodeUi()
                etPassword.visibility = View.GONE
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                tvStatus.text = "סטטוס: מחכה לסיסמת 2FA"
                showCodeUi()
                etPassword.visibility = View.VISIBLE
            }
            is TdApi.AuthorizationStateReady -> {
                tvStatus.text = "✅ התחברת!"
                AppPrefs.setLoggedIn(this, true)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            else -> {
                tvStatus.text = "סטטוס: ${st.javaClass.simpleName}"
            }
        }
    }
}
