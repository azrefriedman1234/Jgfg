package com.pasiflonet.mobile.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.databinding.ActivityLoginBinding
import com.pasiflonet.mobile.td.TdLib
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private lateinit var prefs: AppPrefs

    private val pickWatermark = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.saveWatermark(uri.toString())
                Snackbar.make(b.root, "✅ נשמר סימן מים", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = AppPrefs(applicationContext)

        lifecycleScope.launch {
            // אם כבר מחובר – ישר לטבלה
            if (prefs.loggedInFlow.first()) {
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
                return@launch
            }

            // טוען ערכים שמורים
            val apiId = prefs.apiIdFlow.first()
            val apiHash = prefs.apiHashFlow.first()
            val phone = prefs.phoneFlow.first()
            val target = prefs.targetUsernameFlow.first()
            val watermark = prefs.watermarkFlow.first()

            if (apiId != 0) b.etApiId.setText(apiId.toString())
            if (apiHash.isNotEmpty()) b.etApiHash.setText(apiHash)
            if (phone.isNotEmpty()) b.etPhone.setText(phone)
            if (target.isNotEmpty()) b.etTargetUsername.setText(target)
            if (watermark.isNotEmpty()) b.tvWatermarkHint.text = "✅ סימן מים: נשמר"
        }

        // כפתור לבחור סימן מים
        b.btnPickWatermark.setOnClickListener { pickWatermark.launch("image/*") }

        // שולח קוד אימות (ומציג תיבת קוד + כפתור התחברות)
        b.btnSendCode.setOnClickListener {
            val apiId = b.etApiId.text?.toString()?.trim()?.toIntOrNull() ?: 0
            val apiHash = b.etApiHash.text?.toString()?.trim().orEmpty()
            val phone = b.etPhone.text?.toString()?.trim().orEmpty()
            val target = b.etTargetUsername.text?.toString()?.trim().orEmpty()

            if (apiId == 0 || apiHash.isEmpty() || phone.isEmpty() || target.isEmpty()) {
                Snackbar.make(b.root, "❌ מלא apiId/apiHash/טלפון/@ערוץ יעד", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                prefs.saveApiId(apiId)
                prefs.saveApiHash(apiHash)
                prefs.savePhone(phone)
                prefs.saveTargetUsername(target)

                // אתחול TDLib + פרמטרים
                TdLib.init(applicationContext)
                TdLib.send(setTdParams(apiId, apiHash)) { _ -> }

                // בקש שליחת קוד
                TdLib.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { r ->
                    runOnUiThread {
                        if (r is TdApi.Error) {
                            Snackbar.make(b.root, "❌ שגיאה בשליחת קוד: ${r.message}", Snackbar.LENGTH_LONG).show()
                        } else {
                            showCodeUi()
                            Snackbar.make(b.root, "✅ קוד אימות נשלח", Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // כפתור התחברות – בודק קוד או 2FA
        b.btnLogin.setOnClickListener {
            val code = b.etCode.text?.toString()?.trim().orEmpty()
            val twoFa = b.etTwoFa.text?.toString()?.trim().orEmpty()

            lifecycleScope.launch {
                TdLib.init(applicationContext)

                if (twoFa.isNotEmpty()) {
                    TdLib.send(TdApi.CheckAuthenticationPassword(twoFa)) { r ->
                        handleAuthResult(r)
                    }
                } else {
                    if (code.isEmpty()) {
                        Snackbar.make(b.root, "❌ הכנס קוד אימות", Snackbar.LENGTH_SHORT).show()
                        return@launch
                    }
                    TdLib.send(TdApi.CheckAuthenticationCode(code)) { r ->
                        handleAuthResult(r)
                    }
                }
            }
        }

        // ברירת מחדל: רק שליחת קוד מוצג, והקוד/התחברות מוסתרים עד שליחת קוד
        showPhoneUi()
    }

    private fun handleAuthResult(obj: TdApi.Object) {
        runOnUiThread {
            when (obj) {
                is TdApi.Error -> {
                    val msg = obj.message ?: "שגיאה"
                    if (msg.contains("PASSWORD", ignoreCase = true) || msg.contains("2FA", ignoreCase = true)) {
                        showTwoFaUi()
                        Snackbar.make(b.root, "🔐 נדרש אימות דו-שלבי (2FA)", Snackbar.LENGTH_LONG).show()
                    } else {
                        Snackbar.make(b.root, "❌ התחברות נכשלה: $msg", Snackbar.LENGTH_LONG).show()
                    }
                }
                else -> {
                    // לא מספיק לסמוך על זה — מחכים ל־Ready דרך עדכון סטייט:
                    Snackbar.make(b.root, "⏳ מאמת…", Snackbar.LENGTH_SHORT).show()
                    waitForReadyAndEnter()
                }
            }
        }
    }

    private fun waitForReadyAndEnter() {
        lifecycleScope.launch {
            // TDLib שולח UpdateAuthorizationState -> Ready
            TdLib.authStateFlow.collect { st ->
                if (st is TdApi.AuthorizationStateReady) {
                    prefs.setLoggedIn(true)
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }
            }
        }
    }

    private fun showPhoneUi() {
        b.boxCode.visibility = android.view.View.GONE
        b.boxTwoFa.visibility = android.view.View.GONE
        b.btnLogin.visibility = android.view.View.GONE
        b.btnSendCode.visibility = android.view.View.VISIBLE
    }

    private fun showCodeUi() {
        b.boxCode.visibility = android.view.View.VISIBLE
        b.boxTwoFa.visibility = android.view.View.GONE
        b.btnLogin.visibility = android.view.View.VISIBLE
    }

    private fun showTwoFaUi() {
        b.boxCode.visibility = android.view.View.VISIBLE
        b.boxTwoFa.visibility = android.view.View.VISIBLE
        b.btnLogin.visibility = android.view.View.VISIBLE
    }

    private fun setTdParams(apiId: Int, apiHash: String): TdApi.SetTdlibParameters {
        val dbDir = filesDir.resolve("tdlib").absolutePath
        val filesDir = filesDir.resolve("tdlib_files").absolutePath
        val key = ByteArray(0)

        // חתימה שמתאימה ל־1.8.56 לפי הלוגים שלך (אין enableStorageOptimizer כאן)
        return TdApi.SetTdlibParameters(
            false,          // useTestDc
            dbDir,
            filesDir,
            key,
            true,           // useFileDatabase
            true,           // useChatInfoDatabase
            true,           // useMessageDatabase
            true,           // useSecretChats
            apiId,
            apiHash,
            "en",
            Build.MODEL ?: "Android",
            Build.VERSION.RELEASE ?: "0",
            "1.0"
        )
    }
}
