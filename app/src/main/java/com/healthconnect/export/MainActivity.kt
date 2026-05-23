package com.healthconnect.export

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.healthconnect.export.ui.ExportScreen
import com.healthconnect.export.ui.theme.AppTheme
import com.healthconnect.export.viewmodel.ExportViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: ExportViewModel = viewModel()

                    // Launcher для Google Sign-In
                    val signInLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        viewModel.handleSignInResult(result)
                    }

                    // Launcher для Health Connect permissions
                    val healthPermissionLauncher = rememberLauncherForActivityResult(
                        contract = PermissionController.createRequestPermissionResultContract()
                    ) { grantedPermissions: Set<String> ->
                        Log.d("MainActivity", "Permission results: $grantedPermissions")
                        viewModel.onPermissionsResult(grantedPermissions)
                    }

                    ExportScreen(
                        viewModel = viewModel,
                        onSignInClick = {
                            val signIntent = viewModel.googleSignInClient.signInIntent
                            signInLauncher.launch(signIntent)
                        },
                        onRequestHealthPermissions = { permissions ->
                            Log.d("MainActivity", "Launching permissions: $permissions")
                            try {
                                healthPermissionLauncher.launch(permissions)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Failed to launch permissions", e)
                                viewModel.onPermissionsResult(emptySet())
                            }
                        }
                    )
                }
            }
        }
    }
}
