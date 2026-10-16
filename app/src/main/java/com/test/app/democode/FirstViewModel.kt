package com.test.app.democode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FirstViewModel : ViewModel() {
    private val callbackSource = DemoCallbackSource()

    val coldFlow: Flow<Int> = flow {
        LogUtils.i("FirstViewModel", "coldFlow start")
        var count = 0
        while (true) {
            emit(count++)
            delay(1000)
        }
    }

    private val _stateFlow = MutableStateFlow(0)
    val stateFlow: StateFlow<Int> = _stateFlow.asStateFlow()

    private val _sharedFlow = MutableSharedFlow<String>()
    val sharedFlow: SharedFlow<String> = _sharedFlow.asSharedFlow()

    private val channel = Channel<String>(Channel.BUFFERED)
    val channelFlow: Flow<String> = channel.receiveAsFlow()

    val callbackFlow: Flow<String> = callbackFlow {
        val listener = object : DemoCallbackListener {
            override fun onMessage(message: String) {
                trySend(message)
            }
        }

        callbackSource.addListener(listener)

        awaitClose {
            callbackSource.removeListener(listener)
        }
    }

    init {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                _stateFlow.update { it + 1 }
            }
        }

        viewModelScope.launch {
            var count = 0
            while (true) {
                delay(3000)
                _sharedFlow.emit("shared event ${count++}")
            }
        }

        viewModelScope.launch {
            var count = 0
            while (true) {
                delay(2000)
                callbackSource.dispatch("callback message ${count++}")
            }
        }

        viewModelScope.launch {
            var count = 0
            while (true) {
                delay(4000)
                channel.send("channel message ${count++}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        LogUtils.i("FirstViewModel", "onCleared")
    }
}

private interface DemoCallbackListener {
    fun onMessage(message: String)
}

private class DemoCallbackSource {
    private val listeners = mutableSetOf<DemoCallbackListener>()

    fun addListener(listener: DemoCallbackListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: DemoCallbackListener) {
        listeners.remove(listener)
    }

    fun dispatch(message: String) {
        listeners.forEach { listener ->
            listener.onMessage(message)
        }
    }
}