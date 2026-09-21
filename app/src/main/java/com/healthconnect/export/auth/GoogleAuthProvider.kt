package com.healthconnect.export.auth

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.api.services.drive.DriveScopes
import com.healthconnect.export.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The Google account the user signed in with (authentication result). */
data class GoogleAccountInfo(
    val email: String,
    val displayName: String? = null,
)

/** Outcome of an authorization request for the Drive OAuth scope. */
sealed interface DriveAuthorization {
    /** Access is granted and [accessToken] can be attached to Drive requests. */
    data class Granted(
        val accessToken: String,
    ) : DriveAuthorization

    /** The user has to approve the scope; show [intentSender] to continue. */
    data class ConsentRequired(
        val intentSender: IntentSender,
    ) : DriveAuthorization

    /** No access — either refused by the user or the request failed. */
    data class Failed(
        val reason: String? = null,
    ) : DriveAuthorization
}

/**
 * Google Identity operations, split the way Google recommends:
 *
 * - [signIn] authenticates the user with Credential Manager (email + ID token);
 * - [authorizeDrive] authorizes the Drive OAuth scope with AuthorizationClient.
 *
 * The interface keeps [com.healthconnect.export.viewmodel.DriveManager] and
 * [com.healthconnect.export.repository.GoogleDriveRepository] testable without
 * mocking the Play services statics.
 */
interface GoogleAuthProvider {
    /**
     * Signs the user in with a Google account, preferring accounts already
     * authorized for this app (which allows an automatic, UI-less sign-in when
     * exactly one such account exists). Returns null when the user cancels or
     * no account is available.
     *
     * [activityContext] must be an Activity context: Credential Manager launches
     * system UI.
     */
    suspend fun signIn(
        activityContext: Context,
        autoSelect: Boolean = true,
    ): GoogleAccountInfo?

    /**
     * Requests (or silently reuses) an access token for the Drive scope.
     * Prefers the last account used with the app, so a follow-up call after
     * [signIn] needs no account selection.
     */
    suspend fun authorizeDrive(context: Context): DriveAuthorization

    /** Parses the activity result of the [DriveAuthorization.ConsentRequired] flow. */
    fun authorizationResult(intent: Intent?): DriveAuthorization

    /** Clears the active credential session, so the next sign-in shows all accounts. */
    suspend fun clearCredentialState(context: Context)
}

/**
 * Credential Manager + AuthorizationClient implementation of [GoogleAuthProvider].
 */
class CredentialManagerAuthProvider(
    private val context: Context,
) : GoogleAuthProvider {
    companion object {
        private const val TAG = "GoogleAuthProvider"
    }

    override suspend fun signIn(
        activityContext: Context,
        autoSelect: Boolean,
    ): GoogleAccountInfo? {
        val authorizedOnly = requestGoogleId(activityContext, autoSelect)
        if (authorizedOnly != null || !autoSelect) return authorizedOnly
        // No account has been used with the app yet — let the user pick any
        // Google account on the device.
        return requestGoogleId(activityContext, autoSelect = false)
    }

    private suspend fun requestGoogleId(
        activityContext: Context,
        autoSelect: Boolean,
    ): GoogleAccountInfo? {
        val option =
            GetGoogleIdOption
                .Builder()
                .setServerClientId(BuildConfig.GOOGLE_CLIENT_ID)
                .setFilterByAuthorizedAccounts(autoSelect)
                .setAutoSelectEnabled(autoSelect)
                .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        return try {
            val response =
                CredentialManager
                    .create(activityContext)
                    .getCredential(context = activityContext, request = request)
            val credential = response.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val idToken = GoogleIdTokenCredential.createFrom(credential.data)
                val email = idToken.email
                if (email.isNullOrBlank()) {
                    Log.w(TAG, "signIn: Google ID token carries no email")
                    null
                } else {
                    GoogleAccountInfo(email = email, displayName = idToken.displayName)
                }
            } else {
                Log.w(TAG, "signIn: unexpected credential type ${credential.type}")
                null
            }
        } catch (e: NoCredentialException) {
            Log.i(TAG, "signIn: no Google credential available (authorizedOnly=$autoSelect)")
            null
        } catch (e: GetCredentialException) {
            Log.w(TAG, "signIn: credential request failed", e)
            null
        }
    }

    override suspend fun authorizeDrive(context: Context): DriveAuthorization {
        val request =
            AuthorizationRequest
                .builder()
                .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_FILE)))
                .build()

        return try {
            Identity
                .getAuthorizationClient(context)
                .authorize(request)
                .await()
                .fold(
                    onSuccess = { result -> result.toDriveAuthorization() },
                    onFailure = { e ->
                        Log.w(TAG, "authorizeDrive: authorization failed", e)
                        DriveAuthorization.Failed(e.message)
                    },
                )
        } catch (e: Exception) {
            Log.e(TAG, "authorizeDrive: unable to request Drive access", e)
            DriveAuthorization.Failed(e.message)
        }
    }

    override fun authorizationResult(intent: Intent?): DriveAuthorization =
        try {
            Identity
                .getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(intent)
                .toDriveAuthorization()
        } catch (e: Exception) {
            Log.w(TAG, "authorizationResult: consent not granted", e)
            DriveAuthorization.Failed(e.message)
        }

    override suspend fun clearCredentialState(context: Context) {
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "clearCredentialState: failed to clear credential state", e)
        }
    }

    private fun com.google.android.gms.auth.api.identity.AuthorizationResult.toDriveAuthorization(): DriveAuthorization {
        val token = accessToken
        val pendingIntent = pendingIntent
        return when {
            !token.isNullOrBlank() -> DriveAuthorization.Granted(token)
            pendingIntent != null -> DriveAuthorization.ConsentRequired(pendingIntent.intentSender)
            else -> DriveAuthorization.Failed("no access token")
        }
    }

    private suspend fun <T> Task<T>.await(): Result<T> =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(Result.success(result))
            }
            addOnFailureListener { e ->
                if (continuation.isActive) continuation.resume(Result.failure(e))
            }
        }
}
