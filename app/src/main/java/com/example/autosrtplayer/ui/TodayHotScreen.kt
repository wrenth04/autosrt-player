package com.example.autosrtplayer.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.autosrtplayer.data.todayhot.TodayHotItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@Composable
internal fun TodayHotScreen(
    items: List<TodayHotItem>,
    isLoading: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onItemClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("今日熱門", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) {
                Text("返回")
            }
        }

        TodayHotSection(
            items = items,
            isLoading = isLoading,
            errorMessage = errorMessage,
            onItemClick = onItemClick
        )
    }
}

@Composable
private fun TodayHotSection(
    items: List<TodayHotItem>,
    isLoading: Boolean,
    errorMessage: String?,
    onItemClick: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("今日熱門", style = MaterialTheme.typography.titleMedium)
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }

            if (items.isNotEmpty()) {
                BoxWithConstraints {
                    val columnCount = if (maxWidth >= 600.dp) 2 else 1
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items.chunked(columnCount).forEach { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                rowItems.forEach { item ->
                                    TodayHotItemCard(
                                        item = item,
                                        onItemClick = onItemClick,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (columnCount > rowItems.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            } else if (!isLoading && errorMessage == null) {
                Text(
                    text = "尚未載入資料",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun TodayHotCoverImage(
    imageUrl: String,
    contentDescription: String
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

    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "封面載入中",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun TodayHotItemCard(
    item: TodayHotItem,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .clickable { onItemClick(item.code) }
            ) {
                TodayHotCoverImage(
                    imageUrl = item.fourHoiCoverUrl,
                    contentDescription = item.title ?: item.code
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = item.code, style = MaterialTheme.typography.titleSmall)
                Text(text = item.title ?: item.code, style = MaterialTheme.typography.bodyMedium)
                item.updatedAt?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "更新時間：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
