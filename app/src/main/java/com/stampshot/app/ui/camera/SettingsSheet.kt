package com.stampshot.app.ui.camera

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stampshot.app.MainViewModel
import com.stampshot.app.data.AppSettings
import com.stampshot.app.location.LocationStamper
import com.stampshot.app.stamp.AddressMode
import com.stampshot.app.stamp.DateFormatOption
import com.stampshot.app.stamp.El
import com.stampshot.app.stamp.ElemSize
import com.stampshot.app.stamp.GpsFormat
import com.stampshot.app.stamp.StampAlign
import com.stampshot.app.stamp.StampFont
import com.stampshot.app.stamp.StampPosition
import com.stampshot.app.stamp.StampStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingLocationToggle by remember { mutableStateOf<(() -> Unit)?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        pendingLocationToggle?.invoke()
        pendingLocationToggle = null
    }

    fun enableLocationToggle(apply: () -> Unit) {
        if (LocationStamper.hasPermission(context)) {
            apply()
        } else {
            pendingLocationToggle = apply
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // ---------- Stamp style ----------
            SectionHeader("Stamp style")
            StampStyle.entries.forEach { style ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setStyle(style) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.style == style, onClick = { viewModel.setStyle(style) })
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(style.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when (style) {
                                StampStyle.MINIMAL_CORNER -> "Small translucent block in a corner"
                                StampStyle.CLEAN_BOTTOM_BAR -> "Full-width bar along the bottom"
                                StampStyle.WORK_PROOF -> "Labeled card for site & job documentation"
                                StampStyle.BOTTOM_INFO_STRIP -> "Photo stays clean — info strip added below"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---------- Appearance ----------
            SectionHeader("Stamp appearance")
            if (settings.style == StampStyle.MINIMAL_CORNER || settings.style == StampStyle.WORK_PROOF) {
                ChipRow(
                    label = "Position",
                    options = StampPosition.entries.map { it to it.label },
                    selected = settings.stampPosition,
                ) { viewModel.setStampPosition(it) }
            }
            ChipRow(
                label = "Text size",
                options = AppSettings.FONT_SCALE_OPTIONS,
                selected = settings.fontScale,
            ) { viewModel.setFontScale(it) }
            ChipRow(
                label = "Font",
                options = StampFont.entries.map { it to it.label },
                selected = settings.stampFont,
            ) { viewModel.setStampFont(it) }
            ColorRow(
                label = "Text color",
                choices = AppSettings.FONT_COLOR_CHOICES,
                selected = settings.fontColorArgb,
            ) { viewModel.setFontColorArgb(it) }
            SliderRow(
                label = "Text opacity",
                value = settings.textOpacity,
                range = 0.2f..1f,
            ) { viewModel.setTextOpacity(it) }
            ToggleRow(
                title = "Transparent stamp",
                subtitle = "No background — text blends into the photo with a soft shadow",
                checked = settings.stampTransparent,
            ) { viewModel.setStampTransparent(it) }
            if (!settings.stampTransparent) {
                ColorRow(
                    label = "Background color",
                    choices = AppSettings.BG_COLOR_CHOICES,
                    selected = settings.bgColorArgb,
                ) { viewModel.setBgColorArgb(it) }
                SliderRow(
                    label = "Background opacity",
                    value = settings.bgOpacity,
                    range = 0f..1f,
                ) { viewModel.setBgOpacity(it) }
            }

            // ---------- Stamp content ----------
            SectionHeader("Stamp text")
            Text(
                "Each element gets its own on/off, position (Left/Right column) and size.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val els = settings.elements
            ElementRow(
                title = "Date",
                el = els.date,
            ) { viewModel.setElements(els.copy(date = it)) }
            if (els.date.on) {
                ChipRow(
                    label = "Date format",
                    options = DateFormatOption.entries.map { it to it.label },
                    selected = settings.dateFormat,
                ) { viewModel.setDateFormat(it) }
            }
            ElementRow(
                title = "Time",
                el = els.time,
            ) { viewModel.setElements(els.copy(time = it)) }
            if (els.time.on) {
                ToggleRow(title = "Show seconds", checked = settings.showSeconds) {
                    viewModel.setShowSeconds(it)
                }
                ToggleRow(title = "24-hour clock", checked = settings.time24h) {
                    viewModel.setTime24h(it)
                }
            }
            ElementRow(
                title = "Session · number",
                el = els.session,
            ) { viewModel.setElements(els.copy(session = it)) }
            if (els.session.on) {
                ToggleRow(title = "Photo number (#001)", checked = settings.showNumber) {
                    viewModel.setShowNumber(it)
                }
            }
            ElementRow(
                title = "Activity",
                el = els.activity,
            ) { viewModel.setElements(els.copy(activity = it)) }
            if (els.activity.on) {
                OutlinedTextField(
                    value = settings.activity,
                    onValueChange = { viewModel.setActivity(it) },
                    label = { Text("Activity / kegiatan") },
                    placeholder = { Text("e.g. Site inspection, Patroli malam") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
            ElementRow(
                title = "Name",
                el = els.personName,
            ) { viewModel.setElements(els.copy(personName = it)) }
            if (els.personName.on) {
                OutlinedTextField(
                    value = settings.personName,
                    onValueChange = { viewModel.setPersonName(it) },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Budi Santoso") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
            ElementRow(
                title = "Address",
                el = els.address,
                onToggle = { on ->
                    if (on) enableLocationToggle { viewModel.setElements(els.copy(address = els.address.copy(on = true))) }
                    else viewModel.setElements(els.copy(address = els.address.copy(on = false)))
                },
            ) { viewModel.setElements(els.copy(address = it)) }
            if (els.address.on) {
                ChipRow(
                    label = "Address format",
                    options = AddressMode.entries.map { it to it.label },
                    selected = settings.addressMode,
                ) { viewModel.setAddressMode(it) }
            }
            ElementRow(
                title = "GPS coordinates",
                el = els.gps,
                onToggle = { on ->
                    if (on) enableLocationToggle { viewModel.setElements(els.copy(gps = els.gps.copy(on = true))) }
                    else viewModel.setElements(els.copy(gps = els.gps.copy(on = false)))
                },
            ) { viewModel.setElements(els.copy(gps = it)) }
            if (els.gps.on) {
                ChipRow(
                    label = "GPS format",
                    options = GpsFormat.entries.map { it to it.label },
                    selected = settings.gpsFormat,
                ) { viewModel.setGpsFormat(it) }
            }
            ElementRow(
                title = "Note",
                el = els.note,
            ) { viewModel.setElements(els.copy(note = it)) }
            if (els.note.on) {
                OutlinedTextField(
                    value = settings.note,
                    onValueChange = { viewModel.setNote(it) },
                    label = { Text("Custom note") },
                    placeholder = { Text("e.g. Block C, Floor 2") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }

            // ---------- Camera ----------
            SectionHeader("Camera")
            ToggleRow(title = "Grid lines", checked = settings.showGrid) {
                viewModel.setShowGrid(it)
            }
            ToggleRow(title = "Shutter sound", checked = settings.shutterSound) {
                viewModel.setShutterSound(it)
            }
            ToggleRow(
                title = "Touch to take photo",
                subtitle = "Tap anywhere on the preview to shoot (tap-to-focus is then disabled)",
                checked = settings.touchToCapture,
            ) { viewModel.setTouchToCapture(it) }
            ToggleRow(
                title = "Mirror front camera",
                subtitle = "Flip front-camera photos so text looks natural",
                checked = settings.mirrorFront,
            ) { viewModel.setMirrorFront(it) }
            ToggleRow(
                title = "Volume keys capture",
                subtitle = "Use volume buttons as the shutter",
                checked = settings.volumeKeysCapture,
            ) { viewModel.setVolumeKeysCapture(it) }
            ToggleRow(
                title = "Keep screen on",
                subtitle = "Prevent the screen from sleeping while the camera is open",
                checked = settings.keepScreenOn,
            ) { viewModel.setKeepScreenOn(it) }
            ChipRow(
                label = "Timer",
                options = AppSettings.TIMER_OPTIONS,
                selected = settings.timerSecs,
            ) { viewModel.setTimerSecs(it) }
            ChipRow(
                label = "Photo size",
                options = AppSettings.RES_OPTIONS,
                selected = settings.photoMaxDim,
            ) { viewModel.setPhotoMaxDim(it) }

            // ---------- Video ----------
            SectionHeader("Video")
            ChipRow(
                label = "Video quality",
                options = AppSettings.VIDEO_QUALITY_OPTIONS,
                selected = settings.videoQuality,
            ) { viewModel.setVideoQuality(it) }
            ToggleRow(
                title = "Microphone",
                subtitle = "Record audio with video",
                checked = settings.videoAudio,
            ) { viewModel.setVideoAudio(it) }

            // ---------- Storage & sharing ----------
            SectionHeader("Storage & sharing")
            ToggleRow(
                title = "Keep originals for private sharing",
                subtitle = "Stores a clean copy in private app storage so Private/Approximate shares can fully hide stamped location data. Uses extra storage.",
                checked = settings.keepOriginals,
            ) { viewModel.setKeepOriginals(it) }

            Text(
                "StampShot · offline, no account · photos in Pictures/StampShot",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

/**
 * One stamp element's editor: on/off switch, position (column) chips and size
 * chips. [onToggle] overrides the default switch behavior (e.g. to ask for the
 * location permission before enabling address/GPS).
 */
@Composable
private fun ElementRow(
    title: String,
    el: El,
    onToggle: ((Boolean) -> Unit)? = null,
    onChange: (El) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = el.on,
                onCheckedChange = { on -> onToggle?.invoke(on) ?: onChange(el.copy(on = on)) },
            )
        }
        if (el.on) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StampAlign.entries.forEach { a ->
                    FilterChip(
                        selected = el.side == a,
                        onClick = { onChange(el.copy(side = a)) },
                        label = { Text(a.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ElemSize.entries.forEach { z ->
                    FilterChip(
                        selected = el.size == z,
                        onClick = { onChange(el.copy(size = z)) },
                        label = { Text(z.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column {
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun <T> ChipRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(text, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
private fun ColorRow(
    label: String,
    choices: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            choices.forEach { (color, name) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clickable { onSelect(color) }
                            .background(
                                if (color == -1) Color(0xFF9AA4B0) else Color(color),
                                CircleShape,
                            )
                            .border(
                                width = if (selected == color) 3.dp else 1.dp,
                                color = if (selected == color) MaterialTheme.colorScheme.primary
                                    else Color(0x33000000),
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (color == -1) {
                            Text("A", style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                    Text(name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%d%%".format((value * 100).toInt()), style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
