package com.test.app.democode.architecture

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.test.app.democode.BackButton
import com.test.app.democode.LogUtils

class ArchActivity : AppCompatActivity() {
    private val viewModel: ArchViewModel by viewModels {
        ArchViewModel.Factory(initialCount = 2)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ComposeView(this).apply {
                setContent {
                    ArchRoute(viewModel)
                }
            }
        )
    }
}

@Composable
private fun ArchRoute(viewModel: ArchViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ArchEffect.ShowMessage -> {
                    LogUtils.d("ArchActivity","${effect.message}")
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    ArchScreen(
        state = state,
        onIntent = viewModel::dispatch
    )
}

@Composable
private fun ArchScreen(
    state: ArchState,
    onIntent: (ArchIntent) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        BackButton()
        Spacer(modifier = Modifier.height(16.dp))
        BasicText(
            text = "MVI Counter Demo",
            style = TextStyle(
                color = Color.Black,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        BasicText(
            text = "UI → Intent → Reducer → State → UI",
            style = TextStyle(
                color = Color(0xFF666666),
                fontSize = 14.sp
            )
        )
        Spacer(modifier = Modifier.height(32.dp))
        BasicText(
            text = state.count.toString(),
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(
                color = Color(0xFF1565C0),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        BasicText(
            text = state.lastAction,
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(
                color = Color(0xFF666666),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        )
        Spacer(modifier = Modifier.height(32.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MviButton(
                title = "-1",
                modifier = Modifier.weight(1f),
                onClick = { onIntent(ArchIntent.Decrement) }
            )
            MviButton(
                title = "重置",
                modifier = Modifier.weight(1f),
                onClick = { onIntent(ArchIntent.Reset) }
            )
            MviButton(
                title = "+1",
                modifier = Modifier.weight(1f),
                onClick = { onIntent(ArchIntent.Increment) }
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        BasicText(
            text = "State 是页面唯一数据源；Toast 通过 Effect 单独分发，避免随配置变化重复消费。",
            style = TextStyle(
                color = Color(0xFF333333),
                fontSize = 14.sp,
                lineHeight = 22.sp
            )
        )
    }
}

@Composable
private fun MviButton(
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    BasicText(
        text = title,
        modifier = modifier
            .border(width = 1.dp, color = Color(0xFF1565C0), shape = shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        style = TextStyle(
            color = Color(0xFF1565C0),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    )
}
