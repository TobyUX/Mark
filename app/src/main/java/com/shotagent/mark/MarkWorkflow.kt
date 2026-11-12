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
        return askCursor((logged as ApiCall.Ok).value)
    }

    fun logWalkthrough(session: WalkthroughSession): ApiCall<LogResult> {
        val note = buildString {
            append(promptText(store.instruction, session.transcript))
            append("\n\n")
            append("This is a Mark walkthrough. The video is for humans. Use the attached highlight stills and transcript for the work.")
        }
        val summary = when {
            session.transcript.isNotBlank() -> summaryFrom(session.transcript)
            else -> "Walkthrough from Mark"
        }
        val files = mutableListOf<AttachmentFile>()
        session.stillBytes.forEachIndexed { index, bytes ->
            files.add(AttachmentFile("still-${index + 1}.png", "image/png", bytes))
        }
        session.videoBytes?.let { bytes ->
            files.add(AttachmentFile("walkthrough.mp4", "video/mp4", bytes))
        }
        if (files.isEmpty()) {
            return ApiCall.Err("No walkthrough stills or video to upload. Try highlighting once, then Done.")
        }

        val jira = JiraApi(store.jiraSite, store.jiraEmail, store.jiraToken)
        val issue = jira.createIssueWithAttachments(
            projectKey = store.jiraProject,
            summary = summary,
            description = note,
            files = files,
        )
        if (issue is ApiCall.Err) return issue
        val created = (issue as ApiCall.Ok).value

        val slackFiles = session.stillBytes.mapIndexed { index, bytes ->
            AttachmentFile("still-${index + 1}.png", "image/png", bytes)
        }.ifEmpty {
            session.videoBytes?.let { listOf(AttachmentFile("walkthrough.mp4", "video/mp4", it)) }.orEmpty()
        }
        val slackText = buildString {
            append("Walkthrough logged from Mark\n")
            append(created.url)
            append("\n\nVideo and stills are on the Jira ticket. Use the stills and transcript for implementation.")
            if (session.transcript.isNotBlank()) {
                append("\n\n")
                append(session.transcript.take(1500))
            }
        }
        val posted = SlackApi(store.slackToken).postFiles(
            channelId = store.channelId,
            text = slackText,
            files = slackFiles,
            mentionCursor = false,
            cursorUserId = null,
        )
        if (posted is ApiCall.Err) {
            return ApiCall.Err("Created ${created.key}, but Slack failed: ${posted.message}")
        }
        return ApiCall.Ok(LogResult(issue = created, built = false))
    }

    fun buildWalkthrough(session: WalkthroughSession): ApiCall<LogResult> {
        val logged = logWalkthrough(session)
        if (logged is ApiCall.Err) return logged
        val result = (logged as ApiCall.Ok).value
        return askCursor(
            result,
            extra = "Use the highlight stills and transcript on the ticket. The walkthrough video is for humans.",
        )
    }

    private fun askCursor(result: LogResult, extra: String? = null): ApiCall<LogResult> {
        val slack = SlackApi(store.slackToken)
        var cursorId = store.cursorUserId
        if (cursorId.isBlank()) {
            cursorId = slack.findCursorUserId().orEmpty()
            if (cursorId.isNotBlank()) store.cursorUserId = cursorId
        }
        val text = buildString {
            append("Do this ticket: ${result.issue.url}")
            if (!extra.isNullOrBlank()) {
                append("\n")
                append(extra)
            }
        }
        val followUp = slack.postText(
            channelId = store.channelId,
            text = text,
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
