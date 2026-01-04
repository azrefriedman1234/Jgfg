package com.pasiflonet.mobile.worker

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
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
        const val KEY_BLUR_RECTS = "blur_rects"
        const val KEY_WM_X = "wm_x"
        const val KEY_WM_Y = "wm_y"
    }

    private enum class Kind { PHOTO, VIDEO, DOCUMENT }
    private data class TdMedia(val localFile: File, val mime: String, val kind: Kind)

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

        val targetChatId = resolvePublicChat(username) ?: return Result.failure()
        val caption = TdApi.FormattedText(text, null)

        if (!sendWithMedia) {
            val content = TdApi.InputMessageText().apply {
                this.text = caption
                this.linkPreviewOptions = null
                this.clearDraft = false
            }
            return sendContent(targetChatId, content)
        }

        // MEDIA REQUIRED: no silent fallback
        val tdMedia = if (mediaUriStr.isNotBlank()) {
            val inUri = runCatching { Uri.parse(mediaUriStr) }.getOrNull() ?: return Result.failure()
            val localIn = copyUriToCache(inUri, mediaMimeIn.ifBlank { "application/octet-stream" }) ?: return Result.failure()
            val kind = when {
                mediaMimeIn.lowercase(Locale.ROOT).startsWith("image/") -> Kind.PHOTO
                mediaMimeIn.lowercase(Locale.ROOT).startsWith("video/") -> Kind.VIDEO
                else -> Kind.DOCUMENT
            }
            TdMedia(localIn, mediaMimeIn.ifBlank { "application/octet-stream" }, kind)
        } else {
            fetchMediaFromTelegram(srcChatId, srcMsgId) ?: return Result.failure()
        }

        val createdTemps = mutableListOf<File>()
        createdTemps += tdMedia.localFile

        val edited = processEdits(
            input = tdMedia.localFile,
            mime = tdMedia.mime,
            kind = tdMedia.kind,
            blurRectsStr = blurRectsStr,
            watermarkUriStr = watermarkUriStr,
            wmX = wmX,
            wmY = wmY,
            createdTemps = createdTemps
        ) ?: tdMedia.localFile

        val inputFile = TdApi.InputFileLocal(edited.absolutePath)
        val outContent: TdApi.InputMessageContent = when (tdMedia.kind) {
            Kind.PHOTO -> TdApi.InputMessagePhoto().apply { this.photo = inputFile; this.caption = caption }
            Kind.VIDEO -> TdApi.InputMessageVideo().apply { this.video = inputFile; this.caption = caption; this.supportsStreaming = true }
            Kind.DOCUMENT -> TdApi.InputMessageDocument().apply { this.document = inputFile; this.caption = caption }
        }

        val res = sendContent(targetChatId, outContent)
        cleanupTemps(createdTemps)
        return res
    }

    private fun resolvePublicChat(username: String): Long? {
        val latch = CountDownLatch(1)
        var chatId: Long = 0L
        TdLibManager.send(TdApi.SearchPublicChat(username)) { obj ->
            if (obj is TdApi.Chat) chatId = obj.id
            latch.countDown()
        }
        if (!latch.await(25, TimeUnit.SECONDS) || chatId == 0L) return null
        return chatId
    }

    private fun sendContent(chatId: Long, content: TdApi.InputMessageContent): Result {
        val latch = CountDownLatch(1)
        var ok = false
        TdLibManager.send(TdApi.SendMessage(chatId, null, null, null, null, content)) { obj ->
            ok = (obj is TdApi.Message)
            latch.countDown()
        }
        if (!latch.await(45, TimeUnit.SECONDS)) return Result.failure()
        return if (ok) Result.success() else Result.failure()
    }

    private fun fetchMediaFromTelegram(srcChatId: Long, srcMsgId: Long): TdMedia? {
        val msg = getMessageSync(srcChatId, srcMsgId) ?: return null
        val content = msg.content ?: return null

        return when (content) {
            is TdApi.MessagePhoto -> {
                val sizes = content.photo?.sizes ?: emptyArray()
                val best = sizes.maxByOrNull { it.width * it.height } ?: return null
                val downloaded = ensureFileDownloaded(best.photo.id) ?: return null
                val cached = copyFileToCache(downloaded, "image/jpeg") ?: downloaded
                TdMedia(cached, "image/jpeg", Kind.PHOTO)
            }
            is TdApi.MessageVideo -> {
                val v = content.video ?: return null
                val mime = v.mimeType?.ifBlank { "video/mp4" } ?: "video/mp4"
                val downloaded = ensureFileDownloaded(v.video.id) ?: return null
                val cached = copyFileToCache(downloaded, mime) ?: downloaded
                TdMedia(cached, mime, Kind.VIDEO)
            }
            is TdApi.MessageAnimation -> {
                val a = content.animation ?: return null
                val mime = a.mimeType?.ifBlank { "video/mp4" } ?: "video/mp4"
                val downloaded = ensureFileDownloaded(a.animation.id) ?: return null
                val cached = copyFileToCache(downloaded, mime) ?: downloaded
                TdMedia(cached, mime, Kind.VIDEO)
            }
            is TdApi.MessageDocument -> {
                val d = content.document ?: return null
                val mime = d.mimeType?.ifBlank { "application/octet-stream" } ?: "application/octet-stream"
                val downloaded = ensureFileDownloaded(d.document.id) ?: return null
                val cached = copyFileToCache(downloaded, mime) ?: downloaded
                TdMedia(cached, mime, Kind.DOCUMENT)
            }
            else -> null
        }
    }

    private fun getMessageSync(chatId: Long, msgId: Long): TdApi.Message? {
        val latch = CountDownLatch(1)
        var msg: TdApi.Message? = null
        TdLibManager.send(TdApi.GetMessage(chatId, msgId)) { obj ->
            if (obj is TdApi.Message) msg = obj
            latch.countDown()
        }
        latch.await(20, TimeUnit.SECONDS)
        return msg
    }

    private fun ensureFileDownloaded(fileId: Int, timeoutSec: Int = 180): File? {
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
        } catch (_: Throwable) { null }
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
        } catch (_: Throwable) { null }
    }

    private fun resolveWatermarkFile(wm: String, createdTemps: MutableList<File>): File? {
        return try {
            val u = Uri.parse(wm)
            when (u.scheme?.lowercase(Locale.ROOT)) {
                "content" -> copyUriToCache(u, "image/png")?.also { createdTemps += it }
                "file" -> File(u.path ?: return null).takeIf { it.exists() }
                else -> File(wm).takeIf { it.exists() }
            }
        } catch (_: Throwable) {
            File(wm).takeIf { it.exists() }
        }
    }

    private fun processEdits(
        input: File,
        mime: String,
        kind: Kind,
        blurRectsStr: String,
        watermarkUriStr: String,
        wmX: Float,
        wmY: Float,
        createdTemps: MutableList<File>
    ): File? {
        val hasWm = watermarkUriStr.isNotBlank()
        val hasBlur = blurRectsStr.isNotBlank()
        if (!hasWm && !hasBlur) return null
        if (kind == Kind.DOCUMENT) return null

        val wmFile = if (hasWm) resolveWatermarkFile(watermarkUriStr, createdTemps) else null

        return when (kind) {
            Kind.VIDEO -> {
                val (vw, vh) = getVideoSize(input) ?: return null
                val out = File(applicationContext.cacheDir, "edit_${System.currentTimeMillis()}.mp4")
                createdTemps += out
                val wmW = (vw * 0.22f).roundToInt().coerceAtLeast(48)
                val filter = buildFilter(vw, vh, blurRectsStr, wmFile, wmW, wmX, wmY) ?: return null

                val cmd = mutableListOf<String>()
                cmd += "-y"
                cmd += "-i"; cmd += input.absolutePath
                if (wmFile != null) { cmd += "-i"; cmd += wmFile.absolutePath }
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

            Kind.PHOTO -> {
                val out = File(applicationContext.cacheDir, "edit_${System.currentTimeMillis()}.jpg")
                createdTemps += out
                // photo: treat as 1080p filter target; overlay works the same
                val filter = buildFilter(1080, 1080, blurRectsStr, wmFile, 240, wmX, wmY) ?: return null

                val cmd = mutableListOf<String>()
                cmd += "-y"
                cmd += "-i"; cmd += input.absolutePath
                if (wmFile != null) { cmd += "-i"; cmd += wmFile.absolutePath }
                cmd += "-filter_complex"; cmd += filter
                cmd += "-map"; cmd += "[vout]"
                cmd += "-frames:v"; cmd += "1"
                cmd += "-q:v"; cmd += "2"
                cmd += out.absolutePath

                val session = FFmpegKit.execute(cmd.joinToString(" "))
                val rc = session.returnCode
                if (rc != null && rc.isValueSuccess) out else null
            }

            else -> null
        }
    }

    private fun buildFilter(
        vw: Int, vh: Int,
        blurRectsStr: String,
        wmFile: File?,
        wmW: Int,
        wmX: Float, wmY: Float
    ): String? {
        val rects = parseRects(blurRectsStr).mapNotNull { rr ->
            val x = (rr[0] * vw).roundToInt().coerceAtLeast(0)
            val y = (rr[1] * vh).roundToInt().coerceAtLeast(0)
            val w = ((rr[2] - rr[0]) * vw).roundToInt().coerceAtLeast(2)
            val h = ((rr[3] - rr[1]) * vh).roundToInt().coerceAtLeast(2)
            intArrayOf(x, y, w, h)
        }

        val pad = (vw * 0.02f).roundToInt().coerceAtLeast(12)
        val wmPx = if (wmX < 0f) (vw - wmW - pad).coerceAtLeast(0) else (wmX.coerceIn(0f, 1f) * vw).roundToInt()
        val wmPy = if (wmY < 0f) (vh - (wmW / 2) - pad).coerceAtLeast(0) else (wmY.coerceIn(0f, 1f) * vh).roundToInt()

        if (rects.isEmpty()) {
            return if (wmFile != null) {
                "[1:v]scale=${wmW}:-1[wm];[0:v][wm]overlay=${wmPx}:${wmPy}[vout]"
            } else null
        }

        // simple: apply boxblur to each rect (overlay blurred crops)
        val n = rects.size
        val sb = StringBuilder()
        sb.append("[0:v]split=").append(n + 1)
        for (i in 0 until (n + 1)) sb.append("[v").append(i).append("]")
        sb.append(";")

        for (i in 0 until n) {
            val r = rects[i]
            sb.append("[v").append(i + 1).append("]")
            sb.append("crop=").append(r[2]).append(":").append(r[3]).append(":").append(r[0]).append(":").append(r[1])
            sb.append(",boxblur=10:1")
            sb.append("[b").append(i).append("];")
        }

        var base = "[v0]"
        for (i in 0 until n) {
            val r = rects[i]
            sb.append(base).append("[b").append(i).append("]")
            sb.append("overlay=").append(r[0]).append(":").append(r[1]).append("[o").append(i).append("];")
            base = "[o$i]"
        }

        if (wmFile != null) {
            sb.append(base).append("copy[vb];")
            sb.append("[1:v]scale=").append(wmW).append(":-1[wm];")
            sb.append("[vb][wm]overlay=").append(wmPx).append(":").append(wmPy).append("[vout]")
        } else {
            sb.append(base).append("copy[vout]")
        }
        return sb.toString()
    }

    private fun parseRects(rectsStr: String): List<FloatArray> {
        if (rectsStr.isBlank()) return emptyList()
        return rectsStr.split(";").mapNotNull { part ->
            val p = part.split(",")
            if (p.size != 4) return@mapNotNull null
            val l = p[0].toFloatOrNull() ?: return@mapNotNull null
            val t = p[1].toFloatOrNull() ?: return@mapNotNull null
            val r = p[2].toFloatOrNull() ?: return@mapNotNull null
            val b = p[3].toFloatOrNull() ?: return@mapNotNull null
            if (r <= l || b <= t) return@mapNotNull null
            floatArrayOf(l, t, r, b)
        }
    }

    private fun getVideoSize(f: File): Pair<Int, Int>? {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(f.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            r.release()
            if (w > 0 && h > 0) Pair(w, h) else null
        } catch (_: Throwable) { null }
    }

    private fun cleanupTemps(files: List<File>) {
        val cache = applicationContext.cacheDir.absolutePath
        for (f in files.distinct()) {
            try {
                if (f.absolutePath.startsWith(cache)) {
                    if (f.name.startsWith("tg_") || f.name.startsWith("send_") || f.name.startsWith("edit_")) {
                        if (f.exists()) f.delete()
                    }
                }
            } catch (_: Throwable) {}
        }
    }
}
