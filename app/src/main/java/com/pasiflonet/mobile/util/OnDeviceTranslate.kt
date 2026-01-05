package com.pasiflonet.mobile.util

object OnDeviceTranslate {
    /**
     * כרגע "אופליין" בלי תלות חיצונית: מחזיר את הטקסט כמו שהוא כדי לא לשבור.
     * אחר כך אפשר לשדרג ל-MLKit Translate (חינמי) עם תלות ב-gradle.
     */
    fun translateToHebrewBlocking(
        ctx: android.content.Context,
        text: String,
        timeoutMs: Long = 20000L
    ): String? {
        return text
    }
}
