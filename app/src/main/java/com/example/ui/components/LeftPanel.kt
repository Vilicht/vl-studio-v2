package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.SceneEntity
import com.example.data.model.SourceEntity
import com.example.data.model.SourceType
import com.example.ui.theme.*

/** Perintah urutan layer dari menu source. */
enum class LayerCommand { FRONT, FORWARD, BACKWARD, BACK }

@Composable
fun LeftPanel(
    scenes: List<SceneEntity>,
    selectedScene: SceneEntity?,
    sources: List<SourceEntity>,
    selectedSource: SourceEntity?,
    onSelectScene: (SceneEntity) -> Unit,
    onAddScene: () -> Unit,
    onDuplicateScene: (SceneEntity) -> Unit,
    onRenameScene: (SceneEntity) -> Unit,
    onDeleteScene: (SceneEntity) -> Unit,
    onSelectSource: (String) -> Unit,
    onAddSource: () -> Unit,
    onToggleSourceVisibility: (SourceEntity) -> Unit,
    onToggleSourceLock: (SourceEntity) -> Unit,
    onReorderSource: (SourceEntity, Boolean) -> Unit,
    onDuplicateSource: (SourceEntity) -> Unit,
    onDeleteSource: (SourceEntity) -> Unit,
    /** Drag-and-drop: indeks UI (0 = paling depan/teratas) asal -> tujuan. */
    onMoveLayer: (Int, Int) -> Unit,
    onLayerCommand: (SourceEntity, LayerCommand) -> Unit,
    onRenameSource: (SourceEntity, String) -> Unit,
    panelWidth: androidx.compose.ui.unit.Dp = 288.dp,
    modifier: Modifier = Modifier
) {
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    var rowPx by remember { mutableStateOf(120f) }
    var renaming by remember { mutableStateOf<SourceEntity?>(null) }
    val rowGap = with(LocalDensity.current) { 4.dp.toPx() }

    Surface(
        color = CharcoalPanel,
        modifier = modifier
            .width(panelWidth)
            .fillMaxHeight()
            .border(1.dp, CharcoalBorder)
            .testTag("studio_left_panel")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            // TOP SECTION: SCENE MANAGER
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "SCENES",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                IconButton(
                    onClick = onAddScene,
                    modifier = Modifier.size(24.dp).testTag("add_scene_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Scene",
                        tint = ChampagneGold,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Scenes List (fixed height / weight 0.4)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.42f)
            ) {
                items(items = scenes, key = { it.id }) { scene ->
                    val isSelected = scene.id == selectedScene?.id
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) CharcoalSurface else Color.Transparent)
                            .border(
                                width = if (isSelected) 1.dp else 0.dp,
                                color = if (isSelected) ChampagneGold else Color.Transparent,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .clickable { onSelectScene(scene) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("scene_item_${scene.name}")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = scene.name,
                                color = if (isSelected) TextPrimary else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            Row {
                                IconButton(
                                    onClick = { onRenameScene(scene) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Rename Scene",
                                        tint = TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onDuplicateScene(scene) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Duplicate Scene",
                                        tint = TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                if (scenes.size > 1) {
                                    IconButton(
                                        onClick = { onDeleteScene(scene) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete Scene",
                                            tint = TextMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Divider(color = CharcoalBorder, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

            // BOTTOM SECTION: SOURCE MANAGER
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "SOURCES",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                IconButton(
                    onClick = onAddSource,
                    modifier = Modifier.size(24.dp).testTag("add_source_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Source",
                        tint = ChampagneGold,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Sources List (remaining weight)
            // Urutan sama persis dengan ViewModel (zIndex naik, id sebagai pemutus seri), lalu dibalik:
            // paling depan di atas. Screenshot referensi (khusus editor) ada di bawah daftar dan tidak ikut drag.
            val layers = sources.filter { !it.isReference }.sortedWith(compareBy({ it.zIndex }, { it.id })).reversed()
            val sortedSources = layers + sources.filter { it.isReference }
            if (sortedSources.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.58f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No sources in scene\nTap + to add Screen or Camera",
                        color = TextMuted,
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.58f)
                ) {
                    itemsIndexed(items = sortedSources, key = { _, item -> item.id }) { uiIndex, source ->
                        val isSelected = source.id == selectedSource?.id
                        val isDragging = draggingId == source.id
                        var menuOpen by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer { translationY = if (isDragging) dragY else 0f }
                                .onSizeChanged { rowPx = it.height.toFloat() + rowGap }
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) CharcoalSurface else Color.Transparent)
                                .border(
                                    width = if (isSelected) 1.dp else 0.dp,
                                    color = if (isSelected) ChampagneGold else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .clickable { onSelectSource(source.id) }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .testTag("source_item_${source.name}")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Drag handle: seret ke atas/bawah untuk mengubah urutan layer
                                if (!source.isReference) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "Drag untuk mengatur layer",
                                        tint = if (isDragging) ChampagneGold else TextMuted,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .pointerInput(source.id, uiIndex, layers.size) {
                                                detectDragGestures(
                                                    onDragStart = { draggingId = source.id; dragY = 0f },
                                                    onDrag = { change, amount -> change.consume(); dragY += amount.y },
                                                    onDragEnd = {
                                                        val to = (uiIndex + (dragY / rowPx).roundToInt()).coerceIn(0, layers.lastIndex)
                                                        draggingId = null; dragY = 0f
                                                        if (to != uiIndex) onMoveLayer(uiIndex, to)
                                                    },
                                                    onDragCancel = { draggingId = null; dragY = 0f }
                                                )
                                            }
                                    )
                                }

                                // Visibility toggle
                                IconButton(
                                    onClick = { onToggleSourceVisibility(source) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (source.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Toggle Visibility",
                                        tint = if (source.isVisible) TextPrimary else TextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Lock toggle
                                IconButton(
                                    onClick = { onToggleSourceLock(source) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (source.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                        contentDescription = "Toggle Lock",
                                        tint = if (source.isLocked) ChampagneGold else TextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Source icon & name
                                val typeIcon = when (source.type) {
                                    SourceType.SCREEN -> Icons.Default.ScreenShare
                                    SourceType.CAMERA -> Icons.Default.Videocam
                                    SourceType.TEXT -> Icons.Default.TextFields
                                    SourceType.IMAGE -> Icons.Default.Image
                                    SourceType.WEB -> Icons.Default.Language
                                    SourceType.VIDEO -> Icons.Default.Movie
                                    SourceType.CHAT -> Icons.Default.Chat
                                    SourceType.PNGTUBER -> Icons.Default.Face
                                }
                                Icon(
                                    imageVector = typeIcon,
                                    contentDescription = source.type.name,
                                    tint = ChampagneGold,
                                    modifier = Modifier.size(18.dp)
                                )

                                Text(
                                    text = source.name,
                                    color = if (isSelected) TextPrimary else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )

                                // Reorder actions
                                IconButton(
                                    onClick = { onReorderSource(source, true) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = "Move Up",
                                        tint = TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onReorderSource(source, false) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDownward,
                                        contentDescription = "Move Down",
                                        tint = TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                // Menu: rename, urutan, duplikat
                                Box {
                                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.MoreVert, "Menu source", tint = TextMuted, modifier = Modifier.size(16.dp))
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        DropdownMenuItem(text = { Text("Ganti nama") }, onClick = { menuOpen = false; renaming = source })
                                        if (!source.isReference) {
                                            DropdownMenuItem(text = { Text("Bring to Front") }, onClick = { menuOpen = false; onLayerCommand(source, LayerCommand.FRONT) })
                                            DropdownMenuItem(text = { Text("Bring Forward") }, onClick = { menuOpen = false; onLayerCommand(source, LayerCommand.FORWARD) })
                                            DropdownMenuItem(text = { Text("Send Backward") }, onClick = { menuOpen = false; onLayerCommand(source, LayerCommand.BACKWARD) })
                                            DropdownMenuItem(text = { Text("Send to Back") }, onClick = { menuOpen = false; onLayerCommand(source, LayerCommand.BACK) })
                                            DropdownMenuItem(text = { Text("Duplikat") }, onClick = { menuOpen = false; onDuplicateSource(source) })
                                        }
                                    }
                                }

                                // Delete
                                IconButton(
                                    onClick = { onDeleteSource(source) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Delete Source",
                                        tint = TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Ganti nama source") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { onRenameSource(target, text); renaming = null }) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Batal") } }
        )
    }
}
