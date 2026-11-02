package com.huelpocket.glassui.internal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 静态降级用的 CPU 模糊：降采样 → 饱和度增益 → 三次盒式模糊（近似高斯）。
 * 1080×2400 的截图降到 1/8 后只有约 4 万像素，整个过程在后台线程几毫秒完成。
 */
internal object StaticBlur {
    const val DOWNSCALE = 8

    fun process(source: ImageBitmap, blurRadiusPx: Float, saturation: Float): ImageBitmap {
        val src = source.asAndroidBitmap().let {
            if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it
        }
        val w = max(1, src.width / DOWNSCALE)
        val h = max(1, src.height / DOWNSCALE)
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(small).apply {
            scale(w.toFloat() / src.width, h.toFloat() / src.height)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) })
            }
            drawBitmap(src, 0f, 0f, paint)
        }
        val radius = max(1, (blurRadiusPx / DOWNSCALE / 1.5f).roundToInt())
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            boxBlur(pixels, tmp, w, h, radius, horizontal = true)
            boxBlur(tmp, pixels, w, h, radius, horizontal = false)
        }
        small.setPixels(pixels, 0, w, 0, 0, w, h)
        return small.asImageBitmap()
    }

    private fun boxBlur(input: IntArray, output: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + c else c * w + line
            }
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = input[idx(i)]
                sa += p ushr 24
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (i in 0 until len) {
                output[idx(i)] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = input[idx(i + r + 1)]
                val sub = input[idx(i - r)]
                sa += (add ushr 24) - (sub ushr 24)
                sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}
