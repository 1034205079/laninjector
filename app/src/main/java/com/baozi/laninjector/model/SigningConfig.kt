package com.baozi.laninjector.model

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SigningEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val keystorePath: String? = null,
    val keystorePassword: String = "",
    val keyAlias: String = "",
    val keyPassword: String = ""
)

data class SigningConfig(
    val useCustom: Boolean = false,
    val selectedId: String? = null,
    val entries: List<SigningEntry> = emptyList()
) {
    val selectedEntry: SigningEntry?
        get() = entries.find { it.id == selectedId }

    companion object {
        private const val PREFS_NAME = "signing_config_v2"

        fun load(context: Context): SigningConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val useCustom = prefs.getBoolean("use_custom", false)
            val selectedId = prefs.getString("selected_id", null)
            val entriesJson = prefs.getString("entries", "[]") ?: "[]"

            val entries = mutableListOf<SigningEntry>()
            try {
                val arr = JSONArray(entriesJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    entries.add(SigningEntry(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", ""),
                        keystorePath = obj.optString("keystorePath", null),
                        keystorePassword = obj.optString("keystorePassword", ""),
                        keyAlias = obj.optString("keyAlias", ""),
                        keyPassword = obj.optString("keyPassword", "")
                    ))
                }
            } catch (_: Exception) {}

            return SigningConfig(useCustom, selectedId, entries)
        }

        fun save(context: Context, config: SigningConfig) {
            val arr = JSONArray()
            for (entry in config.entries) {
                arr.put(JSONObject().apply {
                    put("id", entry.id)
                    put("name", entry.name)
                    put("keystorePath", entry.keystorePath ?: "")
                    put("keystorePassword", entry.keystorePassword)
                    put("keyAlias", entry.keyAlias)
                    put("keyPassword", entry.keyPassword)
                })
            }

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean("use_custom", config.useCustom)
                .putString("selected_id", config.selectedId)
                .putString("entries", arr.toString())
                .apply()
        }

        fun copyKeystoreToInternal(context: Context, uri: Uri, entryId: String): String {
            val destFile = File(context.filesDir, "keystore_$entryId")
            context.contentResolver.openInputStream(uri)!!.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            return destFile.absolutePath
        }
    }
}
