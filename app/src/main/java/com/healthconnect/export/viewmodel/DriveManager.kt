package com.healthconnect.export.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.util.Log
import androidx.activity.result.ActivityResult
import com.healthconnect.export.R
import com.healthconnect.export.auth.CredentialManagerAuthProvider
import com.healthconnect.export.auth.DriveAuthorization
import com.healthconnect.export.auth.GoogleAuthProvider
import com.healthconnect.export.repository.GoogleDriveRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference

/**
 * Manages all Google Drive related operations for the export feature.
 *
 * Sign-in (authentication) goes through Credential Manager and Drive access
 * (authorization for the Drive scope) through AuthorizationClient, both behind
 * [GoogleAuthProvider]. The Drive scope is requested lazily — only when a sync
 * actually needs it — so connecting an account never asks for Drive access
 * before it is needed.
 */
class DriveManager(
    private val application: Application,
    internal val authProvider: GoogleAuthProvider = CredentialManagerAuthProvider(application),
) {
    /** Scope for async Drive operations. Made internal for testability. */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val TAG = "DriveManager"

    /** Underlying repository that talks to the Google Drive API. */
    val driveRepo = GoogleDriveRepository(application, authProvider)

    /**
     * Launches the Google consent screen when Drive access has not been granted
     * yet. Registered by the Activity; stays null in tests and background use.
     */
    var authorizationLauncher: ((IntentSender) -> Unit)? = null

    /**
     * Activity used for the interactive Google APIs (Credential Manager needs an
     * Activity context). Held weakly: the Activity owns the manager's lifecycle.
     */
    private var activityRef: WeakReference<Activity> = WeakReference(null)

    /** Files that are waiting for the user to grant Drive access. */
    private var pendingFiles: List<File>? = null

    private val _driveState = MutableStateFlow(DriveState())
    val driveState: StateFlow<DriveState> = _driveState.asStateFlow()

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun str(id: Int): String = application.getString(id)

    private fun str(
        id: Int,
        vararg args: Any?,
    ): String = application.getString(id, *args)

    /** Registers (or clears) the Activity used for interactive Google APIs. */
    fun attachActivity(activity: Activity?) {
        activityRef = WeakReference(activity)
    }

    private fun uiContext(): Context = activityRef.get() ?: application

    // ── Sign-in / Sign-out ───────────────────────────────────────────────────

    /**
     * Authenticates the user with Credential Manager.
     *
     * Only the account identity is requested here; the Drive scope is authorized
     * later, on the first operation that needs it.
     */
    fun signIn() {
        val activity = activityRef.get()
        if (activity == null) {
            _driveState.value =
                DriveState(
                    status = DriveStatus.Error(str(R.string.vm_drive_signin_failed)),
                    message = str(R.string.vm_drive_signin_failed),
                )
            return
        }

        scope.launch {
            val account = authProvider.signIn(activity)
            if (account == null) {
                _driveState.value =
                    DriveState(
                        status = DriveStatus.Error(str(R.string.vm_drive_signin_failed)),
                        message = str(R.string.vm_drive_signin_failed),
                    )
                return@launch
            }

            driveRepo.saveSession(account.email)
            _driveState.value =
                DriveState(
                    status = DriveStatus.Connected,
                    message = str(R.string.vm_drive_connected, account.email),
                )
        }
    }

    /**
     * Sign the user out of Google Drive.
     *
     * The disconnected state is published synchronously so the UI never shows a
     * stale "connected" state while the credential session is being cleared.
     */
    fun signOut() {
        driveRepo.clearSession()
        pendingFiles = null
        _driveState.value =
            DriveState(
                status = DriveStatus.NotConnected,
                message = str(R.string.vm_drive_signed_out),
            )
        scope.launch {
            authProvider.clearCredentialState(application)
        }
    }

    /**
     * Handles the result of the Google consent screen launched for the Drive scope.
     */
    fun onAuthorizationResult(result: ActivityResult) {
        when (val authorization = authProvider.authorizationResult(result.data)) {
            is DriveAuthorization.Granted -> {
                driveRepo.cacheAccessToken(authorization.accessToken)
                val files = pendingFiles
                pendingFiles = null
                if (files == null) {
                    updateConnectedState()
                } else {
                    scope.launch {
                        _driveState.update { it.copy(status = DriveStatus.Syncing, message = null) }
                        uploadFiles(files)
                    }
                }
            }
            is DriveAuthorization.Failed -> {
                pendingFiles = null
                _driveState.value =
                    DriveState(
                        status = DriveStatus.Error(str(R.string.vm_drive_access_denied)),
                        message = str(R.string.vm_drive_access_denied),
                    )
            }
            is DriveAuthorization.ConsentRequired -> Unit
        }
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    /**
     * Upload the given [files] to Google Drive.
     *
     * If no account is connected, [driveState] is set to [DriveStatus.NotConnected]
     * and no upload is attempted. If Drive access has not been granted yet, the
     * Google consent screen is launched and the upload resumes afterwards.
     */
    fun syncToDrive(files: List<File>) {
        if (!driveRepo.isSignedIn()) {
            _driveState.value =
                DriveState(
                    status = DriveStatus.NotConnected,
                    message = str(R.string.vm_drive_not_connected),
                )
            return
        }

        scope.launch {
            _driveState.update { it.copy(status = DriveStatus.Syncing, message = null) }
            when (val authorization = authProvider.authorizeDrive(uiContext())) {
                is DriveAuthorization.Granted -> {
                    driveRepo.cacheAccessToken(authorization.accessToken)
                    uploadFiles(files)
                }
                is DriveAuthorization.ConsentRequired -> {
                    val launcher = authorizationLauncher
                    if (launcher == null) {
                        _driveState.value =
                            DriveState(
                                status = DriveStatus.Error(str(R.string.vm_drive_access_required)),
                                message = str(R.string.vm_drive_access_required),
                            )
                        return@launch
                    }
                    pendingFiles = files
                    _driveState.value =
                        DriveState(
                            status = DriveStatus.Connected,
                            message = str(R.string.vm_drive_access_required),
                        )
                    launcher(authorization.intentSender)
                }
                is DriveAuthorization.Failed -> {
                    _driveState.value =
                        DriveState(
                            status = DriveStatus.Error(str(R.string.vm_drive_access_denied)),
                            message = str(R.string.vm_drive_access_denied),
                        )
                }
            }
        }
    }

    private suspend fun uploadFiles(files: List<File>) {
        try {
            val results = driveRepo.syncFiles(files)
            val syncedCount = results.count { it != null }
            _driveState.value =
                DriveState(
                    status = DriveStatus.Synced(syncedCount),
                    message = str(R.string.vm_drive_synced, syncedCount),
                )
        } catch (e: Exception) {
            _driveState.value =
                DriveState(
                    status = DriveStatus.Error(e.message ?: "Unknown error"),
                    message = e.message,
                )
        }
    }

    // ── Status ───────────────────────────────────────────────────────────────

    /**
     * Refresh the current Drive connection status.
     *
     * With a connected account, the list of previously uploaded files is loaded
     * (silently — the Drive token is reused when it was already granted, and no
     * consent UI is shown at startup). Without a connected account the status
     * stays [DriveStatus.NotConnected].
     */
    fun refreshDriveStatus() {
        if (!driveRepo.isSignedIn()) {
            _driveState.value = DriveState(status = DriveStatus.NotConnected)
            return
        }

        _driveState.value = DriveState(status = DriveStatus.Connected)
        scope.launch {
            val token = driveRepo.accessToken()
            if (token == null) {
                // Drive access has not been granted yet (or was revoked); the
                // consent screen is requested lazily on the next sync.
                Log.i(TAG, "refreshDriveStatus: Drive access not granted yet")
                return@launch
            }
            val driveFiles = driveRepo.listDriveFiles()
            _driveState.value = DriveState(status = DriveStatus.Synced(driveFiles.size))
        }
    }

    /**
     * Marks the user as connected and refreshes the list of files on Drive.
     */
    private fun updateConnectedState() {
        _driveState.value = DriveState(status = DriveStatus.Connected)
        scope.launch {
            val driveFiles = driveRepo.listDriveFiles()
            _driveState.value = DriveState(status = DriveStatus.Synced(driveFiles.size))
        }
    }
}

/**
 * Internal state holder for Drive operations.
 *
 * The ViewModel merges this into its [ExportUiState] by mapping
 * [status] to [ExportUiState.driveStatus] and [message] to
 * [ExportUiState.message].
 */
data class DriveState(
    val status: DriveStatus = DriveStatus.NotConnected,
    val message: String? = null,
) {
    /** Convenience: true when the user is currently signed in. */
    val isConnected: Boolean get() = status is DriveStatus.Connected || status is DriveStatus.Synced || status is DriveStatus.Syncing
}
