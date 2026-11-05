package dev.apkpatcher

data class ApkSource(val label: String, val url: String)

object SourceCatalog {
    val all = listOf(
        ApkSource(
            "APKMirror",
            "https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=citizen",
        ),
        ApkSource("APKPure", "https://apkpure.com/citizen/sp0n.citizen"),
        ApkSource("APKCombo", "https://apkcombo.com/citizen/sp0n.citizen/"),
    )
}
