package dev.apkpatcher

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

internal data class InputApks(
    val base: File,
    val splits: List<File>,
    val packageName: String? = null,
    val versionName: String? = null,
)

internal class ApkArchive {
    fun extract(
        input: File,
        workDirectory: File,
        cancellation: OperationCancellation? = null,
    ): InputApks {
        if (input.extension.equals("apk", ignoreCase = true)) {
            return InputApks(input, emptyList())
        }

        val metadata = readMetadata(input)
        var metadataBaseName: String? = null
        metadata?.optJSONArray("split_apks")?.let { splitApks ->
            for (index in 0 until splitApks.length()) {
                val split = splitApks.optJSONObject(index)
                if (split?.optString("id") == "base") {
                    metadataBaseName = split.optString("file")
                }
            }
        }
        val extractedDirectory = File(workDirectory, "input").apply { mkdirs() }
        val apks = mutableListOf<File>()
        ZipFile(input).use { zip ->
            for (entry in zip.entries().toList()) {
                cancellation?.check()
                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                    val name = entry.name.substringAfterLast('/').ifEmpty { "split-${apks.size}.apk" }
                    var output = File(extractedDirectory, name)
                    var suffix = 1
                    while (output.exists()) {
                        output = File(extractedDirectory, "${name.substringBeforeLast('.')}-$suffix.apk")
                        suffix++
                    }
                    zip.getInputStream(entry).use { inputStream ->
                        FileOutputStream(output).use { outputStream ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                cancellation?.check()
                                val count = inputStream.read(buffer)
                                if (count < 0) break
                                outputStream.write(buffer, 0, count)
                            }
                        }
                    }
                    check(output.length() > 0) { "Could not extract $name from the archive." }
                    apks += output
                }
            }
        }
        if (apks.isEmpty()) {
            throw IllegalArgumentException("No APK files were found inside this archive.")
        }
        val base = apks.firstOrNull {
            it.name == "base.apk" || it.name == "base-master.apk" || it.name == metadataBaseName
        } ?: apks.firstOrNull { it.name == "sp0n.citizen.apk" }
            ?: apks.firstOrNull { candidate ->
                runCatching {
                    ZipFile(candidate).use { zip ->
                        zip.getEntry("AndroidManifest.xml") != null && zip.getEntry("classes.dex") != null
                    }
                }.getOrDefault(false)
            }
            ?: throw IllegalArgumentException("Could not identify the base APK inside this archive.")
        return InputApks(
            base,
            apks.filter { it != base },
            metadata?.optString("package_name")?.takeIf { it.isNotEmpty() },
            metadata?.optString("version_name")?.takeIf { it.isNotEmpty() },
        )
    }

    private fun readMetadata(input: File): JSONObject? = runCatching {
        ZipFile(input).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: zip.getEntry("info.json")
                ?: return@runCatching null
            zip.getInputStream(entry).use { JSONObject(String(it.readBytes(), Charsets.UTF_8)) }
        }
    }.getOrNull()
}
