package com.pasiflonet.mobile.worker

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pasiflonet.mobile.td.TdLibManager
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        private const val TAG = "SendWorker"

        const val KEY_SRC_CHAT_ID = "src_chat_id"
        const val KEY_SRC_MESSAGE_ID = "src_message_id"
        const val KEY_TARGET_USERNAME = "target_username"
        const val KEY_TEXT = "text"
        const val KEY_SEND_WITH_MEDIA = "send_with_media"

        const val KEY_MEDIA_URI = "media_uri"
        const val KEY_MEDIA_MIME = "media_mime"

        const val KEY_WATERMARK_URI = "watermark_uri"
        const val KEY_BLUR_RECTS = "blur_rects"
        const val KEY_WM_X = "wm_x"
        const val KEY_WM_Y = "wm_y"
    }

    private enum class MediaType { PHOTO, VIDEO, ANIMATION, DOCUMENT }
    private data class MediaInfo(val fileId: Int, val mime: String, val type: MediaType)

    override fun doWork(): Result {
        try {
            TdLibManager.init(applicationContext)
            TdLibManager.ensureClient()

            val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
            val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)
            val targetUsername = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim()
            val captionText = inputData.getString(KEY_TEXT).orEmpty()

            val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, true)
            val mimeHint = inputData.getString(KEY_MEDIA_MIME).orEmpty().trim()

            val blurRectsStr = inputData.getString(KEY_BLUR_RECTS).orEmpty().trim()
            val watermarkUriStr = inputData.getString(KEY_WATERMARK_URI).orEmpty().trim()
            val wmX = inputData.getFloat(KEY_WM_X, -1f)
            val wmY = inputData.getFloat(KEY_WM_Y, -1f)

            Log.i(TAG, "start sendWithMedia=$sendWithMedia src=$srcChatId/$srcMsgId target=$targetUsername wm=${watermarkUriStr.isNotBlank()} blur=${blurRectsStr.isNotBlank()}")

            val chatId = resolvePublicChatId(targetUsername) ?: return Result.failure()

            // Caption must be inside the media message (not a separate send).
            val captionFmt = TdApi.FormattedText(captionText, null)

            if (!sendWithMedia) {
                return if (sendMessage(chatId, makeTextContent(captionText))) Result.success() else Result.failure()
            }

            val msg = fetchMessageSync(srcChatId, srcMsgId) ?: return Result.failure()
            val media = extractMedia(msg) ?: run {
                return if (sendMessage(chatId, makeTextContent(captionText))) Result.success() else Result.failure()
            }

            val inFile = ensureFileDownloaded(media.fileId) ?: return Result.failure()
            val mime = if (media.mime.isNotBlank()) media.mime else mimeHint

            val wmFile = resolveToLocalFileCompat(watermarkUriStr)
            val rects = parseRectsNormalized(blurRectsStr)

            val needEdits = (wmFile != null) || rects.isNotEmpty()
            val finalFile = if (needEdits) {
                val out = File(applicationContext.cacheDir, "edited_${System.currentTimeMillis()}${guessExt(inFile, mime, media.type)}")
                val ok = applyEditsFfmpeg(inFile, out, media.type, mime, wmFile, wmX, wmY, rects)
                if (ok) out else inFile
            } else inFile

            val content = buildMediaContent(finalFile, mime, media.type, captionFmt)
            val okSend = sendMessage(chatId, content)

            // cleanup only our cache output
            if (finalFile.absolutePath.startsWith(applicationContext.cacheDir.absolutePath)) runCatching { finalFile.delete() }
            if (wmFile != null && wmFile.absolutePath.startsWith(applicationContext.cacheDir.absolutePath)) runCatching { wmFile.delete() }

            return if (okSend) Result.success() else Result.failure()
        } catch (t: Throwable) {
            Log.e(TAG, "crash", t)
            return Result.failure()
        }
    }

    private fun makeTextContent(text: String): TdApi.InputMessageText {
        val ft = TdApi.FormattedText(text, null)
        val lp = TdApi.LinkPreviewOptions()
        lp.isDisabled = true
        return TdApi.InputMessageText().apply {
            this.text = ft
            this.linkPreviewOptions = lp
            this.clearDraft = false
        }
    }

    private fun extractMedia(m: TdApi.Message): MediaInfo? {
        val c = m.content ?: return null
        return when (c) {
            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes ?: emptyArray()
                val best = sizes.maxByOrNull { it.width * it.height } ?: sizes.lastOrNull()
                val fid = best?.photo?.id ?: return null
                MediaInfo(fid, "image/jpeg", MediaType.PHOTO)
            }
            is TdApi.MessageVideo -> {
                val v = c.video ?: return null
                MediaInfo(v.video?.id ?: return null, v.mimeType ?: "video/mp4", MediaType.VIDEO)
            }
            is TdApi.MessageAnimation -> {
                val a = c.animation ?: return null
                MediaInfo(a.animation?.id ?: return null, a.mimeType ?: "video/mp4", MediaType.ANIMATION)
            }
            is TdApi.MessageDocument -> {
                val d = c.document ?: return null
                MediaInfo(d.document?.id ?: return null, d.mimeType ?: "application/octet-stream", MediaType.DOCUMENT)
            }
            else -> null
        }
    }

    private fun buildMediaContent(file: File, mime: String, type: MediaType, caption: TdApi.FormattedText): TdApi.InputMessageContent {
        val inputFile = TdApi.InputFileLocal(file.absolutePath)
        return when {
            type == MediaType.PHOTO || (mime.startsWith("image/") && !mime.contains("gif")) -> TdApi.InputMessagePhoto().apply {
                photo = inputFile; this.caption = caption; addedStickerFileIds = intArrayOf(); width = 0; height = 0; hasSpoiler = false
            }
            type == MediaType.ANIMATION || mime.contains("gif") -> TdApi.InputMessageAnimation().apply {
                animation = inputFile; this.caption = caption; addedStickerFileIds = intArrayOf(); width = 0; height = 0; duration = 0; hasSpoiler = false
            }
            type == MediaType.VIDEO || mime.startsWith("video/") -> TdApi.InputMessageVideo().apply {
                video = inputFile; this.caption = caption; addedStickerFileIds = intArrayOf(); width = 0; height = 0; duration = 0; supportsStreaming = true; hasSpoiler = false
            }
            else -> TdApi.InputMessageDocument().apply {
                document = inputFile; this.caption = caption
            }
        }
    }

    private fun fetchMessageSync(chatId: Long, msgId: Long): TdApi.Message? {
        if (chatId == 0L || msgId == 0L) return null
        val latch = CountDownLatch(1)
        var msg: TdApi.Message? = null
        TdLibManager.send(TdApi.GetMessage(chatId, msgId)) { obj -> if (obj is TdApi.Message) msg = obj; latch.countDown() }
        latch.await(20, TimeUnit.SECONDS)
        return msg
    }

    private fun ensureFileDownloaded(fileId: Int, timeoutSec: Int = 120): File? {
        TdLibManager.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { }
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            var f: TdApi.File? = null
            TdLibManager.send(TdApi.GetFile(fileId)) { obj -> if (obj is TdApi.File) f = obj; latch.countDown() }
            latch.await(10, TimeUnit.SECONDS)
            val path = f?.local?.path
            val done = f?.local?.isDownloadingCompleted ?: false
            if (!path.isNullOrBlank() && done) {
                val ff = File(path)
                if (ff.exists() && ff.length() > 0) return ff
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun resolvePublicChatId(username: String): Long? {
        val u = username.trim().removePrefix("@")
        if (u.isBlank()) return null
        val latch = CountDownLatch(1)
        var cid: Long? = null
        TdLibManager.send(TdApi.SearchPublicChat(u)) { obj -> if (obj is TdApi.Chat) cid = obj.id; latch.countDown() }
        latch.await(20, TimeUnit.SECONDS)
        return cid
    }

    private fun sendMessage(chatId: Long, content: TdApi.InputMessageContent): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        TdLibManager.send(TdApi.SendMessage(chatId, null, null, null, null, content)) { obj ->
            ok = obj is TdApi.Message
            if (obj is TdApi.Error) Log.e(TAG, "TDLib error ${obj.code}: ${obj.message}")
            latch.countDown()
        }
        latch.await(40, TimeUnit.SECONDS)
        return ok
    }

    // ----------------- FFmpeg edits -----------------

    private data class RectN(val l: Float, val t: Float, val r: Float, val b: Float)

    private fun parseRectsNormalized(s: String): List<RectN> {
        if (s.isBlank()) return emptyList()
        return s.split(";").mapNotNull { p ->
            val nums = p.trim().split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (nums.size != 4) return@mapNotNull null
            val (l, t, r, b) = nums
            val ll = l.coerceIn(0f, 1f); val tt = t.coerceIn(0f, 1f); val rr = r.coerceIn(0f, 1f); val bb = b.coerceIn(0f, 1f)
            if (rr <= ll || bb <= tt) null else RectN(ll, tt, rr, bb)
        }
    }
    private fun applyEditsFfmpeg(
        input: File,
        output: File,
        type: MediaType,
        mime: String,
        wmFile: File?,
        wmX: Float,
        wmY: Float,
        rects: List<RectN>
    ): Boolean {
        val (w, h) = getMediaSize(input, type, mime)
        val delogoChain = rects.joinToString(",") { r ->
            val x = (r.l * w).toInt().coerceAtLeast(0)
            val y = (r.t * h).toInt().coerceAtLeast(0)
            val rw = ((r.r - r.l) * w).toInt().coerceAtLeast(1)
            val rh = ((r.b - r.t) * h).toInt().coerceAtLeast(1)
            "delogo=x=$x:y=$y:w=$rw:h=$rh:show=0"
        }

        val hasWm = wmFile != null && wmFile.exists() && wmFile.length() > 0
        val safeX = if (wmX in 0f..1f) wmX else 0.95f
        val safeY = if (wmY in 0f..1f) wmY else 0.95f

        val base = buildString {
            append("[0:v]format=rgba")
            if (delogoChain.isNotBlank()) append(",").append(delogoChain)
            append("[v0];")
        }

        val fc = if (hasWm) {
            base + "[1:v]format=rgba,scale='min(iw,main_w*0.25)':-1[wm];" +
                "[v0][wm]overlay=x=(main_w-overlay_w)*$safeX:y=(main_h-overlay_h)*$safeY[vout]"
        } else {
            base + "[v0]copy[vout]"
        }

        val isImage = (type == MediaType.PHOTO) || (mime.startsWith("image/") && !mime.contains("gif"))

        val cmd = buildString {
            append("ffmpeg -y -i ").append(q(input.absolutePath)).append(" ")
            if (hasWm) append("-i ").append(q(wmFile!!.absolutePath)).append(" ")
            append("-filter_complex ").append(q(fc)).append(" -map ").append(q("[vout]")).append(" ")
            if (isImage) {
                append("-q:v 3 ")
            } else {
                append("-map 0:a? -c:v libx264 -crf 28 -preset veryfast -c:a aac -b:a 128k -movflags +faststart ")
            }
            append(q(output.absolutePath))
        }

        Log.i(TAG, "ffmpeg: $cmd")
        val session = FFmpegKit.execute(cmd)
        val ok = ReturnCode.isSuccess(session.returnCode) && output.exists() && output.length() > 0
        if (!ok) Log.e(TAG, "ffmpeg failed rc=${session.returnCode} state=${session.state} fail=${session.failStackTrace}")
        return ok
    }

    private fun getMediaSize(file: File, type: MediaType, mime: String): Pair<Int, Int> {
        return try {
            if (type == MediaType.PHOTO || (mime.startsWith("image/") && !mime.contains("gif"))) {
                val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opt)
                Pair(opt.outWidth.coerceAtLeast(1), opt.outHeight.coerceAtLeast(1))
            } else {
                val r = MediaMetadataRetriever()
                r.setDataSource(file.absolutePath)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
                r.release()
                Pair(w.coerceAtLeast(1), h.coerceAtLeast(1))
            }
        } catch (_: Throwable) {
            Pair(1280, 720)
        }
    }

    private fun guessExt(file: File, mime: String, type: MediaType): String {
        val n = file.name.lowercase()
        return when {
            type == MediaType.PHOTO -> ".jpg"
            type == MediaType.VIDEO || type == MediaType.ANIMATION -> ".mp4"
            n.endsWith(".png") -> ".png"
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> ".jpg"
            n.endsWith(".mp4") -> ".mp4"
            mime.contains("png") -> ".png"
            mime.contains("jpeg") || mime.contains("jpg") -> ".jpg"
            mime.contains("mp4") || mime.startsWith("video/") -> ".mp4"
            else -> ".bin"
        }
    }

    private fun resolveToLocalFileCompat(uriStr: String): File? {
        if (uriStr.isBlank()) return null
        return runCatching {
            val uri = Uri.parse(uriStr)
            when (uri.scheme) {
                "file", null -> File(uri.path ?: uriStr).takeIf { it.exists() }
                "content" -> copyContentUriToCache(uri)
                else -> File(uriStr).takeIf { it.exists() }
            }
        }.getOrNull()
    }

    private fun copyContentUriToCache(uri: Uri): File? {
        val cr = applicationContext.contentResolver
        val name = queryDisplayName(cr, uri) ?: "wm_${System.currentTimeMillis()}.png"
        val out = File(applicationContext.cacheDir, name)
        cr.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { os -> input.copyTo(os) }
        } ?: return null
        return out.takeIf { it.exists() && it.length() > 0 }
    }

    private fun queryDisplayName(cr: ContentResolver, uri: Uri): String? {
        var c: Cursor? = null
        return try {
            c = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (c != null && c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        } finally {
            c?.close()
        }
    }

    private fun q(s: String): String = "\"" + s.replace("\"", "\\\"") + "\""
}
