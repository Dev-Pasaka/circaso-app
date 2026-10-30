package com.circaso.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/** 18 dp ring spinner, same as the design's `cx-spin`. */
@Composable
fun Spinner(color: Color, modifier: Modifier = Modifier) {
    val angle = rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart))
    Canvas(modifier.size(18.dp)) {
        val w = 2.dp.toPx()
        rotate(angle.value) {
            drawArc(
                color = color, startAngle = 0f, sweepAngle = 270f, useCenter = false,
                topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }
    }
}
