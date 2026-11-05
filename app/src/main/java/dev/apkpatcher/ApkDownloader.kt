package dev.apkpatcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.zip.ZipFile

class ApkDownloader(private val context: Context) {
    interface Listener {
        fun onProgress(downloadedBytes: Long, totalBytes: Long)
        fun onComplete(file: File)
        fun onError(message: String)
    }

    class Handle {
        @Volatile var cancelled = false
            private set
        var connection: HttpURLConnection? = null
        var destination: File? = null

        fun cancel() {
            cancelled = true
            connection?.disconnect()
            destination?.delete()
        }
    }

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun download(
        url: String,
        listener: Listener,
        userAgent: String? = null,
        cookies: String? = null,
    ): Handle {
        val handle = Handle()
        executor.execute {
            var destination: File? = null
            try {
                val parsedUrl = URL(url)
                if (parsedUrl.protocol !in setOf("https", "http")) {
                    throw IllegalArgumentException("Only direct HTTP(S) download links are supported.")
                }
                val connection = parsedUrl.openConnection() as HttpURLConnection
                handle.connection = connection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty(
                    "User-Agent",
                    userAgent ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36",
                )
                if (!cookies.isNullOrEmpty()) {
                    connection.setRequestProperty("Cookie", cookies)
                }
                if (handle.cancelled) throw InterruptedException("Download cancelled.")
                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw IllegalStateException("The source returned HTTP $responseCode. Try downloading through its website instead.")
                }

                val name = fileName(connection, parsedUrl)
                if (name.extension().lowercase() !in setOf("apk", "apkm", "apks", "xapk")) {
                    throw IllegalArgumentException("The link did not provide an APK, APKM, APKS, or XAPK file.")
                }
                val directory = File(context.cacheDir, "downloads").apply { mkdirs() }
                destination = File(directory, "${System.currentTimeMillis()}-$name")
                handle.destination = destination
                val total = connection.contentLengthLong
                StorageSpace.requireAvailable(directory, StorageSpace.requiredDownloadBytes(total))
                connection.inputStream.buffered().use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastUpdate = 0L
                        while (true) {
                            if (handle.cancelled) throw InterruptedException("Download cancelled.")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 100) {
                                mainHandler.post { listener.onProgress(downloaded, total) }
                                lastUpdate = now
                            }
                        }
                    }
                }
                if (handle.cancelled) throw InterruptedException("Download cancelled.")
                validate(destination)
                val completedFile = destination
                mainHandler.post { listener.onComplete(completedFile) }
                destination = null
            } catch (error: Exception) {
                destination?.delete()
                if (!handle.cancelled) {
                    mainHandler.post {
                        listener.onError(error.message ?: "Download failed. Check the link and try again.")
                    }
                }
            } finally {
                handle.connection?.disconnect()
            }
        }
        return handle
    }

    private fun fileName(connection: HttpURLConnection, url: URL): String {
        val disposition = connection.getHeaderField("Content-Disposition")
        val encodedName = disposition?.let {
            Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
                ?.let { value -> URLDecoder.decode(value, "UTF-8") }
                ?: Regex("filename=\"?([^\";]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }
        val candidate = encodedName ?: url.path.substringAfterLast('/').ifEmpty { "download.apk" }
        val sanitized = candidate.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120)
        return sanitized.ifEmpty { "download.apk" }
    }

    private fun validate(file: File) {
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().toList()
                val valid = if (file.extension.equals("apk", ignoreCase = true)) {
                    entries.any { it.name == "AndroidManifest.xml" } && entries.any { it.name == "classes.dex" }
                } else {
                    entries.any { it.name.endsWith(".apk", ignoreCase = true) }
                }
                if (!valid) throw IllegalArgumentException("The downloaded file is not a valid APK archive.")
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("The source returned an invalid or incomplete APK archive.", error)
        }
    }

    private fun String.extension() = substringAfterLast('.', "")
}
