package com.healthconnect.export.repository

import android.content.Context
import android.util.Log
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.FileContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.healthconnect.export.auth.CredentialManagerAuthProvider
import com.healthconnect.export.auth.DriveAuthorization
import com.healthconnect.export.auth.DriveSessionStore
import com.healthconnect.export.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Google Drive API access on behalf of the signed-in user.
 *
 * Authentication (who the user is) belongs to Credential Manager and lives
 * behind [GoogleAuthProvider]; Drive requests are authorized with an OAuth
 * access token from AuthorizationClient that is attached as a Bearer header.
 * This replaces the legacy `GoogleAccountCredential`, which required a
 * `GoogleSignInAccount` from the removed GoogleSignIn API.
 */
class GoogleDriveRepository(
    private val context: Context,
    private val authProvider: GoogleAuthProvider = CredentialManagerAuthProvider(context),
    internal val session: DriveSessionStore = DriveSessionStore(context),
) {
    companion object {
        private const val TAG = "GoogleDriveRepo"
        private const val APP_NAME = "HealthConnect Export"
        private const val FOLDER_NAME = "HealthConnectExport"
    }

    private val gsonFactory = GsonFactory.getDefaultInstance()
    private val httpTransport = NetHttpTransport()

    /** Allows injecting a mock Drive service for testing. */
    internal var testDrive: Drive? = null

    /** Test seam: a fixed access token, bypassing [GoogleAuthProvider]. */
    internal var testAccessToken: String? = null

    private var cachedToken: String? = null

    /** True while an account is connected (persisted across app restarts). */
    fun isSignedIn(): Boolean = session.isConnected()

    /** Email of the connected account, if any. */
    fun connectedEmail(): String? = session.connectedEmail()

    /** Remembers the account the user signed in with. */
    fun saveSession(email: String) {
        session.save(email)
    }

    /** Forgets the connected account and drops the cached access token. */
    fun clearSession() {
        session.clear()
        cachedToken = null
    }

    /** Caches a token obtained by a caller that already resolved user consent. */
    internal fun cacheAccessToken(token: String) {
        cachedToken = token
    }

    /**
     * Returns an access token for the Drive scope, or null when Drive access has
     * not been granted. Never requests consent — callers that can show UI handle
     * [DriveAuthorization.ConsentRequired] themselves.
     */
    internal suspend fun accessToken(forceRefresh: Boolean = false): String? {
        testAccessToken?.let { return it }
        if (!forceRefresh) cachedToken?.let { return it }
        return when (val result = authProvider.authorizeDrive(context)) {
            is DriveAuthorization.Granted -> result.accessToken.also { cachedToken = it }
            else -> null
        }
    }

    /**
     * Upload a file to Google Drive at the specified path
     */
    suspend fun uploadFile(
        localFile: File,
        drivePath: String,
    ): String? =
        withContext(Dispatchers.IO) {
            try {
                withAuthorizedDrive { drive -> uploadWith(drive, localFile) }
            } catch (e: Exception) {
                Log.e(TAG, "uploadFile: error uploading ${localFile.name}", e)
                null
            }
        }

    private fun uploadWith(
        drive: Drive,
        localFile: File,
    ): String? {
        // Get or create the folder structure
        val folderId = getOrCreateFolder(drive, FOLDER_NAME)
        if (folderId == null) {
            Log.w(TAG, "uploadFile: failed to get or create folder")
            return null
        }

        val fileMetadata =
            com.google.api.services.drive.model.File().apply {
                name = localFile.name
                parents = listOf(folderId)
                mimeType = mimeTypeFor(localFile)
            }

        val mediaContent = FileContent(mimeTypeFor(localFile), localFile)

        // Delete all existing files with the same name before creating a new one
        val existingFiles =
            drive
                .files()
                .list()
                .setQ("name='${localFile.name}' and '$folderId' in parents and trashed=false")
                .setSpaces("drive")
                .execute()
                .files

        if (existingFiles.isNotEmpty()) {
            Log.i(TAG, "uploadFile: deleting ${existingFiles.size} existing file(s) for ${localFile.name}")
            existingFiles.forEach { file ->
                drive.files().delete(file.id).execute()
                Log.i(TAG, "uploadFile: deleted existing file ${file.id}")
            }
        } else {
            Log.i(TAG, "uploadFile: no existing file to overwrite, creating new")
        }

        // Always create a new file
        val driveFile = drive.files().create(fileMetadata, mediaContent).execute()

        Log.i(TAG, "uploadFile: success - ${driveFile.id}")
        return driveFile.id
    }

    private fun mimeTypeFor(file: File): String =
        when (file.extension.lowercase()) {
            "csv" -> "text/csv"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }

    /**
     * Sync multiple local files to Drive
     */
    suspend fun syncFiles(files: List<File>): List<String?> = files.map { uploadFile(it, "$FOLDER_NAME/${it.name}") }

    /**
     * List files already on Drive
     */
    suspend fun listDriveFiles(): List<String> =
        withContext(Dispatchers.IO) {
            try {
                withAuthorizedDrive { drive -> listFilesWith(drive) } ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "listDriveFiles: error listing Drive files", e)
                emptyList()
            }
        }

    private fun listFilesWith(drive: Drive): List<String> {
        val folderId = findFolder(drive, FOLDER_NAME) ?: return emptyList()

        return drive
            .files()
            .list()
            .setQ("'$folderId' in parents and trashed=false")
            .setSpaces("drive")
            .execute()
            .files
            .map { it.name }
    }

    /**
     * Runs [block] against an authorized Drive service, or returns null when no
     * access token is available (i.e. the user has not granted Drive access).
     *
     * A rejected token (HTTP 401) is refreshed once and the request retried.
     */
    private suspend fun <T> withAuthorizedDrive(block: (Drive) -> T): T? {
        val token =
            accessToken() ?: run {
                Log.w(TAG, "withAuthorizedDrive: no Drive access token — user not authorized")
                return null
            }

        return try {
            block(driveService(token))
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode != 401) throw e
            Log.i(TAG, "withAuthorizedDrive: token rejected, refreshing")
            val refreshed = accessToken(forceRefresh = true) ?: return null
            block(driveService(refreshed))
        }
    }

    private fun driveService(token: String): Drive =
        testDrive
            ?: Drive
                .Builder(httpTransport, gsonFactory, HttpRequestInitializer { })
                .setApplicationName(APP_NAME)
                .setHttpRequestInitializer { request ->
                    request.headers.authorization = "Bearer $token"
                }.build()

    private fun findFolder(
        drive: Drive,
        name: String,
    ): String? {
        val result =
            drive
                .files()
                .list()
                .setQ("mimeType='application/vnd.google-apps.folder' and name='$name' and trashed=false")
                .setSpaces("drive")
                .execute()
        return result.files.firstOrNull()?.id
    }

    private fun getOrCreateFolder(
        drive: Drive,
        name: String,
    ): String? {
        val existing = findFolder(drive, name)
        if (existing != null) return existing

        val metadata =
            com.google.api.services.drive.model.File().apply {
                this.name = name
                mimeType = "application/vnd.google-apps.folder"
            }

        return drive
            .files()
            .create(metadata)
            .execute()
            .id
    }
}
