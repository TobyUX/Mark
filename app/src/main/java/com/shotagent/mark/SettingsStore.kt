package com.shotagent.mark

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        @Suppress("DEPRECATION")
        prefs = EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var slackToken: String
        get() = prefs.getString(KEY_SLACK_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SLACK_TOKEN, value).apply()

    var channelId: String
        get() = prefs.getString(KEY_CHANNEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CHANNEL, value).apply()

    var cursorUserId: String
        get() = prefs.getString(KEY_CURSOR_USER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CURSOR_USER, value).apply()

    var jiraSite: String
        get() = prefs.getString(KEY_JIRA_SITE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_JIRA_SITE, value).apply()

    var jiraProject: String
        get() = prefs.getString(KEY_JIRA_PROJECT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_JIRA_PROJECT, value).apply()

    var jiraEmail: String
        get() = prefs.getString(KEY_JIRA_EMAIL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_JIRA_EMAIL, value).apply()

    var jiraToken: String
        get() = prefs.getString(KEY_JIRA_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_JIRA_TOKEN, value).apply()

    var jiraUrlInput: String
        get() = prefs.getString(KEY_JIRA_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_JIRA_URL, value).apply()

    var instruction: String
        get() = prefs.getString(KEY_INSTRUCTION, null) ?: DEFAULT_INSTRUCTION
        set(value) = prefs.edit().putString(KEY_INSTRUCTION, value).apply()

    val isReady: Boolean
        get() = slackToken.isNotBlank() &&
            channelId.isNotBlank() &&
            jiraSite.isNotBlank() &&
            jiraProject.isNotBlank() &&
            jiraEmail.isNotBlank() &&
            jiraToken.isNotBlank()

    companion object {
        const val DEFAULT_INSTRUCTION =
            "This is a UI change. The screenshot is the screen. Colored marks are part of the request. Do only what the note says. Open a pull request."
        const val FEEDBACK_URL = "https://forms.gle/QZupwv39LCg4yXaC8"

        private const val FILE_NAME = "mark_settings"
        private const val KEY_SLACK_TOKEN = "slack_token"
        private const val KEY_CHANNEL = "channel_id"
        private const val KEY_CURSOR_USER = "cursor_user_id"
        private const val KEY_JIRA_SITE = "jira_site"
        private const val KEY_JIRA_PROJECT = "jira_project"
        private const val KEY_JIRA_EMAIL = "jira_email"
        private const val KEY_JIRA_TOKEN = "jira_token"
        private const val KEY_JIRA_URL = "jira_url_input"
        private const val KEY_INSTRUCTION = "instruction"
    }
}
