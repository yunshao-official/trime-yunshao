/*
 * 系统级语音识别服务（Android RecognitionService）
 *
 * 作用：让任何使用系统语音输入的 App（包括讯飞输入法、谷歌键盘、
 *       系统搜索框等）都可以改用 SenseVoice 离线模型。
 *
 * 工作方式：
 *   1. 系统把麦克风音频以 byte[] 缓冲区形式喂进来
 *   2. 累积到一定量就跑一次识别，回报 partialResults（实时字幕效果）
 *   3. onEndOfSpeech 时跑最后一次，回报最终 results
 */
package com.osfans.trime.ime.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
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
        /** 每累积这么多秒音频就做一次中间识别，回报实时结果 */
        private const val PARTIAL_INTERVAL_SEC = 1.2f
    }

    private val main = Handler(Looper.getMainLooper())
    private val working = AtomicBoolean(false)

    private var listener: RecognitionListener? = null
    private val audio = ByteArrayOutputStream()
    @Volatile private var lastPartialBytes = 0
    @Volatile private var stopped = false
    @Volatile private var cancelled = false
    @Volatile private var resultDelivered = false

    override fun onCreate() {
        super.onCreate()
        // 预热模型，避免第一次使用时的加载延迟
        YunshaoAsr.loadAsync(this) { ok ->
            Log.i(TAG, "preload recognizer: ok=$ok")
        }
    }

    override fun onStartListening(intent: Intent?, listener: RecognitionListener?) {
        this.listener = listener
        audio.reset()
        lastPartialBytes = 0
        stopped = false
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
        val ready = Bundle()
        // 声明需要的音频格式：PCM16 / 16kHz / 单声道
        ready.putInt(
            android.speech.SpeechRecognizer.EXTRA_AUDIO_SOURCE,
            0,
        )
        listener?.onReadyForSpeech(ready)
        listener?.onBeginningOfSpeech()
    }

    override fun onBufferReceived(buffer: ByteArray?) {
        if (buffer == null || buffer.isEmpty()) return
        if (!working.get()) return
        audio.write(buffer, 0, buffer.size)

        // 每积累够一段就跑一次中间识别
        val newBytes = audio.size() - lastPartialBytes
        if (newBytes >= (SAMPLE_RATE * 2 * PARTIAL_INTERVAL_SEC).toInt()) {
            lastPartialBytes = audio.size()
            val snapshot = audio.toByteArray()
            runPartial(snapshot)
        }
    }

    override fun onEndOfSpeech() {
        if (!working.get()) return
        stopped = true
        val snapshot = audio.toByteArray()
        runFinal(snapshot)
    }

    override fun onStopListening() {
        stopped = true
    }

    override fun onCancel() {
        cancelled = true
        working.set(false)
        listener?.onError(SpeechRecognizer.ERROR_CLIENT)
        listener = null
    }

    override fun onDestroy() {
        working.set(false)
        listener = null
        super.onDestroy()
    }

    override fun onCheckRecognitionSupport(intent: Intent?) {
        // 报告支持当前设备的默认语言
        val result = Bundle()
        val languages = ArrayList<String>()
        languages.add(java.util.Locale.getDefault().toLanguageTag())
        result.putStringArrayList(
            RecognitionService.SUPPORTED_LANGUAGES,
            languages,
        )
        main.post { listener?.onEvent(RecognitionService.EVENT_SUPPORT_CHECK_COMPLETE, result) }
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
                listener?.onPartialResults(b)
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
                    listener?.onError(SpeechRecognizer.ERROR_NO_MATCH)
                } else {
                    val b = Bundle()
                    b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                    listener?.onResults(b)
                }
                listener = null
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
            listener?.onError(code)
            listener = null
        }
    }
}
