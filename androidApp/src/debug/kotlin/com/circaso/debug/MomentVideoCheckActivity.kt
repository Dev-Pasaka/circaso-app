package com.circaso.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.circaso.core.platform.AndroidMediaLibrary
import com.circaso.core.platform.MomentVideo
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only: the moment video path without signing in, so encode quality is measured, not guessed.
 *
 * - Encode: `adb shell am start -n com.circaso.app/com.circaso.debug.MomentVideoCheckActivity --es trim /sdcard/Movies/clip.mp4 --el start 0 --el end 20000`
 *   runs the moments' trim/shrink ([AndroidMediaLibrary.trim]), logs the source, plan and output (logcat tag `MomentVideo`)
 *   and copies the result to `files/check-out.mp4` (`adb exec-out run-as com.circaso.app cat files/check-out.mp4 > out.mp4`).
 * - Playback: `--es play /path/to/clip.mp4` shows it the way the moment viewer does ([MomentVideo]).
 */
class MomentVideoCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("trim")?.let { source ->
            val start = intent.getLongExtra("start", 0)
            val end = intent.getLongExtra("end", 60_000)
            lifecycleScope.launch {
                val local = File(cacheDir, "check-source.mp4").also { File(source).copyTo(it, overwrite = true) }
                val started = System.currentTimeMillis()
                val out = AndroidMediaLibrary(applicationContext).trim(local.absolutePath, start, end)
                val copy = out?.path?.let { File(it).copyTo(File(filesDir, "check-out.mp4"), overwrite = true) }
                Log.i("MomentVideo", "check: ${if (copy != null) "wrote ${copy.absolutePath} (${copy.length()} bytes)" else "failed"} in ${System.currentTimeMillis() - started} ms")
                finish()
            }
        }
        intent.getStringExtra("play")?.let { path ->
            setContent { MomentVideo(path, playing = true, muted = true, modifier = FillScreen, loop = true) }
        }
    }
}

/** The whole window (compose foundation isn't on the app's own classpath). */
private val FillScreen = Modifier.layout { m, c ->
    val p = m.measure(Constraints.fixed(c.maxWidth, c.maxHeight))
    layout(c.maxWidth, c.maxHeight) { p.place(0, 0) }
}
