package com.baozi.laninjector

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.baozi.laninjector.injection.InjectionPipeline
import com.baozi.laninjector.model.ApkInfo
import com.baozi.laninjector.model.InjectionState
import com.baozi.laninjector.model.SigningConfig
import com.baozi.laninjector.ui.screens.HomeScreen
import com.baozi.laninjector.ui.screens.ProgressScreen
import com.baozi.laninjector.ui.screens.ResultScreen
import com.baozi.laninjector.ui.theme.LaninjectorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var pipeline: InjectionPipeline
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pipeline = InjectionPipeline(this)
        enableEdgeToEdge()

        setContent {
            LaninjectorTheme {
                val state by pipeline.state.collectAsState()
                var selectedUri by remember { mutableStateOf<Uri?>(null) }
                var apkInfo by remember { mutableStateOf<ApkInfo?>(null) }
                var analyzeError by remember { mutableStateOf<String?>(null) }
                var signingConfig by remember { mutableStateOf(SigningConfig.load(this@MainActivity)) }
                var pendingKeystoreEntryId by remember { mutableStateOf<String?>(null) }
                var pendingKeystorePath by remember { mutableStateOf<String?>(null) }

                Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
                    val screenModifier = Modifier.padding(paddingValues)
                    when (state) {
                        is InjectionState.Idle -> {
                            HomeScreen(
                                modifier = screenModifier,
                                apkInfo = apkInfo,
                                signingConfig = signingConfig,
                                onApkSelected = { uri: Uri ->
                                    selectedUri = uri
                                    apkInfo = null
                                    analyzeError = null
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val info = com.baozi.laninjector.injection.ApkAnalyzer(this@MainActivity)
                                                .analyze(contentResolver.openInputStream(uri)!!)
                                            apkInfo = info
                                        } catch (e: Exception) {
                                            analyzeError = e.message
                                        }
                                    }
                                },
                                onInjectClick = {
                                    if (pipeline.state.value is InjectionState.Idle) {
                                        selectedUri?.let { uri ->
                                            scope.launch {
                                                try {
                                                    pipeline.inject(uri, signingConfig)
                                                } catch (_: Exception) {
                                                }
                                            }
                                        }
                                    }
                                },
                                onSigningConfigChanged = { newConfig ->
                                    signingConfig = newConfig
                                    SigningConfig.save(this@MainActivity, newConfig)
                                },
                                onKeystoreSelected = { uri, entryId ->
                                    try {
                                        val path = SigningConfig.copyKeystoreToInternal(
                                            this@MainActivity, uri, entryId)
                                        pendingKeystorePath = path
                                        pendingKeystoreEntryId = entryId
                                        // Update the entry if it already exists in the list
                                        val existing = signingConfig.entries.any { it.id == entryId }
                                        if (existing) {
                                            val newEntries = signingConfig.entries.map {
                                                if (it.id == entryId) it.copy(keystorePath = path) else it
                                            }
                                            signingConfig = signingConfig.copy(entries = newEntries)
                                            SigningConfig.save(this@MainActivity, signingConfig)
                                        }
                                    } catch (e: Exception) {
                                        analyzeError = "Failed to load keystore: ${e.message}"
                                    }
                                },
                                pendingKeystorePath = pendingKeystorePath
                            )
                        }

                        is InjectionState.Analyzing,
                        is InjectionState.PatchingManifest,
                        is InjectionState.InjectingDex,
                        is InjectionState.Rebuilding,
                        is InjectionState.Signing -> {
                            ProgressScreen(modifier = screenModifier, state = state)
                        }

                        is InjectionState.Success -> {
                            ResultScreen(
                                modifier = screenModifier,
                                outputPath = (state as InjectionState.Success).outputPath,
                                errorStep = null,
                                errorMessage = null,
                                onReset = {
                                    pipeline.reset()
                                    apkInfo = null
                                    selectedUri = null
                                }
                            )
                        }

                        is InjectionState.Error -> {
                            ResultScreen(
                                modifier = screenModifier,
                                outputPath = null,
                                errorStep = (state as InjectionState.Error).step,
                                errorMessage = (state as InjectionState.Error).message,
                                onReset = {
                                    pipeline.reset()
                                    apkInfo = null
                                    selectedUri = null
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
