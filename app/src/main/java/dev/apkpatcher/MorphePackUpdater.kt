package dev.apkpatcher

import android.content.Context
import org.jf.dexlib2.DexFileFactory
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

internal data class MorpheUpdateResult(
    val file: File,
    val generation: MorpheGenerationResult,
)

internal class MorphePackUpdater(private val context: Context) {
    fun update(
        input: File,
        cancellation: OperationCancellation?,
        onProgress: (String) -> Unit,
    ): MorpheUpdateResult {
        val work = File(context.cacheDir, "morphe-update-${System.currentTimeMillis()}")
        check(work.mkdirs()) { "Could not create the update working directory." }
        return try {
            onProgress("Reading the selected APK…")
            val inputApks = ApkArchive().extract(input, work, cancellation)
            val info = runCatching {
                context.packageManager.getPackageArchiveInfo(inputApks.base.absolutePath, 0)
            }.getOrNull()
            val packageName = inputApks.packageName ?: info?.packageName
                ?: throw IllegalArgumentException("Could not determine the APK package name.")
            val version = inputApks.versionName ?: info?.versionName?.ifEmpty { null }
                ?: throw IllegalArgumentException("Could not determine the APK version.")
            val base = PackRepository(context).builtInForPackage(packageName)

            onProgress("Downloading the latest Morphe definitions…")
            val commit = JSONObject(fetch("$API/commits/main")).getString("sha")
            require(Regex("[0-9a-fA-F]{40}").matches(commit)) { "GitHub returned an invalid commit ID." }
            val fingerprints = fetch("$RAW/CitizenFingerprints.kt")
            val patchSource = fetch("$RAW/CitizenUnlockProPatch.kt")

            onProgress("Matching Morphe targets to $version…")
            cancellation?.check()
            val available = readMethods(inputApks.base, work, cancellation)
            val generation = MorphePackGenerator.generate(
                base,
                fingerprints,
                patchSource,
                available,
                version,
                commit,
            )
            cancellation?.check()
            val file = PackRepository(context).save(generation.pack)
            MorpheUpdateResult(file, generation)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun readMethods(
        baseApk: File,
        work: File,
        cancellation: OperationCancellation?,
    ): Set<MethodSignature> {
        val methods = mutableSetOf<MethodSignature>()
        ZipFile(baseApk).use { zip ->
            zip.entries().toList().filter { Regex("classes[0-9]*\\.dex").matches(it.name) }
                .sortedBy { it.name }.forEach { entry ->
                    cancellation?.check()
                    val dex = File(work, entry.name)
                    zip.getInputStream(entry).use { input -> dex.outputStream().use { input.copyTo(it) } }
                    DexFileFactory.loadDexFile(dex, null).classes.forEach { classDef ->
                        cancellation?.check()
                        (classDef.directMethods + classDef.virtualMethods).forEach { method ->
                            methods += MethodSignature(
                                method.definingClass,
                                method.name,
                                method.parameters.map { it.type },
                                method.returnType,
                            )
                        }
                    }
                    dex.delete()
                }
        }
        require(methods.isNotEmpty()) { "The base APK contains no DEX methods." }
        return methods
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Accept", "application/vnd.github+json, text/plain")
            connection.setRequestProperty("User-Agent", "APK-Patcher-Android")
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("GitHub returned HTTP $code. Try updating again later.")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val API = "https://api.github.com/repos/rushiranpise/morphe-patches"
        const val RAW = "https://raw.githubusercontent.com/rushiranpise/morphe-patches/main/patches/src/main/kotlin/app/template/patches/citizen"
    }
}
