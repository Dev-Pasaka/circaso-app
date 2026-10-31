package com.circaso.core.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Registered by the activity while it is created (like [PermissionBridge]); the system photo picker needs no permission. */
object ImagePickerBridge {
    private var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var pending: CompletableDeferred<Uri?>? = null

    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> pending?.complete(uri) }
    }

    suspend fun pick(): Uri? {
        val l = launcher ?: return null
        val answer = CompletableDeferred<Uri?>().also { pending = it }
        l.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        return answer.await()
    }
}

class AndroidImagePicker(private val context: Context) : ImagePicker {
    override suspend fun pick(): PickedImage? {
        val uri = ImagePickerBridge.pick() ?: return null
        return withContext(Dispatchers.IO) {
            val bitmap = decode(uri) ?: return@withContext null
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, AvatarJpegQuality, out)
            val (w, h) = bitmap.width to bitmap.height
            bitmap.recycle()
            PickedImage(out.toByteArray(), "image/jpeg", w, h)
        }
    }

    private fun decode(uri: Uri): Bitmap? = decodeScaled(context, uri, AvatarMaxSide)
}

/** Decodes already scaled down so a 50 MP photo never sits in memory at full size. */
internal fun decodeScaled(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder also applies the photo's rotation.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > maxSide) {
                    val f = maxSide.toFloat() / longest
                    decoder.setTargetSize((info.size.width * f).roundToInt(), (info.size.height * f).roundToInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // a hardware bitmap cannot be compressed
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    } catch (e: Exception) {
        null
    }
