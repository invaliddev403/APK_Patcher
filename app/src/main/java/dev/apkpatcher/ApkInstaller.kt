package dev.apkpatcher

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import java.io.File

class ApkInstaller(private val activity: Activity) {
    interface Listener {
        fun onWaitingForUser()
        fun onSuccess()
        fun onFailure(message: String, signatureConflict: Boolean)
    }

    private val action = "${activity.packageName}.INSTALL_STATUS"
    private var receiver: BroadcastReceiver? = null
    private var activeSessionId = -1

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun install(apks: List<File>, listener: Listener) {
        if (apks.isEmpty()) {
            listener.onFailure("There are no APK files to install.", false)
            return
        }
        val packageInstaller = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        activeSessionId = sessionId
        val session = packageInstaller.openSession(sessionId)
        try {
            apks.forEach { apk ->
                session.openWrite(apk.name, 0, apk.length()).use { output ->
                    apk.inputStream().buffered().use { it.copyTo(output) }
                    session.fsync(output)
                }
            }
        } catch (error: Exception) {
            session.close()
            packageInstaller.abandonSession(sessionId)
            throw error
        }

        val statusIntent = Intent(action).setPackage(activity.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            activity,
            sessionId,
            statusIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) return
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        listener.onWaitingForUser()
                        val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirmation != null) {
                            activity.startActivity(confirmation)
                        } else {
                            finish(false)
                            listener.onFailure("Android did not provide an install confirmation screen.", false)
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        finish(true)
                        listener.onSuccess()
                    }
                    else -> {
                        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            ?: "Android could not install the patched app."
                        finish(false)
                        listener.onFailure(
                            friendlyError(status, message),
                            status == PackageInstaller.STATUS_FAILURE_CONFLICT,
                        )
                    }
                }
            }

            private fun finish(success: Boolean) {
                if (!success) {
                    runCatching { packageInstaller.abandonSession(sessionId) }
                }
                activeSessionId = -1
                unregister()
            }
        }
        receiver = statusReceiver
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(statusReceiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(statusReceiver, IntentFilter(action))
        }
        session.commit(pendingIntent.intentSender)
        session.close()
    }

    private fun friendlyError(status: Int, original: String): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "The installed Citizen app uses a different signature. Uninstall it first, then install the patched APK. This will remove its local app data."
        PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "Installation is blocked. Allow APK Patcher to install unknown apps in Android Settings, then try again.\n\n$original"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation was cancelled."
        PackageInstaller.STATUS_FAILURE_STORAGE ->
            "There is not enough free storage to install the patched app."
        else -> original
    }

    private fun unregister() {
        receiver?.let {
            runCatching { activity.unregisterReceiver(it) }
            receiver = null
        }
    }

    fun close() {
        unregister()
    }
}
