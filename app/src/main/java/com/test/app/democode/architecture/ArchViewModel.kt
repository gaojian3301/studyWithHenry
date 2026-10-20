package com.test.app.democode.architecture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class ArchViewModel(
    initialCount: Int
) : ViewModel() {
    init {
        require(initialCount in ArchReducer.MIN_COUNT..ArchReducer.MAX_COUNT) {
            "initialCount must be between ${ArchReducer.MIN_COUNT} and ${ArchReducer.MAX_COUNT}"
        }
    }

    private val intents = Channel<ArchIntent>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(
        ArchState(
            count = initialCount,
            lastAction = "由 Factory 注入初始值"
        )
    )
    private val _effects = Channel<ArchEffect>(Channel.BUFFERED)

    val state: StateFlow<ArchState> = _state.asStateFlow()
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            for (intent in intents) {
                val result = ArchReducer.reduce(_state.value, intent)
                _state.value = result.state
                result.effect?.let { _effects.send(it) }
            }
        }
    }

    fun dispatch(intent: ArchIntent) {
        intents.trySend(intent).getOrThrow()
    }

    class Factory(
        private val initialCount: Int
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ArchViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return ArchViewModel(initialCount) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
