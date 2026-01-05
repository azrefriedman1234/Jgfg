package com.pasiflonet.mobile.util

object OnDeviceTranslate {
    /**
     * כרגע אופליין "בטוח" (לא מפיל) – מחזיר את הטקסט כמו שהוא.
     * אחר כך נחליף ל-MLKit Translate (חינמי) בצורה מסודרת.
     */
    fun translateToHebrewBlocking(
        ctx: android.content.Context,
        text: String,
        timeoutMs: Long = 20000L
    ): String? = text
}
