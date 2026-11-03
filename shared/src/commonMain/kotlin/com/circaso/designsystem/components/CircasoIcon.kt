package com.circaso.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.platform.PlatformStyle
import com.circaso.designsystem.theme.CircasoTheme

/** Semantic icon names. Each is drawn per platform (chevron back on iOS, arrow back on Android). */
enum class CircasoIcon { Back, Close, Check, Delete, Backspace, Offline, Clock, Chat, Wallet, Person, ChevronRight, Plus, Search, Send, Mic, MicOff, Speaker, Stop, Waveform, ArrowUp, ArrowDown, Bank, Eye, EyeOff, Copy, Share, Clipboard, Network, Money, Warning, Scan, Phone, Video, VideoOff, FlipCamera, Minimise, Pin, Play, Pause, Photo, Camera, Spaces, Flash, More, Lock, Heart, HeartFilled, People, VolumeOff, ChevronDown, Trash, Target, Fingerprint, FaceId }

/** Strokes drawn on a 24x24 grid, same as the design's inline SVGs. */
@Composable
fun CircasoIconImage(icon: CircasoIcon, tint: Color, modifier: Modifier = Modifier, strokeWidth: Float = 2f) {
    val platform = CircasoTheme.platform
    Canvas(modifier) {
        scale(size.width / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val p = Path()
            when (icon) {
                CircasoIcon.Back -> if (platform == PlatformStyle.Ios) {
                    p.moveTo(14.5f, 5.5f); p.lineTo(8f, 12f); p.lineTo(14.5f, 18.5f)
                } else {
                    p.moveTo(19f, 12f); p.lineTo(5f, 12f); p.moveTo(11f, 6f); p.lineTo(5f, 12f); p.lineTo(11f, 18f)
                }
                CircasoIcon.Close -> { p.moveTo(6f, 6f); p.lineTo(18f, 18f); p.moveTo(18f, 6f); p.lineTo(6f, 18f) }
                CircasoIcon.Check -> { p.moveTo(5f, 12.5f); p.lineTo(10f, 17.5f); p.lineTo(19f, 7f) }
                CircasoIcon.Delete, CircasoIcon.Backspace -> {
                    p.moveTo(9f, 5f); p.lineTo(20f, 5f); p.lineTo(20f, 19f); p.lineTo(9f, 19f); p.lineTo(3f, 12f); p.close()
                    p.moveTo(12.5f, 9f); p.lineTo(16.5f, 15f); p.moveTo(16.5f, 9f); p.lineTo(12.5f, 15f)
                }
                CircasoIcon.Offline -> {
                    p.moveTo(2f, 9f); p.quadraticTo(12f, 2f, 22f, 9f)
                    p.moveTo(5f, 12.5f); p.quadraticTo(12f, 7.5f, 19f, 12.5f)
                    p.moveTo(8.5f, 16f); p.quadraticTo(12f, 13f, 15.5f, 16f)
                    p.moveTo(12f, 19.5f); p.lineTo(12f, 19.6f)
                    p.moveTo(4f, 4f); p.lineTo(20f, 20f)
                }
                CircasoIcon.ChevronRight -> { p.moveTo(9.5f, 5.5f); p.lineTo(16f, 12f); p.lineTo(9.5f, 18.5f) }
                CircasoIcon.Plus -> { p.moveTo(12f, 5f); p.lineTo(12f, 19f); p.moveTo(5f, 12f); p.lineTo(19f, 12f) }
                CircasoIcon.Search -> { p.addOval(Rect(4f, 4f, 16f, 16f)); p.moveTo(14.5f, 14.5f); p.lineTo(20f, 20f) }
                CircasoIcon.Send -> { p.moveTo(12f, 19f); p.lineTo(12f, 5f); p.moveTo(6f, 11f); p.lineTo(12f, 5f); p.lineTo(18f, 11f) }
                CircasoIcon.Mic, CircasoIcon.MicOff -> {
                    p.addRoundRect(RoundRect(9f, 3f, 15f, 14f, 3f, 3f))
                    p.moveTo(5.5f, 11f); p.cubicTo(5.5f, 17.5f, 18.5f, 17.5f, 18.5f, 11f)
                    p.moveTo(12f, 16.5f); p.lineTo(12f, 21f)
                    if (icon == CircasoIcon.MicOff) { p.moveTo(4f, 4f); p.lineTo(20f, 20f) }
                }
                CircasoIcon.Waveform -> {
                    p.moveTo(4f, 10f); p.lineTo(4f, 14f)
                    p.moveTo(8f, 7f); p.lineTo(8f, 17f)
                    p.moveTo(12f, 4f); p.lineTo(12f, 20f)
                    p.moveTo(16f, 7f); p.lineTo(16f, 17f)
                    p.moveTo(20f, 10f); p.lineTo(20f, 14f)
                }
                CircasoIcon.Speaker -> {
                    p.moveTo(4f, 9.5f); p.lineTo(8f, 9.5f); p.lineTo(13f, 5.5f); p.lineTo(13f, 18.5f); p.lineTo(8f, 14.5f); p.lineTo(4f, 14.5f); p.close()
                    p.moveTo(16f, 9f); p.quadraticTo(18f, 12f, 16f, 15f)
                    p.moveTo(18.5f, 6.5f); p.quadraticTo(22.5f, 12f, 18.5f, 17.5f)
                }
                CircasoIcon.Stop -> p.addRoundRect(RoundRect(7f, 7f, 17f, 17f, 2f, 2f))
                CircasoIcon.Clock -> {
                    p.addOval(Rect(4f, 4f, 20f, 20f))
                    p.moveTo(12f, 8f); p.lineTo(12f, 12f); p.lineTo(15f, 14f)
                }
                CircasoIcon.Chat -> {
                    p.addRoundRect(RoundRect(4f, 4f, 20f, 16f, 4f, 4f))
                    p.moveTo(9f, 16f); p.lineTo(9f, 20f); p.lineTo(13f, 16f)
                }
                CircasoIcon.Wallet -> {
                    p.addRoundRect(RoundRect(3f, 6f, 21f, 19f, 3f, 3f))
                    p.moveTo(3f, 10f); p.lineTo(21f, 10f)
                    p.moveTo(16f, 14.5f); p.lineTo(16.1f, 14.5f)
                }
                // A ring around a centre (the Circaso mark's idea) with two small circles: people around a place.
                CircasoIcon.Flash -> { p.moveTo(13f, 3f); p.lineTo(5.5f, 13.5f); p.lineTo(12f, 13.5f); p.lineTo(11f, 21f); p.lineTo(18.5f, 10.5f); p.lineTo(12f, 10.5f); p.close() }
                CircasoIcon.More -> { p.moveTo(6f, 12f); p.lineTo(6.01f, 12f); p.moveTo(12f, 12f); p.lineTo(12.01f, 12f); p.moveTo(18f, 12f); p.lineTo(18.01f, 12f) }
                CircasoIcon.Lock -> { p.moveTo(7f, 11f); p.lineTo(7f, 8f); p.cubicTo(7f, 1.5f, 17f, 1.5f, 17f, 8f); p.lineTo(17f, 11f); p.addRoundRect(RoundRect(5.5f, 11f, 18.5f, 20f, 2f, 2f)) }
                CircasoIcon.Heart, CircasoIcon.HeartFilled -> {
                    p.moveTo(12f, 19.5f); p.cubicTo(5f, 15f, 4.5f, 11f, 4.5f, 9.5f); p.cubicTo(4.5f, 6.5f, 7f, 5f, 8.8f, 5f)
                    p.cubicTo(10.4f, 5f, 11.4f, 5.9f, 12f, 7f); p.cubicTo(12.6f, 5.9f, 13.6f, 5f, 15.2f, 5f)
                    p.cubicTo(17f, 5f, 19.5f, 6.5f, 19.5f, 9.5f); p.cubicTo(19.5f, 11f, 19f, 15f, 12f, 19.5f); p.close()
                }
                CircasoIcon.People -> {
                    p.addOval(Rect(5.5f, 4f, 12.5f, 11f)); p.moveTo(2.5f, 19.5f); p.cubicTo(2.8f, 16f, 5.5f, 14.3f, 9f, 14.3f); p.cubicTo(12.5f, 14.3f, 15.2f, 16f, 15.5f, 19.5f)
                    p.moveTo(16f, 4.3f); p.cubicTo(18.5f, 5f, 18.5f, 10f, 16f, 10.7f); p.moveTo(18f, 14.6f); p.cubicTo(20f, 15.2f, 21.3f, 16.8f, 21.5f, 19.5f)
                }
                CircasoIcon.VolumeOff -> { p.moveTo(4f, 9.5f); p.lineTo(8f, 9.5f); p.lineTo(13f, 5.5f); p.lineTo(13f, 18.5f); p.lineTo(8f, 14.5f); p.lineTo(4f, 14.5f); p.close(); p.moveTo(16.5f, 9.5f); p.lineTo(21.5f, 14.5f); p.moveTo(21.5f, 9.5f); p.lineTo(16.5f, 14.5f) }
                CircasoIcon.ChevronDown -> { p.moveTo(7f, 10f); p.lineTo(12f, 15f); p.lineTo(17f, 10f) }
                // The design's `target` (GW6): two rings and a centre mark.
                CircasoIcon.Target -> { p.addOval(Rect(3.5f, 3.5f, 20.5f, 20.5f)); p.addOval(Rect(7.5f, 7.5f, 16.5f, 16.5f)); p.moveTo(12f, 11.5f); p.lineTo(12f, 12.5f) }
                // The design's `fingerprint` (GW4, GW8): the ridges as arcs.
                CircasoIcon.Fingerprint -> {
                    p.moveTo(7.5f, 19.5f); p.cubicTo(9f, 17.5f, 9.5f, 15f, 9.5f, 12f); p.cubicTo(9.5f, 10.6f, 10.6f, 9.5f, 12f, 9.5f); p.cubicTo(13.4f, 9.5f, 14.5f, 10.6f, 14.5f, 12f); p.cubicTo(14.5f, 13.2f, 14.4f, 14.4f, 14.2f, 15.5f)
                    p.moveTo(12f, 12f); p.cubicTo(12f, 15.5f, 11.2f, 18.3f, 9.5f, 20.5f)
                    p.moveTo(5f, 16f); p.cubicTo(5.6f, 14.8f, 6f, 13.4f, 6f, 12f); p.cubicTo(6f, 8.7f, 8.7f, 6f, 12f, 6f); p.cubicTo(15.3f, 6f, 18f, 8.7f, 18f, 12f); p.cubicTo(18f, 14.6f, 17.6f, 17f, 16.8f, 19f)
                    p.moveTo(4.5f, 9f); p.cubicTo(6.5f, 4.6f, 17.4f, 4.5f, 19.4f, 8.9f)
                    p.moveTo(17.5f, 19.8f); p.lineTo(17.9f, 18.8f)
                }
                // The design's `faceid` (GW4b): corners, eyes, nose and smile.
                CircasoIcon.FaceId -> {
                    p.moveTo(4f, 8f); p.lineTo(4f, 6f); p.quadraticTo(4f, 4f, 6f, 4f); p.lineTo(8f, 4f)
                    p.moveTo(16f, 4f); p.lineTo(18f, 4f); p.quadraticTo(20f, 4f, 20f, 6f); p.lineTo(20f, 8f)
                    p.moveTo(20f, 16f); p.lineTo(20f, 18f); p.quadraticTo(20f, 20f, 18f, 20f); p.lineTo(16f, 20f)
                    p.moveTo(8f, 20f); p.lineTo(6f, 20f); p.quadraticTo(4f, 20f, 4f, 18f); p.lineTo(4f, 16f)
                    p.moveTo(9f, 9f); p.lineTo(9f, 10.5f); p.moveTo(15f, 9f); p.lineTo(15f, 10.5f)
                    p.moveTo(12f, 9f); p.lineTo(12f, 13.5f); p.lineTo(11f, 13.5f)
                    p.moveTo(9.5f, 16f); p.quadraticTo(12f, 17.8f, 14.5f, 16f)
                }
                CircasoIcon.Trash -> { p.moveTo(5f, 7f); p.lineTo(19f, 7f); p.moveTo(10f, 11f); p.lineTo(10f, 17f); p.moveTo(14f, 11f); p.lineTo(14f, 17f); p.moveTo(6.5f, 7f); p.lineTo(7.5f, 19.5f); p.lineTo(16.5f, 19.5f); p.lineTo(17.5f, 7f); p.moveTo(9.5f, 7f); p.lineTo(9.5f, 4.5f); p.lineTo(14.5f, 4.5f); p.lineTo(14.5f, 7f) }
                CircasoIcon.Spaces -> {
                    p.addOval(Rect(7.2f, 7.2f, 16.8f, 16.8f))
                    p.addOval(Rect(10.6f, 10.6f, 13.4f, 13.4f))
                    p.addOval(Rect(2.6f, 2.6f, 7f, 7f))
                    p.addOval(Rect(17f, 17f, 21.4f, 21.4f))
                }
                CircasoIcon.Person -> {
                    p.addOval(Rect(8.5f, 4f, 15.5f, 11f))
                    p.moveTo(5f, 20f); p.cubicTo(5f, 14.5f, 19f, 14.5f, 19f, 20f)
                }
                CircasoIcon.ArrowUp -> { p.moveTo(12f, 19f); p.lineTo(12f, 5f); p.moveTo(6f, 11f); p.lineTo(12f, 5f); p.lineTo(18f, 11f) }
                CircasoIcon.ArrowDown -> { p.moveTo(12f, 5f); p.lineTo(12f, 19f); p.moveTo(6f, 13f); p.lineTo(12f, 19f); p.lineTo(18f, 13f) }
                CircasoIcon.Bank -> {
                    p.moveTo(3f, 9.5f); p.lineTo(12f, 4f); p.lineTo(21f, 9.5f); p.close()
                    p.moveTo(5f, 10f); p.lineTo(5f, 17f); p.moveTo(9.5f, 10f); p.lineTo(9.5f, 17f); p.moveTo(14.5f, 10f); p.lineTo(14.5f, 17f); p.moveTo(19f, 10f); p.lineTo(19f, 17f)
                    p.moveTo(3f, 20f); p.lineTo(21f, 20f)
                }
                CircasoIcon.Eye, CircasoIcon.EyeOff -> {
                    p.moveTo(2.5f, 12f); p.cubicTo(6f, 5.5f, 18f, 5.5f, 21.5f, 12f); p.cubicTo(18f, 18.5f, 6f, 18.5f, 2.5f, 12f)
                    p.addOval(Rect(9f, 9f, 15f, 15f))
                    if (icon == CircasoIcon.EyeOff) { p.moveTo(4f, 4f); p.lineTo(20f, 20f) }
                }
                CircasoIcon.Copy -> {
                    p.addRoundRect(RoundRect(9f, 9f, 20f, 20f, 2f, 2f))
                    p.moveTo(6f, 15f); p.lineTo(5f, 15f); p.cubicTo(4.4f, 15f, 4f, 14.6f, 4f, 14f); p.lineTo(4f, 5f); p.cubicTo(4f, 4.4f, 4.4f, 4f, 5f, 4f); p.lineTo(14f, 4f); p.cubicTo(14.6f, 4f, 15f, 4.4f, 15f, 5f); p.lineTo(15f, 6f)
                }
                CircasoIcon.Share -> {
                    p.moveTo(12f, 15f); p.lineTo(12f, 3.5f); p.moveTo(8f, 7.5f); p.lineTo(12f, 3.5f); p.lineTo(16f, 7.5f)
                    p.moveTo(5f, 11f); p.lineTo(5f, 19f); p.cubicTo(5f, 19.6f, 5.4f, 20f, 6f, 20f); p.lineTo(18f, 20f); p.cubicTo(18.6f, 20f, 19f, 19.6f, 19f, 19f); p.lineTo(19f, 11f)
                }
                CircasoIcon.Phone -> {
                    // A handset, as the design's call buttons draw it.
                    p.moveTo(6.5f, 3.5f); p.lineTo(9.5f, 3.5f); p.lineTo(11f, 7.5f); p.lineTo(9f, 9f)
                    p.cubicTo(10f, 11.5f, 12.5f, 14f, 15f, 15f); p.lineTo(16.5f, 13f); p.lineTo(20.5f, 14.5f); p.lineTo(20.5f, 17.5f)
                    p.cubicTo(20.5f, 19.5f, 19f, 20.5f, 17.5f, 20.5f); p.cubicTo(10f, 20f, 4f, 14f, 3.5f, 6.5f); p.cubicTo(3.5f, 5f, 4.5f, 3.5f, 6.5f, 3.5f); p.close()
                }
                CircasoIcon.Video, CircasoIcon.VideoOff -> {
                    p.addRoundRect(RoundRect(3f, 6.5f, 15f, 17.5f, 2.5f, 2.5f))
                    p.moveTo(15f, 10.5f); p.lineTo(21f, 7f); p.lineTo(21f, 17f); p.lineTo(15f, 13.5f)
                    if (icon == CircasoIcon.VideoOff) { p.moveTo(3f, 3f); p.lineTo(21f, 21f) }
                }
                CircasoIcon.FlipCamera -> {
                    p.moveTo(4f, 8f); p.lineTo(7f, 8f); p.lineTo(8.5f, 5.5f); p.lineTo(15.5f, 5.5f); p.lineTo(17f, 8f); p.lineTo(20f, 8f); p.lineTo(20f, 19f); p.lineTo(4f, 19f); p.close()
                    p.moveTo(9f, 13f); p.cubicTo(9f, 10f, 14f, 9.5f, 15f, 12.5f); p.moveTo(15f, 14f); p.cubicTo(15f, 17f, 10f, 17.5f, 9f, 14.5f)
                    p.moveTo(13.5f, 12.5f); p.lineTo(15f, 12.5f); p.lineTo(15.5f, 11f)
                }
                CircasoIcon.Minimise -> { p.moveTo(6f, 9.5f); p.lineTo(12f, 15.5f); p.lineTo(18f, 9.5f) }
                CircasoIcon.Scan -> {
                    // The four corners of a viewfinder with a scan line, as the design's E5 button.
                    p.moveTo(4f, 9f); p.lineTo(4f, 6f); p.quadraticTo(4f, 4f, 6f, 4f); p.lineTo(9f, 4f)
                    p.moveTo(15f, 4f); p.lineTo(18f, 4f); p.quadraticTo(20f, 4f, 20f, 6f); p.lineTo(20f, 9f)
                    p.moveTo(20f, 15f); p.lineTo(20f, 18f); p.quadraticTo(20f, 20f, 18f, 20f); p.lineTo(15f, 20f)
                    p.moveTo(9f, 20f); p.lineTo(6f, 20f); p.quadraticTo(4f, 20f, 4f, 18f); p.lineTo(4f, 15f)
                    p.moveTo(7f, 12f); p.lineTo(17f, 12f)
                }
                CircasoIcon.Clipboard -> {
                    p.addRoundRect(RoundRect(5f, 5f, 19f, 21f, 2f, 2f))
                    p.addRoundRect(RoundRect(9f, 3f, 15f, 7f, 1.5f, 1.5f))
                    p.moveTo(9f, 12f); p.lineTo(15f, 12f); p.moveTo(9f, 16f); p.lineTo(13f, 16f)
                }
                CircasoIcon.Network -> {
                    p.addOval(Rect(4f, 4f, 20f, 20f))
                    p.moveTo(4f, 12f); p.lineTo(20f, 12f); p.moveTo(12f, 4f); p.cubicTo(8f, 8f, 8f, 16f, 12f, 20f); p.moveTo(12f, 4f); p.cubicTo(16f, 8f, 16f, 16f, 12f, 20f)
                }
                CircasoIcon.Money -> {
                    p.addRoundRect(RoundRect(3f, 6f, 21f, 18f, 3f, 3f))
                    p.addOval(Rect(9.5f, 9.5f, 14.5f, 14.5f))
                    p.moveTo(6.5f, 12f); p.lineTo(6.6f, 12f); p.moveTo(17.5f, 12f); p.lineTo(17.6f, 12f)
                }
                CircasoIcon.Pin -> {
                    p.moveTo(12f, 21.5f); p.cubicTo(12f, 21.5f, 5f, 15f, 5f, 10f); p.cubicTo(5f, 6f, 8f, 3f, 12f, 3f)
                    p.cubicTo(16f, 3f, 19f, 6f, 19f, 10f); p.cubicTo(19f, 15f, 12f, 21.5f, 12f, 21.5f); p.close()
                    p.addOval(Rect(9.5f, 7.5f, 14.5f, 12.5f))
                }
                // Play and Pause are filled shapes (the media controls read heavier than outline strokes).
                CircasoIcon.Play -> { p.moveTo(8f, 5f); p.lineTo(19f, 12f); p.lineTo(8f, 19f); p.close() }
                CircasoIcon.Pause -> { p.addRoundRect(RoundRect(6.5f, 5f, 10f, 19f, 1f, 1f)); p.addRoundRect(RoundRect(14f, 5f, 17.5f, 19f, 1f, 1f)) }
                CircasoIcon.Photo -> {
                    p.addRoundRect(RoundRect(3f, 5f, 21f, 19f, 3f, 3f))
                    p.addOval(Rect(7f, 8f, 10.5f, 11.5f))
                    p.moveTo(3.5f, 17f); p.lineTo(9f, 12.5f); p.lineTo(13f, 16f); p.lineTo(16f, 13.5f); p.lineTo(20.5f, 17.5f)
                }
                CircasoIcon.Camera -> {
                    p.moveTo(3f, 8.5f); p.lineTo(7.5f, 8.5f); p.lineTo(9f, 6f); p.lineTo(15f, 6f); p.lineTo(16.5f, 8.5f); p.lineTo(21f, 8.5f)
                    p.lineTo(21f, 19f); p.lineTo(3f, 19f); p.close()
                    p.addOval(Rect(8.5f, 9.75f, 15.5f, 16.75f))
                }
                CircasoIcon.Warning -> {
                    p.moveTo(12f, 4f); p.lineTo(21f, 20f); p.lineTo(3f, 20f); p.close()
                    p.moveTo(12f, 10f); p.lineTo(12f, 14.5f); p.moveTo(12f, 17f); p.lineTo(12f, 17.1f)
                }
            }
            if (icon == CircasoIcon.Play || icon == CircasoIcon.Pause || icon == CircasoIcon.HeartFilled) drawPath(p, tint) else drawPath(p, tint, style = stroke)
        }
    }
}
