package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.SourceType
import com.example.ui.theme.*

@Composable
fun AddSourceDialog(
    isOpen: Boolean,
    onAddSource: (name: String, type: SourceType, textContent: String?) -> Unit,
    onAddWebUrl: (name: String, url: String) -> Unit,
    onImportHtml: (name: String, uris: List<Uri>) -> Unit,
    onImportImage: (Uri) -> Unit,
    onImportVideo: (Uri, String) -> Unit = { _, _ -> },
    onAddVideoUrl: (String, String) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    if (!isOpen) return

    var webUrl by remember { mutableStateOf("https://") }
    var videoUrl by remember { mutableStateOf("") }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { onImportImage(uri); onDismiss() }
    }

    var selectedType by remember { mutableStateOf(SourceType.SCREEN) }
    var sourceName by remember { mutableStateOf("Display Capture") }
    var textContent by remember { mutableStateOf("LIVE STREAM") }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { onImportVideo(uri, sourceName); onDismiss() }
    }
    var sourceNameForHtml by remember { mutableStateOf("HTML Lokal") }
    val pickHtml = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { onImportHtml(sourceNameForHtml.ifBlank { "HTML Lokal" }, uris); onDismiss() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, CharcoalBorder, RoundedCornerShape(12.dp))
                .testTag("add_source_dialog"),
            color = CharcoalPanel
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "ADD NEW SOURCE",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Divider(color = CharcoalBorder, thickness = 1.dp)

                Text(text = "SELECT SOURCE TYPE", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceTypeOption(
                        type = SourceType.SCREEN,
                        title = "Screen Capture (Primary)",
                        desc = "Full-display capture via MediaProjection for games & apps",
                        icon = Icons.Default.ScreenShare,
                        isSelected = selectedType == SourceType.SCREEN,
                        onSelect = {
                            selectedType = SourceType.SCREEN
                            sourceName = "Display Capture"
                        }
                    )
                    SourceTypeOption(
                        type = SourceType.CAMERA,
                        title = "Facecam / Camera",
                        desc = "Camera feed overlay with GPU Chroma Key support",
                        icon = Icons.Default.Videocam,
                        isSelected = selectedType == SourceType.CAMERA,
                        onSelect = {
                            selectedType = SourceType.CAMERA
                            sourceName = "Facecam"
                        }
                    )
                    SourceTypeOption(
                        type = SourceType.TEXT,
                        title = "Text Overlay",
                        desc = "Custom titles, watermarks, social handles, or notices",
                        icon = Icons.Default.TextFields,
                        isSelected = selectedType == SourceType.TEXT,
                        onSelect = {
                            selectedType = SourceType.TEXT
                            sourceName = "Text Label"
                        }
                    )
                    SourceTypeOption(
                        type = SourceType.IMAGE,
                        title = "Image Overlay",
                        desc = "Banners, stream frames, sponsor logos",
                        icon = Icons.Default.Image,
                        isSelected = selectedType == SourceType.IMAGE,
                        onSelect = {
                            selectedType = SourceType.IMAGE
                            sourceName = "Image Layer"
                        }
                    )
                    SourceTypeOption(
                        type = SourceType.VIDEO,
                        title = "Video Overlay",
                        desc = "File video lokal (loop), bisa di-crop dan green screen (chroma key). Tanpa suara ke siaran",
                        icon = Icons.Default.Movie,
                        isSelected = selectedType == SourceType.VIDEO,
                        onSelect = {
                            selectedType = SourceType.VIDEO
                            sourceName = "Video Layer"
                        }
                    )
                    SourceTypeOption(
                        type = SourceType.WEB,
                        title = "Browser Overlay",
                        desc = "URL website/alert, atau file HTML lokal. Ikut masuk ke output RTMP",
                        icon = Icons.Default.Language,
                        isSelected = selectedType == SourceType.WEB,
                        onSelect = {
                            selectedType = SourceType.WEB
                            sourceName = "Browser"
                        }
                    )
                }

                OutlinedTextField(
                    value = sourceName,
                    onValueChange = { sourceName = it },
                    label = { Text("Source Label", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth().testTag("source_name_input"),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ChampagneGold,
                        unfocusedBorderColor = CharcoalBorder,
                        focusedContainerColor = CharcoalSurface,
                        unfocusedContainerColor = CharcoalSurface
                    )
                )

                if (selectedType == SourceType.TEXT) {
                    OutlinedTextField(
                        value = textContent,
                        onValueChange = { textContent = it },
                        label = { Text("Display Text", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ChampagneGold,
                            unfocusedBorderColor = CharcoalBorder,
                            focusedContainerColor = CharcoalSurface,
                            unfocusedContainerColor = CharcoalSurface
                        )
                    )
                }

                if (selectedType == SourceType.WEB) {
                    OutlinedTextField(
                        value = webUrl,
                        onValueChange = { webUrl = it },
                        label = { Text("URL Browser Source", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().testTag("web_url_input"),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ChampagneGold, unfocusedBorderColor = CharcoalBorder,
                            focusedContainerColor = CharcoalSurface, unfocusedContainerColor = CharcoalSurface
                        )
                    )
                    OutlinedButton(
                        onClick = {
                            sourceNameForHtml = sourceName
                            pickHtml.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text("Impor HTML lokal (file + resource, atau ZIP)", fontSize = 11.sp, color = ChampagneGoldLight)
                    }
                }

                if (selectedType == SourceType.VIDEO) {
                    OutlinedTextField(
                        value = videoUrl,
                        onValueChange = { videoUrl = it },
                        label = { Text("URL video (opsional, https://...mp4)", fontSize = 10.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("video_url_field")
                    )
                }
                val primaryLabel = when (selectedType) {
                    SourceType.IMAGE -> "PILIH GAMBAR"
                    SourceType.VIDEO -> if (videoUrl.isBlank()) "PILIH VIDEO" else "TAMBAH VIDEO DARI URL"
                    SourceType.WEB -> "TAMBAH DARI URL"
                    else -> "ADD TO SCENE"
                }
                Button(
                    onClick = {
                        when (selectedType) {
                            SourceType.IMAGE -> pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                            SourceType.VIDEO -> if (videoUrl.isBlank()) pickVideo.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            ) else { onAddVideoUrl(sourceName, videoUrl.trim()); onDismiss() }
                            SourceType.WEB -> { onAddWebUrl(sourceName, webUrl); onDismiss() }
                            else -> {
                                onAddSource(sourceName, selectedType, if (selectedType == SourceType.TEXT) textContent else null)
                                onDismiss()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ChampagneGold,
                        contentColor = CharcoalMain
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp).testTag("confirm_add_source_button")
                ) {
                    Text(primaryLabel, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SourceTypeOption(
    type: SourceType,
    title: String,
    desc: String,
    icon: ImageVector,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        color = if (isSelected) CharcoalSurface else CharcoalMain,
        shape = RoundedCornerShape(8.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isSelected) ChampagneGold else CharcoalBorder
            )
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isSelected) ChampagneGold else TextSecondary,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (isSelected) ChampagneGold else TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = desc,
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun AddSceneDialog(
    isOpen: Boolean,
    onAddScene: (name: String, canvas: com.example.composition.CanvasSpec) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isOpen) return

    var sceneName by remember { mutableStateOf("New Scene") }
    var canvas by remember { mutableStateOf(com.example.composition.CanvasSpec.LANDSCAPE) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, CharcoalBorder, RoundedCornerShape(12.dp))
                .testTag("add_scene_dialog"),
            color = CharcoalPanel
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "CREATE NEW SCENE",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Divider(color = CharcoalBorder, thickness = 1.dp)

                OutlinedTextField(
                    value = sceneName,
                    onValueChange = { sceneName = it },
                    label = { Text("Scene Name", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth().testTag("scene_name_input"),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ChampagneGold,
                        unfocusedBorderColor = CharcoalBorder,
                        focusedContainerColor = CharcoalSurface,
                        unfocusedContainerColor = CharcoalSurface
                    )
                )

                CanvasPicker(current = canvas, onChange = { canvas = it })

                Button(
                    onClick = {
                        if (sceneName.isNotBlank()) {
                            onAddScene(sceneName.trim(), canvas)
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ChampagneGold,
                        contentColor = CharcoalMain
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(40.dp).testTag("confirm_add_scene_button")
                ) {
                    Text("CREATE SCENE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}
