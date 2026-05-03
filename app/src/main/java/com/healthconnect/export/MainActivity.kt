package com.healthconnect.export

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
                    val viewModel: ExportViewModel = viewModel {
                        ExportViewModel(applicationContext)
                    }
                    ExportScreen(viewModel = viewModel)
                }
            }
        }
    }
}
