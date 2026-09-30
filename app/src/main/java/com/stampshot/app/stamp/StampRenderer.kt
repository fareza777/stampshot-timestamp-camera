package com.stampshot.app.stamp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * Draws timestamp stamps onto captured photos. All geometry scales with the
 * bitmap width so the stamp looks consistent across sensor resolutions.
 *
 * Smart Readability: the destination region is sampled for average luminance
 * and the stamp flips between a light-text/dark-scrim and dark-text/light-scrim
 * theme so it stays readable on any scene.
 *
 * Transparent mode (StampOptions.transparent) drops the scrim/box entirely and
 * gives each glyph a soft shadow so the stamp blends into the photo.
 */
object StampRenderer {

    private const val DARK_SCENE_THRESHOLD = 105.0

    private class Theme(darkScene: Boolean, opts: StampOptions) {
        private fun withAlpha(color: Int, opacity: Float): Int =
            Color.argb(
                (Color.alpha(color) * opacity).toInt().coerceIn(0, 255),
                Color.red(color), Color.green(color), Color.blue(color),
            )

        val text: Int = withAlpha(
            if (opts.fontColorArgb != -1) opts.fontColorArgb
            else if (darkScene) Color.rgb(245, 247, 250) else Color.rgb(18, 22, 26),
            opts.textOpacity,
        )
        val textDim: Int = withAlpha(
            if (opts.fontColorArgb != -1) Color.argb(204, Color.red(opts.fontColorArgb), Color.green(opts.fontColorArgb), Color.blue(opts.fontColorArgb))
            else if (darkScene) Color.rgb(200, 207, 216) else Color.rgb(60, 68, 78),
            opts.textOpacity,
        )
        val scrim: Int = withAlpha(
            if (opts.bgColorArgb != -1) opts.bgColorArgb
            else if (darkScene) Color.rgb(8, 10, 14) else Color.rgb(250, 251, 253),
            opts.bgOpacity,
        )
        val accent: Int = withAlpha(
            if (opts.fontColorArgb != -1) opts.fontColorArgb
            else if (darkScene) Color.rgb(97, 200, 247) else Color.rgb(2, 119, 189),
            opts.textOpacity,
        )
        /** Halo behind text in transparent mode: opposite of the text tone. */
        val shadow: Int = if (darkScene) Color.argb(190, 0, 0, 0) else Color.argb(200, 255, 255, 255)
    }

    fun render(source: Bitmap, info: StampInfo, style: StampStyle, opts: StampOptions = StampOptions()): Bitmap {
        return when (style) {
            StampStyle.MINIMAL_CORNER -> renderOnImage(source, info, opts) { c, theme ->
                drawMinimalCorner(c, source.width, source.height, info, theme, opts)
            }
            StampStyle.CLEAN_BOTTOM_BAR -> renderOnImage(source, info, opts) { c, theme ->
                drawBottomBar(c, source.width, source.height, info, theme, opts)
            }
            StampStyle.WORK_PROOF -> renderOnImage(source, info, opts) { c, theme ->
                drawWorkProof(c, source.width, source.height, info, theme, opts)
            }
            StampStyle.BOTTOM_INFO_STRIP ->
                // Transparent mode merges into the photo: render as an overlay
                // bottom bar instead of extending the canvas.
                if (opts.transparent) {
                    renderOnImage(source, info, opts) { c, theme ->
                        drawBottomBar(c, source.width, source.height, info, theme, opts)
                    }
                } else {
                    renderInfoStrip(source, info, opts)
                }
        }
    }

    /**
     * Renders just the stamp (no photo) into a transparent bitmap of [w]x[h] —
     * the video pipeline blends this onto decoded YUV frames.
     */
    fun renderOverlayBitmap(w: Int, h: Int, info: StampInfo, style: StampStyle, opts: StampOptions = StampOptions()): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawOnFrame(Canvas(bmp), w, h, info, style, opts)
        return bmp
    }

    /**
     * Draws the stamp overlay onto an existing frame canvas — used per-frame for
     * video via CameraEffect. No luminance sampling (no bitmap to sample): uses
     * the dark-scene theme, which stays readable thanks to the scrim or, in
     * transparent mode, the text shadow.
     * BOTTOM_INFO_STRIP can't extend the frame, so it renders as the same
     * full-width info strip overlaid on the bottom edge.
     */
    fun drawOnFrame(c: Canvas, w: Int, h: Int, info: StampInfo, style: StampStyle, opts: StampOptions = StampOptions()) {
        val theme = Theme(darkScene = true, opts)
        when (style) {
            StampStyle.MINIMAL_CORNER -> drawMinimalCorner(c, w, h, info, theme, opts)
            StampStyle.WORK_PROOF -> drawWorkProof(c, w, h, info, theme, opts)
            StampStyle.CLEAN_BOTTOM_BAR, StampStyle.BOTTOM_INFO_STRIP -> drawBottomBar(c, w, h, info, theme, opts)
        }
    }

    private inline fun renderOnImage(
        source: Bitmap,
        info: StampInfo,
        opts: StampOptions,
        draw: (Canvas, Theme) -> Unit,
    ): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val theme = Theme(regionLuminance(out, stampRegionHint(out, info)) < DARK_SCENE_THRESHOLD, opts)
        draw(canvas, theme)
        return out
    }

    /** Region used for luminance sampling — where the stamp's text will sit. */
    private fun stampRegionHint(b: Bitmap, info: StampInfo): RectF {
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val lines = info.stampLines().size.coerceAtLeast(1)
        val estH = (w * 0.045f) * lines + w * 0.03f
        return RectF(w * 0.02f, h - estH - w * 0.02f, w * 0.85f, h - w * 0.01f)
    }

    private fun stampLeft(position: StampPosition, w: Float, margin: Float, boxW: Float): Float =
        when (position) {
            StampPosition.BOTTOM_LEFT, StampPosition.TOP_LEFT -> margin
            StampPosition.BOTTOM_RIGHT, StampPosition.TOP_RIGHT -> w - margin - boxW
        }

    private fun stampTop(position: StampPosition, h: Int, margin: Float, boxH: Float): Float =
        when (position) {
            StampPosition.BOTTOM_LEFT, StampPosition.BOTTOM_RIGHT -> h - margin - boxH
            StampPosition.TOP_LEFT, StampPosition.TOP_RIGHT -> margin
        }

    private fun positionIsRight(position: StampPosition): Boolean =
        position == StampPosition.BOTTOM_RIGHT || position == StampPosition.TOP_RIGHT

    // ---------- Shared layout ----------

    /** Paint for one stamp line: base size scaled by the element's size setting. */
    private fun linePaint(theme: Theme, opts: StampOptions, wf: Float, line: StampLine): Paint {
        val base = wf * (if (line.bold) 0.034f else 0.028f) * opts.fontScale * line.size.scale
        val p = textPaint(base, if (line.bold) theme.text else theme.textDim, line.bold, opts.font)
        if (opts.transparent) p.setShadowLayer(base * 0.16f, 0f, base * 0.05f, theme.shadow)
        return p
    }

    /**
     * Splits resolved lines into left/right wrapped columns of (text, paint).
     * Column widths adapt: each column keeps at least ~30% of the width and can
     * grow into the space left by the other.
     */
    private fun twoColumns(
        info: StampInfo,
        wf: Float,
        theme: Theme,
        opts: StampOptions,
        padH: Float,
    ): Pair<List<Pair<String, Paint>>, List<Pair<String, Paint>>> {
        val lines = info.stampLines()
        val left = lines.filter { it.side == StampAlign.LEFT }
        val right = lines.filter { it.side == StampAlign.RIGHT }
        val paints = HashMap<StampLine, Paint>(lines.size * 2)
        lines.forEach { paints[it] = linePaint(theme, opts, wf, it) }

        val leftCap = if (right.isEmpty()) wf - padH * 2 else wf * 0.56f
        val leftW = (left.maxOfOrNull { paints[it]!!.measureText(it.text) } ?: 0f)
            .coerceAtMost(leftCap)
        val rightMaxW = if (left.isEmpty()) {
            wf - padH * 2
        } else {
            (wf - padH * 3 - leftW).coerceAtLeast(wf * 0.28f)
        }
        val leftMaxW = if (right.isEmpty()) wf - padH * 2 else (wf - padH * 3 - wf * 0.28f).coerceAtLeast(leftW)

        fun build(ls: List<StampLine>, w: Float) = ls.flatMap { l ->
            val p = paints[l]!!
            wrap(p, l.text, w, l.maxLines).map { it to p }
        }
        return build(left, leftMaxW) to build(right, rightMaxW)
    }

    private fun drawColumn(
        c: Canvas,
        rows: List<Pair<String, Paint>>,
        top: Float,
        edge: Float,
        alignRight: Boolean,
        gap: Float,
    ) {
        var baseline = top
        rows.forEach { (line, paint) ->
            val x = if (alignRight) edge - paint.measureText(line) else edge
            c.drawText(line, x, baseline - paint.ascent(), paint)
            baseline += (paint.descent() - paint.ascent()) + gap
        }
    }

    // ---------- Styles ----------

    private fun drawMinimalCorner(c: Canvas, w: Int, h: Int, info: StampInfo, theme: Theme, opts: StampOptions) {
        val wf = w.toFloat()
        val s = opts.fontScale
        val padH = wf * 0.018f * s
        val padV = wf * 0.014f * s
        val margin = wf * 0.02f
        val radius = wf * 0.008f

        val maxTextW = wf * 0.86f - padH * 2
        val refH = lineHeight(textPaint(wf * 0.03f * s, theme.text, font = opts.font))
        val gap = refH * 0.22f

        val rows = info.stampLines().flatMap { l ->
            val p = linePaint(theme, opts, wf, l)
            wrap(p, l.text, maxTextW, min(l.maxLines, 2)).map { it to p }
        }
        if (rows.isEmpty()) return
        val textW = rows.maxOf { (t, p) -> p.measureText(t) }
        val textH = rows.sumOf { (it.second.descent() - it.second.ascent()).toDouble() }.toFloat() +
            (rows.size - 1) * gap

        val boxW = textW + padH * 2
        val boxH = textH + padV * 2
        val left = stampLeft(opts.position, wf, margin, boxW)
        val top = stampTop(opts.position, h, margin, boxH)

        if (!opts.transparent) {
            c.drawRoundRect(RectF(left, top, left + boxW, top + boxH), radius, radius, fillPaint(theme.scrim))
        }

        val rightAlign = positionIsRight(opts.position)
        var baseline = top + padV
        rows.forEach { (line, paint) ->
            baseline += -paint.ascent()
            val x = if (rightAlign) left + boxW - padH - paint.measureText(line) else left + padH
            c.drawText(line, x, baseline, paint)
            baseline += paint.descent() + gap
        }
    }

    private fun drawBottomBar(c: Canvas, w: Int, h: Int, info: StampInfo, theme: Theme, opts: StampOptions) {
        val wf = w.toFloat()
        val s = opts.fontScale
        val padH = wf * 0.022f
        val padV = wf * 0.016f

        val refPaint = textPaint(wf * 0.028f * s, theme.textDim, font = opts.font)
        val refH = lineHeight(refPaint)
        val gap = refH * 0.25f

        val (leftCol, rightCol) = twoColumns(info, wf, theme, opts, padH)

        fun colH(rows: List<Pair<String, Paint>>): Float =
            if (rows.isEmpty()) 0f
            else rows.sumOf { (it.second.descent() - it.second.ascent()).toDouble() }.toFloat() +
                (rows.size - 1) * gap

        val barH = max(colH(leftCol), colH(rightCol)) + padV * 2
        val top = h - barH

        if (!opts.transparent) {
            c.drawRect(RectF(0f, top, wf, h.toFloat()), fillPaint(theme.scrim))
        }

        drawColumn(c, leftCol, top + padV, padH, alignRight = false, gap)
        drawColumn(c, rightCol, top + padV, wf - padH, alignRight = true, gap)
    }

    /** Word-wrap [text] to lines fitting [maxWidth]; clamps to [maxLines] by ellipsizing the last line. */
    private fun wrap(paint: Paint, text: String, maxWidth: Float, maxLines: Int): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf(text)
        val lines = mutableListOf<String>()
        var cur = ""
        for (word in words) {
            val cand = if (cur.isEmpty()) word else "$cur $word"
            if (paint.measureText(cand) <= maxWidth || cur.isEmpty()) {
                cur = cand
            } else {
                lines.add(cur)
                cur = word
            }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        if (lines.size <= maxLines) return lines
        val kept = lines.take(maxLines).toMutableList()
        val spilled = (listOf(kept.removeAt(kept.size - 1)) + lines.drop(maxLines)).joinToString(" ")
        kept.add(ellipsize(paint, spilled, maxWidth))
        return kept
    }

    private fun ellipsize(paint: Paint, text: String, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end) + ellipsis) > maxWidth) end--
        return text.take(end).trimEnd() + ellipsis
    }

    private fun drawWorkProof(c: Canvas, w: Int, h: Int, info: StampInfo, theme: Theme, opts: StampOptions) {
        val wf = w.toFloat()
        val s = opts.fontScale
        val padH = wf * 0.022f
        val padV = wf * 0.016f
        val margin = wf * 0.02f
        val radius = wf * 0.006f
        val accentW = wf * 0.006f

        val title = textPaint(wf * 0.034f * s, theme.text, bold = true, font = opts.font)
        if (opts.transparent) title.setShadowLayer(wf * 0.034f * s * 0.16f, 0f, 0f, theme.shadow)

        val rightAlign = positionIsRight(opts.position)
        val header = ellipsize(title, info.sessionLine() ?: "StampShot", wf * 0.82f)
        val rowMaxW = wf * 0.82f

        // Rows are the resolved element lines minus the session line (used as header).
        val all = info.stampLines()
        val sessionText = info.sessionLine()
        val rowLines = all.toMutableList().apply {
            if (sessionText != null) {
                val i = indexOfFirst { it.text == sessionText }
                if (i >= 0) removeAt(i)
            }
        }
        val rows = rowLines.flatMap { l ->
            val p = linePaint(theme, opts, wf, l)
            wrap(p, l.text, rowMaxW, l.maxLines).map { it to p }
        }

        val titleH = lineHeight(title)
        val refH = lineHeight(textPaint(wf * 0.029f * s, theme.textDim, font = opts.font))
        val gap = refH * 0.25f
        val dividerGap = refH * 0.55f
        val rowsH = rows.sumOf { (it.second.descent() - it.second.ascent()).toDouble() }.toFloat() +
            (rows.size - 1).coerceAtLeast(0) * gap
        val maxText = max(title.measureText(header), rows.maxOfOrNull { (t, p) -> p.measureText(t) } ?: 0f)

        val boxW = maxText + padH * 2 + accentW
        val boxH = padV * 2 + titleH + dividerGap + rowsH
        val left = stampLeft(opts.position, wf, margin, boxW)
        val top = stampTop(opts.position, h, margin, boxH)

        if (!opts.transparent) {
            c.drawRoundRect(RectF(left, top, left + boxW, top + boxH), radius, radius, fillPaint(theme.scrim))
        }
        val accentL = if (rightAlign) left + boxW - accentW else left
        c.drawRect(RectF(accentL, top, accentL + accentW, top + boxH), fillPaint(theme.accent))

        // Text span inside the card, inset from the accent edge.
        val textL = if (rightAlign) left + padH else left + accentW + padH
        val textR = if (rightAlign) left + boxW - accentW - padH else left + boxW - padH
        fun xFor(paint: Paint, line: String): Float =
            if (rightAlign) textR - paint.measureText(line) else textL

        val titleBaseline = top + padV - title.ascent()
        c.drawText(header, xFor(title, header), titleBaseline, title)

        var baseline = titleBaseline + titleH + dividerGap
        val titleBottom = titleBaseline + title.descent()
        val firstRowTop = baseline + (rows.firstOrNull()?.second?.ascent() ?: 0f)
        val dividerY = titleBottom + (firstRowTop - titleBottom) / 2
        c.drawRect(
            RectF(textL, dividerY, textR, dividerY + wf * 0.0016f),
            fillPaint(theme.accent),
        )

        rows.forEach { (row, paint) ->
            baseline += -paint.ascent()
            c.drawText(row, xFor(paint, row), baseline, paint)
            baseline += paint.descent() + gap
        }
    }

    private fun renderInfoStrip(source: Bitmap, info: StampInfo, opts: StampOptions): Bitmap {
        val w = source.width
        val h = source.height
        val wf = w.toFloat()
        val s = opts.fontScale

        val textColor = if (opts.fontColorArgb != -1) opts.fontColorArgb else Color.rgb(245, 247, 250)
        val dimColor = if (opts.fontColorArgb != -1) {
            Color.argb(204, Color.red(opts.fontColorArgb), Color.green(opts.fontColorArgb), Color.blue(opts.fontColorArgb))
        } else Color.rgb(196, 204, 214)
        val stripColor = if (opts.bgColorArgb != -1) opts.bgColorArgb else Color.rgb(16, 19, 24)

        val padV = wf * 0.016f
        val padH = wf * 0.024f
        val refPaint = textPaint(wf * 0.027f * s, withAlpha(dimColor, opts.textOpacity), font = opts.font)
        val refH = lineHeight(refPaint)
        val gap = refH * 0.28f

        // Column split by element side; paints scaled per element size.
        val lines = info.stampLines()
        val left = lines.filter { it.side == StampAlign.LEFT }
        val right = lines.filter { it.side == StampAlign.RIGHT }
        fun paintOf(l: StampLine): Paint {
            val base = wf * (if (l.bold) 0.032f else 0.027f) * s * l.size.scale
            return textPaint(base, withAlpha(if (l.bold) textColor else dimColor, opts.textOpacity), l.bold, opts.font)
        }
        val paints = HashMap<StampLine, Paint>(lines.size * 2)
        lines.forEach { paints[it] = paintOf(it) }

        val leftCap = if (right.isEmpty()) wf - padH * 2 else wf * 0.56f
        val leftW = (left.maxOfOrNull { paints[it]!!.measureText(it.text) } ?: 0f).coerceAtMost(leftCap)
        val rightMaxW = if (left.isEmpty()) wf - padH * 2 else (wf - padH * 3 - leftW).coerceAtLeast(wf * 0.28f)
        val leftMaxW = if (right.isEmpty()) wf - padH * 2 else (wf - padH * 3 - wf * 0.28f).coerceAtLeast(leftW)

        fun build(ls: List<StampLine>, maxW: Float) = ls.flatMap { l ->
            val p = paints[l]!!
            wrap(p, l.text, maxW, l.maxLines).map { it to p }
        }
        val leftCol = build(left, leftMaxW)
        val rightCol = build(right, rightMaxW)

        fun colH(rows: List<Pair<String, Paint>>): Float =
            if (rows.isEmpty()) 0f
            else rows.sumOf { (it.second.descent() - it.second.ascent()).toDouble() }.toFloat() +
                (rows.size - 1) * gap

        val stripH = (max(colH(leftCol), colH(rightCol)) + padV * 2).toInt()

        val out = Bitmap.createBitmap(w, h + stripH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(source, 0f, 0f, null)
        c.drawRect(RectF(0f, h.toFloat(), wf, (h + stripH).toFloat()), fillPaint(withAlpha(stripColor, max(opts.bgOpacity, 0.85f))))
        c.drawRect(RectF(0f, h.toFloat(), wf, h + wf * 0.002f), fillPaint(withAlpha(textColor, opts.textOpacity)))

        drawColumn(c, leftCol, h + padV, padH, alignRight = false, gap)
        drawColumn(c, rightCol, h + padV, wf - padH, alignRight = true, gap)
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

    private fun withAlpha(color: Int, opacity: Float): Int =
        Color.argb(
            (Color.alpha(color) * opacity).toInt().coerceIn(0, 255),
            Color.red(color), Color.green(color), Color.blue(color),
        )

    private fun typefaceFor(font: StampFont, bold: Boolean): Typeface = when (font) {
        StampFont.DEFAULT -> Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        StampFont.SERIF -> Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        StampFont.MONO -> Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean = false, font: StampFont = StampFont.DEFAULT): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = typefaceFor(font, bold)
        }

    private fun fillPaint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun lineHeight(p: Paint): Float = p.descent() - p.ascent()
}
