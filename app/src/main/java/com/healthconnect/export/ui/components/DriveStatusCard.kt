package com.healthconnect.export.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.healthconnect.export.R
import com.healthconnect.export.viewmodel.DriveStatus

@Composable
fun DriveStatusCard(
    status: DriveStatus,
    onSync: () -> Unit,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
) {
    val (icon, title, color) =
        when (status) {
            is DriveStatus.NotConnected ->
                Triple(
                    Icons.Default.CloudOff,
                    stringResource(R.string.drive_not_connected),
                    MaterialTheme.colorScheme.error,
                )
            is DriveStatus.Connected ->
                Triple(
                    Icons.Default.Cloud,
                    stringResource(R.string.drive_connected),
                    MaterialTheme.colorScheme.primary,
                )
            is DriveStatus.Syncing ->
                Triple(
                    Icons.Default.Refresh,
                    stringResource(R.string.drive_syncing),
                    MaterialTheme.colorScheme.tertiary,
                )
            is DriveStatus.Synced ->
                Triple(
                    Icons.Default.Cloud,
                    stringResource(R.string.drive_synced, status.filesCount),
                    MaterialTheme.colorScheme.primary,
                )
            is DriveStatus.Error ->
                Triple(
                    Icons.Default.CloudOff,
                    stringResource(R.string.drive_error, status.error),
                    MaterialTheme.colorScheme.error,
                )
        }

    MaterialCard(
        prominent = true,
        containerColor = color.copy(alpha = 0.1f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                title,
                modifier = Modifier.weight(1f),
                color = color,
                style = MaterialTheme.typography.titleSmall,
            )
            when (status) {
                is DriveStatus.NotConnected, is DriveStatus.Error -> {
                    TextButton(onClick = onSignInClick) {
                        Text(stringResource(R.string.sign_in))
                    }
                }
                is DriveStatus.Connected, is DriveStatus.Synced -> {
                    TextButton(onClick = onSync) {
                        Text(stringResource(R.string.sync_now))
                    }
                    TextButton(onClick = onSignOutClick) {
                        Text(stringResource(R.string.sign_out))
                    }
                }
                is DriveStatus.Syncing -> {
                    CenteredSpinner(color = color)
                }
            }
        }
    }
}

/**
 * Indeterminate spinner drawn as a simple arc rotating strictly around its
 * own center. Used instead of the Material 3 expressive indeterminate
 * indicator, which visually wobbles off-center at small custom sizes.
 */
@Composable
private fun CenteredSpinner(
    color: Color,
    size: Dp = 20.dp,
    strokeWidth: Dp = 2.dp,
) {
    val transition = rememberInfiniteTransition(label = "centeredSpinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
            ),
        label = "centeredSpinnerAngle",
    )
    Canvas(modifier = Modifier.size(size)) {
        val strokePx = strokeWidth.toPx()
        val arcSize = this.size.minDimension - strokePx
        val inset = strokePx / 2f
        // DrawScope.rotate pivots around the center of the draw area,
        // so the arc always spins around the spinner's own center.
        rotate(degrees = angle) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(arcSize, arcSize),
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
    }
}
