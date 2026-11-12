# Mark

Screenshot something that needs changing on Android. Share it to Mark, circle it, say what to do, then **Log** or **Build**.

- **Log** — creates a Jira ticket with the marked screenshot and posts it to your Slack channel
- **Build** — same as Log, then asks `@Cursor` in Slack to do that ticket (uses *your* Cursor account)

Secrets stay on the phone. Mark does not ship with anyone else's Slack or Jira.

## Install (easiest)

1. Download `Mark.apk` from the latest [Release](https://github.com/TobyUX/Mark/releases)
2. Open it on your Android phone and install (allow installs from that source if asked)

## Set up once

1. Open Mark → **Set up Mark**
2. **Slack**
   - Create a Slack app with user token scopes: `chat:write`, `files:write`, `channels:read`, `users:read`
   - Install it to your workspace and paste the **User OAuth Token** (`xoxp-…`)
   - Paste the channel ID Mark should always use
3. **Jira**
   - Paste a board or project URL (Mark reads the site and project key)
   - Paste your Atlassian email and an [API token](https://id.atlassian.com/manage-profile/security/api-tokens)
4. Tap **Test Slack and Jira**

For **Build**, connect Cursor to your Slack workspace and set that channel’s default repository with `@Cursor settings`.

## Build from source

```bash
git clone https://github.com/TobyUX/Mark.git
cd Mark
# create local.properties with sdk.dir=/path/to/Android/sdk
./gradlew :app:assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Feedback

Use **Send feedback** in the app, or open [this form](https://forms.gle/QZupwv39LCg4yXaC8).
