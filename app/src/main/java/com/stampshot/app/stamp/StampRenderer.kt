package com.stampshot.app.stamp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max

/**
 * Draws timestamp stamps onto captured photos. All geometry scales with the
 * bitmap width so the stamp looks consistent across sensor resolutions.
 *
 * Smart Readability: the destination region is sampled for average luminance
 * and the stamp flips between a light-text/dark-scrim and dark-text/light-scrim
 * theme so it stays readable on any scene.
 */
object StampRenderer {

    private const val DARK_SCENE_THRESHOLD = 105.0

    private class Theme(darkScene: Boolean) {
        val text: Int = if (darkScene) Color.rgb(245, 247, 250) else Color.rgb(18, 22, 26)
        val textDim: Int = if (darkScene) Color.rgb(200, 207, 216) else Color.rgb(60, 68, 78)
        val scrim: Int = if (darkScene) Color.argb(148, 8, 10, 14) else Color.argb(158, 250, 251, 253)
        val accent: Int = if (darkScene) Color.rgb(97, 200, 247) else Color.rgb(2, 119, 189)
    }

    fun render(source: Bitmap, info: StampInfo, style: StampStyle): Bitmap {
        return when (style) {
            StampStyle.MINIMAL_CORNER -> renderOnImage(source, info) { c, theme ->
                drawMinimalCorner(c, source.width, source.height, info, theme)
            }
            StampStyle.CLEAN_BOTTOM_BAR -> renderOnImage(source, info) { c, theme ->
                drawBottomBar(c, source.width, source.height, info, theme, scrimOnly = true)
            }
            StampStyle.WORK_PROOF -> renderOnImage(source, info) { c, theme ->
                drawWorkProof(c, source.width, source.height, info, theme)
            }
            StampStyle.BOTTOM_INFO_STRIP -> renderInfoStrip(source, info)
        }
    }

    private inline fun renderOnImage(
        source: Bitmap,
        info: StampInfo,
        draw: (Canvas, Theme) -> Unit,
    ): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val theme = Theme(regionLuminance(out, stampRegionHint(out, info)) < DARK_SCENE_THRESHOLD)
        draw(canvas, theme)
        return out
    }

    /** Region used for luminance sampling — where the stamp's text will sit. */
    private fun stampRegionHint(b: Bitmap, info: StampInfo): RectF {
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val lines = info.lines().size.coerceAtLeast(1)
        val estH = (w * 0.045f) * lines + w * 0.03f
        return RectF(w * 0.02f, h - estH - w * 0.02f, w * 0.85f, h - w * 0.01f)
    }

    // ---------- Styles ----------

    private fun drawMinimalCorner(c: Canvas, w: Int, h: Int, info: StampInfo, theme: Theme) {
        val wf = w.toFloat()
        val padH = wf * 0.018f
        val padV = wf * 0.014f
        val margin = wf * 0.02f
        val radius = wf * 0.008f

        val primary = textPaint(wf * 0.036f, theme.text, bold = true)
        val secondary = textPaint(wf * 0.030f, theme.textDim)

        val lines = info.lines()
        val textW = lines.maxOf { primary.measureText(it) }
        val lineH = lineHeight(primary)
        val gap = lineH * 0.22f

        val boxW = textW + padH * 2
        val boxH = lines.size * lineH + (lines.size - 1) * gap + padV * 2
        val left = margin
        val top = h - margin - boxH

        c.drawRoundRect(RectF(left, top, left + boxW, top + boxH), radius, radius, fillPaint(theme.scrim))

        var baseline = top + padV - primary.ascent()
        lines.forEachIndexed { i, line ->
            val paint = if (i == 0) primary else secondary
            c.drawText(line, left + padH, baseline, paint)
            baseline += lineH + gap
        }
    }

    private fun drawBottomBar(c: Canvas, w: Int, h: Int, info: StampInfo, theme: Theme, scrimOnly: Boolean) {
        val wf = w.toFloat()
        val padH = wf * 0.022f
        val padV = wf * 0.016f

        val primary = textPaint(wf * 0.034f, theme.text, bold = true)
        val secondary = textPaint(wf * 0.028f, theme.textDim)
        val lineH = lineHeight(primary)
        val gap = lineH * 0.25f

        val leftLines = buildList {
            add(info.dateTime())
            info.sessionLine()?.let { add(it) }
        }
        val leftW = leftLines.maxOf { primary.measureText(it) }
        val rightMaxW = wf - padH * 3 - leftW
        val rightLines = buildList {
            info.addressLine()?.let { add(ellipsize(secondary, it, rightMaxW)) }
            info.gpsLine()?.let { add(ellipsize(secondary, it, rightMaxW)) }
            info.noteLine()?.let { add(ellipsize(secondary, it, rightMaxW)) }
        }
        val rows = max(leftLines.size, rightLines.size)
        val barH = rows * lineH + (rows - 1) * gap + padV * 2
        val top = h - barH

        c.drawRect(RectF(0f, top, wf, h.toFloat()), fillPaint(theme.scrim))

        var baseline = top + padV - primary.ascent()
        leftLines.forEachIndexed { i, line ->
            c.drawText(line, padH, baseline, if (i == 0) primary else secondary)
            baseline += lineH + gap
        }
        baseline = top + padV - secondary.ascent()
        rightLines.forEach { line ->
            c.drawText(line, wf - padH - secondary.measureText(line), baseline, secondary)
            baseline += lineH + gap
        }
    }

    private fun ellipsize(paint: Paint, text: String, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end) + ellipsis) > maxWidth) end--
        return text.take(end).trimEnd() + ellipsis
    }

    private fun drawWorkProof(c: Canvas, w: Int, h: Int, info: StampInfo, theme: Theme) {
        val wf = w.toFloat()
        val padH = wf * 0.022f
        val padV = wf * 0.016f
        val margin = wf * 0.02f
        val radius = wf * 0.006f
        val accentW = wf * 0.006f

        val title = textPaint(wf * 0.034f, theme.text, bold = true)
        val body = textPaint(wf * 0.029f, theme.textDim)
        val label = textPaint(wf * 0.021f, theme.accent, bold = true)

        val header = ellipsize(title, info.sessionLine() ?: "StampShot", wf * 0.82f)
        val rowMaxW = wf * 0.82f
        val rows = buildList {
            add(ellipsize(body, info.dateTime(), rowMaxW))
            info.addressLine()?.let { add(ellipsize(body, it, rowMaxW)) }
            info.gpsLine()?.let { add(ellipsize(body, it, rowMaxW)) }
            info.noteLine()?.let { add(ellipsize(body, it, rowMaxW)) }
        }

        val titleH = lineHeight(title)
        val bodyH = lineHeight(body)
        val gap = bodyH * 0.25f
        val dividerGap = bodyH * 0.55f
        val maxText = max(title.measureText(header), rows.maxOfOrNull { body.measureText(it) } ?: 0f)

        val boxW = maxText + padH * 2 + accentW
        val boxH = padV * 2 + titleH + dividerGap + rows.size * bodyH + (rows.size - 1).coerceAtLeast(0) * gap
        val left = margin
        val top = h - margin - boxH

        c.drawRoundRect(RectF(left, top, left + boxW, top + boxH), radius, radius, fillPaint(theme.scrim))
        c.drawRect(RectF(left, top, left + accentW, top + boxH), fillPaint(theme.accent))

        val titleBaseline = top + padV - title.ascent()
        c.drawText(header, left + accentW + padH, titleBaseline, title)

        var baseline = titleBaseline + titleH + dividerGap
        val titleBottom = titleBaseline + title.descent()
        val firstRowTop = baseline + body.ascent()
        val dividerY = titleBottom + (firstRowTop - titleBottom) / 2
        c.drawRect(
            RectF(left + accentW + padH, dividerY, left + boxW - padH, dividerY + wf * 0.0016f),
            fillPaint(theme.accent),
        )

        rows.forEach { row ->
            c.drawText(row, left + accentW + padH, baseline, body)
            baseline += bodyH + gap
        }
    }

    private fun renderInfoStrip(source: Bitmap, info: StampInfo): Bitmap {
        val w = source.width
        val h = source.height
        val wf = w.toFloat()

        val primary = textPaint(wf * 0.032f, Color.rgb(245, 247, 250), bold = true)
        val secondary = textPaint(wf * 0.027f, Color.rgb(196, 204, 214))
        val lineH = lineHeight(primary)
        val gap = lineH * 0.28f
        val padV = wf * 0.016f
        val padH = wf * 0.024f

        val leftLines = buildList {
            add(info.dateTime())
            info.sessionLine()?.let { add(it) }
        }
        val leftW = leftLines.maxOf { primary.measureText(it) }
        val rightMaxW = wf - padH * 3 - leftW
        val rightLines = buildList {
            info.addressLine()?.let { add(ellipsize(secondary, it, rightMaxW)) }
            info.gpsLine()?.let { add(ellipsize(secondary, it, rightMaxW)) }
            info.noteLine()?.let { add(ellipsize(secondary, it, rightMaxW)) }
        }
        val rows = max(leftLines.size, rightLines.size)
        val stripH = (rows * lineH + (rows - 1) * gap + padV * 2).toInt()

        val out = Bitmap.createBitmap(w, h + stripH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(source, 0f, 0f, null)
        c.drawRect(RectF(0f, h.toFloat(), wf, (h + stripH).toFloat()), fillPaint(Color.rgb(16, 19, 24)))
        c.drawRect(RectF(0f, h.toFloat(), wf, h + wf * 0.002f), fillPaint(Color.rgb(97, 200, 247)))

        var baseline = h + padV - primary.ascent()
        leftLines.forEachIndexed { i, line ->
            c.drawText(line, padH, baseline, if (i == 0) primary else secondary)
            baseline += lineH + gap
        }
        baseline = h + padV - secondary.ascent()
        rightLines.forEach { line ->
            c.drawText(line, wf - padH - secondary.measureText(line), baseline, secondary)
            baseline += lineH + gap
        }
        return out
    }

    // ---------- Luminance sampling ----------

    fun regionLuminance(bitmap: Bitmap, region: RectF): Double {
        val left = region.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = region.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, bitmap.height)

        val stepX = max(1, (right - left) / 24)
        val stepY = max(1, (bottom - top) / 10)
        var total = 0.0
        var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val p = bitmap.getPixel(x, y)
                total += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 0.0 else total / count
    }

    /** Downscaled-sample luminance of an arbitrary preview bitmap region, for the live camera preview. */
    fun previewLuminance(bitmap: Bitmap, leftFrac: Float, topFrac: Float, rightFrac: Float, bottomFrac: Float): Double {
        return regionLuminance(
            bitmap,
            RectF(
                bitmap.width * leftFrac, bitmap.height * topFrac,
                bitmap.width * rightFrac, bitmap.height * bottomFrac,
            ),
        )
    }

    // ---------- Helpers ----------

    private fun textPaint(size: Float, color: Int, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }

    private fun fillPaint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun lineHeight(p: Paint): Float = p.descent() - p.ascent()
}
