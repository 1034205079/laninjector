package com.baozi.laninjector.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baozi.laninjector.model.ApkInfo
import com.baozi.laninjector.model.SigningConfig
import com.baozi.laninjector.model.SigningEntry

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    apkInfo: ApkInfo?,
    signingConfig: SigningConfig,
    onApkSelected: (Uri) -> Unit,
    onInjectClick: () -> Unit,
    onSigningConfigChanged: (SigningConfig) -> Unit,
    onKeystoreSelected: (Uri, String) -> Unit,  // uri, entryId
    pendingKeystorePath: String? = null
) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    // Track which entry is being edited (for keystore file picker)
    var editingEntryId by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<SigningEntry?>(null) }
    // Stable new entry for the add dialog (created once per dialog open)
    var newEntry by remember { mutableStateOf(SigningEntry()) }

    val apkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedUri = it
            onApkSelected(it)
        }
    }

    val keystorePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            editingEntryId?.let { id ->
                onKeystoreSelected(uri, id)
                editingEntryId = null
            }
        }
    }

    // Add/Edit dialog
    if (showAddDialog || editEntry != null) {
        val dialogEntry = editEntry ?: newEntry
        SigningEntryDialog(
            entry = dialogEntry,
            isEdit = editEntry != null,
            onDismiss = {
                showAddDialog = false
                editEntry = null
                newEntry = SigningEntry() // reset for next time
            },
            onSave = { entry ->
                val newEntries = if (editEntry != null) {
                    signingConfig.entries.map { if (it.id == entry.id) entry else it }
                } else {
                    signingConfig.entries + entry
                }
                val newConfig = signingConfig.copy(
                    entries = newEntries,
                    selectedId = entry.id // always select the saved entry
                )
                onSigningConfigChanged(newConfig)
                showAddDialog = false
                editEntry = null
                newEntry = SigningEntry() // reset for next time
            },
            onSelectKeystore = { entryId ->
                editingEntryId = entryId
                keystorePicker.launch(arrayOf("*/*"))
            },
            currentKeystorePath = pendingKeystorePath
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 80.dp, bottom = 48.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "LanInjector",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "APK Language Injection Tool",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Select APK button
            OutlinedButton(
                onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (selectedUri != null) "Change APK" else "Select APK")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // APK Info card
            if (apkInfo != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("APK Info", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow("Package", apkInfo.packageName)
                        InfoRow("Launcher", apkInfo.launcherActivity.substringAfterLast('.'))
                        InfoRow("DEX Files", apkInfo.dexCount.toString())
                        InfoRow("Languages", "${apkInfo.locales.size} found")
                        if (apkInfo.locales.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = apkInfo.locales.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else if (selectedUri != null) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                Text("Analyzing APK...")
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Signing Config card
            SigningConfigCard(
                config = signingConfig,
                onConfigChanged = onSigningConfigChanged,
                onAddEntry = { showAddDialog = true },
                onEditEntry = { editEntry = it },
                onDeleteEntry = { entry ->
                    val newEntries = signingConfig.entries.filter { it.id != entry.id }
                    val newSelected = if (signingConfig.selectedId == entry.id)
                        newEntries.firstOrNull()?.id else signingConfig.selectedId
                    onSigningConfigChanged(signingConfig.copy(entries = newEntries, selectedId = newSelected))
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Inject button
            if (apkInfo != null) {
                Button(
                    onClick = onInjectClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = apkInfo.locales.isNotEmpty()
                ) {
                    Text("Inject Language Menu")
                }
                if (apkInfo.locales.isEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No locale resources found in this APK",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }

        // Footer
        Text(
            text = "Powered by xiaofei",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SigningConfigCard(
    config: SigningConfig,
    onConfigChanged: (SigningConfig) -> Unit,
    onAddEntry: () -> Unit,
    onEditEntry: (SigningEntry) -> Unit,
    onDeleteEntry: (SigningEntry) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Signing Config", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (config.useCustom) "Custom" else "Debug",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (config.useCustom) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Use custom keystore", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = config.useCustom,
                    onCheckedChange = { onConfigChanged(config.copy(useCustom = it)) }
                )
            }

            AnimatedVisibility(visible = config.useCustom) {
                Column {
                    if (config.entries.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No signing keys configured",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    config.entries.forEach { entry ->
                        Spacer(modifier = Modifier.height(8.dp))
                        SigningEntryItem(
                            entry = entry,
                            isSelected = entry.id == config.selectedId,
                            onSelect = { onConfigChanged(config.copy(selectedId = entry.id)) },
                            onEdit = { onEditEntry(entry) },
                            onDelete = { onDeleteEntry(entry) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = onAddEntry,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("+ Add Signing Key")
                    }
                }
            }
        }
    }
}

@Composable
private fun SigningEntryItem(
    entry: SigningEntry,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() },
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = isSelected, onClick = onSelect)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name.ifEmpty { "Unnamed" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "alias: ${entry.keyAlias.ifEmpty { "auto" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete) {
                Text("Del", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SigningEntryDialog(
    entry: SigningEntry,
    isEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: (SigningEntry) -> Unit,
    onSelectKeystore: (String) -> Unit,
    currentKeystorePath: String?
) {
    var name by remember { mutableStateOf(entry.name) }
    var keystorePassword by remember { mutableStateOf(entry.keystorePassword) }
    var keyAlias by remember { mutableStateOf(entry.keyAlias) }
    var keyPassword by remember { mutableStateOf(entry.keyPassword) }
    // Track if keystore was selected (either pre-existing or just picked)
    val hasKeystore = entry.keystorePath != null || currentKeystorePath != null
    val effectivePath = currentKeystorePath ?: entry.keystorePath

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit Signing Key" else "Add Signing Key") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (e.g. GP Release)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        onSelectKeystore(entry.id)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (hasKeystore) "Keystore: selected" else "Select Keystore File")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = keystorePassword,
                    onValueChange = { keystorePassword = it },
                    label = { Text("Keystore Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyAlias,
                    onValueChange = { keyAlias = it },
                    label = { Text("Key Alias") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyPassword,
                    onValueChange = { keyPassword = it },
                    label = { Text("Key Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(entry.copy(
                    name = name,
                    keystorePassword = keystorePassword,
                    keyAlias = keyAlias,
                    keyPassword = keyPassword,
                    keystorePath = effectivePath
                ))
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
