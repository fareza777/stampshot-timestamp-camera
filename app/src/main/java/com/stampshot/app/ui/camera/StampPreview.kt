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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stampshot.app.data.AppSettings
import com.stampshot.app.location.LocationStamper
import com.stampshot.app.stamp.LocationStamp
import com.stampshot.app.stamp.StampAlign
import com.stampshot.app.stamp.StampInfo
import com.stampshot.app.stamp.StampLine
import com.stampshot.app.stamp.StampPosition
import com.stampshot.app.stamp.StampRenderer
import com.stampshot.app.stamp.StampStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Live overlay on the camera preview showing exactly what the stamp will look
 * like for the current style — live clock, session + next photo number, and
 * the configured elements in their chosen columns and sizes. Adapts light/dark
 * the same way the renderer does by sampling the preview frame under the stamp
 * area once a second. In transparent mode the preview drops the scrim and adds
 * the same text shadow the baked stamp gets.
 */
@Composable
fun StampPreview(
    settings: AppSettings,
    previewView: PreviewView,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val s = settings
    val els = s.elements

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    // Sample the preview frame under the stamp region for smart readability.
    var luminance by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(s.style) {
        while (true) {
            delay(1500)
            val frame = previewView.bitmap
            if (frame != null) {
                val style = s.style
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

    // Live location for the preview — resolves the real address so the stamp
    // shows exactly what a photo will get (refreshed at capture anyway).
    var loc by remember { mutableStateOf<LocationStamp?>(null) }
    LaunchedEffect(els.address.on, els.gps.on, els.altitude.on) {
        loc = if ((els.address.on || els.gps.on || els.altitude.on) && LocationStamper.hasPermission(context)) {
            LocationStamper.snapshot(context, timeoutMs = 3000)
        } else null
    }

    val info = StampInfo(
        timestampMillis = now,
        sessionName = s.currentSession,
        photoNumber = s.nextNumber(),
        note = s.note.ifBlank { null },
        activity = s.activity.ifBlank { null },
        personName = s.personName.ifBlank { null },
        address = loc?.address,
        city = loc?.city,
        latitude = loc?.location?.latitude,
        longitude = loc?.location?.longitude,
        altitude = loc?.location?.altitude,
        showNumber = s.showNumber,
        elements = els,
        addressMode = s.addressMode,
        dateFormat = s.dateFormat,
        gpsFormat = s.gpsFormat,
        showSeconds = s.showSeconds,
        time24h = s.time24h,
    )
    val stampLines = info.stampLines()
    val leftLines = stampLines.filter { it.side == StampAlign.LEFT }
    val rightLines = stampLines.filter { it.side == StampAlign.RIGHT }

    val darkScene = luminance < 105.0
    fun Color.withOpacity(o: Float) = copy(alpha = alpha * o)
    val baseText = if (s.fontColorArgb != -1) Color(s.fontColorArgb)
        else if (darkScene) Color(0xFFF5F7FA) else Color(0xFF12161A)
    val text = baseText.withOpacity(s.textOpacity)
    val textDim = baseText.copy(alpha = baseText.alpha * 0.8f).withOpacity(s.textOpacity)
    val scrim = if (s.stampTransparent) Color.Transparent
        else (if (s.bgColorArgb != -1) Color(s.bgColorArgb)
            else if (darkScene) Color(0xFF080A0E) else Color(0xFFFAFBFD)
        ).withOpacity(s.bgOpacity)
    // Shadow opposite of the text tone so transparent text stays readable.
    val textShadow = if (s.stampTransparent) {
        Shadow(
            color = if (darkScene) Color(0xC0000000) else Color(0xC8FFFFFF),
            offset = Offset(0f, 1f),
            blurRadius = 6f,
        )
    } else null

    val fontFamily = when (s.stampFont) {
        com.stampshot.app.stamp.StampFont.SERIF -> FontFamily.Serif
        com.stampshot.app.stamp.StampFont.MONO -> FontFamily.Monospace
        else -> FontFamily.Default
    }
    val scale = s.fontScale

    val alignEnd = s.stampPosition == StampPosition.BOTTOM_RIGHT ||
        s.stampPosition == StampPosition.TOP_RIGHT
    val cornerMod = when (s.stampPosition) {
        StampPosition.BOTTOM_LEFT -> modifier.padding(start = 10.dp, bottom = 12.dp)
        StampPosition.BOTTOM_RIGHT -> modifier.padding(end = 10.dp, bottom = 12.dp).fillMaxWidth()
        StampPosition.TOP_LEFT -> modifier.padding(start = 10.dp, top = 12.dp)
        StampPosition.TOP_RIGHT -> modifier.padding(end = 10.dp, top = 12.dp).fillMaxWidth()
    }

    when (s.style) {
        StampStyle.MINIMAL_CORNER ->
            CornerStamp(stampLines, scrim, text, textDim, textShadow, cornerMod, scale, fontFamily, alignEnd)
        StampStyle.CLEAN_BOTTOM_BAR ->
            TwoColumnStamp(leftLines, rightLines, scrim, text, textDim, textShadow, modifier, scale, fontFamily)
        StampStyle.WORK_PROOF ->
            WorkProofStamp(info, stampLines, scrim, text, textDim, textShadow, cornerMod, scale, fontFamily, alignEnd)
        StampStyle.BOTTOM_INFO_STRIP ->
            TwoColumnStamp(leftLines, rightLines, scrim, text, textDim, textShadow, modifier, scale, fontFamily)
    }
}

@Composable
private fun StampBox(scrim: Color, alignEnd: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val boxMod = if (scrim == Color.Transparent) Modifier
        else Modifier.clip(RoundedCornerShape(6.dp)).background(scrim)
    val padded = Modifier.then(boxMod).padding(horizontal = 10.dp, vertical = 7.dp)
    if (alignEnd) {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(modifier = padded) { content() }
        }
    } else {
        Column(modifier = modifier.then(padded)) { content() }
    }
}

@Composable
private fun CornerStamp(
    lines: List<StampLine>, scrim: Color, text: Color, textDim: Color, shadow: Shadow?,
    modifier: Modifier, scale: Float, fontFamily: FontFamily, alignEnd: Boolean,
) {
    StampBox(scrim, alignEnd, modifier) {
        StampLines(
            lines, text, textDim, primarySp = 13f * scale, secondarySp = 11f * scale,
            fontFamily = fontFamily, alignRight = alignEnd, shadow = shadow,
        )
    }
}

@Composable
private fun TwoColumnStamp(
    leftLines: List<StampLine>, rightLines: List<StampLine>,
    scrim: Color, text: Color, textDim: Color, shadow: Shadow?,
    modifier: Modifier, scale: Float, fontFamily: FontFamily,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (scrim == Color.Transparent) Modifier
                else Modifier.background(scrim),
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(0.5f)) {
            StampLines(
                leftLines, text, textDim, primarySp = 12f * scale, secondarySp = 11f * scale,
                fontFamily = fontFamily, alignRight = false, wrap = true, shadow = shadow,
            )
        }
        Column(
            modifier = Modifier.weight(0.5f),
            horizontalAlignment = androidx.compose.ui.Alignment.End,
        ) {
            StampLines(
                rightLines, text, textDim, primarySp = 12f * scale, secondarySp = 10f * scale,
                fontFamily = fontFamily, alignRight = true, wrap = true, shadow = shadow,
            )
        }
    }
}

@Composable
private fun WorkProofStamp(
    info: StampInfo, lines: List<StampLine>, scrim: Color, text: Color, textDim: Color, shadow: Shadow?,
    modifier: Modifier, scale: Float, fontFamily: FontFamily, alignEnd: Boolean,
) {
    val accent = text
    val header = info.sessionLine() ?: "StampShot"
    val rows = lines.toMutableList().apply {
        val i = indexOfFirst { it.text == header }
        if (i >= 0) removeAt(i)
    }
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .then(if (scrim == Color.Transparent) Modifier else Modifier.background(scrim)),
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .background(accent)
                    .padding(start = 2.dp),
            ) {}
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(
                    header,
                    color = text,
                    fontSize = (13f * scale).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = fontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall.copy(shadow = shadow),
                )
                StampLines(
                    rows, text, textDim,
                    primarySp = 11f * scale, secondarySp = 10f * scale, fontFamily = fontFamily,
                    alignRight = alignEnd, shadow = shadow,
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
private fun StampLines(
    lines: List<StampLine>,
    text: Color,
    textDim: Color,
    primarySp: Float,
    secondarySp: Float,
    fontFamily: FontFamily = FontFamily.Default,
    alignRight: Boolean = false,
    wrap: Boolean = false,
    shadow: Shadow? = null,
) {
    val align = if (alignRight) androidx.compose.ui.text.style.TextAlign.End
        else androidx.compose.ui.text.style.TextAlign.Start
    lines.forEach { line ->
        // One uniform size for all elements; bold affects weight only.
        val sp = primarySp * line.size.scale
        Text(
            line.text,
            color = if (line.bold) text else textDim,
            fontSize = sp.sp,
            fontWeight = if (line.bold) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = fontFamily,
            style = MaterialTheme.typography.bodySmall.copy(shadow = shadow),
            textAlign = align,
            maxLines = if (wrap) line.maxLines else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
