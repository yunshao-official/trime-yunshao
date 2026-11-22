/*
 * 聆星·云韶离线语音识别（SenseVoice int8 + sherpa-onnx）
 * 模型查找顺序：外部私有目录 > 内部私有目录 > assets
 * 外部路径：/sdcard/Android/data/com.osfans.trime/files/asr/
 */
package com.osfans.trime.ime.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

object YunshaoAsr {
    private const val TAG = "YunshaoAsr"
    private const val DIR_NAME = "asr"
    private const val MODEL_FILE = "model.int8.onnx"
    private const val TOKENS_FILE = "tokens.txt"
    private const val MIN_MODEL_BYTES = 10_000_000L

    private var recognizer: OfflineRecognizer? = null
    private val loading = AtomicBoolean(false)
    @Volatile private var ready = false
    @Volatile private var lastError: String? = null

    fun isReady(): Boolean = ready && recognizer != null
    fun getError(): String? = lastError

    /** 外部私有目录（用户可直接放文件），Android/data/<pkg>/files/asr */
    fun externalDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), DIR_NAME)

    /** 内部私有目录（assets 解压目标） */
    fun internalDir(ctx: Context): File = File(ctx.filesDir, DIR_NAME)

    /** 找出可用的模型目录 */
    fun resolveDir(ctx: Context): File? {
        for (d in listOf(externalDir(ctx), internalDir(ctx))) {
            val m = File(d, MODEL_FILE)
            val t = File(d, TOKENS_FILE)
            if (m.exists() && t.exists() && m.length() > MIN_MODEL_BYTES) return d
        }
        return null
    }

    /** 首次运行：若外部/内部都没有，尝试从 assets 解压到内部目录 */
    fun ensureModel(ctx: Context): Boolean {
        if (resolveDir(ctx) != null) return true
        val dir = internalDir(ctx)
        if (!dir.exists()) dir.mkdirs()
        return try {
            copyAsset(ctx, "$DIR_NAME/$MODEL_FILE", File(dir, MODEL_FILE))
            copyAsset(ctx, "$DIR_NAME/$TOKENS_FILE", File(dir, TOKENS_FILE))
            true
        } catch (e: Exception) {
            Log.e(TAG, "ensureModel failed (no assets, no external model)", e)
            lastError = "未找到模型。请把 model.int8.onnx 和 tokens.txt 放到：" +
                externalDir(ctx).absolutePath
            false
        }
    }

    private fun copyAsset(ctx: Context, assetPath: String, dest: File) {
        ctx.assets.open(assetPath).use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out, 1024 * 1024) }
        }
    }

    fun loadAsync(ctx: Context, onDone: (Boolean) -> Unit) {
        if (ready) { onDone(true); return }
        if (!loading.compareAndSet(false, true)) { onDone(false); return }
        Thread {
            try {
                if (!ensureModel(ctx)) { onDone(false); loading.set(false); return@Thread }
                val dir = resolveDir(ctx) ?: run { onDone(false); loading.set(false); return@Thread }
                Log.i(TAG, "loading model from " + dir.absolutePath)
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = File(dir, MODEL_FILE).absolutePath,
                            useInverseTextNormalization = true
                        ),
                        tokens = File(dir, TOKENS_FILE).absolutePath,
                        numThreads = 4,
                        debug = false
                    ),
                    decodingMethod = "greedy_search"
                )
                recognizer = OfflineRecognizer(ctx.assets, config)
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

    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String? {
        val rec = recognizer ?: return null
        return try {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, sampleRate)
            rec.decode(stream)
            val result = rec.getResult(stream)
            val text = result.text.trim()
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
