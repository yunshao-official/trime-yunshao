/*
 * 系统级语音识别服务（Android RecognitionService，Callback 形态）
 *
 * 作用：让任何使用系统语音输入的 App 都能改用 SenseVoice 离线模型。
 *   - 系统「语音输入」设置页会列出本服务
 *   - 输入法（如讯飞）如果走系统语音接口，也能选到本服务
 *
 * 音频契约：RecognitionService 以 byte[] 交付 PCM16 / 16kHz / 单声道。
 */
package com.osfans.trime.ime.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
        /** 每累积这么多秒音频做一次中间识别，回报实时字幕 */
        private const val PARTIAL_INTERVAL_SEC = 1.2f
    }

    private val main = Handler(Looper.getMainLooper())
    private val working = AtomicBoolean(false)

    private var callback: RecognitionService.Callback? = null
    private val audio = ByteArrayOutputStream()
    @Volatile private var lastPartialBytes = 0
    @Volatile private var cancelled = false
    @Volatile private var resultDelivered = false

    override fun onCreate() {
        super.onCreate()
        // 预热模型，避免首次使用时的加载延迟
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

        working.set(true)
        listener?.readyForSpeech(Bundle())
        listener?.beginningOfSpeech()
    }

    override fun onBufferReceived(buffer: ByteArray?) {
        if (buffer == null || buffer.isEmpty()) return
        if (!working.get()) return
        audio.write(buffer, 0, buffer.size)

        val newBytes = audio.size() - lastPartialBytes
        if (newBytes >= (SAMPLE_RATE * 2 * PARTIAL_INTERVAL_SEC).toInt()) {
            lastPartialBytes = audio.size()
            runPartial(audio.toByteArray())
        }
    }

    override fun onEndOfSpeech() {
        if (!working.get()) return
        if (resultDelivered) return
        runFinal(audio.toByteArray())
    }

    override fun onStopListening(listener: RecognitionService.Callback?) {
        // 客户端停止收听；如果还没给最终结果，补一次
        if (working.get() && !resultDelivered) {
            runFinal(audio.toByteArray())
        }
    }

    override fun onCancel(listener: RecognitionService.Callback?) {
        cancelled = true
        working.set(false)
        callback = null
    }

    override fun onDestroy() {
        working.set(false)
        callback = null
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

    /** PCM16 mono 16k -> FloatArray -> 识别 */
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
