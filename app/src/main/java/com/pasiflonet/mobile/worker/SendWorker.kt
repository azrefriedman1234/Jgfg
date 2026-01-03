package com.pasiflonet.mobile.worker

import android.content.Context
import android.net.Uri
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.td.TdLibManager
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        const val KEY_SRC_CHAT_ID = "src_chat_id"
        const val KEY_SRC_MESSAGE_ID = "src_message_id"
        const val KEY_TARGET_USERNAME = "target_username"
        const val KEY_TEXT = "text"
        const val KEY_SEND_WITH_MEDIA = "send_with_media"

        const val KEY_MEDIA_URI = "media_uri"
        const val KEY_MEDIA_MIME = "media_mime"

        // NEW:
        const val KEY_BLUR_RECTS = "blur_rects_json" // e.g. "x1,y1,x2,y2;..."
        const val KEY_USE_WATERMARK = "use_watermark"
    }

    override fun doWork(): Result {
        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()

        val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
        val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)
        val targetUsernameRaw = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim()
        val text = inputData.getString(KEY_TEXT).orEmpty()
        val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, false)
        val mediaUriStr = inputData.getString(KEY_MEDIA_URI).orEmpty().trim()
        val mediaMime = inputData.getString(KEY_MEDIA_MIME).orEmpty().trim()
        val blurRects = inputData.getString(KEY_BLUR_RECTS).orEmpty().trim()
        val useWatermark = inputData.getBoolean(KEY_USE_WATERMARK, true)

        if (srcChatId == 0L || srcMsgId == 0L || targetUsernameRaw.isBlank()) return Result.failure()

        val username = targetUsernameRaw.removePrefix("@").trim()
        if (username.isBlank()) return Result.failure()

        // 1) Resolve @username -> chatId
        val latch1 = CountDownLatch(1)
        var targetChatId: Long = 0L
        TdLibManager.send(TdApi.SearchPublicChat(username)) { obj ->
            if (obj.constructor == TdApi.Chat.CONSTRUCTOR) {
                targetChatId = (obj as TdApi.Chat).id
            }
            latch1.countDown()
        }
        if (!latch1.await(25, TimeUnit.SECONDS) || targetChatId == 0L) return Result.failure()

        // 2) Build content
        val caption = TdApi.FormattedText(text, null)

        val content: TdApi.InputMessageContent =
            if (sendWithMedia && mediaUriStr.isNotBlank()) {

                val srcUri = Uri.parse(mediaUriStr)
                val inFile = copyUriToCache(applicationContext, srcUri)
                    ?: return Result.failure()

                // If video and we have edits -> process via FFmpegKit
                val finalFile = if (mediaMime.startsWith("video/")) {
                    val wm = if (useWatermark) AppPrefs.getWatermark(applicationContext).trim() else ""
                    val out = File(applicationContext.cacheDir, "pf_vid_out_${System.currentTimeMillis()}.mp4")
                    val ok = processVideoFFmpeg(inFile, out, blurRects, wm)
                    if (ok) out else inFile
                } else {
                    // image/doc: already edited (if any) handled in Details by sending edited uri.
                    inFile
                }

                val inputFile = TdApi.InputFileLocal(finalFile.absolutePath)

                if (mediaMime.startsWith("image/")) {
                    TdApi.InputMessagePhoto().apply {
                        photo = inputFile
                        this.caption = caption
                        hasSpoiler = false
                    }
                } else if (mediaMime.startsWith("video/")) {
                    TdApi.InputMessageVideo().apply {
                        video = inputFile
                        this.caption = caption
                        supportsStreaming = true
                        hasSpoiler = false
                    }
                } else {
                    TdApi.InputMessageDocument().apply {
                        document = inputFile
                        this.caption = caption
                    }
                }
            } else {
                TdApi.InputMessageText().apply {
                    this.text = caption
                    linkPreviewOptions = null
                    clearDraft = false
                }
            }

        // 3) Send
        val latch2 = CountDownLatch(1)
        var ok = false

        val fn = TdApi.SendMessage(targetChatId, null, null, null, null, content)
        TdLibManager.send(fn) { obj ->
            ok = (obj.constructor == TdApi.Message.CONSTRUCTOR)
            latch2.countDown()
        }

        if (!latch2.await(45, TimeUnit.SECONDS)) return Result.failure()
        return if (ok) Result.success() else Result.failure()
    }

    private fun copyUriToCache(ctx: Context, uri: Uri): File? {
        return try {
            val out = File(ctx.cacheDir, "pf_in_${System.currentTimeMillis()}")
            ctx.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                FileOutputStream(out).use { output ->
                    input.copyTo(output)
                }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * blurRects format: "x1,y1,x2,y2; x1,y1,x2,y2; ..."
     * coords are normalized 0..1 relative to video frame.
     * watermarkUri is content://... string
     */
    private fun processVideoFFmpeg(inFile: File, outFile: File, blurRects: String, watermarkUri: String): Boolean {
        // Build filtergraph
        // Start: [0:v] -> base
        var filter = "[0:v]format=yuv420p"

        val rects = parseRects(blurRects)
        var last = "v0"
        filter += "[$last];"  // placeholder; we'll rebuild properly below

        // We'll build programmatically:
        val parts = mutableListOf<String>()
        parts.add("[0:v]format=yuv420p[v0]")

        var curTag = "v0"
        var idx = 0
        for (r in rects) {
            idx += 1
            val x = "(${r.x}*W)"
            val y = "(${r.y}*H)"
            val w = "(${r.w}*W)"
            val h = "(${r.h}*H)"
            // blur region: split, crop+boxblur, overlay back
            parts.add("[$curTag]split=2[base$idx][tmp$idx]")
            parts.add("[tmp$idx]crop=$w:$h:$x:$y,boxblur=20:1[blur$idx]")
            parts.add("[base$idx][blur$idx]overlay=$x:$y[v$idx]")
            curTag = "v$idx"
        }

        // watermark overlay if provided
        var wmInputArg = ""
        var wmFilter = ""
        var inputs = "-i \"${inFile.absolutePath}\""

        if (watermarkUri.isNotBlank()) {
            val wmFile = tryCopyWatermarkToCache(watermarkUri)
            if (wmFile != null) {
                inputs += " -i \"${wmFile.absolutePath}\""
                // scale watermark to ~22% video width, keep aspect
                parts.add("[1:v]scale=iw*min(1\\, (W*0.22)/iw):-1[wm]")
                parts.add("[$curTag][wm]overlay=W-w-20:H-h-20:format=auto[vout]")
                curTag = "vout"
            }
        }

        val filterComplex = parts.joinToString(";")
        val cmd = "$inputs -filter_complex \"$filterComplex\" -map \"[$curTag]\" -map 0:a? -c:v libx264 -crf 23 -preset veryfast -c:a aac -b:a 128k -movflags +faststart -y \"${outFile.absolutePath}\""

        val session = FFmpegKit.execute(cmd)
        val rc = session.returnCode
        return ReturnCode.isSuccess(rc)
    }

    private data class NRect(val x: Float, val y: Float, val w: Float, val h: Float)

    private fun parseRects(s: String): List<NRect> {
        if (s.isBlank()) return emptyList()
        val out = mutableListOf<NRect>()
        val chunks = s.split(";").map { it.trim() }.filter { it.isNotBlank() }
        for (c in chunks) {
            val nums = c.split(",").map { it.trim() }
            if (nums.size != 4) continue
            val x1 = nums[0].toFloatOrNull() ?: continue
            val y1 = nums[1].toFloatOrNull() ?: continue
            val x2 = nums[2].toFloatOrNull() ?: continue
            val y2 = nums[3].toFloatOrNull() ?: continue
            val x = minOf(x1, x2).coerceIn(0f, 1f)
            val y = minOf(y1, y2).coerceIn(0f, 1f)
            val w = (kotlin.math.abs(x2 - x1)).coerceIn(0.01f, 1f)
            val h = (kotlin.math.abs(y2 - y1)).coerceIn(0.01f, 1f)
            out.add(NRect(x, y, w, h))
        }
        return out
    }

    private fun tryCopyWatermarkToCache(wmUriStr: String): File? {
        return try {
            val uri = Uri.parse(wmUriStr)
            val out = File(applicationContext.cacheDir, "pf_wm_${System.currentTimeMillis()}.png")
            applicationContext.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }
}
