package com.test.app.democode.binder

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import com.test.app.democode.LogUtils

class AidlRemoteService : Service() {
    private val callbacks = RemoteCallbackList<IAidlCallback>()

    private val binder = object : IDemoAidlInterface.Stub() {
        override fun echo(message: String?): String {
            return "remote echo: $message"
        }

        override fun sendOnewayMessage(message: String?) {
            LogUtils.i("AidlRemoteService", "oneway message: $message")
            notifyClient("server received oneway message: $message")
        }

        override fun setInput(data: Bundle?) {
            LogUtils.i("AidlRemoteService", "in data: ${data?.getString(KEY_MESSAGE)}")
        }

        override fun fillOutput(data: Bundle?) {
            data?.putString(KEY_MESSAGE, "output from remote service")
        }

        override fun updateInOut(data: Bundle?) {
            val oldMessage = data?.getString(KEY_MESSAGE)
            data?.putString(KEY_MESSAGE, "inout updated by remote service, old value: $oldMessage")
        }

        override fun registerCallback(callback: IAidlCallback?) {
            callback ?: return
            callbacks.register(callback)
            callback.onResult("callback registered")
        }

        override fun unregisterCallback(callback: IAidlCallback?) {
            callback ?: return
            callbacks.unregister(callback)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        callbacks.kill()
        super.onDestroy()
    }

    private fun notifyClient(message: String) {
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) {
                callbacks.getBroadcastItem(index).onResult(message)
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private companion object {
        const val KEY_MESSAGE = "message"
    }
}
