package com.baozi.laninjector.injection

import android.content.Context
import android.util.Log
import com.baozi.laninjector.model.SigningConfig
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

class KeyStoreManager(private val context: Context) {

    companion object {
        private const val TAG = "LanInjector"
        private const val KEYSTORE_FILE = "debug.keystore"
        private const val KEYSTORE_PASSWORD = "android"
        private const val KEY_ALIAS = "androiddebugkey"
        private const val KEY_PASSWORD = "android"
    }

    data class SigningKey(
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    fun getSigningKey(config: SigningConfig = SigningConfig()): SigningKey {
        Log.d(TAG, "KeyStoreManager: useCustom=${config.useCustom}, selectedId=${config.selectedId}, entries=${config.entries.size}")
        val entry = if (config.useCustom) config.selectedEntry else null
        if (entry != null && !entry.keystorePath.isNullOrEmpty()) {
            Log.d(TAG, "KeyStoreManager: using custom keystore '${entry.name}', path=${entry.keystorePath}, alias=${entry.keyAlias}")
            return loadCustomKey(entry)
        }

        if (config.useCustom) {
            Log.w(TAG, "KeyStoreManager: custom enabled but no valid entry found, falling back to debug")
        }

        val ksFile = File(context.filesDir, KEYSTORE_FILE)
        Log.d(TAG, "KeyStoreManager: keystore exists=${ksFile.exists()}")

        if (ksFile.exists()) {
            Log.d(TAG, "KeyStoreManager: loading existing keystore")
            return loadKey(ksFile)
        }

        Log.d(TAG, "KeyStoreManager: generating new keystore")
        return generateKey(ksFile)
    }

    private fun loadCustomKey(entry: com.baozi.laninjector.model.SigningEntry): SigningKey {
        val ksFile = File(entry.keystorePath!!)
        require(ksFile.exists()) { "Keystore file not found: ${entry.keystorePath}" }

        val ksType = if (entry.keystorePath.endsWith(".p12") || entry.keystorePath.endsWith(".pfx"))
            "PKCS12" else "JKS"

        val ks = try {
            val store = KeyStore.getInstance(ksType)
            ksFile.inputStream().use { store.load(it, entry.keystorePassword.toCharArray()) }
            store
        } catch (e: Exception) {
            val altType = if (ksType == "PKCS12") "JKS" else "PKCS12"
            val store = KeyStore.getInstance(altType)
            ksFile.inputStream().use { store.load(it, entry.keystorePassword.toCharArray()) }
            store
        }

        val alias = entry.keyAlias.ifEmpty {
            ks.aliases().nextElement()
        }
        val privateKey = ks.getKey(alias, entry.keyPassword.toCharArray()) as PrivateKey
        val certificate = ks.getCertificate(alias) as X509Certificate

        Log.d(TAG, "KeyStoreManager: loaded custom key, alias=$alias")
        return SigningKey(privateKey, certificate)
    }

    private fun loadKey(ksFile: File): SigningKey {
        val ks = KeyStore.getInstance("PKCS12")
        ksFile.inputStream().use { ks.load(it, KEYSTORE_PASSWORD.toCharArray()) }

        val privateKey = ks.getKey(KEY_ALIAS, KEY_PASSWORD.toCharArray()) as PrivateKey
        val certificate = ks.getCertificate(KEY_ALIAS) as X509Certificate

        return SigningKey(privateKey, certificate)
    }

    @Suppress("DEPRECATION")
    private fun generateKey(ksFile: File): SigningKey {
        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048)
        val keyPair = keyPairGen.generateKeyPair()

        // Create self-signed certificate
        // Using Android's hidden API or bouncy castle if available
        // For simplicity, we'll create a minimal self-signed cert using sun.security if available
        // or use the Android Keystore approach

        val startDate = Date()
        val endDate = Date(startDate.time + 365L * 24 * 60 * 60 * 1000 * 30) // 30 years

        val certificate = createSelfSignedCertificate(
            keyPair.private,
            keyPair.public,
            "CN=Android Debug,O=Android,C=US",
            startDate,
            endDate
        )

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, KEYSTORE_PASSWORD.toCharArray())
        ks.setKeyEntry(KEY_ALIAS, keyPair.private, KEY_PASSWORD.toCharArray(), arrayOf(certificate))

        FileOutputStream(ksFile).use { ks.store(it, KEYSTORE_PASSWORD.toCharArray()) }

        return SigningKey(keyPair.private, certificate)
    }

    /**
     * Create a self-signed X.509 certificate.
     * Uses Android's internal API or reflection for certificate generation.
     */
    private fun createSelfSignedCertificate(
        privateKey: PrivateKey,
        publicKey: java.security.PublicKey,
        dn: String,
        startDate: Date,
        endDate: Date
    ): X509Certificate {
        // Try using Android's X509V3CertificateGenerator equivalent
        // On Android, we can use the platform's hidden CertificateBuilder or
        // create a minimal ASN.1 certificate manually.

        // Use Android's own certificate generation utility
        try {
            // Try the android.security approach
            val certGen = Class.forName("com.android.org.bouncycastle.x509.X509V3CertificateGenerator")
            val instance = certGen.newInstance()

            certGen.getMethod("setSerialNumber", BigInteger::class.java)
                .invoke(instance, BigInteger.valueOf(System.currentTimeMillis()))
            certGen.getMethod("setSubjectDN", X500Principal::class.java)
                .invoke(instance, X500Principal(dn))
            certGen.getMethod("setIssuerDN", X500Principal::class.java)
                .invoke(instance, X500Principal(dn))
            certGen.getMethod("setNotBefore", Date::class.java)
                .invoke(instance, startDate)
            certGen.getMethod("setNotAfter", Date::class.java)
                .invoke(instance, endDate)
            certGen.getMethod("setPublicKey", java.security.PublicKey::class.java)
                .invoke(instance, publicKey)
            certGen.getMethod("setSignatureAlgorithm", String::class.java)
                .invoke(instance, "SHA256WithRSAEncryption")

            @Suppress("UNCHECKED_CAST")
            return certGen.getMethod("generate", PrivateKey::class.java)
                .invoke(instance, privateKey) as X509Certificate
        } catch (e: Exception) {
            // Fallback: try newer Bouncy Castle API
            try {
                return createCertWithContentSigner(privateKey, publicKey, dn, startDate, endDate)
            } catch (e2: Exception) {
                throw RuntimeException(
                    "Cannot generate self-signed certificate. " +
                            "Please ensure the app has access to certificate generation APIs. " +
                            "Error: ${e2.message}", e2
                )
            }
        }
    }

    private fun createCertWithContentSigner(
        privateKey: PrivateKey,
        publicKey: java.security.PublicKey,
        dn: String,
        startDate: Date,
        endDate: Date
    ): X509Certificate {
        // Try using org.bouncycastle classes available on Android
        val x500Name = Class.forName("com.android.org.bouncycastle.asn1.x500.X500Name")
            .getConstructor(String::class.java)
            .newInstance(dn)

        val subjectPublicKeyInfo = Class.forName("com.android.org.bouncycastle.asn1.x509.SubjectPublicKeyInfo")
            .getMethod("getInstance", Any::class.java)
            .invoke(null, publicKey.encoded)

        val certBuilder = Class.forName("com.android.org.bouncycastle.cert.X509v3CertificateBuilder")
            .getConstructor(
                Class.forName("com.android.org.bouncycastle.asn1.x500.X500Name"),
                BigInteger::class.java,
                Date::class.java,
                Date::class.java,
                Class.forName("com.android.org.bouncycastle.asn1.x500.X500Name"),
                Class.forName("com.android.org.bouncycastle.asn1.x509.SubjectPublicKeyInfo")
            )
            .newInstance(
                x500Name,
                BigInteger.valueOf(System.currentTimeMillis()),
                startDate,
                endDate,
                x500Name,
                subjectPublicKeyInfo
            )

        val contentSignerBuilder = Class.forName("com.android.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder")
            .getConstructor(String::class.java)
            .newInstance("SHA256WithRSA")

        val contentSigner = contentSignerBuilder.javaClass
            .getMethod("build", PrivateKey::class.java)
            .invoke(contentSignerBuilder, privateKey)

        val certHolder = certBuilder.javaClass
            .getMethod("build", Class.forName("com.android.org.bouncycastle.operator.ContentSigner"))
            .invoke(certBuilder, contentSigner)

        val converter = Class.forName("com.android.org.bouncycastle.cert.jcajce.JcaX509CertificateConverter")
            .newInstance()

        @Suppress("UNCHECKED_CAST")
        return converter.javaClass
            .getMethod("getCertificate", Class.forName("com.android.org.bouncycastle.cert.X509CertificateHolder"))
            .invoke(converter, certHolder) as X509Certificate
    }
}
