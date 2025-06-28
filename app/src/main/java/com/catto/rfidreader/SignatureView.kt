package com.catto.rfidreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.createBitmap
import kotlin.random.Random

class SignatureView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var cardId: ByteArray? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var cornerRadius = 0f

    private var signatureBitmap: Bitmap? = null
    private var signatureCanvas: Canvas? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        val density = context.resources.displayMetrics.density
        cornerRadius = 12 * density
    }

    fun setCardId(id: ByteArray?) {
        this.cardId = id
        regenerateSignatureBitmap()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            signatureBitmap?.recycle()
            signatureBitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888)
            signatureCanvas = Canvas(signatureBitmap!!)
            regenerateSignatureBitmap()
        }
    }

    private fun regenerateSignatureBitmap() {
        val canvas = signatureCanvas ?: return
        val id = cardId

        if (id == null || id.isEmpty()) {
            canvas.drawColor(Color.TRANSPARENT)
            return
        }

        val seed = id.contentHashCode().toLong()
        val random = Random(seed)

        val baseHue = (id.getOrElse(0) { 0 }.toInt() and 0xFF) / 255f * 360f
        val saturation = 0.4f + random.nextFloat() * 0.6f
        val value = 0.5f + random.nextFloat() * 0.4f
        canvas.drawColor(Color.HSVToColor(floatArrayOf(baseHue, saturation, value)))

        val numShapes = 5 + random.nextInt(15)
        repeat(numShapes) {
            val shapeType = random.nextInt(3)
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            val size = (random.nextFloat() * 0.3f + 0.05f) * width

            val hue = (baseHue + random.nextInt(-60, 60) + 360) % 360
            val shapeSaturation = (0.7f + random.nextFloat() * 0.3f).coerceIn(0.7f, 1.0f)
            val shapeValue = (0.8f + random.nextFloat() * 0.2f).coerceIn(0.8f, 1.0f)
            val alpha = (random.nextFloat() * 180 + 75).toInt()

            paint.color = Color.HSVToColor(alpha, floatArrayOf(hue, shapeSaturation, shapeValue))

            when (shapeType) {
                0 -> {
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(x, y, size / 2, paint)
                }
                1 -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = random.nextFloat() * 12f + 4f
                    canvas.drawRect(x - size / 2, y - size / 2, x + size / 2, y + size / 2, paint)
                }
                2 -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = random.nextFloat() * 8f + 3f
                    val x2 = (x + random.nextFloat() * 250 - 125).coerceIn(0f, width.toFloat())
                    val y2 = (y + random.nextFloat() * 250 - 125).coerceIn(0f, height.toFloat())
                    canvas.drawLine(x, y, x2, y2, paint)
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        path.reset()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(path)

        signatureBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, bitmapPaint)
        }
    }
}
