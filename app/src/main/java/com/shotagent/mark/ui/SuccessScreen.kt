package com.shotagent.mark.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shotagent.mark.LogResult

@Composable
fun SuccessScreen(
    result: LogResult,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    fun open(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (result.built) "Built ${result.issue.key}" else "Logged ${result.issue.key}",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (result.built) {
                "Jira ticket created, posted to Slack, and Cursor was asked."
            } else {
                "Jira ticket created and posted to Slack."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { open(result.issue.url) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open Jira ticket")
        }
        Spacer(Modifier.height(8.dp))
        val slackUrl = result.slackPermalink
        OutlinedButton(
            onClick = { if (!slackUrl.isNullOrBlank()) open(slackUrl) },
            enabled = !slackUrl.isNullOrBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (slackUrl.isNullOrBlank()) "Slack link unavailable" else "Open Slack message")
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Done")
        }
    }
}
