package com.healthconnect.export.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the Google account connected to the app.
 *
 * The legacy GoogleSignIn API could answer "is a session cached?" synchronously
 * (`getLastSignedInAccount`); Credential Manager has no equivalent query, so the
 * app remembers the connected account itself. Keeping this flag on disk is what
 * lets background work (e.g. the scheduled export worker) know that Drive sync
 * may be attempted without an Activity.
 */
class DriveSessionStore(
    context: Context,
) {
    // Nullable so a Context without SharedPreferences support (e.g. a test stub)
    // degrades to "no session" instead of crashing.
    private val prefs: SharedPreferences? = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when a Google account is connected. */
    fun isConnected(): Boolean = connectedEmail() != null

    /** Email of the connected account, or null when signed out. */
    fun connectedEmail(): String? = prefs?.getString(KEY_EMAIL, null)

    /** Remembers the account the user signed in with. */
    fun save(email: String) {
        prefs?.edit()?.putString(KEY_EMAIL, email)?.apply()
    }

    /** Forgets the connected account. */
    fun clear() {
        prefs?.edit()?.remove(KEY_EMAIL)?.apply()
    }

    companion object {
        const val PREFS_NAME = "drive_session"
        private const val KEY_EMAIL = "connected_email"
    }
}
