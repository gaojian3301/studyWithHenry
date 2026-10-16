package com.test.app.democode

object LogUtils {
    const val PREFIX = "DemoCode"
    fun d(tag: String, message: String) {
        android.util.Log.i("$PREFIX-$tag", message)
    }

    fun i(tag: String, message: String) {
        android.util.Log.i("$PREFIX-$tag", message)
    }

    fun w(tag: String, message: String) {
        android.util.Log.w("$PREFIX-$tag", message)
    }
}