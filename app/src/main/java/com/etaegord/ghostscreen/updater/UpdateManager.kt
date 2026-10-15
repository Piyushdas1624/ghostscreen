package com.etaegord.ghostscreen.updater

import android.content.Context
import android.util.Log
import com.etaegord.ghostscreen.BuildConfig
import com.etaegord.ghostscreen.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Main coordinator for in-app updates.
 * Manages release checking, downloading, SHA-256 verification, and installer triggers.
 */
class UpdateManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {

    companion object {
        private const val TAG = "UpdateManager"

        @Volatile
        private var instance: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager {
            return instance ?: synchronized(this) {
                val appCtx = context.applicationContext
                instance ?: UpdateManager(
                    context = appCtx,
                    preferencesManager = PreferencesManager.getInstance(appCtx)
                ).also { instance = it }
            }
        }
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private var currentDownloadJob: Job? = null
    private var currentCheckJob: Job? = null

    init {
        syncPeriodicWork()
    }

    /**
     * Synchronizes WorkManager periodic update checks based on user preferences.
     */
    fun syncPeriodicWork() {
        if (preferencesManager.isAutoCheckUpdatesEnabled) {
            UpdateCheckWorker.schedulePeriodicWork(context)
        } else {
            UpdateCheckWorker.cancelPeriodicWork(context)
        }
    }

    /**
     * Directly displays an update (e.g. when launched from a system notification).
     */
    fun showUpdate(updateInfo: UpdateInfo) {
        _updateState.value = UpdateState.UpdateAvailable(updateInfo = updateInfo, isManualCheck = true)
    }

    /**
     * Performs a release check against the GitHub API.
     *
     * @param isManual True if triggered explicitly by the user clicking "Check for Updates"
     * @param customUrl Optional custom API URL (e.g. for testing / mocking)
     */
    fun checkForUpdates(
        isManual: Boolean = false,
        customUrl: String? = null
    ): Job {
        if (!isManual && (currentCheckJob?.isActive == true ||
            _updateState.value is UpdateState.Checking ||
            _updateState.value is UpdateState.UpdateAvailable ||
            _updateState.value is UpdateState.Downloading ||
            _updateState.value is UpdateState.ReadyToInstall)) {
            Log.d(TAG, "Ignoring auto update check because an update or check is already active")
            return coroutineScope.launch {}
        }

        currentCheckJob?.cancel()

        if (isManual) {
            _updateState.value = UpdateState.Checking
        }

        val job = coroutineScope.launch {
            val result = GitHubReleaseChecker.fetchLatestRelease(customUrl = customUrl, context = context)
            result.onSuccess { release ->
                preferencesManager.lastUpdateCheckTime = System.currentTimeMillis()
                val currentVersion = BuildConfig.VERSION_NAME
                val currentVersionCode = BuildConfig.VERSION_CODE.toLong()

                val isNewer = VersionComparator.isNewer(
                    remoteVersion = release.tagName,
                    currentVersion = currentVersion,
                    remoteVersionCode = release.versionCode,
                    currentVersionCode = currentVersionCode
                )
                if (isNewer) {
                    val isSkipped = release.tagName == preferencesManager.skippedUpdateVersion
                    if (!isManual && isSkipped) {
                        Log.d(TAG, "Update ${release.tagName} is available but skipped by user")
                        _updateState.value = UpdateState.Idle
                    } else {
                        Log.d(TAG, "Update available: ${release.tagName}")
                        _updateState.value = UpdateState.UpdateAvailable(
                            updateInfo = release,
                            isManualCheck = isManual
                        )
                    }
                } else {
                    Log.d(TAG, "Current version $currentVersion ($currentVersionCode) is up to date with ${release.tagName} (${release.versionCode ?: 0})")
                    if (isManual) {
                        _updateState.value = UpdateState.UpToDate(
                            currentVersion = currentVersion,
                            isManualCheck = true
                        )
                    } else {
                        _updateState.value = UpdateState.Idle
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Update check failed: ${error.message}")
                if (isManual) {
                    _updateState.value = UpdateState.Error(
                        message = error.message ?: "Failed to check for updates. Check internet connection.",
                        isManualCheck = true
                    )
                } else {
                    _updateState.value = UpdateState.Idle
                }
            }
        }
        currentCheckJob = job
        return job
    }

    /**
     * Starts downloading the APK asset for the specified [UpdateInfo].
     */
    fun startDownload(updateInfo: UpdateInfo) {
        currentDownloadJob?.cancel()

        _updateState.value = UpdateState.Downloading(
            updateInfo = updateInfo,
            progress = 0f,
            bytesDownloaded = 0L,
            totalBytes = updateInfo.apkSize
        )

        currentDownloadJob = coroutineScope.launch {
            val cleanTag = updateInfo.tagName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val targetFileName = "GhostScreen_${cleanTag}.apk"
            val wifiOnly = preferencesManager.isDownloadWifiOnlyEnabled

            val result = ApkDownloader.download(
                context = context,
                downloadUrl = updateInfo.downloadUrl,
                targetFileName = targetFileName,
                expectedSize = updateInfo.apkSize,
                wifiOnly = wifiOnly,
                onProgress = { bytesDownloaded, totalBytes, progressFraction ->
                    _updateState.value = UpdateState.Downloading(
                        updateInfo = updateInfo,
                        progress = progressFraction,
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = totalBytes
                    )
                }
            )

            result.onSuccess { apkFile ->
                Log.d(TAG, "APK downloaded to ${apkFile.absolutePath}. Verifying SHA-256...")

                // Fetch remote checksum file if hash wasn't in release description
                var expectedHash = updateInfo.sha256
                if (expectedHash.isNullOrBlank() && updateInfo.sha256AssetUrl != null) {
                    expectedHash = GitHubReleaseChecker.fetchChecksumFromUrl(
                        updateInfo.sha256AssetUrl,
                        updateInfo.assetName
                    )
                }

                // Verify SHA-256 Checksum
                val checksumValid = ChecksumVerifier.verifySha256(
                    file = apkFile,
                    expectedHash = expectedHash,
                    strict = false
                )

                if (!checksumValid) {
                    apkFile.delete()
                    _updateState.value = UpdateState.Error(
                        message = "SHA-256 verification failed! Downloaded file may be corrupted.",
                        isManualCheck = true
                    )
                    return@onSuccess
                }

                _updateState.value = UpdateState.ReadyToInstall(updateInfo, apkFile)

                // Trigger package installer immediately if permission is available
                if (PackageInstallerHelper.canRequestPackageInstalls(context)) {
                    PackageInstallerHelper.installApk(context, apkFile)
                }
            }.onFailure { error ->
                _updateState.value = UpdateState.Error(
                    message = error.message ?: "Download failed.",
                    isManualCheck = true
                )
            }
        }
    }

    /**
     * Cancels an ongoing download and returns to Idle state.
     */
    fun cancelDownload() {
        currentDownloadJob?.cancel()
        currentDownloadJob = null
        _updateState.value = UpdateState.Idle
    }

    /**
     * Installs the downloaded APK file.
     */
    fun installApk(apkFile: File) {
        PackageInstallerHelper.installApk(context, apkFile)
    }

    /**
     * Skips this version so subsequent auto-checks will not alert the user.
     */
    fun skipVersion(tagName: String) {
        preferencesManager.skippedUpdateVersion = tagName
        _updateState.value = UpdateState.Idle
    }

    /**
     * Resets state back to Idle.
     */
    fun dismiss() {
        _updateState.value = UpdateState.Idle
    }
}
