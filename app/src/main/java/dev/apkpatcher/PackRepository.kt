package dev.apkpatcher

import android.content.Context
import java.io.File

internal class PackRepository(private val context: Context) {
    private val root = File(context.filesDir, "packs")

    fun select(packageName: String, versionName: String): PatchPack {
        val candidates = downloadedPacks(packageName, versionName) + BuiltInPatchPacks.all(context)
        return candidates.firstOrNull { it.application.matches(packageName, versionName) }
            ?: throw IllegalArgumentException("No installed patch pack supports $packageName version $versionName.")
    }

    fun builtInForPackage(packageName: String): PatchPack = BuiltInPatchPacks.all(context).firstOrNull {
        it.application.packageName == packageName
    } ?: throw IllegalArgumentException("No built-in patch pack supports $packageName.")

    fun save(pack: PatchPack): File {
        val directory = File(root, "${pack.application.packageName}/${pack.application.versionName ?: "any"}")
        check(directory.exists() || directory.mkdirs()) { "Could not create the patch-pack directory." }
        val destination = File(directory, "${pack.id}.json")
        val temporary = File(directory, "${pack.id}.tmp")
        temporary.writeText(PatchPackWriter.write(pack))
        val validated = PatchPackParser.parse(temporary.readText())
        require(validated == pack) { "The generated patch pack did not pass round-trip validation." }
        if (destination.exists() && !destination.delete()) {
            throw IllegalStateException("Could not replace the downloaded patch pack.")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException("Could not save the downloaded patch pack.")
        }
        return destination
    }

    private fun downloadedPacks(packageName: String, versionName: String): List<PatchPack> {
        val directory = File(root, "$packageName/$versionName")
        return directory.listFiles { file -> file.extension == "json" }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file -> runCatching { PatchPackParser.parse(file.readText()) }.getOrNull() }
            .filter { it.application.matches(packageName, versionName) }
    }
}
