package com.shotagent.mark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.shotagent.mark.SettingsStore

@Composable
fun SetupScreen(
    slackToken: String,
    channelId: String,
    jiraUrl: String,
    jiraSite: String,
    jiraProject: String,
    jiraEmail: String,
    jiraToken: String,
    instruction: String,
    status: String?,
    testing: Boolean,
    needsSetupForShare: Boolean,
    onSlackToken: (String) -> Unit,
    onChannelId: (String) -> Unit,
    onJiraUrl: (String) -> Unit,
    onJiraEmail: (String) -> Unit,
    onJiraToken: (String) -> Unit,
    onInstruction: (String) -> Unit,
    onTest: () -> Unit,
    onBack: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.height(4.dp))
        }
        Text("Setup", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            if (needsSetupForShare) {
                "Save Slack and Jira, then share the screenshot again."
            } else {
                "These stay on this phone. Use your own Slack channel and Jira project."
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(20.dp))
        Text("Slack", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = slackToken,
            onValueChange = onSlackToken,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Slack user token (xoxp-…)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = channelId,
            onValueChange = onChannelId,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Slack channel ID") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )

        Spacer(Modifier.height(20.dp))
        Text("Jira", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = jiraUrl,
            onValueChange = onJiraUrl,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Jira board or project URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        if (jiraSite.isNotBlank() || jiraProject.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Using $jiraSite / $jiraProject",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = jiraEmail,
            onValueChange = onJiraEmail,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Atlassian email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = jiraToken,
            onValueChange = onJiraToken,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Atlassian API token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )

        Spacer(Modifier.height(20.dp))
        Text("Standing instruction", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = instruction,
            onValueChange = onInstruction,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            label = { Text("Added to every ticket") },
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onTest,
            enabled = !testing &&
                slackToken.isNotBlank() &&
                jiraEmail.isNotBlank() &&
                jiraToken.isNotBlank() &&
                jiraSite.isNotBlank() &&
                jiraProject.isNotBlank(),
        ) {
            Text(if (testing) "Testing…" else "Test Slack and Jira")
        }
        if (testing || status != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = status ?: "Testing…",
                color = Color(0xFF1C1B1F),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Default instruction: ${SettingsStore.DEFAULT_INSTRUCTION}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
