package com.shotagent.mark

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SlackApi(private val token: String) {
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    fun testAuth(): ApiCall<String> {
        val request = Request.Builder()
            .url("$BASE/auth.test")
            .header("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return execute(request) { body ->
            val json = JSONObject(body)
            requireOk(json)
            val user = json.optString("user").ifBlank { "you" }
            val team = json.optString("team").ifBlank { "Slack" }
            "$user on $team"
        }
    }

    fun postScreenshot(
        channelId: String,
        text: String,
        png: ByteArray,
        mentionCursor: Boolean,
        cursorUserId: String?,
    ): ApiCall<PostedMessage> {
        val comment = withMention(text, mentionCursor, cursorUserId)
        return uploadFile(channelId, png, comment)
    }

    fun postText(
        channelId: String,
        text: String,
        mentionCursor: Boolean,
        cursorUserId: String?,
    ): ApiCall<PostedMessage> {
        val body = JSONObject()
            .put("channel", channelId)
            .put("text", withMention(text, mentionCursor, cursorUserId))
        val request = Request.Builder()
            .url("$BASE/chat.postMessage")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json; charset=utf-8")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return execute(request) { raw ->
            val json = JSONObject(raw)
            requireOk(json)
            PostedMessage(
                channelId = channelId,
                fileId = json.optString("ts"),
            )
        }
    }

    fun findCursorUserId(): String? {
        var page: String? = null
        repeat(10) {
            val url = buildString {
                append("$BASE/users.list?limit=200")
                if (!page.isNullOrBlank()) append("&cursor=").append(page)
            }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val result = execute(request) { body ->
                val json = JSONObject(body)
                requireOk(json)
                val members = json.optJSONArray("members") ?: JSONArray()
                var found: String? = null
                for (index in 0 until members.length()) {
                    val member = members.getJSONObject(index)
                    if (member.optBoolean("deleted")) continue
                    val name = member.optString("name")
                    val realName = member.optString("real_name")
                    val display = member.optJSONObject("profile")?.optString("display_name").orEmpty()
                    val isBot = member.optBoolean("is_bot") || member.optBoolean("is_app_user")
                    val haystack = listOf(name, realName, display).joinToString(" ").lowercase()
                    if (isBot && haystack.contains("cursor")) {
                        found = member.getString("id")
                        break
                    }
                }
                val next = json.optJSONObject("response_metadata")?.optString("next_cursor").orEmpty()
                found to next.ifBlank { null }
            }
            when (result) {
                is ApiCall.Err -> return null
                is ApiCall.Ok -> {
                    val (found, next) = result.value
                    if (found != null) return found
                    page = next
                    if (page == null) return null
                }
            }
        }
        return null
    }

    private fun withMention(
        text: String,
        mentionCursor: Boolean,
        cursorUserId: String?,
    ): String {
        if (!mentionCursor) return text.trim()
        val mention = cursorUserId
            ?.takeIf { it.isNotBlank() }
            ?.let { "<@$it>" }
            ?: "@Cursor"
        return "$mention\n\n${text.trim()}".trim()
    }

    private fun uploadFile(
        channelId: String,
        png: ByteArray,
        initialComment: String,
    ): ApiCall<PostedMessage> {
        val length = png.size
        val getUrlRequest = Request.Builder()
            .url("$BASE/files.getUploadURLExternal")
            .header("Authorization", "Bearer $token")
            .post(
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("filename", "mark.png")
                    .addFormDataPart("length", length.toString())
                    .build(),
            )
            .build()
        val uploadSlot = execute(getUrlRequest) { body ->
            val json = JSONObject(body)
            requireOk(json)
            json.getString("upload_url") to json.getString("file_id")
        }
        if (uploadSlot is ApiCall.Err) return uploadSlot
        val (uploadUrl, fileId) = (uploadSlot as ApiCall.Ok).value

        val put = Request.Builder()
            .url(uploadUrl)
            .post(png.toRequestBody("image/png".toMediaType()))
            .build()
        client.newCall(put).execute().use { response ->
            if (!response.isSuccessful) {
                return ApiCall.Err("Slack upload failed: ${response.code}")
            }
        }

        val completeBody = JSONObject()
            .put(
                "files",
                JSONArray().put(JSONObject().put("id", fileId).put("title", "Mark screenshot")),
            )
            .put("channel_id", channelId)
            .put("initial_comment", initialComment)
        val complete = Request.Builder()
            .url("$BASE/files.completeUploadExternal")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json; charset=utf-8")
            .post(completeBody.toString().toRequestBody(JSON))
            .build()
        return execute(complete) { body ->
            val json = JSONObject(body)
            requireOk(json)
            PostedMessage(channelId = channelId, fileId = fileId)
        }
    }

    private fun requireOk(json: JSONObject) {
        if (!json.optBoolean("ok")) {
            val error = json.optString("error").ifBlank { "unknown_error" }
            throw IllegalStateException("Slack error: $error")
        }
    }

    private fun <T> execute(request: Request, parse: (String) -> T): ApiCall<T> {
        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = raw.take(500).ifBlank { response.message }
                    return ApiCall.Err("Slack returned ${response.code}: $detail")
                }
                try {
                    ApiCall.Ok(parse(raw))
                } catch (error: Exception) {
                    ApiCall.Err(error.message ?: "Could not read the Slack response")
                }
            }
        } catch (error: Exception) {
            ApiCall.Err(error.message ?: "Could not reach Slack")
        }
    }

    companion object {
        private const val BASE = "https://slack.com/api"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

data class PostedMessage(
    val channelId: String,
    val fileId: String,
)
