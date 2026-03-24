package com.baozi.laninjector.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baozi.laninjector.model.InjectionState

@Composable
fun ProgressScreen(modifier: Modifier = Modifier, state: InjectionState) {
    val steps = listOf(
        "Analyzing APK" to (state is InjectionState.Analyzing || state.ordinal() > 0),
        "Patching Manifest" to (state is InjectionState.PatchingManifest || state.ordinal() > 1),
        "Injecting Code" to (state is InjectionState.InjectingDex || state.ordinal() > 2),
        "Rebuilding APK" to (state is InjectionState.Rebuilding || state.ordinal() > 3),
        "Signing APK" to (state is InjectionState.Signing || state.ordinal() > 4)
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Injecting...",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))

        steps.forEachIndexed { index, (name, _) ->
            val stepState = getStepState(state, index)
            StepItem(name = name, stepState = stepState)
            if (index < steps.size - 1) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

private enum class StepState { PENDING, IN_PROGRESS, COMPLETED, ERROR }

private fun getStepState(state: InjectionState, stepIndex: Int): StepState {
    val currentIndex = when (state) {
        is InjectionState.Analyzing -> 0
        is InjectionState.PatchingManifest -> 1
        is InjectionState.InjectingDex -> 2
        is InjectionState.Rebuilding -> 3
        is InjectionState.Signing -> 4
        is InjectionState.Success -> 5
        is InjectionState.Error -> {
            val errorStep = when (state.step) {
                "Analysis" -> 0
                "Manifest Patching" -> 1
                "DEX Injection" -> 2
                "Rebuilding" -> 3
                "Signing" -> 4
                else -> -1
            }
            if (stepIndex == errorStep) return StepState.ERROR
            if (stepIndex < errorStep) return StepState.COMPLETED
            return StepState.PENDING
        }
        else -> -1
    }

    return when {
        stepIndex < currentIndex -> StepState.COMPLETED
        stepIndex == currentIndex -> StepState.IN_PROGRESS
        else -> StepState.PENDING
    }
}

@Composable
private fun StepItem(name: String, stepState: StepState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (stepState) {
            StepState.PENDING -> {
                Text("  ○  ", color = MaterialTheme.colorScheme.outline)
            }
            StepState.IN_PROGRESS -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            StepState.COMPLETED -> {
                Text("  ✓  ", color = MaterialTheme.colorScheme.primary)
            }
            StepState.ERROR -> {
                Text("  ✗  ", color = MaterialTheme.colorScheme.error)
            }
        }

        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = when (stepState) {
                StepState.PENDING -> MaterialTheme.colorScheme.outline
                StepState.IN_PROGRESS -> MaterialTheme.colorScheme.onSurface
                StepState.COMPLETED -> MaterialTheme.colorScheme.primary
                StepState.ERROR -> MaterialTheme.colorScheme.error
            },
            fontWeight = if (stepState == StepState.IN_PROGRESS) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// Helper to get ordinal-like value for state ordering
private fun InjectionState.ordinal(): Int = when (this) {
    is InjectionState.Analyzing -> 0
    is InjectionState.PatchingManifest -> 1
    is InjectionState.InjectingDex -> 2
    is InjectionState.Rebuilding -> 3
    is InjectionState.Signing -> 4
    is InjectionState.Success -> 5
    else -> -1
}
