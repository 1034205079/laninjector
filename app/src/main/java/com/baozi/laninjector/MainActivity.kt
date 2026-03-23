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

                Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
                    val contentModifier = Modifier.padding(paddingValues)
                    when (state) {
                        is InjectionState.Idle -> {
                            HomeScreen(
                                apkInfo = apkInfo,
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
                                    // Prevent double-click: only inject if still Idle
                                    if (pipeline.state.value is InjectionState.Idle) {
                                        selectedUri?.let { uri ->
                                            scope.launch {
                                                try {
                                                    pipeline.inject(uri)
                                                } catch (_: Exception) {
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        is InjectionState.Analyzing,
                        is InjectionState.PatchingManifest,
                        is InjectionState.InjectingDex,
                        is InjectionState.Rebuilding,
                        is InjectionState.Signing -> {
                            ProgressScreen(state = state)
                        }

                        is InjectionState.Success -> {
                            ResultScreen(
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
