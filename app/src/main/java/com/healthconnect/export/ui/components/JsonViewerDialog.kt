package com.healthconnect.export.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import com.healthconnect.export.R
import java.io.File

/**
 * Color scheme for the JSON viewer, designed to resemble a dark IDE/editor theme.
 */
private object JsonViewerColors {
    val background = Color(0xFF1E1E1E)
    val text = Color(0xFFD4D4D4)
    val headerBg = Color(0xFF2D2D2D)
    val divider = Color(0xFF3C3C3C)
    val key = Color(0xFF569CD6)
    val string = Color(0xFF6A9955)
    val number = Color(0xFFB5CEA8)
    val bool = Color(0xFF569CD6)
    val nullColor = Color(0xFFD16969)
    val error = Color(0xFFD16969)
    val iconBlue = Color(0xFF569CD6)
    val iconGreen = Color(0xFF6A9955)
}

/**
 * Full-screen dialog for viewing a JSON file with syntax highlighting.
 * Features a dark IDE-style theme, copy-to-clipboard, and monospace rendering.
 *
 * @param file The JSON file to display.
 * @param onDismiss Callback invoked when the dialog is dismissed.
 */
@Composable
fun JsonViewerDialog(
    file: File,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // Read and format JSON in a background coroutine
    var prettyJson by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val openErrorMsg = stringResource(R.string.json_viewer_open_error)

    LaunchedEffect(file) {
        try {
            val content = file.readText()
            prettyJson = JSONObject(content).toString(2)
        } catch (e: Exception) {
            error = openErrorMsg
        }
    }

    // Always use dark background for JSON viewer (like IDE/code editors)
    val viewerBackground = JsonViewerColors.background
    val viewerTextColor = JsonViewerColors.text
    val viewerHeaderBg = JsonViewerColors.headerBg
    val viewerDividerColor = JsonViewerColors.divider

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            color = viewerBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(viewerHeaderBg)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = JsonViewerColors.iconBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.json_viewer_title, file.name),
                        style = MaterialTheme.typography.titleMedium,
                        color = viewerTextColor,
                        modifier = Modifier.weight(1f)
                    )
                    // Copy to clipboard button
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(file.name, prettyJson ?: "")
                        clipboard.setPrimaryClip(clip)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = stringResource(R.string.json_viewer_copy),
                            tint = JsonViewerColors.iconGreen
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.json_viewer_close),
                            tint = viewerTextColor
                        )
                    }
                }

                HorizontalDivider(color = viewerDividerColor)

                // JSON content area
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    when {
                        error != null -> {
                            Text(
                                text = error!!,
                                color = JsonViewerColors.error,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        prettyJson != null -> {
                            val scrollState = rememberScrollState()
                            val formattedJson = prettyJson!!

                            Text(
                                text = highlightJsonSyntax(formattedJson),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = viewerTextColor
                                ),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                                    .padding(12.dp)
                            )
                        }
                        else -> {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                color = Color(0xFF569CD6)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Simple JSON syntax highlighting using AnnotatedString.
 * Colors keys, strings, numbers, booleans, and null values distinctly.
 */
internal fun highlightJsonSyntax(json: String): androidx.compose.ui.text.AnnotatedString {
    val keyColor = JsonViewerColors.key
    val stringColor = JsonViewerColors.string
    val numberColor = JsonViewerColors.number
    val boolColor = JsonViewerColors.bool
    val nullColor = JsonViewerColors.nullColor
    val defaultColor = JsonViewerColors.text

    return buildAnnotatedString {
        var i = 0
        while (i < json.length) {
            when {
                // String (value or key)
                json[i] == '"' -> {
                    val start = i
                    i++
                    while (i < json.length) {
                        if (json[i] == '"') {
                            // Count consecutive backslashes before the quote
                            var slashCount = 0
                            var j = i - 1
                            while (j >= 0 && json[j] == '\\') {
                                slashCount++
                                j--
                            }
                            // Quote is escaped only with an ODD number of backslashes
                            if (slashCount % 2 == 0) break
                        }
                        i++
                    }
                    if (i < json.length) i++
                    val token = json.substring(start, i)

                    // Determine if this is a key or a value
                    // Look for ':' after the string, skipping whitespace
                    var j = i
                    while (j < json.length && json[j].isWhitespace()) j++
                    val isKey = j < json.length && json[j] == ':'

                    withStyle(SpanStyle(color = if (isKey) keyColor else stringColor)) {
                        append(token)
                    }
                }
                // Numbers (including negative and decimal)
                json[i] == '-' || json[i].isDigit() -> {
                    val start = i
                    if (json[i] == '-') i++
                    while (i < json.length && (json[i].isDigit() || json[i] == '.' || json[i] == 'e' || json[i] == 'E' || json[i] == '+' || json[i] == '-')) i++
                    withStyle(SpanStyle(color = numberColor)) {
                        append(json.substring(start, i))
                    }
                }
                // true / false
                json.startsWith("true", i) -> {
                    withStyle(SpanStyle(color = boolColor, fontWeight = FontWeight.SemiBold)) {
                        append("true")
                    }
                    i += 4
                }
                json.startsWith("false", i) -> {
                    withStyle(SpanStyle(color = boolColor, fontWeight = FontWeight.SemiBold)) {
                        append("false")
                    }
                    i += 5
                }
                // null
                json.startsWith("null", i) -> {
                    withStyle(SpanStyle(color = nullColor, fontWeight = FontWeight.SemiBold)) {
                        append("null")
                    }
                    i += 4
                }
                // Everything else (brackets, commas, colons, whitespace, newlines)
                else -> {
                    withStyle(SpanStyle(color = defaultColor)) {
                        append(json[i].toString())
                    }
                    i++
                }
            }
        }
    }
}
