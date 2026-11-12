package com.shotagent.mark

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.shotagent.mark.ui.AboutScreen
import com.shotagent.mark.ui.SetupScreen
import com.shotagent.mark.ui.ShareScreen
import com.shotagent.mark.ui.WalkthroughReviewScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val store by lazy { SettingsStore(this) }
    private var sharedBytes by mutableStateOf<ByteArray?>(null)
    private var sharedError by mutableStateOf<String?>(null)
    private var shareToken by mutableStateOf(0)
    private var micGranted by mutableStateOf(false)
    private var walkthroughSession by mutableStateOf<WalkthroughSession?>(null)
    private var walkStatus by mutableStateOf<String?>(null)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted && pendingWalkthrough) {
            pendingWalkthrough = false
            requestOverlayThenProjection()
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val overlaySettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            requestProjection()
        } else {
            walkStatus = "Mark needs overlay permission for the floating Done button."
        }
    }
    private val projectionPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            WalkthroughService.start(this, result.resultCode, data)
            moveTaskToBack(true)
        } else {
            walkStatus = "Screen capture was not allowed."
        }
    }

    private var pendingWalkthrough = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        consumeShare(intent)
        consumeWalkthrough(intent)
        micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (shareToken > 0 && store.isReady && !micGranted) {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Surface(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    val scope = rememberCoroutineScope()
                    var showSetup by remember { mutableStateOf(false) }
                    var slackToken by remember { mutableStateOf(store.slackToken) }
                    var channelId by remember { mutableStateOf(store.channelId) }
                    var jiraUrl by remember { mutableStateOf(store.jiraUrlInput) }
                    var jiraSite by remember { mutableStateOf(store.jiraSite) }
                    var jiraProject by remember { mutableStateOf(store.jiraProject) }
                    var jiraEmail by remember { mutableStateOf(store.jiraEmail) }
                    var jiraToken by remember { mutableStateOf(store.jiraToken) }
                    var instruction by remember { mutableStateOf(store.instruction) }
                    var setupStatus by remember { mutableStateOf<String?>(null) }
                    var testing by remember { mutableStateOf(false) }
                    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
                    var sending by remember { mutableStateOf(false) }
                    var shareStatus by remember { mutableStateOf<String?>(null) }

                    val ready = slackToken.isNotBlank() &&
                        channelId.isNotBlank() &&
                        jiraSite.isNotBlank() &&
                        jiraProject.isNotBlank() &&
                        jiraEmail.isNotBlank() &&
                        jiraToken.isNotBlank()

                    LaunchedEffect(shareToken) {
                        val bytes = sharedBytes
                        bitmap = null
                        if (bytes == null) return@LaunchedEffect
                        val decoded = withContext(Dispatchers.IO) { decodeBytes(bytes) }
                        if (decoded == null) sharedError = "Could not read that image."
                        bitmap = decoded
                    }

                    fun applyJiraUrl(raw: String) {
                        jiraUrl = raw
                        store.jiraUrlInput = raw
                        val parsed = parseJiraUrl(raw)
                        if (parsed != null) {
                            jiraSite = parsed.siteHost
                            jiraProject = parsed.projectKey
                            store.jiraSite = parsed.siteHost
                            store.jiraProject = parsed.projectKey
                        }
                    }

                    fun runAction(build: Boolean, png: ByteArray, transcript: String) {
                        if (sending) return
                        sending = true
                        shareStatus = null
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                val workflow = MarkWorkflow(store)
                                if (build) workflow.build(png, transcript) else workflow.log(png, transcript)
                            }
                            sending = false
                            shareStatus = when (result) {
                                is ApiCall.Err -> result.message
                                is ApiCall.Ok -> {
                                    if (result.value.built) {
                                        "Built ${result.value.issue.key}. Cursor was asked in Slack."
                                    } else {
                                        "Logged ${result.value.issue.key} and posted to Slack."
                                    }
                                }
                            }
                        }
                    }

                    fun runWalkthrough(build: Boolean) {
                        val session = walkthroughSession ?: return
                        if (sending) return
                        sending = true
                        walkStatus = null
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                val workflow = MarkWorkflow(store)
                                if (build) {
                                    workflow.buildWalkthrough(session)
                                } else {
                                    workflow.logWalkthrough(session)
                                }
                            }
                            sending = false
                            walkStatus = when (result) {
                                is ApiCall.Err -> result.message
                                is ApiCall.Ok -> {
                                    if (result.value.built) {
                                        "Built ${result.value.issue.key}. Cursor was asked in Slack."
                                    } else {
                                        "Logged ${result.value.issue.key} and posted to Slack."
                                    }
                                }
                            }
                        }
                    }

                    val openingShare = shareToken > 0
                    val reviewingWalk = walkthroughSession != null
                    when {
                        reviewingWalk -> {
                            val session = walkthroughSession!!
                            WalkthroughReviewScreen(
                                stillCount = session.stillFiles.size,
                                hasVideo = session.videoFile != null,
                                transcript = session.transcript,
                                sending = sending,
                                status = walkStatus,
                                onLog = { runWalkthrough(false) },
                                onBuild = { runWalkthrough(true) },
                            )
                        }

                        openingShare && ready -> {
                            val loaded = bitmap
                            if (loaded == null) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (sharedError == null) {
                                            CircularProgressIndicator()
                                            Text(
                                                "Opening the screenshot",
                                                modifier = Modifier.padding(top = 16.dp),
                                            )
                                        } else {
                                            Text(sharedError ?: "Could not read that image.")
                                        }
                                    }
                                }
                            } else {
                                ShareScreen(
                                    bitmap = loaded,
                                    sending = sending,
                                    status = shareStatus,
                                    micGranted = micGranted,
                                    onLog = { png, transcript -> runAction(false, png, transcript) },
                                    onBuild = { png, transcript -> runAction(true, png, transcript) },
                                )
                            }
                        }

                        openingShare && !ready || showSetup -> {
                            SetupScreen(
                                slackToken = slackToken,
                                channelId = channelId,
                                jiraUrl = jiraUrl,
                                jiraSite = jiraSite,
                                jiraProject = jiraProject,
                                jiraEmail = jiraEmail,
                                jiraToken = jiraToken,
                                instruction = instruction,
                                status = setupStatus,
                                testing = testing,
                                needsSetupForShare = openingShare && !ready,
                                onSlackToken = {
                                    val trimmed = it.trim()
                                    slackToken = trimmed
                                    store.slackToken = trimmed
                                },
                                onChannelId = {
                                    val trimmed = it.trim()
                                    channelId = trimmed
                                    store.channelId = trimmed
                                },
                                onJiraUrl = { applyJiraUrl(it) },
                                onJiraEmail = {
                                    val trimmed = it.trim()
                                    jiraEmail = trimmed
                                    store.jiraEmail = trimmed
                                },
                                onJiraToken = {
                                    val trimmed = it.trim()
                                    jiraToken = trimmed
                                    store.jiraToken = trimmed
                                },
                                onInstruction = {
                                    instruction = it
                                    store.instruction = it
                                },
                                onTest = {
                                    testing = true
                                    setupStatus = null
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            val slack = SlackApi(store.slackToken).testAuth()
                                            if (slack is ApiCall.Err) return@withContext slack
                                            val jira = JiraApi(
                                                store.jiraSite,
                                                store.jiraEmail,
                                                store.jiraToken,
                                            ).testAccess(store.jiraProject)
                                            if (jira is ApiCall.Err) return@withContext jira
                                            val cursorId = SlackApi(store.slackToken).findCursorUserId()
                                            if (!cursorId.isNullOrBlank()) store.cursorUserId = cursorId
                                            ApiCall.Ok(
                                                "Slack: ${(slack as ApiCall.Ok).value}. Jira: ${(jira as ApiCall.Ok).value}." +
                                                    if (!cursorId.isNullOrBlank()) " Found @Cursor." else "",
                                            )
                                        }
                                        testing = false
                                        setupStatus = when (result) {
                                            is ApiCall.Ok -> result.value
                                            is ApiCall.Err -> result.message
                                        }
                                    }
                                },
                                onBack = if (openingShare) null else ({ showSetup = false }),
                            )
                        }

                        else -> {
                            Column(Modifier.fillMaxSize()) {
                                AboutScreen(
                                    setupReady = ready,
                                    onOpenSetup = { showSetup = true },
                                    onStartWalkthrough = { beginWalkthrough() },
                                    onFeedback = {
                                        startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse(SettingsStore.FEEDBACK_URL),
                                            ),
                                        )
                                    },
                                )
                                if (!walkStatus.isNullOrBlank()) {
                                    Text(
                                        walkStatus ?: "",
                                        modifier = Modifier.padding(24.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeShare(intent)
        consumeWalkthrough(intent)
        if (shareToken > 0 && store.isReady && !micGranted) {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun beginWalkthrough() {
        walkStatus = null
        if (!store.isReady) {
            walkStatus = "Set up Slack and Jira first."
            return
        }
        if (!micGranted) {
            pendingWalkthrough = true
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        requestOverlayThenProjection()
    }

    private fun requestOverlayThenProjection() {
        if (!Settings.canDrawOverlays(this)) {
            overlaySettings.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionPermission.launch(mgr.createScreenCaptureIntent())
    }

    private fun consumeWalkthrough(intent: Intent?) {
        if (intent?.action != WalkthroughService.ACTION_REVIEW) return
        val transcript = intent.getStringExtra(WalkthroughService.EXTRA_TRANSCRIPT).orEmpty()
        val videoPath = intent.getStringExtra(WalkthroughService.EXTRA_VIDEO_PATH)
        val stillPaths = intent.getStringArrayListExtra(WalkthroughService.EXTRA_STILL_PATHS).orEmpty()
        walkthroughSession = WalkthroughSession(
            videoFile = videoPath?.let { File(it) }?.takeIf { it.exists() },
            transcript = transcript,
            stillFiles = stillPaths.map(::File).filter { it.exists() },
        )
        walkStatus = null
    }

    private fun consumeShare(intent: Intent?) {
        val uri = intent?.shareUri() ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            sharedBytes = bytes?.takeIf { it.isNotEmpty() }
            sharedError = if (sharedBytes == null) "Could not read that image." else null
        } catch (error: Exception) {
            sharedBytes = null
            sharedError = "Could not read that image. Share it to Mark again."
        }
        shareToken += 1
    }

    private fun decodeBytes(bytes: ByteArray): Bitmap? =
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 1600)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (error: Throwable) {
            null
        }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var size = 1
        val wide = width.coerceAtLeast(1)
        val high = height.coerceAtLeast(1)
        while (wide / size > maxEdge || high / size > maxEdge) size *= 2
        return size
    }
}

private fun android.content.Intent.shareUri(): Uri? {
    if (action != android.content.Intent.ACTION_SEND) return null
    val mime = type
    if (mime != null && !mime.startsWith("image/")) return null
    val extra = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(android.content.Intent.EXTRA_STREAM)
    }
    if (extra != null) return extra
    val clip = clipData ?: return null
    for (index in 0 until clip.itemCount) {
        val uri = clip.getItemAt(index).uri ?: continue
        return uri
    }
    return null
}
