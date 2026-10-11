package me.rerere.common.util

/** Service payloads, URLs and exception chains can contain credentials. Log only fixed events. */
@Suppress("UNUSED_PARAMETER")
object ServicePrivacyLog {
    fun v(tag: String, message: String, cause: Throwable? = null) = android.util.Log.v(tag, "Service event")
    fun d(tag: String, message: String, cause: Throwable? = null) = android.util.Log.d(tag, "Service event")
    fun i(tag: String, message: String, cause: Throwable? = null) = android.util.Log.i(tag, "Service event")
    fun w(tag: String, message: String, cause: Throwable? = null) = android.util.Log.w(tag, "Service warning")
    fun e(tag: String, message: String, cause: Throwable? = null) = android.util.Log.e(tag, "Service operation failed")
}
