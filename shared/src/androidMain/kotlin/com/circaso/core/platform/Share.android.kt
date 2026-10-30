package com.circaso.core.platform

import android.content.Intent
import org.koin.mp.KoinPlatform

actual fun shareText(text: String, subject: String?) {
    val activity = runCatching { KoinPlatform.getKoin().get<CurrentActivity>().get() }.getOrNull() ?: return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }
    activity.startActivity(Intent.createChooser(send, subject))
}
