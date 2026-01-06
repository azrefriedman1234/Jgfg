package com.pasiflonet.mobile.ui

import android.content.Intent
import com.pasiflonet.mobile.util.CrashLogger
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.pasiflonet.mobile.R
class MainActivity : AppCompatActivity() {

    private lateinit var etTargetChannel: TextInputEditText
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val target = etTargetChannel.text?.toString()?.trim().orEmpty()
            val i = Intent(this, DetailsActivity::class.java)
                .putExtra(DetailsActivity.EXTRA_TARGET_CHANNEL, target)
                .putExtra(DetailsActivity.EXTRA_INPUT_URI, uri.toString())
            startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // SHOW_LAST_CRASH
        CrashLogger.readAndClear(this)?.let { crash ->
            android.app.AlertDialog.Builder(this)
                .setTitle("קריסה אחרונה")
                .setMessage(crash.take(6000))
                .setPositiveButton("OK", null)
                .show()
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                R.id.menu_exit -> { finishAffinity(); true }
                else -> false
            }
        }

        etTargetChannel = findViewById(R.id.etTargetChannel)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        findViewById<MaterialButton>(R.id.btnPickVideo).setOnClickListener {
            pickVideo.launch("video/*")
        }

        findViewById<MaterialButton>(R.id.btnClearTemp).setOnClickListener {
            val (count, bytes) = clearPasiflonetTmpCount()
            android.widget.Toast.makeText(
                this,
                "נוקו $count קבצים (" + (bytes/1024).toString() + "KB)",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
tvStatus.text = "סטטוס: בחר וידאו כדי להתחיל"
    }
            android.widget.Toast.makeText(this, "נוקו קבצים זמניים", android.widget.Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            android.widget.Toast.makeText(this, "ניקוי זמניים נכשל: " + (t.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
        }
    }


    // Safe: deletes ONLY cacheDir/pasiflonet_tmp
    private fun clearPasiflonetTmpCount(): kotlin.Pair<Int, Long> {
        val dir = java.io.File(cacheDir, "pasiflonet_tmp")
        var count = 0
        var bytes = 0L
        if (dir.exists()) {
            // delete children first
            for (f in dir.walkBottomUp()) {
                if (f.isFile) {
                    count += 1
                    bytes += (try { f.length() } catch (_: Throwable) { 0L })
                }
                if (f != dir) {
                    try { f.delete() } catch (_: Throwable) {}
                }
            }
        }
        return kotlin.Pair(count, bytes)
    }

}
