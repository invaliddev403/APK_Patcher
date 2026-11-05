package dev.apkpatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkArchiveTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun xapkMetadataSelectsBaseInsteadOfLargerNativeSplit() {
        val archive = temporaryFolder.newFile("citizen.xapk")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.add("config.arm64_v8a.apk", ByteArray(4096))
            zip.add("sp0n.citizen.apk", apkBytes())
            zip.add("manifest.json", """
                {"package_name":"sp0n.citizen","version_name":"1.2.3",
                 "split_apks":[{"file":"sp0n.citizen.apk","id":"base"},
                 {"file":"config.arm64_v8a.apk","id":"config.arm64_v8a"}]}
            """.trimIndent().toByteArray())
        }

        val result = ApkArchive().extract(archive, temporaryFolder.newFolder("work"))
        assertEquals("sp0n.citizen.apk", result.base.name)
        assertEquals(listOf("config.arm64_v8a.apk"), result.splits.map { it.name })
        assertEquals("sp0n.citizen", result.packageName)
        assertEquals("1.2.3", result.versionName)
        assertEquals(apkBytes().size.toLong(), result.base.length())
    }

    @Test
    fun structuralFallbackFindsBaseWithoutMetadata() {
        val archive = temporaryFolder.newFile("citizen.apkm")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.add("config.large.apk", ByteArray(2048))
            zip.add("application.apk", apkBytes())
        }

        val result = ApkArchive().extract(archive, temporaryFolder.newFolder("fallback"))
        assertEquals("application.apk", result.base.name)
        assertEquals(1, result.splits.size)
    }

    @Test
    fun emptyArchiveFailsClearly() {
        val archive = temporaryFolder.newFile("empty.xapk")
        ZipOutputStream(archive.outputStream()).close()
        val error = assertThrows(IllegalArgumentException::class.java) {
            ApkArchive().extract(archive, temporaryFolder.newFolder("empty"))
        }
        assertEquals("No APK files were found inside this archive.", error.message)
    }

    @Test
    fun cancelledExtractionStopsBeforeWritingFiles() {
        val archive = temporaryFolder.newFile("citizen.xapk")
        ZipOutputStream(archive.outputStream()).use { zip -> zip.add("base.apk", apkBytes()) }
        val cancellation = OperationCancellation().apply { cancel() }

        assertThrows(java.util.concurrent.CancellationException::class.java) {
            ApkArchive().extract(archive, temporaryFolder.newFolder("cancelled"), cancellation)
        }
    }

    private fun apkBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.add("AndroidManifest.xml", byteArrayOf(1, 2, 3))
            zip.add("classes.dex", byteArrayOf(4, 5, 6))
        }
        return output.toByteArray()
    }

    private fun ZipOutputStream.add(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}
