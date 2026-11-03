package com.circaso.core.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * CameraX for Moments (M1). The viewfinder binds preview, photo and video to its own lifecycle while it is on screen;
 * this controller holds the bound use cases and takes the shots. Photos come back as JPEG with the long side at most
 * [MomentPhotoMaxSide]; videos as MP4, stopped by CameraX at [MomentVideoMaxMs].
 */
class AndroidMomentCamera(private val context: Context) : MomentCamera {
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var finished: CompletableDeferred<File?>? = null
    private var videoFile: File? = null
    private var startedAt = 0L
    private var front = false
    private var flash = false
    private var bound: Pair<LifecycleOwner, PreviewView>? = null
    private val scope = MainScope()

    override suspend fun open(): CameraStart {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ||
            PermissionBridge.request(Manifest.permission.CAMERA)
        if (!granted) return CameraStart.PermissionDenied
        // The microphone now too, so a hold to record never stops for a prompt; refused, videos are silent.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) PermissionBridge.request(Manifest.permission.RECORD_AUDIO)
        return if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) CameraStart.Started else CameraStart.Unavailable
    }

    /** Called by [CameraViewfinder] when it appears, and again after a flip. */
    internal suspend fun bind(owner: LifecycleOwner, view: PreviewView) {
        bound = owner to view
        val p = provider ?: kotlinx.coroutines.suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
        }.also { provider = it }
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val photo = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF).build()
        val video = VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD))
            // About 3.5 Mbit/s: a full minute stays near 26 MB, under the server's 32 MiB for a moment's media.
            .setTargetVideoEncodingBitRate(MomentVideoBitRate).build())
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        runCatching {
            p.unbindAll()
            camera = p.bindToLifecycle(owner, selector, preview, photo, video)
            imageCapture = photo
            videoCapture = video
        }
    }

    override suspend fun takePhoto(): CapturedMedia? {
        val capture = imageCapture ?: return null
        val proxy = kotlinx.coroutines.suspendCancellableCoroutine<ImageProxy?> { cont ->
            capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) { cont.resume(image) }
                override fun onError(exception: ImageCaptureException) { cont.resume(null) }
            })
        } ?: return null
        return withContext(Dispatchers.Default) {
            val bitmap = proxy.use { image ->
                val raw = image.toBitmap()
                val turn = image.imageInfo.rotationDegrees
                // The front camera's shot is mirrored back to how the person saw themselves in the viewfinder.
                if (turn == 0 && !front) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply {
                    postRotate(turn.toFloat()); if (front) postScale(-1f, 1f)
                }, true)
            }
            val scaled = bitmap.scaledTo(MomentPhotoMaxSide)
            CapturedMedia(scaled.jpeg(90), "image/jpeg", scaled.width, scaled.height, thumb = scaled.scaledTo(MomentThumbSide).jpeg(50))
        }
    }

    @SuppressLint("MissingPermission") // checked just before: without the microphone the video records silent
    override suspend fun startVideo(): Boolean {
        val capture = videoCapture ?: return false
        val file = File(context.cacheDir, "moment-${System.currentTimeMillis()}.mp4")
        val withAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val done = CompletableDeferred<File?>()
        val options = FileOutputOptions.Builder(file).setDurationLimitMillis(MomentVideoMaxMs).build()
        val pending = capture.output.prepareRecording(context, options).let { if (withAudio) it.withAudioEnabled() else it }
        if (flash) camera?.cameraControl?.enableTorch(true)
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                val ok = !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                done.complete(if (ok) file else null)
            }
        }
        finished = done
        videoFile = file
        startedAt = System.currentTimeMillis()
        return true
    }

    override suspend fun stopVideo(): CapturedMedia? {
        recording?.stop()
        recording = null
        camera?.cameraControl?.enableTorch(false)
        val file = finished?.await() ?: return null
        finished = null
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: (System.currentTimeMillis() - startedAt)
                val frame = retriever.getFrameAtTime(0)
                CapturedMedia(
                    file.readBytes(), "video/mp4", frame?.width, frame?.height, durationMs = duration,
                    thumb = frame?.scaledTo(MomentThumbSide)?.jpeg(50), path = file.absolutePath,
                )
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
    }

    override fun flip() {
        front = !front
        val (owner, view) = bound ?: return
        scope.launch { bind(owner, view) }
    }

    override fun setFlash(on: Boolean) {
        flash = on
        imageCapture?.flashMode = if (on) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    override fun close() {
        recording?.stop()
        recording = null
        runCatching { provider?.unbindAll() }
        camera = null
        imageCapture = null
        videoCapture = null
        bound = null
    }
}

private fun Bitmap.scaledTo(maxSide: Int): Bitmap {
    val longest = max(width, height)
    if (longest <= maxSide) return this
    val f = maxSide.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * f).roundToInt().coerceAtLeast(1), (height * f).roundToInt().coerceAtLeast(1), true)
}

private fun Bitmap.jpeg(quality: Int): ByteArray = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

@Composable
actual fun CameraViewfinder(camera: MomentCamera, modifier: Modifier) {
    val android = camera as? AndroidMomentCamera ?: return
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    androidx.compose.runtime.LaunchedEffect(owner) { android.bind(owner, view) }
    DisposableEffect(Unit) { onDispose { android.close() } }
    AndroidView({ view }, modifier)
}

actual fun momentThumbnail(jpeg: ByteArray): ByteArray? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MomentThumbSide) sample *= 2
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })?.scaledTo(MomentThumbSide)?.jpeg(50)
}.getOrNull()
