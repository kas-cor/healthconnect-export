package com.healthconnect.export.viewmodel

import android.app.Application
import androidx.activity.result.ActivityResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.common.api.ApiException
import com.healthconnect.export.R
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

/**
 * Manages all Google Drive related operations for the export feature.
 *
 * Encapsulates sign-in, sign-out, file sync, and drive status tracking,
 * exposing a [DriveState] flow that the ViewModel can collect and merge
 * into its UI state.
 */
class DriveManager(
    private val application: Application
) {
    /** Scope for async Drive operations. Made internal for testability. */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val TAG = "DriveManager"

    /** Underlying repository that talks to the Google Drive API. */
    val driveRepo = GoogleDriveRepository(application)

    /** Pre-configured Google Sign-In client. */
    val googleSignInClient: GoogleSignInClient =
        GoogleSignIn.getClient(application, driveRepo.getSignInOptions())

    private val _driveState = MutableStateFlow(DriveState())
    val driveState: StateFlow<DriveState> = _driveState.asStateFlow()

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun str(id: Int): String = application.getString(id)
    private fun str(id: Int, vararg args: Any?): String =
        application.getString(id, *args)

    // ── Sign-in / Sign-out ───────────────────────────────────────────────────

    /**
     * Handle the result from the Google Sign-In activity.
     * Updates [driveState] with the outcome.
     */
    fun handleSignInResult(result: ActivityResult) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                _driveState.value = DriveState(
                    status = DriveStatus.Connected,
                    message = str(R.string.vm_drive_connected, account.email)
                )
                refreshDriveStatus()
            }
        } catch (e: ApiException) {
            _driveState.value = DriveState(
                status = DriveStatus.Error(str(R.string.vm_drive_signin_error, e.statusCode)),
                message = str(R.string.vm_drive_signin_error, e.statusCode)
            )
        }
    }

    /**
     * Sign the user out of Google Drive.
     * Updates [driveState] to reflect the disconnected state.
     */
    fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener {
            _driveState.value = DriveState(
                status = DriveStatus.NotConnected,
                message = str(R.string.vm_drive_signed_out)
            )
        }
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    /**
     * Upload the given [files] to Google Drive.
     *
     * If the user is not signed in, [driveState] is set to [DriveStatus.NotConnected]
     * and no upload is attempted.
     */
    fun syncToDrive(files: List<File>) {
        if (!driveRepo.isSignedIn()) {
            _driveState.value = DriveState(
                status = DriveStatus.NotConnected,
                message = str(R.string.vm_drive_not_connected)
            )
            return
        }

        scope.launch {
            _driveState.update { it.copy(status = DriveStatus.Syncing) }
            try {
                val results = driveRepo.syncFiles(files)
                val syncedCount = results.count { it != null }
                _driveState.value = DriveState(
                    status = DriveStatus.Synced(syncedCount),
                    message = str(R.string.vm_drive_synced, syncedCount)
                )
            } catch (e: Exception) {
                _driveState.value = DriveState(
                    status = DriveStatus.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    // ── Status ───────────────────────────────────────────────────────────────

    /**
     * Refresh the current Drive connection status.
     *
     * If signed in, queries Drive for the list of previously uploaded files
     * and reports the count. Otherwise sets status to [DriveStatus.NotConnected].
     */
    fun refreshDriveStatus() {
        if (driveRepo.isSignedIn()) {
            _driveState.value = DriveState(status = DriveStatus.Connected)
            scope.launch {
                val driveFiles = driveRepo.listDriveFiles()
                _driveState.value = DriveState(status = DriveStatus.Synced(driveFiles.size))
            }
        } else {
            _driveState.value = DriveState(status = DriveStatus.NotConnected)
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
    val message: String? = null
) {
    /** Convenience: true when the user is currently signed in. */
    val isConnected: Boolean get() = status is DriveStatus.Connected || status is DriveStatus.Synced || status is DriveStatus.Syncing
}
