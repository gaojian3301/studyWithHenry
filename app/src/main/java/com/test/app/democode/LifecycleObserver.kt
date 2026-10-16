package com.test.app.democode

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class LifecycleObserver : DefaultLifecycleObserver {
    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        LogUtils.i("LifecycleObserver", "onCreate")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        LogUtils.i("LifecycleObserver", "onDestroy")
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        LogUtils.i("LifecycleObserver", "onPause")
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        LogUtils.d("LifecycleObserver", "onResume")
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        LogUtils.d("LifecycleObserver", "onStart")
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        LogUtils.d("LifecycleObserver", "onStop")
    }
}