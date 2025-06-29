package com.catto.rfidreader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.toColorInt
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.math.sin

class SignatureView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var cardId: ByteArray? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var cornerRadius = 0f

    // --- Audio Synthesis Variables ---
    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    @Volatile private var isPlaying = false
    @Volatile private var targetAmplitude = 0.0
    @Volatile private var currentAmplitude = 0.0
    @Volatile private var frequency = 440.0
    private val sampleRate = 44100
    private val cMajorScale = doubleArrayOf(
        261.63, 293.66, 329.63, 349.23, 392.00, 440.00, 493.88, 523.25
    )
    private val ATTACK_TIME_SAMPLES = sampleRate * 0.02 // 20ms attack
    private val RELEASE_TIME_SAMPLES = sampleRate * 0.08 // 80ms release

    // --- Touch Interaction Variables ---
    private var currentlyTouchedBar = -1

    init {
        val density = context.resources.displayMetrics.density
        cornerRadius = 12 * density
    }

    fun setCardId(id: ByteArray?) {
        this.cardId = id
        invalidate()
    }

    // --- Sound Generation ---
    private fun startAudio() {
        targetAmplitude = 1.0
        if (audioThread?.isAlive == true) {
            return
        }

        isPlaying = true
        audioThread = thread(start = true) {
            audioLoop()
        }
    }

    private fun audioLoop() {
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buffer = ShortArray(bufferSize)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        var angle = 0.0
        audioTrack?.play()

        while (isPlaying) {
            val angularIncrement = 2 * Math.PI * frequency / sampleRate

            for (i in buffer.indices) {
                // Per-sample envelope calculation for a smooth, click-free sound.
                if (targetAmplitude > currentAmplitude) {
                    currentAmplitude += 1.0 / ATTACK_TIME_SAMPLES
                    if (currentAmplitude > 1.0) currentAmplitude = 1.0
                } else if (targetAmplitude < currentAmplitude) {
                    currentAmplitude -= 1.0 / RELEASE_TIME_SAMPLES
                    if (currentAmplitude < 0.0) currentAmplitude = 0.0
                }

                if (currentAmplitude == 0.0 && targetAmplitude == 0.0) {
                    // Optimization: if silent, fill buffer with zeros
                    buffer[i] = 0
                    continue
                }

                val sample = (sin(angle) * 0.7 + sin(angle * 2) * 0.3)
                buffer[i] = (sample * currentAmplitude * Short.MAX_VALUE).toInt().toShort()
                angle += angularIncrement
            }

            audioTrack?.write(buffer, 0, buffer.size)

            if (currentAmplitude == 0.0 && targetAmplitude == 0.0) {
                isPlaying = false
            }
        }

        audioTrack?.stop()
        audioTrack?.release()
        audioThread = null
    }


    private fun stopAudio() {
        targetAmplitude = 0.0
    }

    // --- Touch Event Handling ---
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (cardId == null) return false

        val x = event.x
        val barCount = cardId?.size ?: return false
        val barWidth = width.toFloat() / barCount
        val touchedBarIndex = (x / barWidth).toInt().coerceIn(0, barCount - 1)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                updateFrequency(touchedBarIndex)
                startAudio()
                currentlyTouchedBar = touchedBarIndex
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchedBarIndex != currentlyTouchedBar) {
                    updateFrequency(touchedBarIndex)
                    currentlyTouchedBar = touchedBarIndex
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopAudio()
                currentlyTouchedBar = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFrequency(barIndex: Int) {
        val id = cardId ?: return
        if (barIndex >= id.size) return

        val byteValue = id[barIndex].toInt() and 0xFF
        val normalizedValue = byteValue / 255f
        val scaleIndex = (normalizedValue * (cMajorScale.size - 1)).roundToInt()

        // Set frequency directly for an immediate note change.
        frequency = cMajorScale[scaleIndex]
    }

    // --- Drawing Logic ---
    override fun onDraw(canvas: Canvas) {
        val id = cardId
        if (id == null || id.isEmpty() || width == 0 || height == 0) {
            canvas.drawColor(Color.TRANSPARENT)
            return
        }

        path.reset()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(path)
        canvas.drawColor("#2C2C2E".toColorInt())

        val barCount = id.size
        val barWidth = width.toFloat() / barCount
        paint.style = Paint.Style.FILL

        for (i in id.indices) {
            val byteValue = id[i].toInt() and 0xFF
            val barHeight = (byteValue / 255f) * height.toFloat()
            val left = i * barWidth
            val top = height - barHeight
            val right = left + barWidth
            val bottom = height.toFloat()

            val hue = (byteValue / 255f) * 120f
            val saturation = 0.9f
            val value = 0.85f
            paint.color = Color.HSVToColor(floatArrayOf(hue, saturation, value))
            canvas.drawRect(left, top, right, bottom, paint)

            if (i == currentlyTouchedBar) {
                paint.color = Color.argb(100, 255, 255, 255)
                canvas.drawRect(left, 0f, right, height.toFloat(), paint)
            }
        }
    }
}
