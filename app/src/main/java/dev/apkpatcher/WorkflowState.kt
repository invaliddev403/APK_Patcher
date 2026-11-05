package dev.apkpatcher

import java.io.File

enum class WorkflowPhase {
    IDLE,
    DOWNLOADING,
    IMPORTING,
    UPDATING_PATCHES,
    PATCHING,
    INSTALLING,
}

data class WorkflowState(
    val phase: WorkflowPhase = WorkflowPhase.IDLE,
    val selectedFile: File? = null,
    val selectedFileName: String? = null,
    val selectedFileSize: Long = 0,
    val patchResult: PatchResult? = null,
    val status: String = "",
    val progress: Int? = null,
    val signatureConflict: Boolean = false,
    val installed: Boolean = false,
    val installRequested: Boolean = false,
) {
    val running: Boolean get() = phase != WorkflowPhase.IDLE
}
