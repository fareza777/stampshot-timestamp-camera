package com.stampshot.app.ui.camera

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stampshot.app.MainViewModel
import com.stampshot.app.location.LocationStamper
import com.stampshot.app.stamp.StampStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingLocationToggle by remember { mutableStateOf<(() -> Unit)?>(null) }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        pendingLocationToggle?.invoke()
        pendingLocationToggle = null
    }

    fun enableLocationToggle(apply: () -> Unit, context: android.content.Context) {
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
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("Stamp style", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StampStyle.entries.forEach { style ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setStyle(style) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.style == style, onClick = { viewModel.setStyle(style) })
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(style.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when (style) {
                                StampStyle.MINIMAL_CORNER -> "Small translucent block, bottom-left"
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text("Stamp content", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            val context = androidx.compose.ui.platform.LocalContext.current
            ToggleRow(
                title = "Address on stamp",
                checked = settings.showAddress,
            ) { v ->
                if (v) enableLocationToggle({ viewModel.setShowAddress(true) }, context)
                else viewModel.setShowAddress(false)
            }
            ToggleRow(
                title = "GPS coordinates",
                checked = settings.showGps,
            ) { v ->
                if (v) enableLocationToggle({ viewModel.setShowGps(true) }, context)
                else viewModel.setShowGps(false)
            }

            OutlinedTextField(
                value = settings.note,
                onValueChange = { viewModel.setNote(it) },
                label = { Text("Custom note (optional)") },
                placeholder = { Text("e.g. Block C, Floor 2") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
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
