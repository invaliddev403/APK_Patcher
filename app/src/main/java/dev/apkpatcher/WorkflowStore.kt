package dev.apkpatcher

import android.content.Context
import android.content.SharedPreferences
import java.io.File

internal object WorkflowStore {
    private val lock = Any()
    private val listeners = mutableSetOf<(WorkflowState) -> Unit>()
    private var preferences: SharedPreferences? = null

    @Volatile
    var state = WorkflowState()
        private set

    fun initialize(context: Context) {
        synchronized(lock) {
            if (preferences != null) return
            preferences = context.applicationContext.getSharedPreferences("workflow", Context.MODE_PRIVATE)
            state = readState(context, preferences!!)
        }
    }

    fun addListener(listener: (WorkflowState) -> Unit) {
        synchronized(lock) { listeners += listener }
        listener(state)
    }

    fun removeListener(listener: (WorkflowState) -> Unit) {
        synchronized(lock) { listeners -= listener }
    }

    fun update(transform: (WorkflowState) -> WorkflowState): WorkflowState {
        val updated: WorkflowState
        val currentListeners: List<(WorkflowState) -> Unit>
        synchronized(lock) {
            updated = transform(state)
            state = updated
            writeState(updated)
            currentListeners = listeners.toList()
        }
        currentListeners.forEach { it(updated) }
        return updated
    }

    private fun readState(context: Context, preferences: SharedPreferences): WorkflowState {
        val selected = preferences.getString(SELECTED_PATH, null)?.let(::File)?.takeIf { it.isFile }
        val outputDirectory = preferences.getString(OUTPUT_DIRECTORY, null)?.let(::File)
        val apkPaths = preferences.getStringSet(APK_PATHS, null).orEmpty().sorted().map(::File)
        val result = if (outputDirectory != null && apkPaths.isNotEmpty() && apkPaths.all { it.isFile }) {
            PatchResult(
                preferences.getString(PACKAGE_NAME, null) ?: BuiltInPatchPacks.CITIZEN_PACKAGE_NAME,
                preferences.getString(VERSION_NAME, null) ?: "unknown",
                preferences.getString(PROFILE_ID, null) ?: BuiltInPatchPacks.CITIZEN_PROFILE_ID,
                outputDirectory,
                apkPaths,
                preferences.getInt(PATCHED_COUNT, 0),
            )
        } else null
        val savedPhase = runCatching {
            WorkflowPhase.valueOf(preferences.getString(PHASE, null) ?: WorkflowPhase.IDLE.name)
        }.getOrDefault(WorkflowPhase.IDLE)
        val interrupted = savedPhase != WorkflowPhase.IDLE
        return WorkflowState(
            phase = WorkflowPhase.IDLE,
            selectedFile = selected,
            selectedFileName = preferences.getString(SELECTED_NAME, null),
            selectedFileSize = preferences.getLong(SELECTED_SIZE, 0),
            patchResult = result,
            status = if (interrupted) {
                context.getString(R.string.operation_interrupted)
            } else {
                preferences.getString(STATUS, null) ?: context.getString(R.string.select_apk_begin)
            },
            signatureConflict = preferences.getBoolean(SIGNATURE_CONFLICT, false),
            installed = preferences.getBoolean(INSTALLED, false),
            installRequested = preferences.getBoolean(INSTALL_REQUESTED, false) && !interrupted,
        )
    }

    private fun writeState(state: WorkflowState) {
        preferences?.edit()?.apply {
            putString(PHASE, state.phase.name)
            putString(SELECTED_PATH, state.selectedFile?.absolutePath)
            putString(SELECTED_NAME, state.selectedFileName)
            putLong(SELECTED_SIZE, state.selectedFileSize)
            putString(STATUS, state.status)
            putInt(PROGRESS, state.progress ?: -1)
            putBoolean(INSTALLED, state.installed)
            putBoolean(SIGNATURE_CONFLICT, state.signatureConflict)
            putBoolean(INSTALL_REQUESTED, state.installRequested)
            if (state.patchResult == null) {
                remove(PACKAGE_NAME)
                remove(VERSION_NAME)
                remove(PROFILE_ID)
                remove(OUTPUT_DIRECTORY)
                remove(APK_PATHS)
                remove(PATCHED_COUNT)
            } else {
                val result = state.patchResult
                putString(PACKAGE_NAME, result.packageName)
                putString(VERSION_NAME, result.versionName)
                putString(PROFILE_ID, result.profileId)
                putString(OUTPUT_DIRECTORY, result.outputDirectory.absolutePath)
                putStringSet(APK_PATHS, result.apks.map { it.absolutePath }.toSet())
                putInt(PATCHED_COUNT, result.patchedMethodCount)
            }
        }?.apply()
    }

    private const val PHASE = "phase"
    private const val SELECTED_PATH = "selectedPath"
    private const val SELECTED_NAME = "selectedName"
    private const val SELECTED_SIZE = "selectedSize"
    private const val STATUS = "status"
    private const val PROGRESS = "progress"
    private const val INSTALLED = "installed"
    private const val SIGNATURE_CONFLICT = "signatureConflict"
    private const val PACKAGE_NAME = "packageName"
    private const val VERSION_NAME = "versionName"
    private const val PROFILE_ID = "profileId"
    private const val OUTPUT_DIRECTORY = "outputDirectory"
    private const val APK_PATHS = "apkPaths"
    private const val PATCHED_COUNT = "patchedCount"
    private const val INSTALL_REQUESTED = "installRequested"
}
