/*
 * 系统级语音识别服务（Android RecognitionService，Callback 形态 / SDK 37）
 *
 * 与旧版 RecognitionService 不同，新 API 不再由系统喂音频，
 * 服务必须自己开麦录音。因此这里自行用 AudioRecord 采集
 * 16kHz / PCM16 / 单声道，并：
 *   - 同时喂给 SenseVoice 做识别
 *   - 通过 callback.bufferReceived() 把原始音频回报给客户端（供音量反馈等）
 *
 * 客户端（如系统「语音输入」、输入法）只需调用标准 SpeechRecognizer API。
 */
package com.osfans.trime.ime.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class YunshaoRecognitionService : RecognitionService() {
    companion object {
        private const val TAG = "YunshaoRecognizer"
        private const val SAMPLE_RATE = 16000
        private const val PARTIAL_INTERVAL_SEC = 1.2f
        private const val MAX_RECORD_MS = 60000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val working = AtomicBoolean(false)

    private var callback: RecognitionService.Callback? = null
    private var record: AudioRecord? = null
    private var recordThread: Thread? = null

    private val audio = ByteArrayOutputStream()
    @Volatile private var lastPartialBytes = 0
    @Volatile private var cancelled = false
    @Volatile private var resultDelivered = false
    @Volatile private var speakingDetected = false

    override fun onCreate() {
        super.onCreate()
        YunshaoAsr.loadAsync(this) { ok ->
            Log.i(TAG, "preload recognizer: ok=$ok")
        }
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: RecognitionService.Callback?) {
        this.callback = listener
        audio.reset()
        lastPartialBytes = 0
        cancelled = false
        resultDelivered = false
        speakingDetected = false

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            deliverError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        if (!YunshaoAsr.isReady()) {
            deliverError(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        listener?.readyForSpeech(Bundle())
        working.set(true)
        startRecording()
    }

    /** 自己开麦录音 */
    private fun startRecording() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            deliverError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        val bufSize = maxOf(minBuf * 2, SAMPLE_RATE * 2)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize,
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord failed", e)
            deliverError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            deliverError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        record = rec
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            record = null
            deliverError(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        recordThread = Thread {
            val shortBuf = ShortArray(bufSize / 2)
            var totalMs = 0L
            var silenceMs = 0L
            while (working.get() && !cancelled) {
                val n = rec.read(shortBuf, 0, shortBuf.size)
                if (n <= 0) continue
                totalMs += n * 1000L / SAMPLE_RATE

                val bytes = ByteArray(n * 2)
                var sum = 0.0
                for (i in 0 until n) {
                    val s = shortBuf[i]
                    bytes[i * 2] = (s.toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
                    val v = s / 32768f
                    sum += (v * v).toDouble()
                }
                audio.write(bytes, 0, bytes.size)

                // 把原始音频回报给客户端（音量反馈等）
                try {
                    callback?.bufferReceived(bytes)
                } catch (_: Exception) {}

                val rms = kotlin.math.sqrt(sum / n).toFloat()
                if (rms > 0.012f) {
                    if (!speakingDetected) {
                        speakingDetected = true
                        main.post { callback?.beginningOfSpeech() }
                    }
                    silenceMs = 0
                } else if (speakingDetected) {
                    silenceMs += n * 1000L / SAMPLE_RATE
                }

                // 中间识别
                val newBytes = audio.size() - lastPartialBytes
                if (newBytes >= (SAMPLE_RATE * 2 * PARTIAL_INTERVAL_SEC).toInt()) {
                    lastPartialBytes = audio.size()
                    val snapshot = audio.toByteArray()
                    runPartial(snapshot)
                }

                // 静音 1.6s 视为说完
                if (speakingDetected && silenceMs >= 1600L) {
                    main.post { callback?.endOfSpeech() }
                    runFinal(audio.toByteArray())
                    break
                }
                if (totalMs >= MAX_RECORD_MS) {
                    main.post { callback?.endOfSpeech() }
                    runFinal(audio.toByteArray())
                    break
                }
            }
            stopRecording()
        }.also { it.start() }
    }

    private fun stopRecording() {
        val rec = record
        record = null
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
    }

    /** 服务内部判定“用户说完了”，不是框架抽象方法 */
    private fun handleSpeechEnded() {
        if (!working.get()) return
        if (resultDelivered) return
        runFinal(audio.toByteArray())
    }

    override fun onStopListening(listener: RecognitionService.Callback?) {
        if (working.get() && !resultDelivered) {
            runFinal(audio.toByteArray())
        }
        working.set(false)
    }

    override fun onCancel(listener: RecognitionService.Callback?) {
        cancelled = true
        working.set(false)
        callback = null
        stopRecording()
    }

    override fun onDestroy() {
        cancelled = true
        working.set(false)
        callback = null
        stopRecording()
        super.onDestroy()
    }

    // ---------------- 识别 ----------------

    private fun runPartial(snapshot: ByteArray) {
        Thread {
            val text = recognize(snapshot) ?: return@Thread
            if (cancelled || resultDelivered) return@Thread
            main.post {
                if (cancelled || resultDelivered) return@post
                val b = Bundle()
                b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                callback?.partialResults(b)
            }
        }.start()
    }

    private fun runFinal(snapshot: ByteArray) {
        Thread {
            val text = recognize(snapshot)
            main.post {
                if (cancelled || resultDelivered) return@post
                resultDelivered = true
                working.set(false)
                if (text.isNullOrBlank()) {
                    callback?.error(SpeechRecognizer.ERROR_NO_MATCH)
                } else {
                    val b = Bundle()
                    b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                    callback?.results(b)
                }
                callback = null
            }
        }.start()
    }

    private fun recognize(pcm: ByteArray): String? {
        if (pcm.size < 2) return null
        val samples = FloatArray(pcm.size / 2)
        for (i in samples.indices) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return YunshaoAsr.transcribe(samples, SAMPLE_RATE)
    }

    private fun deliverError(code: Int) {
        main.post {
            callback?.error(code)
            callback = null
        }
    }
}
