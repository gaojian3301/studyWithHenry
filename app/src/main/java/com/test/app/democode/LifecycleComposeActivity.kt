package com.test.app.democode

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class LifecycleComposeActivity : AppCompatActivity() {
    private val firstViewModel: FirstViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(LifecycleObserver())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    firstViewModel.coldFlow.collect { value ->
                        LogUtils.i("LifecycleComposeActivity", "coldFlow value: $value")
                    }
                }

                launch {
                    firstViewModel.stateFlow.collect { value ->
                        LogUtils.i("LifecycleComposeActivity", "stateFlow value: $value")
                    }
                }

                launch {
                    firstViewModel.sharedFlow.collect { value ->
                        LogUtils.i("LifecycleComposeActivity", "sharedFlow value: $value")
                    }
                }

                launch {
                    firstViewModel.callbackFlow.collect { value ->
                        LogUtils.i("LifecycleComposeActivity", "callbackFlow value: $value")
                    }
                }

                launch {
                    firstViewModel.channelFlow.collect { value ->
                        LogUtils.i("LifecycleComposeActivity", "channelFlow value: $value")
                    }
                }
            }
        }
        setContentView(
            ComposeView(this).apply {
                setContent {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White)
                            .padding(horizontal = 16.dp, vertical = 24.dp)
                    ) {
                        BackButton()
                        PlayerScreen(firstViewModel)
                    }
                }
            }
        )
    }
}

@Composable
fun PlayerScreen(firstViewModel: FirstViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val stateValue by firstViewModel.stateFlow.collectAsState()
    val lifecycleStateValue by firstViewModel.stateFlow.collectAsStateWithLifecycle()

    Spacer(modifier = Modifier.height(16.dp))
    BasicText(
        text = "collectAsState: $stateValue",
        style = TextStyle(
            color = Color.Black,
            fontSize = 16.sp
        )
    )
    Spacer(modifier = Modifier.height(8.dp))
    BasicText(
        text = "collectAsStateWithLifecycle: $lifecycleStateValue",
        style = TextStyle(
            color = Color.Black,
            fontSize = 16.sp
        )
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {}
                Lifecycle.Event.ON_PAUSE -> {}
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}