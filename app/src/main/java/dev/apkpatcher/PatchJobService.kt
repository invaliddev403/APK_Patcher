package dev.apkpatcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

class PatchJobService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    @Volatile
    private var activeJob = 0L
    private var jobSequence = 0L
    private var cancellation: OperationCancellation? = null
    @Volatile
    private var downloadHandle: ApkDownloader.Handle? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WorkflowStore.initialize(this)
        NotificationChannel(
            CHANNEL_ID,
            getString(R.string.job_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).also { notifications.createNotificationChannel(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelJob()
            return START_NOT_STICKY
        }
        if (activeJob != 0L || intent == null) return START_NOT_STICKY
        val jobId = ++jobSequence
        activeJob = jobId
        cancellation = OperationCancellation()
        showProgress(getString(R.string.preparing_job), null, true)
        executor.execute {
            try {
                when (intent.action) {
                    ACTION_DOWNLOAD -> download(jobId, intent)
                    ACTION_IMPORT -> import(jobId, Uri.parse(intent.getStringExtra(EXTRA_URI)))
                    ACTION_UPDATE_PATCHES -> updatePatches(jobId, intent)
                    ACTION_PATCH -> patch(jobId, intent)
                }
            } catch (error: Exception) {
                if (error !is CancellationException) {
                    Log.e("ApkPatcher", "Background job failed", error)
                    fail(jobId, error.message ?: getString(R.string.job_failed))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun download(jobId: Long, intent: Intent) {
        update(jobId) {
            it.copy(
                phase = WorkflowPhase.DOWNLOADING,
                status = getString(R.string.starting_download),
                progress = null,
                signatureConflict = false,
                installed = false,
                installRequested = false,
            )
        }
        downloadHandle = ApkDownloader(this).download(
            intent.getStringExtra(EXTRA_URL).orEmpty(),
            object : ApkDownloader.Listener {
                override fun onProgress(downloadedBytes: Long, totalBytes: Long) {
                    val progress = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt() else null
                    val status = if (totalBytes > 0) {
                        getString(
                            R.string.downloading_progress,
                            formatBytes(downloadedBytes),
                            formatBytes(totalBytes),
                        )
                    } else {
                        getString(R.string.downloading_unknown_size, formatBytes(downloadedBytes))
                    }
                    update(jobId) { it.copy(status = status, progress = progress) }
                }

                override fun onComplete(file: File) {
                    if (!isActive(jobId)) return
                    WorkflowStore.update {
                        WorkflowState(
                            selectedFile = file,
                            selectedFileName = file.name.substringAfter('-'),
                            selectedFileSize = file.length(),
                            status = getString(R.string.download_complete_ready),
                        )
                    }
                    finish(jobId, getString(R.string.download_complete))
                }

                override fun onError(message: String) = fail(jobId, message)
            },
            intent.getStringExtra(EXTRA_USER_AGENT),
            intent.getStringExtra(EXTRA_COOKIES),
        )
        if (!isActive(jobId)) downloadHandle?.cancel()
    }

    private fun import(jobId: Long, uri: Uri) {
        update(jobId) { it.copy(phase = WorkflowPhase.IMPORTING, status = getString(R.string.copying_file)) }
        var destination: File? = null
        try {
            val metadata = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use {
                if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
            }
            val displayName = metadata?.first ?: "selected.apk"
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120)
            require(safeName.substringAfterLast('.', "").lowercase() in ApkPatcher.SUPPORTED_EXTENSIONS) {
                "Choose an APK, APKM, APKS, or XAPK file."
            }
            val directory = File(cacheDir, "downloads")
            StorageSpace.requireAvailable(directory, StorageSpace.requiredDownloadBytes(metadata?.second ?: -1))
            destination = File(directory, "${System.currentTimeMillis()}-$safeName")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        cancellation?.check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalArgumentException("The selected file could not be opened.")
            cancellation?.check()
            val selected = destination!!
            if (!isActive(jobId)) return
            WorkflowStore.update {
                WorkflowState(
                    selectedFile = selected,
                    selectedFileName = selected.name.substringAfter('-'),
                    selectedFileSize = selected.length(),
                    status = getString(R.string.ready_to_patch),
                )
            }
            destination = null
            finish(jobId, getString(R.string.file_imported))
        } finally {
            destination?.delete()
        }
    }

    private fun updatePatches(jobId: Long, intent: Intent) {
        val input = File(intent.getStringExtra(EXTRA_INPUT).orEmpty())
        update(jobId) {
            it.copy(
                phase = WorkflowPhase.UPDATING_PATCHES,
                patchResult = null,
                status = getString(R.string.preparing_morphe_update),
                progress = null,
                installRequested = false,
                installed = false,
            )
        }
        val result = MorphePackUpdater(this).update(input, cancellation) { message ->
            update(jobId) { it.copy(status = message) }
        }
        if (!isActive(jobId)) return
        val generation = result.generation
        val message = getString(
            R.string.morphe_update_complete,
            generation.pack.id,
            generation.applied,
            generation.adjustedSignatures,
            generation.missing,
            generation.unsupported,
            generation.retainedBasePatches,
        )
        WorkflowStore.update {
            it.copy(phase = WorkflowPhase.IDLE, status = message, patchResult = null, installRequested = false)
        }
        finish(jobId, getString(R.string.morphe_update_saved))
    }

    private fun patch(jobId: Long, intent: Intent) {
        val input = File(intent.getStringExtra(EXTRA_INPUT).orEmpty())
        val installAfter = intent.getBooleanExtra(EXTRA_INSTALL_AFTER, false)
        update(jobId) {
            it.copy(
                phase = WorkflowPhase.PATCHING,
                patchResult = null,
                status = getString(R.string.preparing_patch),
                progress = null,
                signatureConflict = false,
                installed = false,
                installRequested = false,
            )
        }
        StorageSpace.requireAvailable(cacheDir, StorageSpace.requiredPatchBytes(input.length()))
        val result = ApkPatcher(this).patch(
            input,
            intent.getStringExtra(EXTRA_USER_ID)?.ifEmpty { null },
            intent.getStringExtra(EXTRA_USER_TOKEN)?.ifEmpty { null },
            cancellation,
        ) { message -> update(jobId) { it.copy(status = message) } }
        if (!isActive(jobId)) return
        WorkflowStore.update {
            it.copy(
                phase = WorkflowPhase.IDLE,
                patchResult = result,
                status = getString(
                    R.string.patch_complete,
                    result.patchedMethodCount,
                    result.profileId,
                    result.apks.size,
                    result.outputDirectory.absolutePath,
                ),
                installRequested = installAfter,
            )
        }
        finish(
            jobId,
            getString(if (installAfter) R.string.patch_ready_to_install else R.string.patch_finished),
        )
    }

    private fun fail(jobId: Long, message: String) {
        if (!isActive(jobId)) return
        WorkflowStore.update {
            it.copy(phase = WorkflowPhase.IDLE, status = message, progress = null, installRequested = false)
        }
        finish(jobId, message)
    }

    private fun cancelJob() {
        if (activeJob == 0L) return
        activeJob = ++jobSequence
        cancellation?.cancel()
        downloadHandle?.cancel()
        cancellation = null
        downloadHandle = null
        WorkflowStore.update {
            it.copy(
                phase = WorkflowPhase.IDLE,
                status = getString(R.string.operation_cancelled),
                progress = null,
                installRequested = false,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun update(jobId: Long, transform: (WorkflowState) -> WorkflowState) {
        if (!isActive(jobId)) return
        val updated = WorkflowStore.update(transform)
        showProgress(updated.status, updated.progress, false)
    }

    private fun isActive(jobId: Long) = activeJob == jobId

    private fun finish(jobId: Long, message: String) {
        if (!isActive(jobId)) return
        activeJob = 0
        cancellation = null
        downloadHandle = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.notify(
            NOTIFICATION_ID,
            notification(message, null, false)
                .setAutoCancel(true)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .build(),
        )
        stopSelf()
    }

    private fun showProgress(message: String, progress: Int?, initial: Boolean) {
        val notification = notification(message, progress, true).build()
        if (initial) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            notifications.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(message: String, progress: Int?, ongoing: Boolean): Notification.Builder {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(100, progress ?: 0, progress == null)
            .apply {
                if (ongoing) {
                    addAction(
                        Notification.Action.Builder(
                            null,
                            getString(R.string.cancel_operation),
                            PendingIntent.getService(
                                this@PatchJobService,
                                1,
                                Intent(this@PatchJobService, PatchJobService::class.java).setAction(ACTION_CANCEL),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                            ),
                        ).build(),
                    )
                }
            }
    }

    override fun onDestroy() {
        if (WorkflowStore.state.running) {
            cancellation?.cancel()
            downloadHandle?.cancel()
            WorkflowStore.update {
                it.copy(
                    phase = WorkflowPhase.IDLE,
                    status = getString(R.string.operation_interrupted),
                    progress = null,
                    installRequested = false,
                )
            }
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "patch_jobs"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_DOWNLOAD = "dev.apkpatcher.DOWNLOAD"
        private const val ACTION_IMPORT = "dev.apkpatcher.IMPORT"
        private const val ACTION_PATCH = "dev.apkpatcher.PATCH"
        private const val ACTION_UPDATE_PATCHES = "dev.apkpatcher.UPDATE_PATCHES"
        private const val ACTION_CANCEL = "dev.apkpatcher.CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_USER_AGENT = "userAgent"
        private const val EXTRA_COOKIES = "cookies"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_INPUT = "input"
        private const val EXTRA_USER_ID = "userId"
        private const val EXTRA_USER_TOKEN = "userToken"
        private const val EXTRA_INSTALL_AFTER = "installAfter"

        fun download(context: Context, url: String, userAgent: String? = null, cookies: String? = null) {
            start(context, Intent(context, PatchJobService::class.java).setAction(ACTION_DOWNLOAD).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_USER_AGENT, userAgent)
                putExtra(EXTRA_COOKIES, cookies)
            })
        }

        fun import(context: Context, uri: Uri) {
            start(context, Intent(context, PatchJobService::class.java).setAction(ACTION_IMPORT).apply {
                putExtra(EXTRA_URI, uri.toString())
            })
        }

        fun updatePatches(context: Context, input: File) {
            start(context, Intent(context, PatchJobService::class.java).setAction(ACTION_UPDATE_PATCHES).apply {
                putExtra(EXTRA_INPUT, input.absolutePath)
            })
        }

        fun patch(context: Context, input: File, userId: String, userToken: String, installAfter: Boolean) {
            start(context, Intent(context, PatchJobService::class.java).setAction(ACTION_PATCH).apply {
                putExtra(EXTRA_INPUT, input.absolutePath)
                putExtra(EXTRA_USER_ID, userId)
                putExtra(EXTRA_USER_TOKEN, userToken)
                putExtra(EXTRA_INSTALL_AFTER, installAfter)
            })
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, PatchJobService::class.java).setAction(ACTION_CANCEL),
            )
        }

        private fun start(context: Context, intent: Intent) {
            context.startForegroundService(intent)
        }
    }
}
