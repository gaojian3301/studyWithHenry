package com.test.app.democode

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun BackButton(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Canvas(
        modifier = modifier
            .size(40.dp)
            .clickable {
                context.findActivity()?.finish()
            }
            .padding(8.dp)
    ) {
        val strokeWidth = 3.dp.toPx()
        val centerY = size.height / 2
        drawLine(
            color = Color.Black,
            start = Offset(size.width * 0.65f, size.height * 0.2f),
            end = Offset(size.width * 0.35f, centerY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.Black,
            start = Offset(size.width * 0.35f, centerY),
            end = Offset(size.width * 0.65f, size.height * 0.8f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
