package com.pasiflonet.mobile.worker

import android.content.Context
import android.net.Uri
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
import kotlin.math.max
import kotlin.math.min

class SendWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

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

    private enum class Kind { PHOTO, VIDEO, ANIMATION, DOCUMENT }

    override fun doWork(): Result {
        try {
            TdLibManager.init(applicationContext)
            TdLibManager.ensureClient()

            val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
            val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)
            val targetUsernameRaw = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim()
            val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, true)

            val captionText = inputData.getString(KEY_TEXT).orEmpty()
            val captionFmt = TdApi.FormattedText(captionText, null)
            val lpOpts = TdApi.LinkPreviewOptions() // חשוב: לא boolean

            val blurRectsStr = inputData.getString(KEY_BLUR_RECTS).orEmpty().trim()
            val watermarkUriStr = inputData.getString(KEY_WATERMARK_URI).orEmpty().trim()
            val wmX = inputData.getFloat(KEY_WM_X, -1f)
            val wmY = inputData.getFloat(KEY_WM_Y, -1f)

            val hasBlur = blurRectsStr.isNotBlank()
            val hasWm = watermarkUriStr.isNotBlank() && wmX >= 0f && wmY >= 0f
            val needsEdits = hasBlur || hasWm

            logI("start: target=$targetUsernameRaw sendWithMedia=$sendWithMedia srcChat=$srcChatId srcMsg=$srcMsgId textLen=${captionText.length} needsEdits=$needsEdits")

            val targetChatId = resolvePublicChatId(targetUsernameRaw)
                ?: return fail("Cannot resolve target chat id for $targetUsernameRaw")

            if (!sendWithMedia) {
                val content = TdApi.InputMessageText(captionFmt, lpOpts, false)
                if (!sendContent(targetChatId, content)) return fail("send text failed")
                logI("Sent TEXT len=${captionText.length}")
                return Result.success()
            }

            // === fetch source message and extract media file id ===
            val msgObj = tdSendSync(TdApi.GetMessage(srcChatId, srcMsgId), 20) ?: return fail("GetMessage timeout")
            val msg = msgObj as? TdApi.Message ?: return fail("GetMessage returned ${msgObj::class.java.simpleName}")
            val (kind, fileId) = extractKindAndFileId(msg) ?: return fail("Source message has no supported media")

            val srcFile = ensureFileDownloaded(fileId) ?: return fail("DownloadFile/GetFile failed for fileId=$fileId")
            val tmpDir = File(applicationContext.cacheDir, "pasiflonet_tmp").apply { mkdirs() }

            val inFile = File(tmpDir, "in_${System.currentTimeMillis()}_${File(srcFile).name}")
            copyFile(File(srcFile), inFile)

            val wmFile = if (hasWm) resolveWatermarkToFile(watermarkUriStr, tmpDir) else null
            if (hasWm && wmFile == null) return fail("watermark file cannot be read (permission/uri?)")

            val rects = if (hasBlur) parseRects(blurRectsStr) else emptyList()

            val finalFile = if (needsEdits) {
                val outExt = if (kind == Kind.PHOTO) "jpg" else "mp4"
                val outFile = File(tmpDir, "out_${System.currentTimeMillis()}.$outExt")
                val cmd = buildFfmpegCmd(kind, inFile, wmFile, rects, wmX, wmY, outFile)
                logI("FFmpeg cmd: $cmd")
                val session = FFmpegKit.execute(cmd)
                val rc = session.returnCode
                if (!ReturnCode.isSuccess(rc) || !outFile.exists() || outFile.length() == 0L) {
                    val err = session.allLogsAsString
                    logE("FFmpeg failed rc=${rc?.value}: $err")
                    // דרישה שלך: אם ביקשת עריכות – לא שולחים בלי עריכה
                    return fail("FFmpeg failed -> not sending unedited media")
                }
                outFile
            } else {
                inFile
            }

            val inputFile = TdApi.InputFileLocal(finalFile.absolutePath)
            val content: TdApi.InputMessageContent = when (kind) {
                Kind.PHOTO -> TdApi.InputMessagePhoto().apply {
                    photo = inputFile
                    caption = captionFmt
                }
                Kind.VIDEO -> TdApi.InputMessageVideo().apply {
                    video = inputFile
                    supportsStreaming = true
                    caption = captionFmt
                }
                Kind.ANIMATION -> TdApi.InputMessageAnimation().apply {
                    animation = inputFile
                    caption = captionFmt
                }
                Kind.DOCUMENT -> TdApi.InputMessageDocument().apply {
                    document = inputFile
                    caption = captionFmt
                }
            }

            if (!sendContent(targetChatId, content)) return fail("send media failed")

            logI("Sent MEDIA kind=$kind file=${finalFile.name} captionLen=${captionText.length}")

            // cleanup
            val (deleted, freedBytes) = cleanTmp(tmpDir)
            logI("TempCleaner: deleted=$deleted freed=${"%.1f".format(freedBytes / 1024.0 / 1024.0)} MB")

            return Result.success()
        } catch (t: Throwable) {
            logE("Crash: ${t.message}", t)
            return Result.failure()
        }
    }

    private fun extractKindAndFileId(msg: TdApi.Message): Pair<Kind, Int>? {
        val c = msg.content ?: return null
        return when (c) {
            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes ?: emptyArray()
                val best = sizes.maxByOrNull { it.width * it.height } ?: return null
                Kind.PHOTO to best.photo.id
            }
            is TdApi.MessageVideo -> Kind.VIDEO to (c.video?.video?.id ?: return null)
            is TdApi.MessageAnimation -> Kind.ANIMATION to (c.animation?.animation?.id ?: return null)
            is TdApi.MessageDocument -> Kind.DOCUMENT to (c.document?.document?.id ?: return null)
            else -> null
        }
    }

    private fun buildFfmpegCmd(
        kind: Kind,
        inFile: File,
        wmFile: File?,
        rects: List<RectN>,
        wmX: Float,
        wmY: Float,
        outFile: File
    ): String {
        fun q(s: String) = "\"" + s.replace("\"", "\\\"") + "\""

        val hasWm = wmFile != null
        val hasBlur = rects.isNotEmpty()

        val filters = StringBuilder()
        // base
        filters.append("[0:v]format=rgba[v0];")
        var cur = "v0"

        if (hasBlur) {
            rects.forEachIndexed { i, r ->
                val main = "vmain$i"
                val tmp = "vtmp$i"
                val blur = "blur$i"
                val vnext = "v${i+1}"

                val x = "${r.l}*iw"
                val y = "${r.t}*ih"
                val w = "(${r.r}-${r.l})*iw"
                val h = "(${r.b}-${r.t})*ih"

                // crop+blur uses iw/ih (זה תקין), overlay must use main_w/main_h (לא iw/ih!)
                filters.append("[$cur]split=2[$main][$tmp];")
                filters.append("[$tmp]crop=w='$w':h='$h':x='$x':y='$y',boxblur=10:1[$blur];")
                filters.append("[$main][$blur]overlay=x='${r.l}*main_w':y='${r.t}*main_h':format=auto[$vnext];")

                cur = vnext
            }
        }

        if (hasWm) {
            filters.append("[1:v]format=rgba[wm];")
            filters.append("[$cur][wm]overlay=x='${wmX}*(main_w-overlay_w)':y='${wmY}*(main_h-overlay_h)':format=auto[vout];")
        } else {
            filters.append("[$cur]null[vout];")
        }

        val base = StringBuilder()
        base.append("-y -i ${q(inFile.absolutePath)} ")
        if (hasWm) base.append("-i ${q(wmFile!!.absolutePath)} ")

        base.append("-filter_complex ${q(filters.toString())} ")
        base.append("-map \"[vout]\" ")

        if (kind == Kind.PHOTO) {
            // תמונה אחת
            base.append("-frames:v 1 -q:v 2 ${q(outFile.absolutePath)}")
        } else {
            // וידאו עם אודיו אם קיים
            base.append("-map 0:a? -c:a copy -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -movflags +faststart ${q(outFile.absolutePath)}")
        }

        return base.toString()
    }

    private fun resolvePublicChatId(usernameRaw: String): Long? {
        val u = usernameRaw.removePrefix("@").trim()
        if (u.isBlank()) return null
        val obj = tdSendSync(TdApi.SearchPublicChat(u), 20) ?: return null
        val chat = obj as? TdApi.Chat ?: return null
        return chat.id
    }

    private fun sendContent(chatId: Long, content: TdApi.InputMessageContent): Boolean {
        val latch = CountDownLatch(1)
        var ok = false

        val req = TdApi.SendMessage().apply {
            this.chatId = chatId
            this.inputMessageContent = content
            // לא נוגעים ב-messageTopic/messageThreadId כדי לא להיתקע על טיפוסים
        }

        TdLibManager.send(req) { obj ->
            ok = obj is TdApi.Message || obj is TdApi.Ok
            latch.countDown()
        }

        latch.await(25, TimeUnit.SECONDS)
        return ok
    }

    private fun tdSendSync(fn: TdApi.Function<out TdApi.Object>, timeoutSec: Int): TdApi.Object? {
        val latch = CountDownLatch(1)
        var res: TdApi.Object? = null
        TdLibManager.send(fn) { obj ->
            res = obj
            latch.countDown()
        }
        if (!latch.await(timeoutSec.toLong(), TimeUnit.SECONDS)) return null
        return res
    }

    private fun ensureFileDownloaded(fileId: Int, timeoutSec: Int = 90): String? {
        TdLibManager.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { }
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L

        while (System.currentTimeMillis() < deadline) {
            val obj = tdSendSync(TdApi.GetFile(fileId), 10) ?: continue
            val f = obj as? TdApi.File ?: continue
            val done = f.local?.isDownloadingCompleted ?: false
            val path = f.local?.path
            if (done && !path.isNullOrBlank()) {
                val ff = File(path)
                if (ff.exists() && ff.length() > 0) return path
            }
            Thread.sleep(250)
        }
        return null
    }

    private fun resolveWatermarkToFile(uriStr: String, tmpDir: File): File? {
        return try {
            val uri = Uri.parse(uriStr)
            val out = File(tmpDir, "wm_${System.currentTimeMillis()}.png")
            applicationContext.contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(out).use { outp -> inp.copyTo(outp) }
            } ?: return null
            if (!out.exists() || out.length() == 0L) null else out
        } catch (t: Throwable) {
            logE("resolveWatermarkToFile failed: ${t.message}")
            null
        }
    }

    private data class RectN(val l: Float, val t: Float, val r: Float, val b: Float)

    private fun parseRects(s: String): List<RectN> {
        // פורמט: "l,t,r,b;l,t,r,b"
        val out = mutableListOf<RectN>()
        s.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { part ->
            val nums = part.split(",").map { it.trim() }
            if (nums.size == 4) {
                val l = nums[0].toFloatOrNull()
                val t = nums[1].toFloatOrNull()
                val r = nums[2].toFloatOrNull()
                val b = nums[3].toFloatOrNull()
                if (l != null && t != null && r != null && b != null) {
                    // clamp 0..1 and ensure min<max
                    val ll = clamp01(min(l, r))
                    val rr = clamp01(max(l, r))
                    val tt = clamp01(min(t, b))
                    val bb = clamp01(max(t, b))
                    if (rr - ll > 0.001f && bb - tt > 0.001f) out += RectN(ll, tt, rr, bb)
                }
            }
        }
        return out
    }

    private fun clamp01(v: Float) = min(1f, max(0f, v))

    private fun copyFile(src: File, dst: File) {
        dst.outputStream().use { out ->
            src.inputStream().use { inp ->
                inp.copyTo(out)
            }
        }
    }

    private fun cleanTmp(dir: File): Pair<Int, Long> {
        var deleted = 0
        var freed = 0L
        val files = dir.listFiles() ?: return 0 to 0L
        for (f in files) {
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                deleted++
                freed += len
            }
        }
        return deleted to freed
    }

    private fun fail(msg: String): Result {
        logE(msg)
        return Result.failure()
    }

    private fun logI(msg: String) = Log.i(TAG, msg)
    private fun logE(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)
}
