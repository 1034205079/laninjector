package com.baozi.laninjector.injection

import android.util.Log
import com.android.apksig.ApkSigner as GoogleApkSigner
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

class ApkSigner {

    companion object {
        private const val TAG = "LanInjector"
    }

    fun sign(unsignedApk: File, outputApk: File, signingKey: KeyStoreManager.SigningKey) {
        Log.d(TAG, "ApkSigner: signing ${unsignedApk.name} (${unsignedApk.length()} bytes)")

        val signerConfig = GoogleApkSigner.SignerConfig.Builder(
            "CERT",
            signingKey.privateKey,
            listOf(signingKey.certificate)
        ).build()

        val signer = GoogleApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .build()

        signer.sign()

        Log.d(TAG, "ApkSigner: signed -> ${outputApk.name} (${outputApk.length()} bytes)")
    }
}
