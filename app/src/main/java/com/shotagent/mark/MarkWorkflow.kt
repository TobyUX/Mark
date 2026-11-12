package com.shotagent.mark

class MarkWorkflow(private val store: SettingsStore) {
    fun log(png: ByteArray, transcript: String): ApiCall<LogResult> {
        val note = promptText(store.instruction, transcript)
        val summary = summaryFrom(transcript)
        val jira = JiraApi(store.jiraSite, store.jiraEmail, store.jiraToken)
        val issue = jira.createIssueWithAttachment(
            projectKey = store.jiraProject,
            summary = summary,
            description = note,
            png = png,
        )
        if (issue is ApiCall.Err) return issue
        val created = (issue as ApiCall.Ok).value

        val slack = SlackApi(store.slackToken)
        val slackText = buildString {
            append("Logged from Mark\n")
            append(created.url)
            if (transcript.trim().isNotEmpty()) {
                append("\n\n")
                append(transcript.trim())
            }
        }
        val posted = slack.postScreenshot(
            channelId = store.channelId,
            text = slackText,
            png = png,
            mentionCursor = false,
            cursorUserId = null,
        )
        if (posted is ApiCall.Err) {
            return ApiCall.Err("Created ${created.key}, but Slack failed: ${posted.message}")
        }
        return ApiCall.Ok(LogResult(issue = created, built = false))
    }

    fun build(png: ByteArray, transcript: String): ApiCall<LogResult> {
        val logged = log(png, transcript)
        if (logged is ApiCall.Err) return logged
        val result = (logged as ApiCall.Ok).value

        val slack = SlackApi(store.slackToken)
        var cursorId = store.cursorUserId
        if (cursorId.isBlank()) {
            cursorId = slack.findCursorUserId().orEmpty()
            if (cursorId.isNotBlank()) store.cursorUserId = cursorId
        }
        val followUp = slack.postText(
            channelId = store.channelId,
            text = "Do this ticket: ${result.issue.url}",
            mentionCursor = true,
            cursorUserId = cursorId.ifBlank { null },
        )
        if (followUp is ApiCall.Err) {
            return ApiCall.Err(
                "Logged ${result.issue.key} and posted to Slack, but could not ask Cursor: ${followUp.message}",
            )
        }
        return ApiCall.Ok(result.copy(built = true))
    }
}

data class LogResult(
    val issue: CreatedIssue,
    val built: Boolean,
)

fun promptText(instruction: String, transcript: String): String {
    val standing = instruction.ifBlank { SettingsStore.DEFAULT_INSTRUCTION }
    val note = transcript.trim()
    return if (note.isEmpty()) standing else "$standing\n\n$note"
}

fun summaryFrom(transcript: String): String {
    val note = transcript.trim().replace('\n', ' ')
    if (note.isBlank()) return "UI change from Mark"
    return note.take(120)
}
