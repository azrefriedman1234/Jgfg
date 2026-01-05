package com.pasiflonet.mobile.util

import android.content.Context

/**
 * כרגע: כדי לסיים פרויקט בלי הפתעות קומפילציה/תלויות,
 * ברירת מחדל מחזירה את הטקסט כמו שהוא.
 *
 * אחרי שהכל ירוק, נוסיף MLKit offline בצורה מסודרת (עם תלות Gradle אחת).
 */
object OnDeviceTranslate {

    @JvmStatic
    fun translateToHebrewBlocking(ctx: Context, text: String, timeoutMs: Long = 20000L): String? {
        // TODO (אחרי שהכל ירוק): MLKit on-device translate
        return text
    }
}
