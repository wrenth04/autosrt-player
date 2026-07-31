package com.example.autosrtplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.autosrtplayer.ui.vr.depth.DepthModel
import com.example.autosrtplayer.ui.vr.depth.ModelStatus

@Composable
fun DepthModelManagementDialog(
    availableModels: List<DepthModel>,
    modelStatuses: Map<String, ModelStatus>,
    selectedModelId: String?,
    totalModelSizeMB: Float,
    onSelectModel: (String) -> Unit,
    onDownloadModel: (DepthModel) -> Unit,
    onDeleteModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("深度模型管理") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "已使用空間：${String.format("%.1f", totalModelSizeMB)} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                availableModels.forEach { model ->
                    val status = modelStatuses[model.id] ?: ModelStatus.NotDownloaded
                    DepthModelCard(
                        model = model,
                        status = status,
                        isSelected = model.id == selectedModelId,
                        onSelect = { onSelectModel(model.id) },
                        onDownload = { onDownloadModel(model) },
                        onDelete = { onDeleteModel(model.id) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("關閉")
            }
        }
    )
}

@Composable
private fun DepthModelCard(
    model: DepthModel,
    status: ModelStatus,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (model.recommended) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "推薦",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = "${model.fileSizeMB} MB • ${model.license}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when (status) {
                    is ModelStatus.NotDownloaded -> {
                        IconButton(onClick = onDownload) {
                            Icon(Icons.Default.Download, "下載")
                        }
                    }
                    is ModelStatus.Downloading -> {
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    is ModelStatus.Downloaded -> {
                        Row {
                            RadioButton(
                                selected = isSelected,
                                onClick = onSelect
                            )
                            IconButton(onClick = onDelete) {
                                Icon(Icons.Default.Delete, "刪除")
                            }
                        }
                    }
                    is ModelStatus.Error -> {
                        Text(
                            text = "錯誤",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
