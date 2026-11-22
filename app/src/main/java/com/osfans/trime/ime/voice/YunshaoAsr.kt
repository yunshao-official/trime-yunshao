/*
 * 聆星·云韶离线语音识别（SenseVoice int8 + sherpa-onnx）
 * 模型查找顺序（依次尝试）：
 *   1. /sdcard/Android/media/com.osfans.trime/asr/    ← 公共共享目录，用户可直接放
 *   2. /sdcard/Download/trime-asr/                    ← 备用
 *   3. <App内部>/files/asr/                            ← assets 解压目标
 *   4. assets/asr/                                     ← 打包进 APK 时
 */
package com.osfans.trime.ime.voice

import android.content.Context
import android.os.Environment
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
    private const val PKG = "com.osfans.trime"

    private var recognizer: OfflineRecognizer? = null
    private val loading = AtomicBoolean(false)
    @Volatile private var ready = false
    @Volatile private var lastError: String? = null

    fun isReady(): Boolean = ready && recognizer != null
    fun getError(): String? = lastError

    /** App 内部私有目录（assets 解压目标，永远可读） */
    fun internalDir(ctx: Context): File = File(ctx.filesDir, DIR_NAME)

    /** 候选外部目录（按优先级），都可被用户直接放文件或文件管理器拷进去 */
    fun candidateDirs(ctx: Context): List<File> {
        val list = mutableListOf<File>()
        // 1. Android/media/<pkg>/asr —— 特殊媒体目录，其他 App 可读写
        try {
            val media = File(Environment.getExternalStorageDirectory(), "Android/media/$PKG/$DIR_NAME")
            list.add(media)
        } catch (_: Exception) {}
        // 2. /sdcard/Download/trime-asr
        try {
            val dl = File(Environment.getExternalStorageDirectory(), "Download/trime-asr")
            list.add(dl)
        } catch (_: Exception) {}
        // 3. App 私有外部目录
        try {
            ctx.getExternalFilesDir(null)?.let { list.add(File(it, DIR_NAME)) }
        } catch (_: Exception) {}
        // 4. App 私有内部目录
        list.add(internalDir(ctx))
        return list
    }

    /** 找到第一个含有效模型的目录 */
    fun resolveDir(ctx: Context): File? {
        for (d in candidateDirs(ctx)) {
            val m = File(d, MODEL_FILE)
            val t = File(d, TOKENS_FILE)
            if (m.exists() && t.exists() && m.length() > MIN_MODEL_BYTES) {
                Log.i(TAG, "model dir found: " + d.absolutePath)
                return d
            }
        }
        return null
    }

    /** 首次运行：外部都没有时，尝试从 assets 解压到内部目录 */
    fun ensureModel(ctx: Context): Boolean {
        if (resolveDir(ctx) != null) return true
        val dir = internalDir(ctx)
        if (!dir.exists()) dir.mkdirs()
        return try {
            copyAsset(ctx, "$DIR_NAME/$MODEL_FILE", File(dir, MODEL_FILE))
            copyAsset(ctx, "$DIR_NAME/$TOKENS_FILE", File(dir, TOKENS_FILE))
            true
        } catch (e: Exception) {
            Log.e(TAG, "ensureModel failed", e)
            lastError = buildString {
                append("未找到语音模型。请把两个文件放到以下任一目录：\n")
                candidateDirs(ctx).take(2).forEach { append("  " + it.absolutePath + "\n") }
                append("  需要：$MODEL_FILE (227MB) 和 $TOKENS_FILE")
            }
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
                Log.i(TAG, "loading from " + dir.absolutePath)
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

    /** Run recognition on a background thread and deliver the result on main. */
    fun transcribeAsync(ctx: Context, samples: FloatArray, onResult: (String?) -> Unit) {
        Thread {
            val text = try {
                transcribe(samples)
            } catch (e: Exception) {
                Log.e(TAG, "transcribeAsync failed", e)
                lastError = "识别失败: " + e.message
                null
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(text) }
        }.start()
    }

    fun release() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
        ready = false
    }
}
