package com.baozi.laninjector.model

data class ApkInfo(
    val packageName: String,
    val launcherActivity: String,
    val locales: List<String>,
    val dexCount: Int,
    val isFlutter: Boolean = false,
    val languageCoverage: List<LanguageCoverage> = emptyList(),
    val coverageBasis: String = "无法判断翻译覆盖率"
)

data class LanguageCoverage(val locale: String, val translated: Int, val total: Int, val recommended: Boolean,
    val directTranslated: Int = translated) {
    val percent: Int get() = if (total == 0) 0 else (translated * 100L / total).toInt()
}
