package com.stampshot.app.ui.camera

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontFamily
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
    val s = settings
    fun Color.withOpacity(o: Float) = copy(alpha = alpha * o)
    val baseText = if (s.fontColorArgb != -1) Color(s.fontColorArgb)
        else if (darkScene) Color(0xFFF5F7FA) else Color(0xFF12161A)
    val text = baseText.withOpacity(s.textOpacity)
    val textDim = baseText.copy(alpha = baseText.alpha * 0.8f).withOpacity(s.textOpacity)
    val baseScrim = if (s.bgColorArgb != -1) Color(s.bgColorArgb)
        else if (darkScene) Color(0xFF080A0E) else Color(0xFFFAFBFD)
    val scrim = baseScrim.withOpacity(s.bgOpacity)

    val fontFamily = when (s.stampFont) {
        com.stampshot.app.stamp.StampFont.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif
        com.stampshot.app.stamp.StampFont.MONO -> androidx.compose.ui.text.font.FontFamily.Monospace
        else -> androidx.compose.ui.text.font.FontFamily.Default
    }
    val scale = s.fontScale

    val date = SimpleDateFormat(s.dateFormat.pattern, Locale.getDefault()).format(Date(now))
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
    val sessionLine = if (s.showNumber) "${s.currentSession} · #%03d".format(s.nextNumber()) else s.currentSession
    val lines = buildList {
        add("$date · $time")
        add(sessionLine)
        if (s.showAddress && locationHint != null) add(locationHint!!)
        if (s.showGps && coordsHint != null) add(coordsHint!!)
        if (s.note.isNotBlank()) add(s.note)
    }

    val cornerMod = when (s.stampPosition) {
        com.stampshot.app.stamp.StampPosition.BOTTOM_LEFT ->
            modifier.padding(start = 10.dp, bottom = 150.dp)
        com.stampshot.app.stamp.StampPosition.BOTTOM_RIGHT ->
            modifier.padding(end = 10.dp, bottom = 150.dp).fillMaxWidth()
        com.stampshot.app.stamp.StampPosition.TOP_LEFT ->
            modifier.padding(start = 10.dp, top = 90.dp)
        com.stampshot.app.stamp.StampPosition.TOP_RIGHT ->
            modifier.padding(end = 10.dp, top = 90.dp).fillMaxWidth()
    }
    val alignEnd = s.stampPosition == com.stampshot.app.stamp.StampPosition.BOTTOM_RIGHT ||
        s.stampPosition == com.stampshot.app.stamp.StampPosition.TOP_RIGHT

    when (s.style) {
        StampStyle.MINIMAL_CORNER -> CornerStamp(lines, scrim, text, textDim, cornerMod, scale, fontFamily, alignEnd)
        StampStyle.CLEAN_BOTTOM_BAR -> BottomBarStamp(lines, scrim, text, textDim, modifier, scale, fontFamily)
        StampStyle.WORK_PROOF -> WorkProofStamp(lines, scrim, text, textDim, cornerMod, scale, fontFamily, alignEnd)
        StampStyle.BOTTOM_INFO_STRIP -> InfoStripStamp(lines, scrim, text, textDim, modifier, scale, fontFamily)
    }
}

@Composable
private fun StampBox(scrim: Color, alignEnd: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    if (alignEnd) {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(scrim)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) { content() }
        }
    } else {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(scrim)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) { content() }
    }
}

@Composable
private fun CornerStamp(
    lines: List<String>, scrim: Color, text: Color, textDim: Color,
    modifier: Modifier, scale: Float, fontFamily: FontFamily, alignEnd: Boolean,
) {
    StampBox(scrim, alignEnd, modifier) {
        StampLines(lines, text, textDim, primarySp = 13f * scale, secondarySp = 11f * scale, fontFamily = fontFamily)
    }
}

@Composable
private fun BottomBarStamp(
    lines: List<String>, scrim: Color, text: Color, textDim: Color,
    modifier: Modifier, scale: Float, fontFamily: FontFamily,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 132.dp)
            .background(scrim)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(0.5f)) {
            StampLines(lines.take(2), text, textDim, primarySp = 13f * scale, secondarySp = 11f * scale, fontFamily = fontFamily)
        }
        Column(
            modifier = Modifier.weight(0.5f),
            horizontalAlignment = androidx.compose.ui.Alignment.End,
        ) {
            StampLines(lines.drop(2), text, textDim, primarySp = 11f * scale, secondarySp = 10f * scale, fontFamily = fontFamily)
        }
    }
}

@Composable
private fun WorkProofStamp(
    lines: List<String>, scrim: Color, text: Color, textDim: Color,
    modifier: Modifier, scale: Float, fontFamily: FontFamily, alignEnd: Boolean,
) {
    val accent = text
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(scrim),
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .background(accent)
                    .padding(start = 2.dp),
            ) {}
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(
                    lines.getOrElse(1) { "" },
                    color = text,
                    fontSize = (13f * scale).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StampLines(
                    listOf(lines[0]) + lines.drop(2), text, textDim,
                    primarySp = 11f * scale, secondarySp = 10f * scale, fontFamily = fontFamily,
                )
            }
        }
    }
    if (alignEnd) {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { body() }
    } else {
        Box(modifier = modifier) { body() }
    }
}

@Composable
private fun InfoStripStamp(
    lines: List<String>, scrim: Color, text: Color, textDim: Color,
    modifier: Modifier, scale: Float, fontFamily: FontFamily,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 118.dp)
            .background(scrim)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(0.5f)) {
            StampLines(lines.take(2), text, textDim, primarySp = 12f * scale, secondarySp = 10f * scale, fontFamily = fontFamily)
        }
        Column(
            modifier = Modifier.weight(0.5f),
            horizontalAlignment = androidx.compose.ui.Alignment.End,
        ) {
            StampLines(lines.drop(2), text, textDim, primarySp = 10f * scale, secondarySp = 10f * scale, fontFamily = fontFamily)
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
    fontFamily: FontFamily = FontFamily.Default,
) {
    lines.forEachIndexed { i, line ->
        Text(
            line,
            color = if (i == 0) text else textDim,
            fontSize = (if (i == 0) primarySp else secondarySp).sp,
            fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = fontFamily,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
