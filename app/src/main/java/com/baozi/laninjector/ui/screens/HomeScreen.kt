package com.baozi.laninjector.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baozi.laninjector.model.ApkInfo

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    apkInfo: ApkInfo?,
    onApkSelected: (Uri) -> Unit,
    onInjectClick: () -> Unit
) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    val apkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedUri = it
            onApkSelected(it)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 80.dp, bottom = 48.dp),
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
                onClick = {
                    apkPicker.launch(arrayOf("application/vnd.android.package-archive"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (selectedUri != null) "Change APK" else "Select APK")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // APK Info card
            if (apkInfo != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "APK Info",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
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

                Spacer(modifier = Modifier.height(24.dp))

                // Inject button
                Button(
                    onClick = onInjectClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = apkInfo.locales.isNotEmpty()
                ) {
                    Text("Inject Language Menu")
                }

                if (apkInfo.locales.isEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No locale resources found in this APK",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else if (selectedUri != null) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                Text("Analyzing APK...")
            }

            Spacer(modifier = Modifier.weight(1f))
        }

        // Footer
        Text(
            text = "Powered by xiaofei",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
