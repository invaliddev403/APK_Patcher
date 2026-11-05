package dev.apkpatcher

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

class ApkPackageSigner(context: Context) {
    private val signerConfig: ApkSigner.SignerConfig

    init {
        val password = "on-device-patcher".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        context.resources.openRawResource(R.raw.patch_signing_key).use {
            keyStore.load(it, password)
        }
        val alias = keyStore.aliases().toList().single()
        signerConfig = ApkSigner.SignerConfig.Builder(
            "patcher",
            keyStore.getKey(alias, password) as PrivateKey,
            listOf(keyStore.getCertificate(alias) as X509Certificate),
        ).build()
    }

    fun sign(input: File, output: File) {
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .build()
            .sign()
    }
}
