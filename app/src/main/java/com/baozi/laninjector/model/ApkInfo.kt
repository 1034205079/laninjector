package com.baozi.laninjector.model

data class ApkInfo(
    val packageName: String,
    val launcherActivity: String,
    val locales: List<String>,
    val dexCount: Int
)
