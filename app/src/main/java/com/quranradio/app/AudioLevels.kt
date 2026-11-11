package com.quranradio.app

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * يقرأ الصوت الفعلي الخارج من المشغّل ويحسب مستوى 9 نطاقات ترددية (FFT)
 * لتتحرك أعمدة الصوت في الواجهة مع الصوت الحقيقي — بدون أي صلاحيات إضافية.
 */
object AudioLevels {
    const val BANDS = 9
    @Volatile var levels = FloatArray(BANDS)
    @Volatile var updatedAt = 0L

    fun snapshot(): String {
        val fresh = System.currentTimeMillis() - updatedAt < 400
        val l = levels
        return (0 until BANDS).joinToString(",") { if (fresh) "%.2f".format(java.util.Locale.US, l[it]) else "0" }
    }
}

@OptIn(UnstableApi::class)
class LevelSink : TeeAudioProcessor.AudioBufferSink {
    private val n = 1024
    private val win = FloatArray(n)
    private var pos = 0
    private var rate = 44100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val smooth = FloatArray(AudioLevels.BANDS)
    // حدود النطاقات بالهرتز
    private val edges = intArrayOf(50, 110, 220, 420, 800, 1500, 2800, 5000, 8500, 14000)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = sampleRateHz; channels = max(1, channelCount); this.encoding = encoding; pos = 0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> while (b.remaining() >= 2 * channels) {
                var s = 0f
                repeat(channels) { s += b.short / 32768f }
                push(s / channels)
            }
            C.ENCODING_PCM_FLOAT -> while (b.remaining() >= 4 * channels) {
                var s = 0f
                repeat(channels) { s += b.float }
                push(s / channels)
            }
            else -> return
        }
    }

    private fun push(v: Float) {
        win[pos++] = v
        if (pos == n) { analyze(); pos = 0 }
    }

    private fun analyze() {
        for (i in 0 until n) { // نافذة هان
            re[i] = win[i] * (0.5 - 0.5 * cos(2 * Math.PI * i / (n - 1))); im[i] = 0.0
        }
        fft()
        val binHz = rate.toDouble() / n
        val out = FloatArray(AudioLevels.BANDS)
        for (k in 0 until AudioLevels.BANDS) {
            val a = max(1, (edges[k] / binHz).toInt()); val z = minOf(n / 2 - 1, max(a + 1, (edges[k + 1] / binHz).toInt()))
            var sum = 0.0
            for (i in a until z) sum += hypot(re[i], im[i])
            val mag = sum / (z - a)
            val db = 20 * ln(mag + 1e-9) / ln(10.0)      // تقريباً من -60 إلى +30
            var lv = ((db + 10) / 45).toFloat().coerceIn(0f, 1f)
            lv = if (lv > smooth[k]) lv else smooth[k] * 0.82f + lv * 0.18f // صعود سريع وهبوط هادئ
            smooth[k] = lv; out[k] = lv
        }
        AudioLevels.levels = out
        AudioLevels.updatedAt = System.currentTimeMillis()
    }

    private fun fft() { // Cooley–Tukey
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
