package com.test.app.democode.architecture

sealed interface ArchIntent {
    data object Increment : ArchIntent
    data object Decrement : ArchIntent
    data object Reset : ArchIntent
}

data class ArchState(
    val count: Int = 0,
    val lastAction: String = "等待用户操作"
)

sealed interface ArchEffect {
    data class ShowMessage(val message: String) : ArchEffect
}

internal data class ArchResult(
    val state: ArchState,
    val effect: ArchEffect? = null
)

internal object ArchReducer {
    const val MIN_COUNT = -5
    const val MAX_COUNT = 5

    fun reduce(state: ArchState, intent: ArchIntent): ArchResult {
        return when (intent) {
            ArchIntent.Increment -> {
                if (state.count == MAX_COUNT) {
                    ArchResult(state, ArchEffect.ShowMessage("已经达到最大值"))
                } else {
                    ArchResult(
                        state.copy(
                            count = state.count + 1,
                            lastAction = "收到 Increment Intent"
                        )
                    )
                }
            }

            ArchIntent.Decrement -> {
                if (state.count == MIN_COUNT) {
                    ArchResult(state, ArchEffect.ShowMessage("已经达到最小值"))
                } else {
                    ArchResult(
                        state.copy(
                            count = state.count - 1,
                            lastAction = "收到 Decrement Intent"
                        )
                    )
                }
            }

            ArchIntent.Reset -> {
                ArchResult(
                    state.copy(
                        count = 0,
                        lastAction = "收到 Reset Intent"
                    ),
                    ArchEffect.ShowMessage("计数器已重置")
                )
            }
        }
    }
}
