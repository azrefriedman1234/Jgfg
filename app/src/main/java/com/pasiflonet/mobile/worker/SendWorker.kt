package com.pasiflonet.mobile.worker

import android.content.Context
import android.net.Uri
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.pasiflonet.mobile.td.TdLibManager
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.drinkless.tdlib.TdApi
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        const val KEY_SRC_CHAT_ID = "src_chat_id"
        const val KEY_SRC_MESSAGE_ID = "src_message_id"
        const val KEY_TARGET_USERNAME = "target_username"
        const val KEY_TEXT = "text"
        const val KEY_SEND_WITH_MEDIA = "send_with_media"

        // optional
        const val KEY_WATERMARK_URI = "watermark_uri"          // string uri or file path
        const val KEY_BLUR_RECTS_JSON = "blur_rects_json"      // JSON array of rects: [{"l":0.1,"t":0.2,"r":0.4,"b":0.5}, ...]
    }

    override fun doWork(): Result {
        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()

        val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
        val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)
        val targetUsernameRaw = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim()
        val requestedText = inputData.getString(KEY_TEXT).orEmpty()
        val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, false)

        val watermarkUri = inputData.getString(KEY_WATERMARK_URI).orEmpty().trim()
        val blurJson = inputData.getString(KEY_BLUR_RECTS_JSON).orEmpty().trim()

        if (srcChatId == 0L || srcMsgId == 0L || targetUsernameRaw.isBlank()) return Result.failure()

        val username = targetUsernameRaw.removePrefix("@").trim()
        if (username.isBlank()) return Result.failure()

        // 1) Resolve @username -> chatId
        val targetChatId = resolvePublicChatId(username) ?: return Result.failure()

        // 2) Get source message (כדי לדעת אם יש מדיה)
        val srcMessage = getMessage(srcChatId, srcMsgId) ?: return Result.failure()

        // 3) Decide final text:
        // אם המשתמש לא כתב כלום - נשתמש בטקסט/כיתוב של ההודעה המקורית (אם יש)
        val fallbackText = extractAnyText(srcMessage).orEmpty()
        val finalText = (requestedText.ifBlank { fallbackText }).trim()

        // 4) If not sending media -> send text only
        if (!sendWithMedia) {
            return if (sendTextOnly(targetChatId, finalText)) Result.success() else Result.failure()
        }

        // 5) Extract media fileId from src message (photo/video/document/animation)
        val media = extractMedia(srcMessage)
        if (media == null) {
            // אין מדיה - fallback לטקסט בלבד
            return if (sendTextOnly(targetChatId, finalText)) Result.success() else Result.failure()
        }

        // 6) Download telegram file if needed
        val localPath = downloadTdFileBlocking(media.fileId, timeoutSec = 120) ?: run {
            // לא הצליח להוריד -> fallback לטקסט בלבד כדי שלא "ישקר" שנשלח
            return if (sendTextOnly(targetChatId, finalText)) Result.success() else Result.failure()
        }

        val inFile = File(localPath)
        if (!inFile.exists() || inFile.length() <= 0) {
            return Result.failure()
        }

        // 7) Optional processing: blur + watermark (ffmpeg)
        val wmPath = resolveWatermarkPath(watermarkUri)
        val outFile = processMediaIfNeeded(
            inPath = inFile.absolutePath,
            kind = media.kind,
            watermarkPath = wmPath,
            blurRectsJson = blurJson
        ) ?: inFile.absolutePath

        // 8) Send media + caption
        val ok = sendMediaWithCaption(targetChatId, outFile, media.kind, finalText)
        return if (ok) Result.success() else Result.failure()
    }

    // -------- TDLib helpers --------

    private fun resolvePublicChatId(username: String): Long? {
        val latch = CountDownLatch(1)
        var chatId: Long? = null

        TdLibManager.send(TdApi.SearchPublicChat(username)) { obj ->
            if (obj != null && obj.constructor == TdApi.Chat.CONSTRUCTOR) {
                chatId = (obj as TdApi.Chat).id
            }
            latch.countDown()
        }

        if (!latch.await(25, TimeUnit.SECONDS)) return null
        return chatId
    }

    private fun getMessage(chatId: Long, messageId: Long): TdApi.Message? {
        val latch = CountDownLatch(1)
        var msg: TdApi.Message? = null

        TdLibManager.send(TdApi.GetMessage(chatId, messageId)) { obj ->
            if (obj != null && obj.constructor == TdApi.Message.CONSTRUCTOR) {
                msg = obj as TdApi.Message
            }
            latch.countDown()
        }

        if (!latch.await(25, TimeUnit.SECONDS)) return null
        return msg
    }

    private fun sendTextOnly(targetChatId: Long, text: String): Boolean {
        val content = TdApi.InputMessageText().apply {
            this.text = TdApi.FormattedText(text.ifBlank { " " }, null)
            this.linkPreviewOptions = null
            this.clearDraft = false
        }

        val fn = TdApi.SendMessage(targetChatId, null, null, null, null, content)

        val latch = CountDownLatch(1)
        var ok = false
        TdLibManager.send(fn) { obj ->
            ok = (obj != null && obj.constructor == TdApi.Message.CONSTRUCTOR)
            latch.countDown()
        }
        if (!latch.await(40, TimeUnit.SECONDS)) return false
        return ok
    }

    // TDLib: download and wait for completion
    private fun downloadTdFileBlocking(fileId: Int, timeoutSec: Int): String? {
        // kick off download
        run {
            val latch = CountDownLatch(1)
            TdLibManager.send(TdApi.DownloadFile(fileId, 1, 0, 0, true)) { _ -> latch.countDown() }
            latch.await(10, TimeUnit.SECONDS)
        }

        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val file = getFile(fileId)
            if (file != null) {
                val local = file.local
                if (local != null && local.isDownloadingCompleted && !local.path.isNullOrBlank()) {
                    return local.path
                }
            }
            Thread.sleep(500)
        }
        return null
    }

    private fun getFile(fileId: Int): TdApi.File? {
        val latch = CountDownLatch(1)
        var f: TdApi.File? = null
        TdLibManager.send(TdApi.GetFile(fileId)) { obj ->
            if (obj != null && obj.constructor == TdApi.File.CONSTRUCTOR) {
                f = obj as TdApi.File
            }
            latch.countDown()
        }
        if (!latch.await(8, TimeUnit.SECONDS)) return null
        return f
    }

    // -------- Media extraction --------

    private data class MediaRef(val kind: Kind, val fileId: Int)
    private enum class Kind { PHOTO, VIDEO, DOCUMENT, ANIMATION }

    private fun extractAnyText(m: TdApi.Message): String? {
        return when (val c = m.content) {
            is TdApi.MessageText -> c.text?.text
            is TdApi.MessagePhoto -> c.caption?.text
            is TdApi.MessageVideo -> c.caption?.text
            is TdApi.MessageAnimation -> c.caption?.text
            is TdApi.MessageDocument -> c.caption?.text
            else -> null
        }
    }

    private fun extractMedia(m: TdApi.Message): MediaRef? {
        return when (val c = m.content) {
            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes ?: return null
                val best = sizes.maxByOrNull { it.photo?.size ?: 0 } ?: return null
                val fileId = best.photo?.id ?: return null
                MediaRef(Kind.PHOTO, fileId)
            }
            is TdApi.MessageVideo -> {
                val fileId = c.video?.video?.id ?: return null
                MediaRef(Kind.VIDEO, fileId)
            }
            is TdApi.MessageAnimation -> {
                val fileId = c.animation?.animation?.id ?: return null
                MediaRef(Kind.ANIMATION, fileId)
            }
            is TdApi.MessageDocument -> {
                val fileId = c.document?.document?.id ?: return null
                MediaRef(Kind.DOCUMENT, fileId)
            }
            else -> null
        }
    }

    // -------- FFmpeg processing (optional) --------

    private fun resolveWatermarkPath(watermarkUriOrPath: String): String? {
        if (watermarkUriOrPath.isBlank()) return null

        return try {
            // if it's a file path
            val f = File(watermarkUriOrPath)
            if (f.exists()) return f.absolutePath

            // if it's a Uri - try to copy to cache
            val uri = Uri.parse(watermarkUriOrPath)
            val out = File(applicationContext.cacheDir, "wm_${System.currentTimeMillis()}.png")
            applicationContext.contentResolver.openInputStream(uri)?.use { ins ->
                out.outputStream().use { outs -> ins.copyTo(outs) }
            } ?: return null
            if (out.exists() && out.length() > 0) out.absolutePath else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun getMediaSize(path: String): Pair<Int, Int>? {
        return try {
            val info = FFprobeKit.getMediaInformation(path).mediaInformation ?: return null
            val streams = info.streams ?: return null
            val v = streams.firstOrNull { it.type == "video" } ?: return null
            val w = v.width
            val h = v.height
            if (w != null && h != null && w > 0 && h > 0) Pair(w, h) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun processMediaIfNeeded(
        inPath: String,
        kind: Kind,
        watermarkPath: String?,
        blurRectsJson: String
    ): String? {
        val wantBlur = blurRectsJson.isNotBlank()
        val wantWm = !watermarkPath.isNullOrBlank()
        if (!wantBlur && !wantWm) return null

        val size = getMediaSize(inPath)
        val (W, H) = size ?: Pair(0, 0)

        val out = when (kind) {
            Kind.PHOTO -> File(applicationContext.cacheDir, "out_${System.currentTimeMillis()}.jpg")
            Kind.VIDEO, Kind.ANIMATION -> File(applicationContext.cacheDir, "out_${System.currentTimeMillis()}.mp4")
            Kind.DOCUMENT -> File(applicationContext.cacheDir, "out_${System.currentTimeMillis()}_${File(inPath).name}")
        }

        // For DOCUMENT: אם זה לא וידאו/תמונה אמיתית, לא נוגעים (כדי לא לשבור)
        if (kind == Kind.DOCUMENT && (wantBlur || wantWm)) {
            // ננסה רק watermark אם זה תמונה/וידאו – אבל בלי לדעת בטוח, עדיף לא לעבד.
            return null
        }

        val filter = buildFilterGraph(
            W = W, H = H,
            blurRectsJson = blurRectsJson,
            watermarkPath = watermarkPath
        ) ?: return null

        val cmd = if (kind == Kind.PHOTO) {
            val inputs = if (wantWm) "-i \"$inPath\" -i \"$watermarkPath\"" else "-i \"$inPath\""
            "$inputs -filter_complex \"$filter\" -frames:v 1 -q:v 2 -y \"${out.absolutePath}\""
        } else {
            val inputs = if (wantWm) "-i \"$inPath\" -i \"$watermarkPath\"" else "-i \"$inPath\""
            "$inputs -filter_complex \"$filter\" -c:v libx264 -preset veryfast -crf 23 -c:a copy -movflags +faststart -y \"${out.absolutePath}\""
        }

        val session = FFmpegKit.execute(cmd)
        val rc = session.returnCode
        return if (ReturnCode.isSuccess(rc) && out.exists() && out.length() > 0) out.absolutePath else null
    }

    private fun buildFilterGraph(W: Int, H: Int, blurRectsJson: String, watermarkPath: String?): String? {
        val wantBlur = blurRectsJson.isNotBlank()
        val wantWm = !watermarkPath.isNullOrBlank()

        // no video size -> can't convert normalized rects to pixels reliably
        if (wantBlur && (W <= 0 || H <= 0)) return null

        // base labels
        var current = "[base]"
        val parts = mutableListOf<String>()

        // start from input 0 video
        parts += "[0:v]format=rgba[base0]"
        current = "[base0]"

        if (wantBlur) {
            // blurred copy
            parts += "[0:v]format=rgba,boxblur=20:1[blurred0]"

            val arr = JSONArray(blurRectsJson)
            var idx = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val l = o.optDouble("l", -1.0)
                val t = o.optDouble("t", -1.0)
                val r = o.optDouble("r", -1.0)
                val b = o.optDouble("b", -1.0)
                if (l < 0 || t < 0 || r <= l || b <= t) continue

                val x = (l * W).toInt().coerceAtLeast(0)
                val y = (t * H).toInt().coerceAtLeast(0)
                val w = ((r - l) * W).toInt().coerceAtLeast(2)
                val h = ((b - t) * H).toInt().coerceAtLeast(2)

                parts += "[blurred0]crop=${w}:${h}:${x}:${y}[br$idx]"
                parts += "$current[br$idx]overlay=${x}:${y}[ov$idx]"
                current = "[ov$idx]"
                idx++
            }
        }

        if (wantWm) {
            // watermark is input #1
            // scale watermark to 20% of width, keep aspect
            parts += "[1:v]scale=iw*0.20:-1[wm]"
            parts += "$current[wm]overlay=W-w-20:H-h-20[outv]"
            current = "[outv]"
        } else {
            parts += "$current[outv]"
        }

        return parts.joinToString(";")
    }

    // -------- Send media via TDLib --------

    private fun sendMediaWithCaption(targetChatId: Long, localPath: String, kind: Kind, caption: String): Boolean {
        val inputFile = TdApi.InputFileLocal(localPath)
        val cap = TdApi.FormattedText(caption.ifBlank { " " }, null)

        val content: TdApi.InputMessageContent = when (kind) {
            Kind.PHOTO -> TdApi.InputMessagePhoto(inputFile, null, null, 0, 0, cap, null, false)
            Kind.VIDEO, Kind.ANIMATION -> TdApi.InputMessageVideo(inputFile, null, null, 0, 0, 0, false, true, cap, null, false)
            Kind.DOCUMENT -> TdApi.InputMessageDocument(inputFile, null, false, cap, null)
        }

        val fn = TdApi.SendMessage(targetChatId, null, null, null, null, content)

        val latch = CountDownLatch(1)
        var ok = false
        TdLibManager.send(fn) { obj ->
            ok = (obj != null && obj.constructor == TdApi.Message.CONSTRUCTOR)
            latch.countDown()
        }
        if (!latch.await(60, TimeUnit.SECONDS)) return false
        return ok
    }
}
