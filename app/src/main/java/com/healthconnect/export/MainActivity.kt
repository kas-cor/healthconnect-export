package com.healthconnect.export

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.healthconnect.export.ui.ExportScreen
import com.healthconnect.export.ui.theme.AppTheme
import com.healthconnect.export.util.LocaleManager
import com.healthconnect.export.viewmodel.ExportViewModel

class MainActivity : ComponentActivity() {

    private var lastLocale: String? = null

    override fun attachBaseContext(newBase: Context) {
        val localeCode = LocaleManager.getSavedLocale(newBase)
        lastLocale = localeCode
        super.attachBaseContext(LocaleManager.wrapContext(newBase, localeCode))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: ExportViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return ExportViewModel(this@MainActivity.application) as T
                    }
                }
            )

            val uiState by viewModel.uiState.collectAsState()
            val useDarkTheme = uiState.isDarkTheme ?: isSystemInDarkTheme()

            // Перезапуск Activity при смене локали
            LaunchedEffect(uiState.locale) {
                if (uiState.locale != lastLocale) {
                    lastLocale = uiState.locale
                    recreate()
                }
            }

            // Launcher для Google Sign-In — вынесен наружу, чтобы переживал AnimatedContent
            val signInLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                viewModel.handleSignInResult(result)
            }

            // Launcher для Health Connect permissions
            val healthPermissionLauncher = rememberLauncherForActivityResult(
                contract = PermissionController.createRequestPermissionResultContract(
                    this@MainActivity.packageName
                )
            ) { grantedPermissions: Set<String> ->
                Log.d("MainActivity", "Permission results: $grantedPermissions")
                viewModel.onPermissionsResult(grantedPermissions)
            }

            val onSignInClick: () -> Unit = {
                val signIntent = viewModel.driveManager.googleSignInClient.signInIntent
                signInLauncher.launch(signIntent)
            }

            val onRequestHealthPermissions: (Set<String>) -> Unit = { permissions ->
                Log.d("MainActivity", "Launching permissions: $permissions")
                try {
                    healthPermissionLauncher.launch(permissions)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to launch permissions", e)
                    viewModel.onPermissionsResult(emptySet())
                }
            }

            // Плавный crossfade при переключении темы
            AnimatedContent(
                targetState = useDarkTheme,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(400))
                        togetherWith fadeOut(animationSpec = tween(400)))
                },
                label = "themeTransition"
            ) { isDark ->
                AppTheme(darkTheme = isDark) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        ExportScreen(
                            viewModel = viewModel,
                            onSignInClick = onSignInClick,
                            onRequestHealthPermissions = onRequestHealthPermissions
                        )
                    }
                }
            }
        }
    }
}
