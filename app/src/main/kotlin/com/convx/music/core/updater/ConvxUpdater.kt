/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.core.updater


import com.convx.music.ui.component.sf
import com.convx.music.ui.component.SymbolIcon
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.tencent.mmkv.MMKV
import timber.log.Timber
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import com.convx.music.ui.ios.compat.Icon
import com.convx.music.ui.ios.compat.AppTheme
import com.convx.music.ui.ios.compat.Scaffold
import com.convx.music.ui.ios.compat.Surface
import com.convx.music.ui.ios.compat.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.convx.music.BuildConfig
import com.convx.music.R
import coil3.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.convx.music.core.updater.downloadmanager.UpdateDownloadWorker
import com.convx.music.core.updater.downloadmanager.DownloadNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import com.convx.music.ui.component.ChangelogItem
import com.convx.music.ui.component.leadingItemShape
import com.convx.music.ui.component.middleItemShape
import com.convx.music.ui.component.endItemShape
import com.convx.music.ui.component.detachedItemShape
import com.convx.music.ui.component.AnimatedActionButton
import com.convx.music.ui.component.ExpressiveIconButton
import com.convx.music.ui.component.ErrorSnackbar
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import com.convx.music.ui.ios.compat.CircularProgressIndicator
import com.convx.music.ui.ios.compat.LargeTopAppBar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.style.TextDecoration

data class ChangelogSection(val title: String, val items: List<String>)

sealed class ConvxUpdateStatus {
    object Idle : ConvxUpdateStatus()
    object Checking : ConvxUpdateStatus()
    data class Available(
        val version: String,
        val changelog: List<ChangelogSection>,
        val size: String,
        val releaseDate: String,
        val description: String?,
        val imageUrl: String?,
        val apkUrl: String?
    ) : ConvxUpdateStatus()

    data class NoUpdate(val version: String) : ConvxUpdateStatus()
    data class Error(val message: String) : ConvxUpdateStatus()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ConvxUpdateStatus>(ConvxUpdateStatus.NoUpdate(BuildConfig.VERSION_NAME)) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var isDownloadComplete by remember { mutableStateOf(false) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val currentVersion = BuildConfig.VERSION_NAME
    val autoUpdateCheckEnabled = getAutoUpdateCheckSetting(context)

    LaunchedEffect(Unit) {
        DownloadNotificationManager.initialize(context)
    }

    // Observe WorkManager for download progress
    LaunchedEffect(Unit) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData("update_download")
            .observeForever { workInfos ->
                val workInfo = workInfos?.firstOrNull() ?: return@observeForever

                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        isDownloading = true
                        downloadProgress = workInfo.progress.getFloat("progress", 0f)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        isDownloading = false
                        isDownloadComplete = true
                        val filePath = workInfo.outputData.getString("file_path")
                        if (filePath != null) {
                            downloadedFile = File(filePath)
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        isDownloading = false
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.download_failed))
                        }
                    }
                    WorkInfo.State.CANCELLED -> {
                        isDownloading = false
                        downloadProgress = 0f
                    }
                    else -> {}
                }
            }
    }

    LaunchedEffect(isDownloadComplete, downloadedFile) {
        if (isDownloadComplete && downloadedFile?.exists() == false) {
            isDownloadComplete = false
            downloadedFile = null
            downloadProgress = 0f
        }
    }

    fun triggerUpdateCheck() {
        status = ConvxUpdateStatus.Checking
        scope.launch {
            // Keep the checking state visible before starting the request.
            delay(1000L)
            checkForUpdate(
                context = context,
                onSuccess = { tag, isAvailable, changelog, size, date, description, imageUrl, apkUrl ->
                    saveLastCheckedTime(context, LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a")))
                    saveUpdateAvailableState(context, isAvailable)
                    status = if (isAvailable) {
                        ConvxUpdateStatus.Available(
                            version = tag,
                            changelog = changelog,
                            size = size,
                            releaseDate = date,
                            description = description,
                            imageUrl = imageUrl,
                            apkUrl = apkUrl
                        )
                    } else {
                        ConvxUpdateStatus.NoUpdate(tag)
                    }
                },
                onError = {
                    status = ConvxUpdateStatus.Error(context.getString(R.string.cant_check_updates))
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        if (autoUpdateCheckEnabled) {
            triggerUpdateCheck()
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    val titleText = if (status is ConvxUpdateStatus.Available) {
                        buildAnnotatedString {
                            append(stringResource(R.string.new_update) + " ")
                            withStyle(
                                SpanStyle(
                                    color = AppTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append((status as ConvxUpdateStatus.Available).version)
                            }
                        }
                    } else {
                        AnnotatedString(stringResource(R.string.settings_check_updates_title))
                    }
                    Text(text = titleText, maxLines = 1)
                },
                navigationIcon = {
                    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
                        ExpressiveIconButton(
                            onClick = { navController.navigateUp() },
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.cancel),
                            containerColor = AppTheme.colorScheme.surfaceContainerHigh,
                            contentColor = AppTheme.colorScheme.onSurface
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = AppTheme.colorScheme.background,
                    scrolledContainerColor = AppTheme.colorScheme.background,
                    titleContentColor = AppTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            Surface(
                color = AppTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(WindowInsets.navigationBars.asPaddingValues())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (val currentStatus = status) {
                        is ConvxUpdateStatus.Idle, is ConvxUpdateStatus.Checking, is ConvxUpdateStatus.NoUpdate, is ConvxUpdateStatus.Error -> {
                            AnimatedActionButton(
                                text = stringResource(R.string.check_for_update),
                                onClick = { triggerUpdateCheck() },
                                enabled = currentStatus !is ConvxUpdateStatus.Checking && !isDownloading,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        is ConvxUpdateStatus.Available -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AnimatedActionButton(
                                    text = stringResource(R.string.later),
                                    onClick = { navController.navigateUp() },
                                    modifier = Modifier.weight(1f),
                                    isOutlined = true,
                                    enabled = !isDownloading
                                )
                                AnimatedActionButton(
                                    text = if (isDownloading) "${(downloadProgress * 100).toInt()}%" else if (isDownloadComplete) stringResource(R.string.install) else stringResource(R.string.update_available),
                                    onClick = {
                                        if (isDownloadComplete) {
                                            val file = downloadedFile
                                            if (file == null || !file.exists()) {
                                                isDownloadComplete = false
                                                downloadedFile = null
                                                downloadProgress = 0f
                                                return@AnimatedActionButton
                                            }
                                            file.let { f ->
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    if (!context.packageManager.canRequestPackageInstalls()) {
                                                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                                            data = Uri.parse("package:${context.packageName}")
                                                        }
                                                        context.startActivity(intent)
                                                        return@let
                                                    }
                                                }
                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
                                                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "application/vnd.android.package-archive")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                ContextCompat.startActivity(context, installIntent, null)
                                            }
                                        } else {
                                            val urlToDownload = currentStatus.apkUrl ?: "https://github.com/cosmictaserdev-creator/Convx/releases/download/${currentStatus.version}/convx-${currentStatus.version}.apk"
                                            val downloadRequest = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
                                                .setInputData(workDataOf("apk_url" to urlToDownload, "version" to currentStatus.version, "file_size" to currentStatus.size))
                                                .addTag("update_download")
                                                .build()
                                            WorkManager.getInstance(context).enqueueUniqueWork("update_download", ExistingWorkPolicy.REPLACE, downloadRequest)
                                            isDownloading = true
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !isDownloading || isDownloadComplete
                                )
                            }
                        }
                    }
                }
            }
        },
        snackbarHost = { ErrorSnackbar(hostState = snackbarHostState) },
        containerColor = AppTheme.colorScheme.background,
        modifier = Modifier
            .widthIn(max = 700.dp)
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    val contentModifier = if (status is ConvxUpdateStatus.Available) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.fillParentMaxSize()
                    }

                    Box(
                        modifier = contentModifier,
                        contentAlignment = Alignment.Center
                    ) {
                        when (val currentStatus = status) {
                            is ConvxUpdateStatus.Checking -> {
                                com.convx.music.ui.component.ShimmerBox(
                                    modifier = Modifier
                                        .size(width = 160.dp, height = 64.dp),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                )
                            }

                            is ConvxUpdateStatus.NoUpdate -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.deployed_app_update),
                                        contentDescription = null,
                                        modifier = Modifier.size(120.dp),
                                        tint = AppTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Text(
                                        text = stringResource(R.string.on_latest_version),
                                        style = AppTheme.typography.titleLarge,
                                        color = AppTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.current_version_v, currentStatus.version),
                                        style = AppTheme.typography.bodyMedium,
                                        color = AppTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            is ConvxUpdateStatus.Error -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    SymbolIcon(symbol = sf("exclamationmark.circle"), contentDescription = null, size = 120.dp, tint = AppTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Text(
                                        text = currentStatus.message,
                                        style = AppTheme.typography.titleLarge,
                                        color = AppTheme.colorScheme.error,
                                        textAlign = TextAlign.Center,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            is ConvxUpdateStatus.Available -> {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = stringResource(R.string.release_date_v, currentStatus.releaseDate),
                                        style = AppTheme.typography.bodyMedium,
                                        color = AppTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (currentStatus.size.isNotBlank()) {
                                        Text(
                                            text = stringResource(R.string.update_size_v, currentStatus.size),
                                            style = AppTheme.typography.bodyMedium,
                                            color = AppTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(24.dp))
                                    if (!currentStatus.imageUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = currentStatus.imageUrl,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(200.dp)
                                                .clip(RoundedCornerShape(24.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                        Spacer(modifier = Modifier.height(24.dp))
                                    }
                                    if (!currentStatus.description.isNullOrBlank()) {
                                        val urls = currentStatus.description.extractUrls()
                                        val annotatedText = buildAnnotatedString {
                                            append(currentStatus.description.trim())
                                            urls.forEach { (range, url) ->
                                                addStringAnnotation("URL", url, range.first, range.last + 1)
                                                addStyle(
                                                    SpanStyle(
                                                        color = AppTheme.colorScheme.primary,
                                                        textDecoration = TextDecoration.Underline
                                                    ),
                                                    range.first,
                                                    range.last + 1
                                                )
                                            }
                                        }

                                        ClickableText(
                                            text = annotatedText,
                                            onClick = { offset ->
                                                annotatedText.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                                                    ContextCompat.startActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(it.item)), null)
                                                }
                                            },
                                            style = AppTheme.typography.bodyMedium.copy(
                                                color = AppTheme.colorScheme.onSurfaceVariant,
                                                lineHeight = 20.sp
                                            ),
                                            modifier = Modifier.padding(bottom = 24.dp)
                                        )
                                    }
                                    if (isDownloading) {
                                        if (downloadProgress > 0f) {
                                            androidx.compose.material3.LinearProgressIndicator(
                                                progress = downloadProgress,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(8.dp)
                                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                                                color = AppTheme.colorScheme.primary,
                                                trackColor = AppTheme.colorScheme.surfaceContainerHigh
                                            )
                                        } else {
                                            androidx.compose.material3.LinearProgressIndicator(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(8.dp)
                                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                                                color = AppTheme.colorScheme.primary,
                                                trackColor = AppTheme.colorScheme.surfaceContainerHigh
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(24.dp))
                                    }
                                    currentStatus.changelog.forEach { section ->
                                        if (section.title.isNotBlank()) {
                                            Text(
                                                text = section.title,
                                                style = AppTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                                            )
                                        }
                                        section.items.forEachIndexed { index, item ->
                                            val shape = when {
                                                section.items.size == 1 -> com.convx.music.ui.component.detachedItemShape()
                                                index == 0 -> com.convx.music.ui.component.leadingItemShape()
                                                index == section.items.size - 1 -> com.convx.music.ui.component.endItemShape()
                                                else -> com.convx.music.ui.component.middleItemShape()
                                            }
                                            com.convx.music.ui.component.ChangelogItem(
                                                text = item,
                                                shape = shape,
                                                modifier = Modifier.padding(vertical = 1.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}


const val PREFS_NAME = "settings"
const val KEY_AUTO_UPDATE_CHECK = "auto_update_check"
const val KEY_LAST_CHECKED_TIME = "last_checked_time"
const val KEY_BETA_UPDATES = "beta_updates"
const val KEY_UPDATE_AVAILABLE = "update_available"
const val AUTOMATIC_UPDATE_ALERTS_PAUSED = false

const val KEY_LAST_NOTIFIED_TAG = "last_notified_update_tag"

fun getLastNotifiedUpdateTag(): String = updatePrefs().decodeString(KEY_LAST_NOTIFIED_TAG, "") ?: ""

fun saveLastNotifiedUpdateTag(tag: String) {
    updatePrefs().encode(KEY_LAST_NOTIFIED_TAG, tag)
}

private fun updatePrefs(): MMKV = MMKV.mmkvWithID(PREFS_NAME)

fun getUpdateAvailableState(context: Context): Boolean =
    !AUTOMATIC_UPDATE_ALERTS_PAUSED && updatePrefs().decodeBool(KEY_UPDATE_AVAILABLE, false)

fun saveUpdateAvailableState(context: Context, available: Boolean) {
    updatePrefs().encode(KEY_UPDATE_AVAILABLE, available)
}

fun getAutoUpdateCheckSetting(context: Context): Boolean =
    !AUTOMATIC_UPDATE_ALERTS_PAUSED && updatePrefs().decodeBool(KEY_AUTO_UPDATE_CHECK, false)

fun saveAutoUpdateCheckSetting(context: Context, enabled: Boolean) {
    updatePrefs().encode(KEY_AUTO_UPDATE_CHECK, enabled)
}

const val KEY_UPDATE_NOTIFICATIONS = "update_notifications"

fun getUpdateNotificationsSetting(context: Context): Boolean =
    !AUTOMATIC_UPDATE_ALERTS_PAUSED && updatePrefs().decodeBool(KEY_UPDATE_NOTIFICATIONS, false)

fun saveUpdateNotificationsSetting(context: Context, enabled: Boolean) {
    updatePrefs().encode(KEY_UPDATE_NOTIFICATIONS, enabled)
}

fun saveLastCheckedTime(context: Context, timestamp: String) {
    updatePrefs().encode(KEY_LAST_CHECKED_TIME, timestamp)
}

fun getLastCheckedTime(context: Context): String =
    updatePrefs().decodeString(KEY_LAST_CHECKED_TIME, "") ?: ""

fun getBetaUpdatesSetting(context: Context): Boolean =
    updatePrefs().decodeBool(KEY_BETA_UPDATES, false)

fun saveBetaUpdatesSetting(context: Context, enabled: Boolean) {
    updatePrefs().encode(KEY_BETA_UPDATES, enabled)
}

private fun formatGitHubDate(githubDate: String): String = try {
    val githubFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
    val displayFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a")
    val dateTime = LocalDateTime.parse(githubDate, githubFormatter)
    dateTime.format(displayFormatter)
} catch (e: Exception) {
    githubDate
}

fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
    val latestVersionClean = latestVersion.removePrefix("b").removePrefix("v")
    val currentVersionClean = currentVersion.removePrefix("b").removePrefix("v")

    val latestParts = latestVersionClean.split(".").map { it.toIntOrNull() ?: 0 }
    val currentParts = currentVersionClean.split(".").map { it.toIntOrNull() ?: 0 }
    
    for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
        val latest = latestParts.getOrElse(i) { 0 }
        val current = currentParts.getOrElse(i) { 0 }
        when {
            latest > current -> return true
            latest < current -> return false
        }
    }
    
    // For equal version numbers, prefer stable over beta.
    if (latestVersionClean == currentVersionClean) {
        val latestIsBeta = latestVersion.startsWith("b")
        val currentIsBeta = currentVersion.startsWith("b")
        if (currentIsBeta && !latestIsBeta) return true
    }
    
    return false
}

// Returns the nightly artifact size for display, or empty when unavailable.
private fun fetchNightlyArtifactSize(runId: Long): String = try {
    val artifactsUrl =
        URL("https://api.github.com/repos/cosmictaserdev-creator/Convx/actions/runs/$runId/artifacts")
    val artifacts = JSONObject(artifactsUrl.openStream().bufferedReader().use { it.readText() })
        .optJSONArray("artifacts")
    val bytes = artifacts?.let { artifactArray ->
        (0 until artifactArray.length())
            .map { artifactArray.getJSONObject(it) }
            .firstOrNull { it.optString("name") == "convx-gms-nightly" }
            ?.optLong("size_in_bytes")
    } ?: 0L
    if (bytes > 0) String.format("%.1f", bytes / (1024.0 * 1024.0)) else ""
} catch (e: Exception) {
    Timber.tag("UpdateCheck").e(e, "Error fetching nightly artifact size: ${e.message}")
    ""
}

// Finds the newest release eligible for the user's update preference.
suspend fun checkForUpdate(
    context: Context,
    onSuccess: (tag: String, isAvailable: Boolean, changelog: List<ChangelogSection>, size: String, date: String, description: String?, imageUrl: String?, apkUrl: String?) -> Unit,
    onError: () -> Unit,
) {
    withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/cosmictaserdev-creator/Convx/releases")
            val json = url.openStream().bufferedReader().use { it.readText() }
            val releases = JSONArray(json)
            
            val currentVersion = BuildConfig.VERSION_NAME
            val betaEnabled = getBetaUpdatesSetting(context)

            var isNightlyUpdate = false
            var nightlyRunObject: JSONObject? = null

            if (betaEnabled) {
                try {
                    val nightlyUrl = URL("https://api.github.com/repos/cosmictaserdev-creator/Convx/actions/workflows/nightly.yml/runs?status=success&per_page=1")
                    val nightlyJson = nightlyUrl.openStream().bufferedReader().use { it.readText() }
                    val nightlyData = JSONObject(nightlyJson)
                    val runs = nightlyData.optJSONArray("workflow_runs")
                    if (runs != null && runs.length() > 0) {
                        val firstRun = runs.getJSONObject(0)
                        val runNumber = firstRun.getInt("run_number")

                        // Stable and local builds embed 0 and are never offered nightlies.
                        if (BuildConfig.NIGHTLY_RUN > 0 && runNumber > BuildConfig.NIGHTLY_RUN) {
                            isNightlyUpdate = true
                            nightlyRunObject = firstRun
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("UpdateCheck").e(e, "Error checking nightly updates: ${e.message}")
                }
            }

            if (isNightlyUpdate && nightlyRunObject != null) {
                val runNumber = nightlyRunObject.getInt("run_number")
                val runUpdatedAt = nightlyRunObject.getString("updated_at")
                val displayTag = "nightly-r$runNumber"
                
                val changelogList = mutableListOf<ChangelogSection>()
                val headCommit = nightlyRunObject.optJSONObject("head_commit")
                val commitMessage = headCommit?.optString("message") ?: "New features and bug fixes"
                // Use only the commit subject as the nightly changelog entry.
                val subjectLine = commitMessage.lineSequence().firstOrNull { it.isNotBlank() } ?: commitMessage
                changelogList.add(ChangelogSection(context.getString(R.string.changelog), listOf(subjectLine)))
                
                val formattedReleaseDate = formatGitHubDate(runUpdatedAt)
                val apkDownloadUrl = "https://nightly.link/cosmictaserdev-creator/Convx/workflows/nightly.yml/main/convx-gms-nightly.zip"
                val apkSize = fetchNightlyArtifactSize(nightlyRunObject.getLong("id"))

                withContext(Dispatchers.Main) {
                    onSuccess(displayTag, true, changelogList, apkSize, formattedReleaseDate, "Bleeding-edge nightly build from main branch.", null, apkDownloadUrl)
                }
                return@withContext
            }

            var bestStableRelease: JSONObject? = null
            var bestOverallRelease: JSONObject? = null

            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                val tagName = release.getString("tag_name")
                // Pre-releases (nightly) only reach users who opted into beta updates.
                val isBeta = tagName.startsWith("b") || release.optBoolean("prerelease", false)
                
                if (!isBeta) {
                    if (bestStableRelease == null || isNewerVersion(tagName, bestStableRelease.getString("tag_name"))) {
                        bestStableRelease = release
                    }
                }
                
                if (bestOverallRelease == null || isNewerVersion(tagName, bestOverallRelease.getString("tag_name"))) {
                    bestOverallRelease = release
                }
            }

            val targetRelease = if (betaEnabled) bestOverallRelease else bestStableRelease

            if (targetRelease != null) {
                val targetTagName = targetRelease.getString("tag_name")
                if (isNewerVersion(targetTagName, currentVersion)) {
                    val tagWithPrefix = targetRelease.getString("tag_name")
                    val displayTag = tagWithPrefix

                    // Load the structured changelog asset when available.
                    val changelogList = mutableListOf<ChangelogSection>()
                    var description: String? = null
                    var imageUrl: String? = null
                    try {
                        val changelogUrl =
                            URL("https://github.com/cosmictaserdev-creator/Convx/releases/download/$tagWithPrefix/changelog.json")
                        val changelogJson = changelogUrl.openStream().bufferedReader().use { it.readText() }
                        val changelogData = JSONObject(changelogJson)

                        description = changelogData.optString("description").takeIf { it.isNotEmpty() }
                        imageUrl = changelogData.optString("image").takeIf { it.isNotEmpty() }

                        val changelogArray = changelogData.getJSONArray("changelog")
                        for (j in 0 until changelogArray.length()) {
                            val sectionObj = changelogArray.getJSONObject(j)
                            val title = sectionObj.getString("title")
                            val itemsArray = sectionObj.getJSONArray("items")
                            val itemsList = mutableListOf<String>()
                            for (k in 0 until itemsArray.length()) {
                                itemsList.add(itemsArray.getString(k))
                            }
                            changelogList.add(ChangelogSection(title, itemsList))
                        }
                    } catch (e: Exception) {
                        // Fall back to the release body when the changelog asset is missing or invalid.
                        val body = targetRelease.optString("body", context.getString(R.string.no_changelog_available))
                        val fallbackItems = body.split("\n").filter { it.isNotBlank() }
                        changelogList.add(ChangelogSection(context.getString(R.string.changelog), fallbackItems))
                    }

                    val publishedAt = targetRelease.getString("published_at")
                    val formattedReleaseDate = formatGitHubDate(publishedAt)
                    val assets = targetRelease.getJSONArray("assets")

                    var apkSizeInMB = ""
                    var apkDownloadUrl = ""
                    for (j in 0 until assets.length()) {
                        val asset = assets.getJSONObject(j)
                        val assetName = asset.getString("name")
                        if (assetName.endsWith(".apk") && assetName.contains("arm32") == (BuildConfig.ARCHITECTURE == "arm32")) {
                            val apkSizeInBytes = asset.getLong("size")
                            apkSizeInMB = String.format("%.1f", apkSizeInBytes / (1024.0 * 1024.0))
                            apkDownloadUrl = asset.getString("browser_download_url")
                            break
                        }
                    }

                    if (apkDownloadUrl.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            onSuccess(displayTag, true, changelogList, apkSizeInMB, formattedReleaseDate, description, imageUrl, apkDownloadUrl)
                        }
                        return@withContext
                    }
                }
            }

            withContext(Dispatchers.Main) {
                onSuccess(currentVersion, false, emptyList(), "", "", null, null, null)
            }
        } catch (e: Exception) {
            Timber.tag("UpdateCheck").e(e, "Error checking for updates: ${e.message}")
            withContext(Dispatchers.Main) { onError() }
        }
    }
}
fun String.extractUrls(): List<Pair<IntRange, String>> {
    val urlPattern = Pattern.compile(
        "(?:^|[\\s])((https?://|www\\.|pic\\.)[\\w-]+(\\.[\\w-]+)+([/?].*)?)"
    )
    val matcher = urlPattern.matcher(this)
    val urlList = mutableListOf<Pair<IntRange, String>>()

    while (matcher.find()) {
        val url = matcher.group(1)?.trim() ?: continue
        val range = IntRange(matcher.start(1), matcher.end(1) - 1)
        val fullUrl = if (url.startsWith("http")) url else "https://$url"
        urlList.add(range to fullUrl)
    }

    return urlList
}
