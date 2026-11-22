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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class VoicePermissionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "录音权限已授予", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQ_CODE,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE) {
            val ok = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (ok) "录音权限已授予，回到输入法点「开始」即可" else "未授予录音权限，语音输入不可用",
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }

    companion object {
        private const val REQ_CODE = 4101
    }
}
