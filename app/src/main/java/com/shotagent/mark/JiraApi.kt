package com.shotagent.mark

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class JiraApi(
    private val siteHost: String,
    private val email: String,
    private val apiToken: String,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val base = "https://${siteHost.trim().trimEnd('/')}/rest/api/3"
    private val auth = Credentials.basic(email.trim(), apiToken.trim())

    fun testAccess(projectKey: String): ApiCall<String> {
        val request = Request.Builder()
            .url("$base/project/${projectKey.trim()}")
            .header("Authorization", auth)
            .header("Accept", "application/json")
            .get()
            .build()
        return execute(request) { body ->
            val json = JSONObject(body)
            val key = json.optString("key").ifBlank { projectKey }
            val name = json.optString("name").ifBlank { key }
            "$key ($name)"
        }
    }

    fun createIssueWithAttachment(
        projectKey: String,
        summary: String,
        description: String,
        png: ByteArray,
    ): ApiCall<CreatedIssue> {
        val issueType = resolveIssueType(projectKey)
        if (issueType is ApiCall.Err) return issueType
        val typeName = (issueType as ApiCall.Ok).value

        val body = JSONObject()
            .put(
                "fields",
                JSONObject()
                    .put("project", JSONObject().put("key", projectKey.trim().uppercase()))
                    .put("summary", summary.take(255))
                    .put("issuetype", JSONObject().put("name", typeName))
                    .put("description", adfDocument(description)),
            )
        val create = Request.Builder()
            .url("$base/issue")
            .header("Authorization", auth)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val created = execute(create) { raw ->
            val json = JSONObject(raw)
            val key = json.getString("key")
            CreatedIssue(
                key = key,
                url = "https://$siteHost/browse/$key",
            )
        }
        if (created is ApiCall.Err) return created
        val issue = (created as ApiCall.Ok).value

        val attach = attachPng(issue.key, png)
        if (attach is ApiCall.Err) {
            return ApiCall.Err("Created ${issue.key}, but could not attach the screenshot: ${attach.message}")
        }
        return ApiCall.Ok(issue)
    }

    private fun resolveIssueType(projectKey: String): ApiCall<String> {
        val request = Request.Builder()
            .url("$base/issue/createmeta?projectKeys=${projectKey.trim()}&expand=projects.issuetypes")
            .header("Authorization", auth)
            .header("Accept", "application/json")
            .get()
            .build()
        return execute(request) { body ->
            val projects = JSONObject(body).optJSONArray("projects") ?: JSONArray()
            if (projects.length() == 0) throw IllegalStateException("Jira project not found")
            val types = projects.getJSONObject(0).optJSONArray("issuetypes") ?: JSONArray()
            val preferred = listOf("Task", "Story", "Bug", "Improvement")
            for (name in preferred) {
                for (index in 0 until types.length()) {
                    val type = types.getJSONObject(index)
                    if (type.optBoolean("subtask")) continue
                    if (type.optString("name").equals(name, ignoreCase = true)) {
                        return@execute type.getString("name")
                    }
                }
            }
            for (index in 0 until types.length()) {
                val type = types.getJSONObject(index)
                if (!type.optBoolean("subtask")) return@execute type.getString("name")
            }
            throw IllegalStateException("No Jira issue type available in that project")
        }
    }

    private fun attachPng(issueKey: String, png: ByteArray): ApiCall<Unit> {
        val request = Request.Builder()
            .url("$base/issue/$issueKey/attachments")
            .header("Authorization", auth)
            .header("X-Atlassian-Token", "no-check")
            .header("Accept", "application/json")
            .post(
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        "mark.png",
                        png.toRequestBody("image/png".toMediaType()),
                    )
                    .build(),
            )
            .build()
        return execute(request) { }
    }

    private fun adfDocument(text: String): JSONObject {
        val paragraphs = text.split("\n").ifEmpty { listOf("") }
        val content = JSONArray()
        paragraphs.forEach { line ->
            val paragraph = JSONObject().put("type", "paragraph")
            if (line.isNotEmpty()) {
                paragraph.put(
                    "content",
                    JSONArray().put(JSONObject().put("type", "text").put("text", line)),
                )
            }
            content.put(paragraph)
        }
        return JSONObject()
            .put("type", "doc")
            .put("version", 1)
            .put("content", content)
    }

    private fun <T> execute(request: Request, parse: (String) -> T): ApiCall<T> {
        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = raw.take(500).ifBlank { response.message }
                    return ApiCall.Err("Jira returned ${response.code}: $detail")
                }
                try {
                    ApiCall.Ok(parse(raw))
                } catch (error: Exception) {
                    ApiCall.Err(error.message ?: "Could not read the Jira response")
                }
            }
        } catch (error: Exception) {
            ApiCall.Err(error.message ?: "Could not reach Jira")
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

data class CreatedIssue(
    val key: String,
    val url: String,
)
