package dev.apkpatcher

import android.app.Activity
import android.net.Uri
import java.io.File

class PatchWorkflowController(
    private val activity: Activity,
    private val listener: Listener,
) {
    interface Listener {
        fun onStateChanged(state: WorkflowState)
        fun onError(message: String)
    }

    private val installer = ApkInstaller(activity)
    private var closed = false
    private val stateListener: (WorkflowState) -> Unit = { updated ->
        activity.runOnUiThread {
            if (closed) return@runOnUiThread
            listener.onStateChanged(updated)
            if (updated.installRequested && !updated.running && updated.patchResult != null) {
                WorkflowStore.update { it.copy(installRequested = false) }
                install()
            }
        }
    }

    val state: WorkflowState get() = WorkflowStore.state

    fun initialize() {
        WorkflowStore.initialize(activity)
        WorkflowStore.addListener(stateListener)
        if (state.status.isEmpty()) {
            WorkflowStore.update { it.copy(status = activity.getString(R.string.select_apk_begin)) }
        }
    }

    fun download(url: String) {
        if (state.running) return
        if (url.isEmpty()) {
            listener.onError(activity.getString(R.string.url_required))
            return
        }
        PatchJobService.download(activity, url)
    }

    fun importFile(uri: Uri) {
        if (!state.running) PatchJobService.import(activity, uri)
    }

    fun selectFile(file: File, downloaded: Boolean = false) {
        if (state.running) return
        if (!file.isFile || file.length() == 0L) {
            listener.onError(activity.getString(R.string.file_read_failed))
            return
        }
        WorkflowStore.update {
            WorkflowState(
                selectedFile = file,
                selectedFileName = file.name.substringAfter('-'),
                selectedFileSize = file.length(),
                status = activity.getString(
                    if (downloaded) R.string.download_complete_ready else R.string.ready_to_patch,
                ),
            )
        }
    }

    fun patch(userId: String, userToken: String, installAfter: Boolean) {
        if (state.running) return
        val input = state.selectedFile
        if (input == null) {
            listener.onError(activity.getString(R.string.apk_required))
            return
        }
        if (userId.isEmpty() != userToken.isEmpty()) {
            listener.onError(activity.getString(R.string.credentials_pair_required))
            return
        }
        PatchJobService.patch(activity, input, userId, userToken, installAfter)
    }

    fun updatePatches() {
        if (state.running) return
        val input = state.selectedFile
        if (input == null) {
            listener.onError(activity.getString(R.string.apk_required))
            return
        }
        PatchJobService.updatePatches(activity, input)
    }

    fun install() {
        if (state.running) return
        val result = state.patchResult ?: return
        WorkflowStore.update {
            it.copy(
                phase = WorkflowPhase.INSTALLING,
                status = activity.getString(R.string.preparing_install),
                installRequested = false,
                signatureConflict = false,
                installed = false,
            )
        }
        try {
            installer.install(result.apks, object : ApkInstaller.Listener {
                override fun onWaitingForUser() {
                    WorkflowStore.update { it.copy(status = activity.getString(R.string.confirm_install)) }
                }

                override fun onSuccess() {
                    WorkflowStore.update {
                        it.copy(
                            phase = WorkflowPhase.IDLE,
                            status = activity.getString(R.string.install_success, result.versionName),
                            installed = true,
                            signatureConflict = false,
                        )
                    }
                }

                override fun onFailure(message: String, signatureConflict: Boolean) {
                    WorkflowStore.update {
                        it.copy(
                            phase = WorkflowPhase.IDLE,
                            status = message,
                            signatureConflict = signatureConflict,
                        )
                    }
                    listener.onError(message)
                }
            })
        } catch (error: Exception) {
            val message = error.message ?: activity.getString(R.string.install_start_failed)
            WorkflowStore.update { it.copy(phase = WorkflowPhase.IDLE, status = message) }
            listener.onError(message)
        }
    }

    fun cancelOperation() {
        PatchJobService.cancel(activity)
    }

    fun close() {
        closed = true
        WorkflowStore.removeListener(stateListener)
        installer.close()
    }
}
