package com.test.app.democode

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.test.app.democode.architecture.ArchActivity
import com.test.app.democode.binder.AidlActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ComposeView(this).apply {
                setContent {
                    ActivityListScreen()
                }
            }
        )
    }
}

@Composable
private fun ActivityListScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        BasicText(
            text = "Activity 列表",
            style = TextStyle(
                color = Color.Black,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
        ActivityListItem(title = "android-lifecycle-activity-compose-coroutines-guide") {
            context.startActivity(Intent(context, LifecycleComposeActivity::class.java))
        }
        ActivityListItem(title = "AIDL remote service demo") {
            context.startActivity(Intent(context, AidlActivity::class.java))
        }
        ActivityListItem(title = "Arch demo") {
            context.startActivity(Intent(context, ArchActivity::class.java))
        }
    }
}

@Composable
private fun ActivityListItem(
    title: String,
    onClick: () -> Unit
) {
    BasicText(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        style = TextStyle(
            color = Color.Black,
            fontSize = 16.sp
        )
    )
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFFE0E0E0))
    )
}