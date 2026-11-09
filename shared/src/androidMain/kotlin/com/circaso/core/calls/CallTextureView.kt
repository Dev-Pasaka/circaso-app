package com.circaso.core.calls

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceEglRenderer
import org.webrtc.ThreadUtils
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch

/**
 * A call tile's picture on a TextureView rather than WebRTC's SurfaceViewRenderer (2026-11-09). A SurfaceView is a
 * hole punched through the window: it ignores the tile's rounded clip, can't be faded, scaled or moved smoothly, and
 * which of two overlapping ones is on top is up to the system. Swapping the 1:1 self-view with the big picture, and a
 * group tile growing into the spotlight, need all of that, so the frames are drawn into a texture that composes like
 * any other view. The same EGL renderer does the drawing; only the surface differs.
 *
 * [fill] crops to the tile (a face); otherwise the whole frame is shown letterboxed (a shared screen).
 */
internal class CallTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = SurfaceEglRenderer("call-tile")
    private val main = Handler(Looper.getMainLooper())
    private var fill = true
    private var frameWidth = 0
    private var frameHeight = 0
    private var released = false

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun init(egl: EglBase.Context) {
        renderer.init(egl, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() = Unit
            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                // The renderer's own thread: the view is laid out again on the main one.
                val turned = rotation == 90 || rotation == 270
                main.post {
                    frameWidth = if (turned) height else width
                    frameHeight = if (turned) width else height
                    relayout()
                }
            }
        }, EglBase.CONFIG_PLAIN, GlRectDrawer())
    }

    fun setMirror(mirror: Boolean) = renderer.setMirror(mirror)

    fun setFill(fill: Boolean) {
        if (this.fill == fill) return
        this.fill = fill
        relayout()
    }

    override fun onFrame(frame: VideoFrame) = renderer.onFrame(frame)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        relayout()
    }

    /** Fill: the renderer crops the frame to the view. Fit: the whole frame, scaled down to its own shape, centred. */
    private fun relayout() {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        if (fill || frameWidth == 0 || frameHeight == 0) {
            renderer.setLayoutAspectRatio(w.toFloat() / h)
            setTransform(null)
        } else {
            renderer.setLayoutAspectRatio(0f) // no crop: the frame is stretched over the view, then the matrix fixes its shape
            val frame = frameWidth.toFloat() / frameHeight
            val view = w.toFloat() / h
            val (sx, sy) = if (frame > view) 1f to view / frame else frame / view to 1f
            setTransform(Matrix().apply { setScale(sx, sy, w / 2f, h / 2f) })
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        renderer.createEglSurface(surface)
        relayout()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = relayout()

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // The texture may only go once the renderer has stopped drawing into it.
        val done = CountDownLatch(1)
        renderer.releaseEglSurface { done.countDown() }
        ThreadUtils.awaitUninterruptibly(done)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    fun release() {
        if (released) return
        released = true
        renderer.release()
    }
}
