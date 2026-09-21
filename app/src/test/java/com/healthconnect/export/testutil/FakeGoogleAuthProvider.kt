package com.healthconnect.export.testutil

import android.content.Context
import android.content.Intent
import com.healthconnect.export.auth.DriveAuthorization
import com.healthconnect.export.auth.GoogleAccountInfo
import com.healthconnect.export.auth.GoogleAuthProvider

/**
 * Deterministic in-memory [GoogleAuthProvider] used by unit tests, so tests
 * never have to mock the Play services statics behind Credential Manager or
 * AuthorizationClient.
 */
class FakeGoogleAuthProvider : GoogleAuthProvider {
    /** Account returned by [signIn]; null mimics a cancelled sign-in. */
    var signInAccount: GoogleAccountInfo? = null

    /** Outcome of [authorizeDrive]; defaults to a granted test token. */
    var authorization: DriveAuthorization = DriveAuthorization.Granted(TEST_TOKEN)

    /** Outcome parsed from the consent activity result; null falls back to [authorization]. */
    var parsedAuthorization: DriveAuthorization? = null

    var signInCalls = 0
    var authorizeCalls = 0
    var clearCredentialStateCalls = 0
    var lastActivityContext: Context? = null

    override suspend fun signIn(
        activityContext: Context,
        autoSelect: Boolean,
    ): GoogleAccountInfo? {
        signInCalls++
        lastActivityContext = activityContext
        return signInAccount
    }

    override suspend fun authorizeDrive(context: Context): DriveAuthorization {
        authorizeCalls++
        return authorization
    }

    override fun authorizationResult(intent: Intent?): DriveAuthorization = parsedAuthorization ?: authorization

    override suspend fun clearCredentialState(context: Context) {
        clearCredentialStateCalls++
    }

    companion object {
        const val TEST_TOKEN = "test-access-token"
    }
}
