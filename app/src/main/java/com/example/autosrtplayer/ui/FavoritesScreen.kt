package com.example.autosrtplayer.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.autosrtplayer.data.favorites.FavoriteItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

@Composable
internal fun FavoritesScreen(
    items: List<FavoriteItem>,
    onBack: () -> Unit,
    onItemClick: (String) -> Unit,
    onRemoveClick: (String) -> Unit,
    onExportClick: () -> Unit,
    onImportConfirmed: (String) -> Unit,
    feedbackMessage: String? = null,
    onDismissFeedback: () -> Unit = {}
) {
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var importDraft by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("我的最愛", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onExportClick) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "匯出最愛"
                    )
                }
                IconButton(onClick = { showImportDialog = true }) {
                    Icon(
                        imageVector = Icons.Filled.ContentPaste,
                        contentDescription = "匯入最愛"
                    )
                }
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "返回"
                    )
                }
            }
        }

        feedbackMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
            LaunchedEffect(message) {
                delay(3000L)
                onDismissFeedback()
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (items.isEmpty()) {
                    Text(
                        text = "尚未加入我的最愛",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items.forEach { item ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FavoriteCoverImage(
                                        imageUrl = favoriteCoverUrl(item.id),
                                        contentDescription = item.id,
                                        modifier = Modifier
                                            .width(80.dp)
                                            .aspectRatio(4f / 3f)
                                    )
                                    Text(
                                        text = item.id,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { onItemClick(item.id) }) {
                                        Text("▶")
                                    }
                                    TextButton(onClick = { onRemoveClick(item.id) }) {
                                        Text("✕")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                importDraft = ""
            },
            title = { Text("匯入最愛") },
            text = {
                OutlinedTextField(
                    value = importDraft,
                    onValueChange = { importDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("貼上 ID 列表（每行一個）") },
                    placeholder = { Text("每行一個 ID") },
                    minLines = 3,
                    maxLines = 20,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onImportConfirmed(importDraft)
                        showImportDialog = false
                        importDraft = ""
                    },
                    enabled = importDraft.isNotBlank()
                ) {
                    Text("確認匯入")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    importDraft = ""
                }) {
                    Text("取消")
                }
            }
        )
    }
}

private fun favoriteCoverUrl(id: String): String {
    val cleanCode = id.trim().lowercase(Locale.ROOT)
    return "https://fourhoi.com/$cleanCode/cover-n.jpg"
}

@Composable
private fun FavoriteCoverImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val client = remember { OkHttpClient() }
    val bitmapState = produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, imageUrl) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(imageUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    response.body?.byteStream()?.use { input ->
                        BitmapFactory.decodeStream(input)?.asImageBitmap()
                    }
                }
            }.getOrNull()
        }
    }

    val shape = RoundedCornerShape(6.dp)
    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier.clip(shape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}
