/*
 * 聆星·云韶语音录音器
 * 录音 16kHz 单声道，边录边检测静音自动停止
 */
package com.osfans.trime.ime.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

class VoiceRecorder(private val ctx: Context) {
    companion object {
        private const val TAG = "VoiceRecorder"
        const val SAMPLE_RATE = 16000
        private const val SILENCE_RMS = 0.012f      // 静音阈值
        private const val SILENCE_MS = 1200L        // 连续静音多久自动停
        private const val MIN_SPEECH_MS = 300L      // 太短不算
        private const val MAX_RECORD_MS = 60000L    // 最长 60 秒
    }

    interface Listener {
        fun onLevel(rms: Float)                    // 实时音量（0..1），用于波形
        fun onFinished(samples: FloatArray, truncated: Boolean)
        fun onError(msg: String)
        fun onAutoStop()
    }

    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(listener: Listener) {
        if (running) return
        if (!hasPermission()) { listener.onError("缺少录音权限"); return }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf * 2, SAMPLE_RATE * 2) // 至少 1 秒缓冲
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: Exception) {
            listener.onError("录音器初始化失败: " + e.message); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            listener.onError("录音器未就绪（可能被占用）"); rec.release(); return
        }

        record = rec
        running = true
        rec.startRecording()

        thread = Thread {
            val shortBuf = ShortArray(bufSize / 2)
            val out = ByteArrayOutputStream()
            var speechMs = 0L
            var silenceMs = 0L
            var totalMs = 0L
            var speechStarted = false
            var truncated = false

            while (running) {
                val n = rec.read(shortBuf, 0, shortBuf.size)
                if (n <= 0) continue
                totalMs += n * 1000L / SAMPLE_RATE

                // 计算 RMS
                var sum = 0.0
                for (i in 0 until n) { val v = shortBuf[i] / 32768f; sum += (v * v).toDouble() }
                val rms = sqrt(sum / n).toFloat()
                listener.onLevel(rms.coerceIn(0f, 1f))

                // 写字节
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val s = shortBuf[i]
                    bytes[i * 2] = (s.toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
                }
                out.write(bytes)

                if (rms > SILENCE_RMS) {
                    speechStarted = true
                    speechMs += n * 1000L / SAMPLE_RATE
                    silenceMs = 0
                } else if (speechStarted) {
                    silenceMs += n * 1000L / SAMPLE_RATE
                    if (silenceMs >= SILENCE_MS) { listener.onAutoStop(); break }
                }

                if (totalMs >= MAX_RECORD_MS) { truncated = true; break }
            }

            running = false
            try { rec.stop() } catch (_: Exception) {}
            try { rec.release() } catch (_: Exception) {}
            record = null

            val bytes = out.toByteArray()
            val samples = FloatArray(bytes.size / 2)
            for (i in samples.indices) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                samples[i] = ((hi shl 8) or lo).toShort() / 32768f
            }

            if (speechMs < MIN_SPEECH_MS) listener.onError("说话时间太短")
            else listener.onFinished(samples, truncated)
        }.also { it.start() }
    }

    fun stop() { running = false }

    fun isRunning(): Boolean = running
}
