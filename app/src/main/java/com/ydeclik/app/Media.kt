package com.ydeclik.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

/** Renders PDF pages. Thread-safe: PdfRenderer only allows one open page at a time. */
class PdfDoc(cr: ContentResolver, uri: Uri) {
    private val pfd: ParcelFileDescriptor =
        cr.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Cannot open PDF")
    private val renderer: PdfRenderer = PdfRenderer(pfd)
    private val lock = Any()
    private var closed = false

    val pageCount: Int = renderer.pageCount

    fun render(index: Int, widthPx: Int): Bitmap? = synchronized(lock) {
        if (closed || index !in 0 until pageCount) return null
        val page = renderer.openPage(index)
        try {
            val w = max(1, widthPx)
            val h = max(1, (w.toFloat() / page.width * page.height).toInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } finally {
            page.close()
        }
    }

    fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        }
    }
}

object Pics {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Decodes an image so that its largest side is at least [maxDim] (but not much more), honoring EXIF rotation. */
    fun decode(cr: ContentResolver, uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val orientation = runCatching {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
        return if (m.isIdentity) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun thumb(cr: ContentResolver, e: Exercise): Bitmap? {
        cache.get(e.uri)?.let { return it }
        val b = runCatching {
            if (e.isPdf) {
                val d = PdfDoc(cr, Uri.parse(e.uri))
                try { d.render(0, 400) } finally { d.close() }
            } else {
                decode(cr, Uri.parse(e.uri), 400)
            }
        }.getOrNull()
        if (b != null) cache.put(e.uri, b)
        return b
    }
}
