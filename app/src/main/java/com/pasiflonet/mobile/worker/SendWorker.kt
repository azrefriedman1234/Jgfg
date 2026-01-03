package com.pasiflonet.mobile.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.pasiflonet.mobile.td.TdLibManager
import org.drinkless.tdlib.TdApi
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
        const val KEY_MEDIA_PATH = "media_path"
    }

    override fun doWork(): Result {
        TdLibManager.init(applicationContext)
        TdLibManager.ensureClient()

        val srcChatId = inputData.getLong(KEY_SRC_CHAT_ID, 0L)
        val srcMsgId = inputData.getLong(KEY_SRC_MESSAGE_ID, 0L)
        val targetUsernameRaw = inputData.getString(KEY_TARGET_USERNAME).orEmpty().trim()
        val text = inputData.getString(KEY_TEXT).orEmpty()

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

        // 2) Choose content: text or photo+caption
        val sendWithMedia = inputData.getBoolean(KEY_SEND_WITH_MEDIA, false)
        val mediaPath = inputData.getString(KEY_MEDIA_PATH).orEmpty()

        val content: TdApi.InputMessageContent = if (sendWithMedia && mediaPath.isNotBlank() && File(mediaPath).exists()) {
            val photo = TdApi.InputMessagePhoto().apply {
                this.photo = TdApi.InputFileLocal(mediaPath)
                this.addedStickerFileIds = intArrayOf()
                this.width = 0
                this.height = 0
                this.caption = TdApi.FormattedText(text, null)
                this.selfDestructType = null
                this.hasSpoiler = false
            }
            photo
        } else {
            TdApi.InputMessageText().apply {
                this.text = TdApi.FormattedText(text, null)
                this.linkPreviewOptions = null
                this.clearDraft = false
            }
        }

        val latch2 = CountDownLatch(1)
        var ok = false

        val fn = TdApi.SendMessage(targetChatId, null, null, null, null, content)

        TdLibManager.send(fn) { obj ->
            ok = (obj.constructor == TdApi.Message.CONSTRUCTOR)
            latch2.countDown()
        }

        if (!latch2.await(40, TimeUnit.SECONDS)) return Result.failure()
        return if (ok) Result.success() else Result.failure()
    }
}
