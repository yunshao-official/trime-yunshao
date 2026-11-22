/*
 * 聆星·云韶语音输入面板
 * 点语音键弹出：加载模型 → 录音 → 识别 → 上屏
 */
package com.osfans.trime.ime.voice

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoicePanel(
    private val ctx: Context,
    private val onText: (String) -> Unit,
) {
    private var dialog: Dialog? = null
    private var recorder: VoiceRecorder? = null
    private var scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var statusText: TextView
    private lateinit var levelBar: View
    private lateinit var hintText: TextView
    private var isRecording = false
    private var isBusy = false

    fun show() {
        if (dialog?.isShowing == true) return
        val d = Dialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog = d

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(Color.parseColor("#1E1E22"))
        }

        statusText = TextView(ctx).apply {
            text = "准备中…"
            setTextColor(Color.parseColor("#E8E8EC"))
            textSize = 16f
            gravity = Gravity.CENTER
        }
        root.addView(statusText, lp(match(), wrap(), bottom = dp(10)))

        // 音量条（简单的水平进度）
        levelBar = View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.parseColor("#4A9EFF"))
            }
        }
        val barBg = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.parseColor("#2A2A30"))
            }
            addView(levelBar, LinearLayout.LayoutParams(0, match()))
        }
        root.addView(barBg, lp(match(), dp(6), bottom = dp(12)))

        hintText = TextView(ctx).apply {
            text = "点“开始”说话，说完自动结束"
            setTextColor(Color.parseColor("#9A9AA2"))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        root.addView(hintText, lp(match(), wrap(), bottom = dp(14)))

        // 按钮行
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val btnStart = makeButton("开始", "#4A9EFF")
        val btnCancel = makeButton("取消", "#3A3A42")
        btnRow.addView(btnStart, lp(wrap(), wrap(), right = dp(12)).also { it.weight = 1f; it.width = 0 })
        btnRow.addView(btnCancel, lp(wrap(), wrap()).also { it.weight = 1f; it.width = 0 })
        root.addView(btnRow, lp(match(), wrap()))

        d.setContentView(root)
        d.setCancelable(true)
        d.setOnDismissListener {
            recorder?.stop(); recorder = null
            isRecording = false
        }

        d.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        d.show()

        btnCancel.setOnClickListener { d.dismiss() }
        btnStart.setOnClickListener {
            if (isBusy) return@setOnClickListener
            if (isRecording) {
                recorder?.stop()
                statusText.text = "识别中…"
                hintText.text = "请不要关闭"
            } else startFlow()
        }

        // 启动时先加载模型
        loadModel()
    }

    private fun loadModel() {
        isBusy = true
        statusText.text = "加载离线模型（首次约5秒）…"
        YunshaoAsr.loadAsync(ctx) { ok ->
            scope.launch(Dispatchers.Main) {
                isBusy = false
                if (ok) {
                    statusText.text = "模型就绪 · 全本地识别"
                    hintText.text = "点“开始”说话，说完自动结束"
                } else {
                    statusText.text = "模型加载失败"
                    hintText.text = YunshaoAsr.getError() ?: "请确认 assets/asr 下有模型文件"
                }
            }
        }
    }

    private fun startFlow() {
        if (!YunshaoAsr.isReady()) {
            statusText.text = "模型尚未就绪，请稍候"
            return
        }
        val rec = VoiceRecorder(ctx)
        if (!rec.hasPermission()) {
            statusText.text = "缺少录音权限"
            hintText.text = "请在系统设置里给同文输入法录音权限"
            return
        }
        recorder = rec
        isRecording = true
        statusText.text = "录音中… 请说话"
        hintText.text = "说完停顿约1秒自动结束"

        rec.start(object : VoiceRecorder.Listener {
            override fun onLevel(rms: Float) {
                scope.launch(Dispatchers.Main) {
                    val p = (rms * 4f).coerceIn(0.02f, 1f)
                    val parent = levelBar.parent as? LinearLayout ?: return@launch
                    val w = (parent.width * p).toInt().coerceAtLeast(4)
                    levelBar.layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            }
            override fun onFinished(samples: FloatArray, truncated: Boolean) {
                isRecording = false
                scope.launch {
                    statusText.text = "识别中…"
                    val text = withContext(Dispatchers.Default) {
                        YunshaoAsr.transcribe(samples)
                    }
                    if (text.isNullOrBlank()) {
                        statusText.text = "没听清，请再说一次"
                        hintText.text = if (truncated) "已达最长时长" else "点“开始”重试"
                    } else {
                        statusText.text = "识别完成"
                        hintText.text = text.take(60)
                        onText(text)
                        dialog?.dismiss()
                    }
                }
            }
            override fun onError(msg: String) {
                isRecording = false
                scope.launch {
                    statusText.text = msg
                    hintText.text = "点“开始”重试"
                }
            }
            override fun onAutoStop() {
                scope.launch { statusText.text = "识别中…" }
            }
        })
    }

    private fun makeButton(text: String, color: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(color))
            }
        }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    private fun match() = ViewGroup.LayoutParams.MATCH_PARENT
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).also { it.setMargins(left, top, right, bottom) }
}
