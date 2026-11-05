package dev.apkpatcher

import android.content.Context
import android.os.Environment
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class PatchResult(
    val packageName: String,
    val versionName: String,
    val profileId: String,
    val outputDirectory: File,
    val apks: List<File>,
    val patchedMethodCount: Int,
)

internal class ApkPatcher(private val context: Context) {
    fun patch(
        input: File,
        userId: String?,
        userToken: String?,
        cancellation: OperationCancellation? = null,
        onProgress: (String) -> Unit,
    ): PatchResult {
        require(userId.isNullOrEmpty() == userToken.isNullOrEmpty()) {
            "User ID and token must be supplied together."
        }
        require(input.extension.lowercase() in SUPPORTED_EXTENSIONS) {
            "Choose an APK, APKM, APKS, or XAPK file."
        }

        val workDirectory = File(context.cacheDir, "patch-${System.currentTimeMillis()}")
        check(workDirectory.mkdirs()) { "Could not create a working directory." }
        try {
            onProgress("Preparing APK files…")
            cancellation?.check()
            val inputApks = ApkArchive().extract(input, workDirectory, cancellation)
            val info = runCatching {
                context.packageManager.getPackageArchiveInfo(inputApks.base.absolutePath, 0)
            }.getOrNull()
            val packageName = inputApks.packageName ?: info?.packageName
                ?: throw IllegalArgumentException("Could not determine the APK package name.")
            val versionName = inputApks.versionName ?: info?.versionName?.ifEmpty { null } ?: "unknown"
            val pack = PackRepository(context).select(packageName, versionName)

            onProgress("Patching ${pack.application.displayName} bytecode…")
            cancellation?.check()
            val profile = pack.resolve(mapOf(
                "userId" to userId.orEmpty(),
                "userToken" to userToken.orEmpty(),
            ))
            val dexResult = DexPatcher(inputApks.base, workDirectory, profile, cancellation).patch()

            onProgress("Rebuilding the base APK…")
            cancellation?.check()
            val unsignedBase = File(workDirectory, "base-unsigned.apk")
            rewriteApk(inputApks.base, unsignedBase, dexResult.replacementDexFiles, cancellation)

            val outputDirectory = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
                "patched/${pack.id}-$versionName",
            )
            outputDirectory.deleteRecursively()
            check(outputDirectory.mkdirs()) { "Could not create the output directory." }

            onProgress("Signing the patched APK…")
            cancellation?.check()
            val signedBase = File(outputDirectory, "base.apk")
            ApkPackageSigner(context).sign(unsignedBase, signedBase)

            val outputApks = mutableListOf(signedBase)
            inputApks.splits.forEachIndexed { index, split ->
                onProgress("Signing split ${index + 1} of ${inputApks.splits.size}…")
                cancellation?.check()
                val unsignedSplit = File(workDirectory, "split-$index-unsigned.apk")
                rewriteApk(split, unsignedSplit, emptyMap(), cancellation)
                val signedSplit = File(outputDirectory, split.name)
                ApkPackageSigner(context).sign(unsignedSplit, signedSplit)
                outputApks += signedSplit
            }
            cancellation?.check()

            return PatchResult(
                packageName,
                versionName,
                profile.id,
                outputDirectory,
                outputApks,
                dexResult.patchedMethods.size,
            )
        } finally {
            workDirectory.deleteRecursively()
        }
    }

    private fun rewriteApk(
        input: File,
        output: File,
        replacements: Map<String, File>,
        cancellation: OperationCancellation?,
    ) {
        ZipFile(input).use { source ->
            CountingOutputStream(BufferedOutputStream(FileOutputStream(output))).use { counted ->
                ZipOutputStream(counted).use { destination ->
                    val names = source.entries().toList().map { it.name }.toSet()
                    val entries = source.entries().toList().filter { !isOldSignature(it.name) } +
                        replacements.keys.filter { it !in names }.map { ZipEntry(it) }
                    for (entry in entries) {
                        cancellation?.check()
                        val replacement = replacements[entry.name]
                        if (entry.isDirectory) {
                            continue
                        }
                        val newEntry = ZipEntry(entry.name)
                        val bytes = if (replacement != null) {
                            replacement.readBytes()
                        } else if (entry.method == ZipEntry.STORED) {
                            source.getInputStream(entry).use { it.readBytes() }
                        } else {
                            null
                        }
                        if (entry.method == ZipEntry.STORED || replacement != null && entry.method == ZipEntry.STORED) {
                            val content = bytes ?: replacement?.readBytes()
                                ?: throw IllegalArgumentException("Could not read ${entry.name}")
                            newEntry.method = ZipEntry.STORED
                            newEntry.size = content.size.toLong()
                            newEntry.compressedSize = content.size.toLong()
                            newEntry.crc = CRC32().apply { update(content) }.value
                            val alignment = if (entry.name.endsWith(".so")) 4096 else 4
                            newEntry.extra = alignmentPadding(
                                counted.count + 30 + entry.name.toByteArray(Charsets.UTF_8).size,
                                alignment,
                            )
                        } else {
                            newEntry.method = ZipEntry.DEFLATED
                        }
                        destination.putNextEntry(newEntry)
                        if (bytes != null) {
                            destination.write(bytes)
                        } else {
                            source.getInputStream(entry).use { inputStream ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    cancellation?.check()
                                    val count = inputStream.read(buffer)
                                    if (count < 0) break
                                    destination.write(buffer, 0, count)
                                }
                            }
                        }
                        destination.closeEntry()
                    }
                }
            }
        }
    }

    private fun alignmentPadding(dataStartWithoutExtra: Long, alignment: Int): ByteArray? {
        val remainder = (dataStartWithoutExtra % alignment).toInt()
        if (remainder == 0) return null
        var padding = (alignment - remainder) % alignment
        if (padding in 1..3) padding += alignment
        val result = ByteArray(padding)
        result[0] = 0x35
        result[1] = 0xD9.toByte()
        val dataLength = padding - 4
        result[2] = (dataLength and 0xff).toByte()
        result[3] = ((dataLength shr 8) and 0xff).toByte()
        return result
    }

    private fun isOldSignature(name: String): Boolean {
        val upper = name.uppercase()
        if (!upper.startsWith("META-INF/")) return false
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") || upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") || upper.endsWith(".EC") ||
            upper.substringAfterLast('/').startsWith("SIG-")
    }

    private class CountingOutputStream(output: OutputStream) : OutputStream() {
        private val delegate = output
        var count = 0L
            private set

        override fun write(value: Int) {
            delegate.write(value)
            count++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            delegate.write(bytes, offset, length)
            count += length
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    companion object {
        val SUPPORTED_EXTENSIONS = setOf("apk", "apkm", "apks", "xapk")
    }
}
