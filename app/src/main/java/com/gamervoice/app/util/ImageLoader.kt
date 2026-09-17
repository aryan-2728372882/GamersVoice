package com.gamervoice.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.gamervoice.app.R
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

object ImageLoader {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder().build()

    // Ultra-lean 512KB memory cache for small avatar thumbnails
    private val memoryCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(512 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    /**
     * Completely evicts all cached bitmaps from memory and requests immediate GC.
     * Called when the app is minimized so Free Fire/BGMI runs with minimum PSS RAM.
     */
    fun clearMemoryCache() {
        try {
            memoryCache.evictAll()
            System.gc()
        } catch (_: Throwable) {}
    }

    fun loadAvatar(
        imageView: ImageView,
        avatarKeyOrUrl: String?,
        fallbackRes: Int = R.drawable.ic_avatar_1
    ) {
        if (avatarKeyOrUrl.isNullOrBlank()) {
            imageView.tag = null
            imageView.setImageResource(fallbackRes)
            return
        }

        if (avatarKeyOrUrl.startsWith("http://") || avatarKeyOrUrl.startsWith("https://")) {
            loadRemoteCircularImage(imageView, avatarKeyOrUrl, fallbackRes)
        } else {
            imageView.tag = null
            imageView.setImageResource(AvatarHelper.getDrawableRes(avatarKeyOrUrl))
        }
    }

    private fun loadRemoteCircularImage(
        imageView: ImageView,
        url: String,
        fallbackRes: Int
    ) {
        val cached = memoryCache.get(url)
        if (cached != null && !cached.isRecycled) {
            imageView.tag = url
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageResource(fallbackRes)
        imageView.tag = url

        val request = Request.Builder()
            .url(url)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Keep fallback on failure
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) return
                val bytes = response.body?.bytes() ?: return
                try {
                    // Downsample to max 96x96 thumbnail to keep RAM ultra lean (<40KB per bitmap)
                    val opts = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

                    val targetSize = 96
                    var sampleSize = 1
                    while (opts.outWidth / (sampleSize * 2) >= targetSize && opts.outHeight / (sampleSize * 2) >= targetSize) {
                        sampleSize *= 2
                    }

                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return
                    val circularBitmap = getCircularBitmap(rawBitmap)
                    if (rawBitmap != circularBitmap && !rawBitmap.isRecycled) {
                        rawBitmap.recycle()
                    }
                    memoryCache.put(url, circularBitmap)

                    mainHandler.post {
                        if (imageView.tag == url) {
                            imageView.setImageBitmap(circularBitmap)
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore decode issues
                }
            }
        })
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = Math.min(bitmap.width, bitmap.height)
        val x = (bitmap.width - size) / 2
        val y = (bitmap.height - size) / 2
        val squared = Bitmap.createBitmap(bitmap, x, y, size, size)

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val rect = Rect(0, 0, size, size)
        canvas.drawBitmap(squared, rect, rect, paint)

        if (squared != bitmap && !squared.isRecycled) {
            squared.recycle()
        }

        return output
    }
}
