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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
        containerColor = UiConstants.Pal.Card
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
                    color = UiConstants.Pal.Text
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onImportClicked) {
                    Icon(
                        Icons.Filled.Add,
                        "Import",
                        tint = UiConstants.Pal.Accent,
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
                        color = UiConstants.Pal.Text3,
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
        color = UiConstants.Pal.CardLight
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
                    color = UiConstants.Pal.Text,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Text(
                    "${model.format.uppercase()} • ${ModelManager.formatSize(model.sizeBytes)}",
                    color = UiConstants.Pal.Text3,
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
                    tint = UiConstants.Pal.Red,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = UiConstants.Pal.Card,
            title = {
                Text("Delete Model?", color = UiConstants.Pal.Text, fontSize = 16.sp)
            },
            text = {
                Text(
                    "Remove ${model.name} from your device?",
                    color = UiConstants.Pal.Text2,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text("Delete", color = UiConstants.Pal.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = UiConstants.Pal.Text2)
                }
            }
        )
    }
}