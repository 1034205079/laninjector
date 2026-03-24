package com.baozi.laninjector.injection

import android.content.Context
import android.net.Uri
import android.util.Log
import com.baozi.laninjector.model.ApkInfo
import com.baozi.laninjector.model.InjectionState
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

class InjectionPipeline(private val context: Context) {

    companion object {
        private const val TAG = "LanInjector"
    }

    private val _state = MutableStateFlow<InjectionState>(InjectionState.Idle)
    val state: StateFlow<InjectionState> = _state

    private val analyzer = ApkAnalyzer(context)
    private val manifestPatcher = ManifestPatcher()
    private val rebuilder = ApkRebuilder()
    private val zipAligner = ZipAligner()
    private val keyStoreManager = KeyStoreManager(context)
    private val signer = ApkSigner()

    var lastApkInfo: ApkInfo? = null
        private set

    suspend fun inject(apkUri: Uri): String = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "apk_temp")
        tempDir.mkdirs()
        val tempApk = File(tempDir, "original.apk")

        // Resolve original filename from URI
        val originalFileName = resolveFileName(apkUri)

        try {
            // Step 0: Copy APK to temp file for random access
            Log.d(TAG, "Copying APK to temp file...")
            context.contentResolver.openInputStream(apkUri)!!.use { input ->
                tempApk.outputStream().use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "Copied to ${tempApk.absolutePath} (${tempApk.length()} bytes)")

            // Step 1: Analyze APK
            Log.d(TAG, "Step 1: Analyzing APK")
            _state.value = InjectionState.Analyzing()
            val apkInfo = tempApk.inputStream().use { stream ->
                analyzer.analyze(stream)
            }
            lastApkInfo = apkInfo
            Log.d(TAG, "Analysis done: pkg=${apkInfo.packageName}, launcher=${apkInfo.launcherActivity}, locales=${apkInfo.locales.size}, dex=${apkInfo.dexCount}")

            // Step 2: Extract manifest only
            Log.d(TAG, "Step 2: Extracting manifest")
            var manifestData: ByteArray? = null

            val zipFile = ZipFile(tempApk)
            val manifestEntry = zipFile.getEntry("AndroidManifest.xml")
            if (manifestEntry != null) {
                manifestData = zipFile.getInputStream(manifestEntry).readBytes()
                Log.d(TAG, "Extracted AndroidManifest.xml (${manifestData.size} bytes)")
            }
            zipFile.close()

            requireNotNull(manifestData) { "AndroidManifest.xml not found" }
            require(apkInfo.locales.isNotEmpty()) { "No locale resources found in APK" }

            // Step 3: Patch manifest (add provider declaration)
            Log.d(TAG, "Step 3: Patching manifest")
            _state.value = InjectionState.PatchingManifest()
            val patchedManifest = manifestPatcher.patchManifest(manifestData, apkInfo.packageName)
            Log.d(TAG, "Manifest patched: ${manifestData.size} -> ${patchedManifest.size} bytes")

            // Step 4: Add payload DEX
            Log.d(TAG, "Step 4: Adding payload DEX")
            _state.value = InjectionState.InjectingDex()
            val payloadDex = context.assets.open("payload.dex").readBytes()
            val payloadDexName = "classes${apkInfo.dexCount + 1}.dex"
            val additionalDexFiles = mapOf(payloadDexName to payloadDex)
            Log.d(TAG, "Payload DEX: $payloadDexName (${payloadDex.size} bytes)")

            // Step 5: Rebuild APK
            Log.d(TAG, "Step 5: Rebuilding APK")
            _state.value = InjectionState.Rebuilding()

            val unsignedApk = File(tempDir, "unsigned.apk")
            val signedApk = File(
                context.getExternalFilesDir("output"),
                "inject_$originalFileName"
            )

            // Generate locales asset file
            val localesFileContent = apkInfo.locales.joinToString("\n").toByteArray(Charsets.UTF_8)
            val additionalAssets = mapOf("laninjector_locales.txt" to localesFileContent)

            rebuilder.rebuild(
                originalApkFile = tempApk,
                outputFile = unsignedApk,
                modifiedManifest = patchedManifest,
                modifiedDexFiles = emptyMap(),
                additionalDexFiles = additionalDexFiles,
                additionalAssets = additionalAssets
            )
            Log.d(TAG, "APK rebuilt: ${unsignedApk.length()} bytes")

            // Step 5.5: Zipalign (page-align .so files)
            Log.d(TAG, "Zipaligning APK...")
            val alignedApk = File(tempDir, "aligned.apk")
            zipAligner.align(unsignedApk, alignedApk)
            unsignedApk.delete()
            Log.d(TAG, "Aligned APK: ${alignedApk.length()} bytes")

            // Step 6: Sign APK
            Log.d(TAG, "Step 6: Signing APK")
            _state.value = InjectionState.Signing()
            val signingKey = keyStoreManager.getSigningKey()
            signer.sign(alignedApk, signedApk, signingKey)
            Log.d(TAG, "APK signed: ${signedApk.absolutePath} (${signedApk.length()} bytes)")

            // Cleanup
            alignedApk.delete()
            tempApk.delete()
            tempDir.deleteRecursively()

            val outputPath = signedApk.absolutePath
            _state.value = InjectionState.Success(outputPath)
            Log.d(TAG, "Injection complete! Output: $outputPath")
            outputPath

        } catch (e: Exception) {
            Log.e(TAG, "Injection failed", e)
            tempApk.delete()
            val currentState = _state.value
            val step = when (currentState) {
                is InjectionState.Analyzing -> "Analysis"
                is InjectionState.PatchingManifest -> "Manifest Patching"
                is InjectionState.InjectingDex -> "DEX Injection"
                is InjectionState.Rebuilding -> "Rebuilding"
                is InjectionState.Signing -> "Signing"
                else -> "Unknown"
            }
            _state.value = InjectionState.Error(step, e.message ?: "Unknown error")
            throw e
        }
    }

    fun reset() {
        _state.value = InjectionState.Idle
        lastApkInfo = null
    }

    private fun resolveFileName(uri: Uri): String {
        // Try content resolver query first
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        // Fallback: extract from URI path
        val path = uri.lastPathSegment ?: return "unknown.apk"
        return path.substringAfterLast('/')
    }
}
