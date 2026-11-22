/*
 * Transparent activity used only to request RECORD_AUDIO at runtime.
 * An InputMethodService cannot request dangerous permissions itself,
 * so the voice panel launches this to get the system dialog.
 */
package com.osfans.trime.ime.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class VoicePermissionActivity : ComponentActivity() {
    private val launcher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(
                this,
                if (granted) "录音权限已授予，回到输入法点「开始」即可" else "未授予录音权限，语音输入不可用",
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            Toast.makeText(this, "录音权限已授予", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
