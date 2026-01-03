package com.pasiflonet.mobile.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.pasiflonet.mobile.td.TdLibManager
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        const val KEY_SRC_CHAT_ID = "src_chat_id"
        const val KEY_SRC_MESSAGE_ID = "src_message_id"
        const val KEY_TARGET_USERNAME = "target_username"
        const val KEY_TEXT = "text"
        const val KEY_SEND_WITH_MEDIA = "send_with_media"
        const val KEY_WATERMARK_URI = "watermark_uri"
    }

    override fun doWork(): Result {
        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()

        val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
        val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)
        val targetUsernameRaw = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim()
        val textOverride = inputData.getString(KEY_TEXT).orEmpty()
        val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, true)

        if (srcChatId == 0L || srcMsgId == 0L || targetUsernameRaw.isBlank()) return Result.failure()

        val username = targetUsernameRaw.removePrefix("@").trim()
        if (username.isBlank()) return Result.failure()

        // 1) Resolve @username -> chatId
        val latch1 = CountDownLatch(1)
        var targetChatId: Long = 0L
        TdLibManager.send(TdApi.SearchPublicChat(username)) { obj ->
            if (obj.constructor == TdApi.Chat.CONSTRUCTOR) targetChatId = (obj as TdApi.Chat).id
            latch1.countDown()
        }
        if (!latch1.await(25, TimeUnit.SECONDS) || targetChatId == 0L) return Result.failure()

        // 2) Load source message (for media)
        var srcMsg: TdApi.Message? = null
        val latchMsg = CountDownLatch(1)
        TdLibManager.send(TdApi.GetMessage(srcChatId, srcMsgId)) { obj ->
            if (obj.constructor == TdApi.Message.CONSTRUCTOR) srcMsg = obj as TdApi.Message
            latchMsg.countDown()
        }
        if (!latchMsg.await(25, TimeUnit.SECONDS) || srcMsg == null) return Result.failure()

        val captionText = textOverride.ifBlank {
            // אם לא הוזן טקסט חדש, ננסה לקחת מההודעה המקורית אם זו הודעת טקסט
            val c = srcMsg!!.content
            if (c != null && c.constructor == TdApi.MessageText.CONSTRUCTOR) {
                val mt = c as TdApi.MessageText
                mt.text?.text.orEmpty()
            } else ""
        }

        fun formatted(t: String) = TdApi.FormattedText(t, null)

        // 3) Build content
        val content: TdApi.InputMessageContent = if (sendWithMedia) {
            when (srcMsg!!.content.constructor) {
                TdApi.MessagePhoto.CONSTRUCTOR -> {
                    val mp = srcMsg!!.content as TdApi.MessagePhoto
                    val sizes = mp.photo?.sizes
                    val last = sizes?.lastOrNull()
                    val fileId = last?.photo?.id ?: 0
                    if (fileId != 0) {
                        TdApi.InputMessagePhoto().apply {
                            photo = TdApi.InputFileId(fileId)
                            thumbnail = null
                            addedStickerFileIds = null
                            width = last?.width ?: 0
                            height = last?.height ?: 0
                            caption = formatted(captionText)
                            selfDestructType = null
                            hasSpoiler = false
                        }
                    } else {
                        TdApi.InputMessageText().apply {
                            text = formatted(captionText)
                            linkPreviewOptions = null
                            clearDraft = false
                        }
                    }
                }

                TdApi.MessageVideo.CONSTRUCTOR -> {
                    val mv = srcMsg!!.content as TdApi.MessageVideo
                    val v = mv.video
                    val fileId = v?.video?.id ?: 0
                    if (fileId != 0) {
                        TdApi.InputMessageVideo().apply {
                            video = TdApi.InputFileId(fileId)
                            thumbnail = null
                            addedStickerFileIds = null
                            duration = v?.duration ?: 0
                            width = v?.width ?: 0
                            height = v?.height ?: 0
                            supportsStreaming = true
                            caption = formatted(captionText)
                            selfDestructType = null
                            hasSpoiler = false
                        }
                    } else {
                        TdApi.InputMessageText().apply {
                            text = formatted(captionText)
                            linkPreviewOptions = null
                            clearDraft = false
                        }
                    }
                }

                TdApi.MessageDocument.CONSTRUCTOR -> {
                    val md = srcMsg!!.content as TdApi.MessageDocument
                    val d = md.document
                    val fileId = d?.document?.id ?: 0
                    if (fileId != 0) {
                        TdApi.InputMessageDocument().apply {
                            document = TdApi.InputFileId(fileId)
                            thumbnail = null
                            disableContentTypeDetection = false
                            caption = formatted(captionText)
                        }
                    } else {
                        TdApi.InputMessageText().apply {
                            text = formatted(captionText)
                            linkPreviewOptions = null
                            clearDraft = false
                        }
                    }
                }

                else -> {
                    TdApi.InputMessageText().apply {
                        text = formatted(captionText)
                        linkPreviewOptions = null
                        clearDraft = false
                    }
                }
            }
        } else {
            TdApi.InputMessageText().apply {
                text = formatted(captionText)
                linkPreviewOptions = null
                clearDraft = false
            }
        }

        // 4) Send
        val latchSend = CountDownLatch(1)
        var ok = false
        val fn = TdApi.SendMessage(targetChatId, null, null, null, null, content)

        TdLibManager.send(fn) { obj ->
            ok = (obj.constructor == TdApi.Message.CONSTRUCTOR)
            latchSend.countDown()
        }

        if (!latchSend.await(35, TimeUnit.SECONDS)) return Result.failure()
        return if (ok) Result.success() else Result.failure()
    }
}
