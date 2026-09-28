package com.stampshot.app.ui.camera

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stampshot.app.data.AppSettings
import com.stampshot.app.location.LocationStamper
import com.stampshot.app.stamp.StampInfo
import com.stampshot.app.stamp.StampRenderer
import com.stampshot.app.stamp.StampStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live overlay on the camera preview showing exactly what the stamp will look
 * like for the current style — live clock, session + next photo number, and
 * location lines when enabled. Adapts light/dark the same way the renderer
 * does by sampling the preview frame under the stamp area once a second.
 */
@Composable
fun StampPreview(
    settings: AppSettings,
    previewView: PreviewView,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    // Sample the preview frame under the stamp region for smart readability.
    var luminance by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(settings.style) {
        while (true) {
            delay(800)
            val frame = previewView.bitmap
            if (frame != null) {
                val style = settings.style
                luminance = withContext(Dispatchers.Default) {
                    when (style) {
                        StampStyle.CLEAN_BOTTOM_BAR ->
                            StampRenderer.previewLuminance(frame, 0f, 0.82f, 1f, 0.97f)
                        else ->
                            StampRenderer.previewLuminance(frame, 0f, 0.75f, 0.7f, 0.97f)
                    }
                }
            }
        }
    }

    // One-shot coarse location hint for the preview (fine data resolved at capture).
    var locationHint by remember { mutableStateOf<String?>(null) }
    var coordsHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.showAddress, settings.showGps) {
        if ((settings.showAddress || settings.showGps) && LocationStamper.hasPermission(context)) {
            val loc = LocationStamper.lastKnown(context)
            if (loc != null) {
                coordsHint = "%.5f, %.5f".format(Locale.US, loc.latitude, loc.longitude)
                locationHint = "address resolved at capture"
            }
        } else {
            locationHint = null
            coordsHint = null
        }
    }

    val darkScene = luminance < 105.0
    val text = if (darkScene) Color(0xFFF5F7FA) else Color(0xFF12161A)
    val textDim = if (darkScene) Color(0xFFC8CFD8) else Color(0xFF3C444E)
    val scrim = if (darkScene) Color(0x94080A0E) else Color(0x9EFAFBFD)

    val date = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(now))
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
    val sessionLine = "${settings.currentSession} · #%03d".format(settings.nextNumber())
    val lines = buildList {
        add("$date · $time")
        add(sessionLine)
        if (settings.showAddress && locationHint != null) add(locationHint!!)
        if (settings.showGps && coordsHint != null) add(coordsHint!!)
        if (settings.note.isNotBlank()) add(settings.note)
    }

    when (settings.style) {
        StampStyle.MINIMAL_CORNER -> CornerStamp(lines, scrim, text, textDim, modifier)
        StampStyle.CLEAN_BOTTOM_BAR -> BottomBarStamp(lines, scrim, text, textDim, modifier)
        StampStyle.WORK_PROOF -> WorkProofStamp(lines, scrim, text, textDim, modifier)
        StampStyle.BOTTOM_INFO_STRIP -> InfoStripStamp(lines, text, textDim, modifier)
    }
}

@Composable
private fun CornerStamp(lines: List<String>, scrim: Color, text: Color, textDim: Color, modifier: Modifier) {
    Column(
        modifier = modifier
            .padding(start = 10.dp, bottom = 150.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(scrim)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        StampLines(lines, text, textDim, primarySp = 13f, secondarySp = 11f)
    }
}

@Composable
private fun BottomBarStamp(lines: List<String>, scrim: Color, text: Color, textDim: Color, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 132.dp)
            .background(scrim)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(0.5f)) {
            StampLines(lines.take(2), text, textDim, primarySp = 13f, secondarySp = 11f)
        }
        Column(
            modifier = Modifier.weight(0.5f),
            horizontalAlignment = androidx.compose.ui.Alignment.End,
        ) {
            StampLines(lines.drop(2), text, textDim, primarySp = 11f, secondarySp = 10f)
        }
    }
}

@Composable
private fun WorkProofStamp(lines: List<String>, scrim: Color, text: Color, textDim: Color, modifier: Modifier) {
    Row(
        modifier = modifier
            .padding(start = 10.dp, bottom = 150.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(scrim),
    ) {
        Column(
            modifier = Modifier
                .padding(start = 6.dp)
                .background(Color(0xFF4FC3F7))
                .padding(start = 2.dp),
        ) {}
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(
                lines.getOrElse(1) { "" },
                color = text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            StampLines(listOf(lines[0]) + lines.drop(2), text, textDim, primarySp = 11f, secondarySp = 10f)
        }
    }
}

@Composable
private fun InfoStripStamp(lines: List<String>, text: Color, textDim: Color, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 118.dp)
            .background(Color(0xFF101318))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(0.5f)) {
            StampLines(lines.take(2), Color(0xFFF5F7FA), Color(0xFFC4CCD6), primarySp = 12f, secondarySp = 10f)
        }
        Column(
            modifier = Modifier.weight(0.5f),
            horizontalAlignment = androidx.compose.ui.Alignment.End,
        ) {
            StampLines(lines.drop(2), Color(0xFFF5F7FA), Color(0xFFC4CCD6), primarySp = 10f, secondarySp = 10f)
        }
    }
}

@Composable
private fun StampLines(
    lines: List<String>,
    text: Color,
    textDim: Color,
    primarySp: Float,
    secondarySp: Float,
) {
    lines.forEachIndexed { i, line ->
        Text(
            line,
            color = if (i == 0) text else textDim,
            fontSize = (if (i == 0) primarySp else secondarySp).sp,
            fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
