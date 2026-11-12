package com.shotagent.mark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun WalkthroughReviewScreen(
    stillCount: Int,
    hasVideo: Boolean,
    transcript: String,
    sending: Boolean,
    status: String?,
    onLog: () -> Unit,
    onBuild: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Walkthrough ready", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            buildString {
                append("$stillCount highlight still")
                if (stillCount != 1) append("s")
                if (hasVideo) append(" · video saved")
                else append(" · no video")
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text("Transcript", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            transcript.ifBlank { "No speech captured. You can still Log the stills and video." },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onLog,
            enabled = !sending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (sending) "Working…" else "Log")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onBuild,
            enabled = !sending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Build")
        }
        if (!status.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(status, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
