package com.shotagent.mark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
fun AboutScreen(
    setupReady: Boolean,
    onOpenSetup: () -> Unit,
    onStartWalkthrough: () -> Unit,
    onFeedback: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Mark", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                "Screenshot something that needs changing, or record a walkthrough while you talk. Then Log or Build.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(20.dp))
            Text("Still", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Share a screenshot to Mark, circle it, say what to do, then Log or Build.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text("Walkthrough", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Record the screen while you navigate. Tap Highlight to draw, Done when finished. Mark saves the video, transcript, and highlight stills.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text("Log", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Creates a Jira ticket and posts it to your Slack channel. No agent run.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text("Build", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Same as Log, then asks @Cursor in Slack to do that ticket. That uses your Cursor account.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                if (setupReady) {
                    "Setup is saved on this phone. Open Setup to change Slack or Jira."
                } else {
                    "Add your Slack and Jira details once in Setup. Mark never ships with someone else's project."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onStartWalkthrough,
                enabled = setupReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start walkthrough")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenSetup,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (setupReady) "Open Setup" else "Set up Mark")
            }
        }
        Column {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = onFeedback,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Send feedback")
            }
        }
    }
}
