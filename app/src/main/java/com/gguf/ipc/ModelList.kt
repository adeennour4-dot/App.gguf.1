package com.gguf.ipc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Palette (duplicate for ModelList access) ──────────────────────────
private object PalModelList {
    val Bg = Color(0xFF0A0A0F)
    val Surface = Color(0xFF141420)
    val Card = Color(0xFF1A1A2E)
    val CardLight = Color(0xFF22223A)
    val Text = Color(0xFFEAEAEE)
    val Text2 = Color(0xFF9898AA)
    val Text3 = Color(0xFF5C5C72)
    val Accent = Color(0xFF6C63FF)
    val Red = Color(0xFFFF4757)
    val Purple = Color(0xFFBB86FC)
    val ThinkBg = Color(0xFF1E1A33)
}

// Model List Dialog Composable
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelListDialog(
    onDismiss: () -> Unit,
    onModelSelected: (ModelManager.Model) -> Unit,
    onModelDeleted: (String) -> Unit,
    onImportClicked: () -> Unit
) {
    val models = ModelManager.getModels()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PalModelList.Card
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Text(
                    "Your Models",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = PalModelList.Text
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onImportClicked) {
                    Icon(
                        Icons.Filled.Add,
                        "Import",
                        tint = PalModelList.Accent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (models.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No models imported yet\nTap + to add one",
                        color = PalModelList.Text3,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn {
                    items(models) { model ->
                        ModelListItem(
                            model = model,
                            onClick = { onModelSelected(model) },
                            onDelete = { onModelDeleted(model.id) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun ModelListItem(
    model: ModelManager.Model,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = PalModelList.CardLight
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                ModelManager.getEngineIcon(model.format),
                fontSize = 20.sp,
                modifier = Modifier.padding(end = 12.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    model.name,
                    color = PalModelList.Text,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Text(
                    "${model.format.uppercase()} • ${ModelManager.formatSize(model.sizeBytes)}",
                    color = PalModelList.Text3,
                    fontSize = 11.sp
                )
            }
            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Filled.Delete,
                    "Delete",
                    tint = PalModelList.Red,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = PalModelList.Card,
            title = {
                Text("Delete Model?", color = PalModelList.Text, fontSize = 16.sp)
            },
            text = {
                Text(
                    "Remove ${model.name} from your device?",
                    color = PalModelList.Text2,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text("Delete", color = PalModelList.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = PalModelList.Text2)
                }
            }
        )
    }
}