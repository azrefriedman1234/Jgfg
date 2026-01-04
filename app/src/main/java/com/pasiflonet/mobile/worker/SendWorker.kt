package com.pasiflonet.mobile.worker

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.arthenica.ffmpegkit.FFmpegKit
import com.pasiflonet.mobile.td.TdLibManager
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class SendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        const val KEY_SRC_CHAT_ID = "src_chat_id"
        const val KEY_SRC_MESSAGE_ID = "src_message_id"
        const val KEY_TARGET_USERNAME = "target_username"
        const val KEY_TEXT = "text"
        const val KEY_SEND_WITH_MEDIA = "send_with_media"

        const val KEY_WATERMARK_URI = "watermark_uri"
        const val KEY_MEDIA_URI = "media_uri"
        const val KEY_MEDIA_MIME = "media_mime"
        const val KEY_BLUR_RECTS = "blur_rects" // "l,t,r,b;..."

        const val KEY_WM_X = "wm_x" // 0..1 or -1
        const val KEY_WM_Y = "wm_y" // 0..1 or -1
    }

    private enum class Kind { PHOTO, VIDEO, DOCUMENT }

    private data class TdMedia(
        val localFile: File,
        val mime: String,
        val kind: Kind
    )

    override fun doWork(): Result {
        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()

        val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
        val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)
        val targetUsernameRaw = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim()
        val text = inputData.getString(KEY_TEXT).orEmpty()
        val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, false)

        val mediaUriStr = inputData.getString(KEY_MEDIA_URI).orEmpty().trim()
        val mediaMimeIn = inputData.getString(KEY_MEDIA_MIME).orEmpty().trim()

        val blurRectsStr = inputData.getString(KEY_BLUR_RECTS).orEmpty().trim()
        val watermarkUriStr = inputData.getString(KEY_WATERMARK_URI).orEmpty().trim()

        val wmX = inputData.getFloat(KEY_WM_X, -1f)
        val wmY = inputData.getFloat(KEY_WM_Y, -1f)

        if (srcChatId == 0L || srcMsgId == 0L || targetUsernameRaw.isBlank()) return Result.failure()
        val username = targetUsernameRaw.removePrefix("@").trim()
        if (username.isBlank()) return Result.failure()

        // Resolve @username -> chatId
        val targetChatId = resolvePublicChat(username) ?: return Result.failure()

        val caption = TdApi.FormattedText(text, null)

        // Text-only
        if (!sendWithMedia) {
            val content = TdApi.InputMessageText().apply {
                this.text = caption
                this.linkPreviewOptions = null
                this.clearDraft = false
            }
            return sendContent(targetChatId, content)
        }

        // Media required (NO text fallback)
        val tdMedia: TdMedia? = if (mediaUriStr.isNotBlank()) {
            val inUri = runCatching { Uri.parse(mediaUriStr) }.getOrNull() ?: return Result.failure()
            val localIn = copyUriToCache(inUri, mediaMimeIn.ifBlank { "application/octet-stream" }) ?: return Result.failure()
            val kind = when {
                mediaMimeIn.lowercase(Locale.ROOT).startsWith("image/") -> Kind.PHOTO
                mediaMimeIn.lowercase(Locale.ROOT).startsWith("video/") -> Kind.VIDEO
                else -> Kind.DOCUMENT
            }
            TdMedia(localIn, mediaMimeIn.ifBlank { "application/octet-stream" }, kind)
        } else {
            fetchMediaFromTelegram(srcChatId, srcMsgId)
        }

        if (tdMedia == null) {
            Log.e("SendWorker", "sendWithMedia=true but media could not be resolved")
            return Result.failure()
        }

        val editedFile = applyEdits(
            input = tdMedia.localFile,
            mime = tdMedia.mime,
            kind = tdMedia.kind,
            blurRectsStr = blurRectsStr,
            watermarkUriStr = watermarkUriStr,
            wmX = wmX,
            wmY = wmY
        ) ?: tdMedia.localFile

        val inputFile = TdApi.InputFileLocal(editedFile.absolutePath)

        val content: TdApi.InputMessageContent = when (tdMedia.kind) {
            Kind.PHOTO -> TdApi.InputMessagePhoto().apply { this.photo = inputFile; this.caption = caption }
            Kind.VIDEO -> TdApi.InputMessageVideo().apply { this.video = inputFile; this.caption = caption; this.supportsStreaming = true }
            Kind.DOCUMENT -> TdApi.InputMessageDocument().apply { this.document = inputFile; this.caption = caption }
        }

        return sendContent(targetChatId, content)
    }

    private fun resolvePublicChat(username: String): Long? {
        val latch = CountDownLatch(1)
        var chatId: Long = 0L
        TdLibManager.send(TdApi.SearchPublicChat(username)) { obj ->
            if (obj is TdApi.Chat) chatId = obj.id
            latch.countDown()
        }
        if (!latch.await(25, TimeUnit.SECONDS)) return null
        return chatId.takeIf { it != 0L }
    }

    private fun sendContent(chatId: Long, content: TdApi.InputMessageContent): Result {
        val latch = CountDownLatch(1)
        var ok = false
        TdLibManager.send(TdApi.SendMessage(chatId, null, null, null, null, content)) { obj ->
            ok = (obj is TdApi.Message)
            latch.countDown()
        }
        if (!latch.await(60, TimeUnit.SECONDS)) return Result.failure()
        return if (ok) Result.success() else Result.failure()
    }

    private fun fetchMediaFromTelegram(srcChatId: Long, srcMsgId: Long): TdMedia? {
        val msg = getMessageSync(srcChatId, srcMsgId) ?: return null
        val c = msg.content ?: return null

        val (fileId, mime, kind) = when (c) {
            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes ?: emptyArray()
                val best = sizes.maxByOrNull { it.width * it.height } ?: return null
                Triple(best.photo.id, "image/jpeg", Kind.PHOTO)
            }
            is TdApi.MessageVideo -> {
                val v = c.video ?: return null
                Triple(v.video.id, v.mimeType?.ifBlank { "video/mp4" } ?: "video/mp4", Kind.VIDEO)
            }
            is TdApi.MessageAnimation -> {
                val a = c.animation ?: return null
                Triple(a.animation.id, a.mimeType?.ifBlank { "video/mp4" } ?: "video/mp4", Kind.VIDEO)
            }
            is TdApi.MessageDocument -> {
                val d = c.document ?: return null
                Triple(d.document.id, d.mimeType?.ifBlank { "application/octet-stream" } ?: "application/octet-stream", Kind.DOCUMENT)
            }
            else -> return null
        }

        val downloaded = ensureFileDownloaded(fileId) ?: return null
        val cached = copyFileToCache(downloaded, mime) ?: downloaded
        return TdMedia(cached, mime, kind)
    }

    private fun getMessageSync(chatId: Long, msgId: Long): TdApi.Message? {
        val latch = CountDownLatch(1)
        var msg: TdApi.Message? = null
        TdLibManager.send(TdApi.GetMessage(chatId, msgId)) { obj ->
            if (obj is TdApi.Message) msg = obj
            latch.countDown()
        }
        if (!latch.await(25, TimeUnit.SECONDS)) return null
        return msg
    }

    private fun ensureFileDownloaded(fileId: Int, timeoutSec: Int = 180): File? {
        // start download (async)
        TdLibManager.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { }

        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            var f: TdApi.File? = null
            TdLibManager.send(TdApi.GetFile(fileId)) { obj ->
                if (obj is TdApi.File) f = obj
                latch.countDown()
            }
            latch.await(10, TimeUnit.SECONDS)

            val path = f?.local?.path
            val done = f?.local?.isDownloadingCompleted ?: false
            if (!path.isNullOrBlank() && done) {
                val ff = File(path)
                if (ff.exists() && ff.length() > 0) return ff
            }
            Thread.sleep(400)
        }
        return null
    }

    private fun copyFileToCache(src: File, mime: String): File? {
        return try {
            val ext = when {
                mime.startsWith("image/") -> ".jpg"
                mime.startsWith("video/") -> ".mp4"
                else -> ".bin"
            }
            val out = File(applicationContext.cacheDir, "tg_${System.currentTimeMillis()}$ext")
            src.inputStream().use { input ->
                FileOutputStream(out).use { fos -> input.copyTo(fos) }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    private fun copyUriToCache(uri: Uri, mime: String): File? {
        return try {
            val ext = when {
                mime.startsWith("image/") -> ".jpg"
                mime.startsWith("video/") -> ".mp4"
                else -> ".bin"
            }
            val out = File(applicationContext.cacheDir, "send_${System.currentTimeMillis()}$ext")
            applicationContext.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                FileOutputStream(out).use { fos -> input.copyTo(fos) }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    private data class PxRect(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun parseRects(rectsStr: String, w: Int, h: Int): List<PxRect> {
        if (rectsStr.isBlank()) return emptyList()
        val out = ArrayList<PxRect>()
        for (part in rectsStr.split(";")) {
            val p = part.split(",")
            if (p.size != 4) continue
            val l = p[0].toFloatOrNull() ?: continue
            val t = p[1].toFloatOrNull() ?: continue
            val r = p[2].toFloatOrNull() ?: continue
            val b = p[3].toFloatOrNull() ?: continue
            if (r <= l || b <= t) continue

            val x = (l.coerceIn(0f, 1f) * w).roundToInt().coerceIn(0, w - 2)
            val y = (t.coerceIn(0f, 1f) * h).roundToInt().coerceIn(0, h - 2)
            val ww = ((r - l).coerceAtLeast(0.001f) * w).roundToInt().coerceAtLeast(2).coerceIn(2, w - x)
            val hh = ((b - t).coerceAtLeast(0.001f) * h).roundToInt().coerceAtLeast(2).coerceIn(2, h - y)
            out.add(PxRect(x, y, ww, hh))
        }
        return out
    }

    private fun getVideoSize(f: File): Pair<Int, Int>? {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(f.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            r.release()
            if (w > 0 && h > 0) Pair(w, h) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun getImageSize(f: File): Pair<Int, Int>? {
        return try {
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opt)
            if (opt.outWidth > 0 && opt.outHeight > 0) Pair(opt.outWidth, opt.outHeight) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveWatermarkFile(wm: String): File? {
        return try {
            val u = Uri.parse(wm)
            when (u.scheme?.lowercase(Locale.ROOT)) {
                "content" -> copyUriToCache(u, "image/png")
                "file" -> File(u.path ?: return null).takeIf { it.exists() }
                else -> File(wm).takeIf { it.exists() }
            }
        } catch (_: Throwable) {
            File(wm).takeIf { it.exists() }
        }
    }

    private fun wmXY(w: Int, h: Int, wmW: Int, wmX: Float, wmY: Float): Pair<Int, Int> {
        val pad = (w * 0.02f).roundToInt().coerceAtLeast(12)
        if (wmX < 0f || wmY < 0f) {
            val x = (w - wmW - pad).coerceAtLeast(0)
            val y = (h - (wmW / 2) - pad).coerceAtLeast(0)
            return Pair(x, y)
        }
        val x = (wmX.coerceIn(0f, 1f) * w).roundToInt().coerceIn(0, w - 2)
        val y = (wmY.coerceIn(0f, 1f) * h).roundToInt().coerceIn(0, h - 2)
        return Pair(x, y)
    }

    private fun applyEdits(
        input: File,
        mime: String,
        kind: Kind,
        blurRectsStr: String,
        watermarkUriStr: String,
        wmX: Float,
        wmY: Float
    ): File? {
        val wmFile = if (watermarkUriStr.isNotBlank()) resolveWatermarkFile(watermarkUriStr) else null
        val hasWm = (wmFile != null)
        val hasBlur = blurRectsStr.isNotBlank()

        if (!hasWm && !hasBlur) return null

        return when (kind) {
            Kind.PHOTO -> {
                val (iw, ih) = getImageSize(input) ?: return null
                val rects = parseRects(blurRectsStr, iw, ih)
                val wmW = (iw * 0.22f).roundToInt().coerceAtLeast(48)
                val (px, py) = wmXY(iw, ih, wmW, wmX, wmY)

                val filter = buildFilter(rects, hasWm, wmW, px, py)
                val out = File(applicationContext.cacheDir, "img_${System.currentTimeMillis()}.jpg")

                val cmd = mutableListOf<String>()
                cmd += "-y"
                cmd += "-i"; cmd += input.absolutePath
                if (hasWm) { cmd += "-i"; cmd += wmFile!!.absolutePath }
                cmd += "-filter_complex"; cmd += filter
                cmd += "-map"; cmd += "[vout]"
                cmd += "-frames:v"; cmd += "1"
                cmd += "-q:v"; cmd += "2"
                cmd += out.absolutePath

                val session = FFmpegKit.execute(cmd.joinToString(" "))
                val rc = session.returnCode
                if (rc != null && rc.isValueSuccess) out else null
            }

            Kind.VIDEO -> {
                val (vw, vh) = getVideoSize(input) ?: return null
                val rects = parseRects(blurRectsStr, vw, vh)
                val wmW = (vw * 0.22f).roundToInt().coerceAtLeast(48)
                val (px, py) = wmXY(vw, vh, wmW, wmX, wmY)

                val filter = buildFilter(rects, hasWm, wmW, px, py)
                val out = File(applicationContext.cacheDir, "vid_${System.currentTimeMillis()}.mp4")

                val cmd = mutableListOf<String>()
                cmd += "-y"
                cmd += "-i"; cmd += input.absolutePath
                if (hasWm) { cmd += "-i"; cmd += wmFile!!.absolutePath }
                cmd += "-filter_complex"; cmd += filter
                cmd += "-map"; cmd += "[vout]"
                cmd += "-map"; cmd += "0:a?"
                cmd += "-c:v"; cmd += "libx264"
                cmd += "-crf"; cmd += "23"
                cmd += "-preset"; cmd += "veryfast"
                cmd += "-c:a"; cmd += "aac"
                cmd += out.absolutePath

                val session = FFmpegKit.execute(cmd.joinToString(" "))
                val rc = session.returnCode
                if (rc != null && rc.isValueSuccess) out else null
            }

            Kind.DOCUMENT -> null
        }
    }

    private fun buildFilter(rects: List<PxRect>, hasWm: Boolean, wmW: Int, wmXpx: Int, wmYpx: Int): String {
        // no blur
        if (rects.isEmpty()) {
            return if (hasWm) {
                "[1:v]scale=${wmW}:-1[wm];[0:v][wm]overlay=${wmXpx}:${wmYpx}[vout]"
            } else {
                "[0:v]copy[vout]"
            }
        }

        val n = rects.size
        val sb = StringBuilder()

        sb.append("[0:v]split=").append(n + 1)
        for (i in 0 until (n + 1)) sb.append("[v").append(i).append("]")
        sb.append(";")

        for (i in 0 until n) {
            val r = rects[i]
            sb.append("[v").append(i + 1).append("]")
            sb.append("crop=").append(r.w).append(":").append(r.h).append(":").append(r.x).append(":").append(r.y)
            sb.append(",boxblur=10:1")
            sb.append("[b").append(i).append("];")
        }

        var base = "[v0]"
        for (i in 0 until n) {
            val r = rects[i]
            sb.append(base)
                .append("[b").append(i).append("]")
                .append("overlay=").append(r.x).append(":").append(r.y)
                .append("[o").append(i).append("];")
            base = "[o$i]"
        }

        if (hasWm) {
            sb.append(base).append("copy[vb];")
            sb.append("[1:v]scale=").append(wmW).append(":-1[wm];")
            sb.append("[vb][wm]overlay=").append(wmXpx).append(":").append(wmYpx).append("[vout]")
        } else {
            sb.append(base).append("copy[vout]")
        }
        return sb.toString()
    }
}
