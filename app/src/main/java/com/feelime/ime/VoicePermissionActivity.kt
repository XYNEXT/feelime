package com.feelime.ime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * 语音首用直弹系统录音权限（docs/design/userdata.md §2）。
 *
 * IME Service 不能 requestPermissions；以前无权限只 toast 指引用户去设置页，
 * 路径太深。这个透明壳替 Service 发起系统权限弹窗：授权后广播回 Service
 * 重试 startVoice，拒绝则维持原有的去设置页手动路径。
 *
 * transparent + no history：不进最近任务，弹窗消失即 finish，用户无感。
 */
class VoicePermissionActivity : Activity() {

    private var requested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        if (savedInstanceState != null) requested = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            notifyGranted()
            finish()
            return
        }
        if (!requested) {
            requested = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
        }
        // 被系统回收重建（requested=true）时不再二次弹窗；等用户再按一次麦克风。
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        if (requestCode != REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) notifyGranted() else sendDenied()
        finish()
    }

    private fun notifyGranted() {
        sendBroadcast(ACTION_VOICE_PERMISSION_GRANTED)
    }

    private fun sendDenied() {
        sendBroadcast(ACTION_VOICE_PERMISSION_DENIED)
    }

    private fun sendBroadcast(action: String) {
        // 收方注册为 NOT_EXPORTED，setPackage 限定同应用内投递。
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    companion object {
        private const val REQUEST_CODE = 4201
        const val ACTION_VOICE_PERMISSION_GRANTED = "com.feelime.ime.VOICE_PERMISSION_GRANTED"
        const val ACTION_VOICE_PERMISSION_DENIED = "com.feelime.ime.VOICE_PERMISSION_DENIED"
    }
}
