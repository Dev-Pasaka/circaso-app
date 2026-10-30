package com.circaso.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoTheme
import io.github.alexzhirkevich.qrose.options.QrBallShape
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.QrFrameShape
import io.github.alexzhirkevich.qrose.options.QrPixelShape
import io.github.alexzhirkevich.qrose.options.circle
import io.github.alexzhirkevich.qrose.options.roundCorners
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

/**
 * The design's QR tile (E4): the code on `surfaceRaised` with an 18 dp inset and a hairline ring. The code itself is
 * always dark on white whatever the theme, because scanners read contrast, not tokens; rounded modules keep it soft.
 */
@Composable
fun QrCode(data: String, label: String, modifier: Modifier = Modifier, size: Dp = 196.dp) {
    val c = CircasoTheme.colors
    val painter = rememberQrCodePainter(data) {
        shapes {
            ball = QrBallShape.circle()
            darkPixel = QrPixelShape.roundCorners()
            frame = QrFrameShape.roundCorners(.25f)
        }
        colors {
            dark = QrBrush.solid(Color(0xFF111111))
            light = QrBrush.solid(Color.White)
        }
    }
    Box(
        modifier.clip(CircasoShapes.lg).background(c.surfaceRaised).border(1.dp, c.separator, CircasoShapes.lg).padding(18.dp)
            .semantics { contentDescription = label },
    ) {
        Box(Modifier.clip(CircasoShapes.md).background(Color.White).padding(10.dp)) {
            Image(painter, contentDescription = null, modifier = Modifier.size(size))
        }
    }
}
