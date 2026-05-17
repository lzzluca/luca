package com.example.assistant.android.capture

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugScreenshotCapturer {

    fun captureActivityWindow(activity: Activity): Bitmap {
        val root = activity.window.decorView.rootView
        val width = root.width.coerceAtLeast(1)
        val height = root.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        return bitmap
    }

    fun saveJpegToCache(activity: Activity, bitmap: Bitmap): File {
        val dir = File(activity.cacheDir, "captures").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(dir, "capture_$stamp.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.flush()
        }
        return file
    }
}

