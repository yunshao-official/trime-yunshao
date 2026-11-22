/*
 * 聆星·云韶离线语音识别（FireRedASR2 CTC int8 + sherpa-onnx）
 * 2026-11-22 为知世定制：全本地、零联网、零隐私泄露
 */
package com.osfans.trime.ime.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

object YunshaoAsr {
    private const val TAG = "YunshaoAsr"
    private const val ASSET_DIR = "asr"
    private const val MODEL_FILE = "model.int8.onnx"
    private const val TOKENS_FILE = "tokens.txt"

    private var recognizer: OfflineRecognizer? = null
    private val loading = AtomicBoolean(false)
    @Volatile private var ready = false
    @Volatile private var lastError: String? = null

    fun isReady(): Boolean = ready && recognizer != null
    fun getError(): String? = lastError

    /** 模型存放目录：App 私有 files/asr（由 App 首次启动时从 assets 解出，或用户手动放入） */
    fun modelDir(ctx: Context): File = File(ctx.filesDir, ASSET_DIR)

    /** 首次使用：把 assets/asr 下的模型复制到 filesDir（若尚未存在） */
    fun ensureModel(ctx: Context): Boolean {
        val dir = modelDir(ctx)
        val model = File(dir, MODEL_FILE)
        val tokens = File(dir, TOKENS_FILE)
        if (model.exists() && tokens.exists() && model.length() > 100_000_000L) return true
        if (!dir.exists()) dir.mkdirs()
        return try {
            copyAsset(ctx, "$ASSET_DIR/$MODEL_FILE", model)
            copyAsset(ctx, "$ASSET_DIR/$TOKENS_FILE", tokens)
            true
        } catch (e: Exception) {
            Log.e(TAG, "ensureModel failed", e)
            lastError = "模型解压失败: " + e.message
            false
        }
    }

    private fun copyAsset(ctx: Context, assetPath: String, dest: File) {
        ctx.assets.open(assetPath).use { input ->
            FileOutputStream(dest).use { out ->
                input.copyTo(out, 1024 * 1024)
            }
        }
    }

    /** 后台加载识别器（耗时约 4-5 秒），完成后 ready=true */
    fun loadAsync(ctx: Context, onDone: (Boolean) -> Unit) {
        if (ready) { onDone(true); return }
        if (!loading.compareAndSet(false, true)) { onDone(false); return }
        Thread {
            try {
                if (!ensureModel(ctx)) { onDone(false); loading.set(false); return@Thread }
                val dir = modelDir(ctx)
                val config = OfflineRecognizerConfig(
                    featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        fireRedAsr = OfflineFireRedAsrCtcModelConfig(
                            model = File(dir, MODEL_FILE).absolutePath
                        ),
                        tokens = File(dir, TOKENS_FILE).absolutePath,
                        numThreads = 4,
                        debug = false
                    ),
                    decodingMethod = "greedy_search"
                )
                recognizer = OfflineRecognizer(config)
                ready = true
                Log.i(TAG, "recognizer ready")
                onDone(true)
            } catch (e: Exception) {
                Log.e(TAG, "loadAsync failed", e)
                lastError = "加载失败: " + e.message
                onDone(false)
            } finally {
                loading.set(false)
            }
        }.start()
    }

    /**
     * 识别一段 16kHz 单声道 float 采样（-1..1）。
     * 返回识别文字；失败或无结果返回 null。
     */
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String? {
        val rec = recognizer ?: return null
        return try {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, sampleRate)
            rec.decodeStream(stream)
            val text = stream.result.text.trim()
            stream.release()
            text.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "transcribe failed", e)
            lastError = "识别失败: " + e.message
            null
        }
    }

    fun release() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
        ready = false
    }
}
