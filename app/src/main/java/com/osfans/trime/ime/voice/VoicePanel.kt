/*
 * Yunshao offline voice input panel.
 * Builds a Dialog that the IME service shows via service.showDialog()
 * (which sets the correct window token; calling show() directly on a
 * Service context would crash with BadTokenException).
 */
package com.osfans.trime.ime.voice

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
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
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val main = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var levelFill: View
    private lateinit var levelTrack: LinearLayout
    private var isRecording = false
    private var isBusy = false

    /** Build the dialog. Caller must show it with IME service.showDialog(). */
    fun build(): Dialog {
        val d = Dialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog = d

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.parseColor("#1E1E22"))
        }

        statusText = TextView(ctx).apply {
            text = "正在加载离线模型…"
            setTextColor(Color.parseColor("#E8E8EC"))
            textSize = 14f
            gravity = Gravity.CENTER
        }
        root.addView(statusText, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = dp(6)))

        levelTrack = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(Color.parseColor("#2A2A30"))
            }
        }
        levelFill = View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(Color.parseColor("#4A9EFF"))
            }
        }
        levelTrack.addView(levelFill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(levelTrack, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(5), bottom = dp(6)))

        hintText = TextView(ctx).apply {
            text = "点“开始”说话，说完停顿自动结束"
            setTextColor(Color.parseColor("#9A9AA2"))
            textSize = 12f
            gravity = Gravity.CENTER
        }
        root.addView(hintText, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom = dp(8)))

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val btnStart = makeButton("开始", "#4A9EFF")
        val btnCancel = makeButton("取消", "#3A3A42")
        val p1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        p1.rightMargin = dp(8)
        val p2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        btnRow.addView(btnStart, p1)
        btnRow.addView(btnCancel, p2)
        root.addView(btnRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        d.setContentView(root)
        d.setCancelable(true)
        d.setOnDismissListener {
            recorder?.stop()
            recorder = null
            isRecording = false
        }

        btnCancel.setOnClickListener { d.dismiss() }
        btnStart.setOnClickListener { onStartOrStop(d, btnStart) }

        loadModel()
        return d
    }

    private fun loadModel() {
        isBusy = true
        post { statusText.text = "正在加载离线模型…" }
        YunshaoAsr.loadAsync(ctx) { ok ->
            post {
                isBusy = false
                if (ok) {
                    statusText.text = "模型就绪 · 全本地识别"
                    hintText.text = "点“开始”说话，说完停顿自动结束"
                } else {
                    statusText.text = "模型加载失败"
                    hintText.text = YunshaoAsr.getError() ?: "未知错误"
                }
            }
        }
    }

    private fun onStartOrStop(d: Dialog, btn: TextView) {
        if (isBusy) return
        if (isRecording) {
            recorder?.stop()
            return
        }
        startRecording(d, btn)
    }

    private fun startRecording(d: Dialog, btn: TextView) {
        if (!YunshaoAsr.isReady()) {
            statusText.text = "模型尚未就绪"
            hintText.text = YunshaoAsr.getError() ?: "请稍候再试"
            return
        }
        val rec = VoiceRecorder(ctx)
        if (!rec.hasPermission()) {
            statusText.text = "需要录音权限"
            hintText.text = "已打开授权页，允许后回来点「开始」"
            try {
                val it = android.content.Intent(ctx, VoicePermissionActivity::class.java)
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(it)
            } catch (e: Exception) {
                hintText.text = "请在系统设置中手动授予录音权限"
            }
            return
        }
        recorder = rec
        isRecording = true
        btn.text = "停止"
        statusText.text = "录音中… 请说话"
        hintText.text = "说完停顿约1秒自动结束"

        rec.start(object : VoiceRecorder.Listener {
            override fun onLevel(rms: Float) {
                post {
                    val p = (rms * 4f).coerceIn(0.02f, 1f)
                    val w = (levelTrack.width * p).toInt().coerceAtLeast(dp(2))
                    levelFill.layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            }

            override fun onFinished(samples: FloatArray, truncated: Boolean) {
                isRecording = false
                btn.text = "开始"
                YunshaoAsr.transcribeAsync(ctx, samples) { text ->
                    post {
                        if (text.isNullOrBlank()) {
                            statusText.text = "没听清，请再说一次"
                            hintText.text = if (truncated) "已达最长时长" else "点“开始”重试"
                        } else {
                            statusText.text = "识别完成"
                            hintText.text = text
                            onText(text)
                            d.dismiss()
                        }
                    }
                }
            }

            override fun onError(msg: String) {
                isRecording = false
                btn.text = "开始"
                post {
                    statusText.text = msg
                    hintText.text = "点“开始”重试"
                }
            }

            override fun onAutoStop() {
                post { statusText.text = "识别中…" }
            }
        })
    }

    private fun post(block: () -> Unit) {
        main.post { block() }
    }

    private fun makeButton(text: String, color: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor(color))
            }
        }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).also { it.bottomMargin = bottom }
}
