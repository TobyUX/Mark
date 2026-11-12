package com.shotagent.mark

data class JiraTarget(
    val siteHost: String,
    val projectKey: String,
)

fun parseJiraUrl(raw: String): JiraTarget? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val withScheme = when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        else -> "https://$trimmed"
    }
    val uri = runCatching { android.net.Uri.parse(withScheme) }.getOrNull() ?: return null
    val host = uri.host?.lowercase()?.trim('.').orEmpty()
    if (host.isBlank() || !host.contains("atlassian.net")) return null

    val path = uri.path.orEmpty()
    val projectMatch = Regex("""/projects/([A-Za-z][A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
        .find(path)
    val browseMatch = Regex("""/browse/([A-Za-z][A-Za-z0-9]+)-\d+""", RegexOption.IGNORE_CASE)
        .find(path)
    val projectKey = projectMatch?.groupValues?.getOrNull(1)
        ?: browseMatch?.groupValues?.getOrNull(1)
        ?: return null

    return JiraTarget(siteHost = host, projectKey = projectKey.uppercase())
}
