package com.baozi.laninjector.model

sealed class InjectionState {
    data object Idle : InjectionState()
    data class Analyzing(val message: String = "Analyzing APK...") : InjectionState()
    data class PatchingManifest(val message: String = "Patching manifest...") : InjectionState()
    data class InjectingDex(val message: String = "Injecting code...") : InjectionState()
    data class Rebuilding(val message: String = "Rebuilding APK...") : InjectionState()
    data class Signing(val message: String = "Signing APK...") : InjectionState()
    data class Success(val outputPath: String) : InjectionState()
    data class Error(val step: String, val message: String) : InjectionState()
}
