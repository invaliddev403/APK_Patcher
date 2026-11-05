package dev.apkpatcher

import android.os.StatFs
import java.io.File

internal object StorageSpace {
    private const val PATCH_OVERHEAD = 64L * 1024 * 1024
    private const val DOWNLOAD_OVERHEAD = 8L * 1024 * 1024
    private const val UNKNOWN_DOWNLOAD_RESERVE = 64L * 1024 * 1024

    fun requiredPatchBytes(inputBytes: Long): Long = inputBytes * 4 + PATCH_OVERHEAD

    fun requiredDownloadBytes(totalBytes: Long): Long = if (totalBytes > 0) {
        totalBytes + DOWNLOAD_OVERHEAD
    } else {
        UNKNOWN_DOWNLOAD_RESERVE
    }

    fun requireAvailable(directory: File, requiredBytes: Long) {
        check(directory.exists() || directory.mkdirs()) { "Could not create the working directory." }
        val available = StatFs(directory.absolutePath).availableBytes
        if (available < requiredBytes) {
            throw IllegalStateException(
                "Not enough free storage. About ${formatBytes(requiredBytes)} is needed, " +
                    "but only ${formatBytes(available)} is available. Free some space and try again.",
            )
        }
    }
}
