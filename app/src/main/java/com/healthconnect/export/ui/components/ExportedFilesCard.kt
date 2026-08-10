package com.healthconnect.export.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.healthconnect.export.R
import java.io.File

/** Number of newest files shown before the user expands the list. */
private const val INITIAL_VISIBLE_FILES = 10

/**
 * Max height of the file list inside the card — keeps the card compact and
 * lets long lists scroll lazily instead of building every row at once.
 * Sized to comfortably fit the collapsed 10 rows without internal scrolling.
 */
private val MAX_LIST_HEIGHT = 440.dp

/**
 * Card listing exported files (newest first). Shows the newest files and
 * offers a "Show all" toggle to reveal every file, so any file can be opened,
 * shared or deleted. The list is rendered in a lazy column with a bounded
 * height, so expanding hundreds of files does not build all rows at once.
 *
 * @param files All exported files.
 * @param showAll Whether the full list is expanded (state is hoisted to the caller).
 * @param onShowAllChange Callback to toggle the expanded state.
 * @param onFileClick Callback invoked when a file row is tapped (opens the viewer).
 * @param onShareFile Callback invoked to share a file via the system share sheet.
 * @param onDeleteFile Callback invoked to delete a file (after confirmation).
 */
@Composable
fun ExportedFilesCard(
    files: List<File>,
    showAll: Boolean,
    onShowAllChange: (Boolean) -> Unit,
    onFileClick: (File) -> Unit = {},
    onShareFile: (File) -> Unit = {},
    onDeleteFile: (File) -> Unit = {},
) {
    var pendingDelete by rememberSaveable { mutableStateOf<File?>(null) }

    val hasMore = files.size > INITIAL_VISIBLE_FILES
    val visibleFiles = remember(files, showAll) {
        visibleExportFiles(files, showAll, INITIAL_VISIBLE_FILES)
    }
    val totalSize = remember(files) { files.sumOf { it.length() } }

    MaterialCard(
        prominent = true,
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.15f),
    ) {
        Column {
            MaterialSectionHeader(
                icon = Icons.Default.Save,
                title = stringResource(R.string.exported_files_title, files.size),
                supportingText = stringResource(
                    R.string.exported_files_total_size,
                    formatFileSize(totalSize)
                ),
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = MAX_LIST_HEIGHT),
            ) {
                items(visibleFiles, key = { it.absolutePath }) { file ->
                    ExportedFileRow(
                        file = file,
                        onFileClick = onFileClick,
                        onShareClick = { onShareFile(file) },
                        onDeleteClick = { pendingDelete = file },
                    )
                }
            }

            if (hasMore) {
                Spacer(modifier = Modifier.height(4.dp))
                val rotation by animateFloatAsState(
                    targetValue = if (showAll) 180f else 0f,
                    label = "expandRotation",
                )
                TextButton(
                    onClick = { onShowAllChange(!showAll) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (showAll) {
                                stringResource(R.string.exported_files_show_less)
                            } else {
                                stringResource(R.string.exported_files_show_all, files.size)
                            },
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start,
                        )
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(rotation),
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.file_delete_title)) },
            text = { Text(stringResource(R.string.file_delete_message, file.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteFile(file)
                    }
                ) {
                    Text(stringResource(R.string.file_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.file_delete_cancel))
                }
            }
        )
    }
}

@Composable
private fun ExportedFileRow(
    file: File,
    onFileClick: (File) -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onFileClick(file) }
            .padding(vertical = 4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = formatFileSize(file.length()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onShareClick) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = stringResource(R.string.share_file),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onDeleteClick) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.file_delete_title),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * Files shown in the card: the newest [maxVisible] entries, or the full list
 * when [showAll] is set (or there are not more than [maxVisible] files).
 */
internal fun visibleExportFiles(
    files: List<File>,
    showAll: Boolean,
    maxVisible: Int = INITIAL_VISIBLE_FILES,
): List<File> {
    val sorted = files.sortedByDescending { it.lastModified() }
    return if (showAll || sorted.size <= maxVisible) {
        sorted
    } else {
        sorted.take(maxVisible)
    }
}

@Composable
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> stringResource(R.string.file_size_bytes, bytes)
        bytes < 1024 * 1024 -> stringResource(R.string.file_size_kb, bytes / 1024)
        else -> stringResource(R.string.file_size_mb, bytes / (1024.0 * 1024.0))
    }
}
