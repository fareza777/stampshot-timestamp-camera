package com.stampshot.app.capture

import android.graphics.Bitmap
import android.media.Image as MediaImage
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.stampshot.app.stamp.StampInfo
import com.stampshot.app.stamp.StampOptions
import com.stampshot.app.stamp.StampRenderer
import com.stampshot.app.stamp.StampStyle
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Burns the timestamp stamp into a recorded mp4: decode every frame in buffer
 * mode (readable YUV_420_888 Image), alpha-blend the stamp overlay into the
 * YUV planes, re-encode H.264, and copy the audio track through untouched.
 * Runs on a background dispatcher.
 */
object VideoStampProcessor {

    private const val TAG = "VideoStampProcessor"
    private const val OUT_MIME = "video/avc"
    private const val I_FRAME_SECS = 1
    private const val TIMEOUT_US = 10_000L

    /** Stamps [src]; returns a new file next to it (the caller deletes both). */
    fun stamp(src: File, info: StampInfo, style: StampStyle, opts: StampOptions): File {
        val dest = File(src.parentFile, "stamped_${src.name}")
        if (dest.exists()) dest.delete()
        try {
            process(src, dest, info, style, opts)
        } catch (e: Exception) {
            Log.e(TAG, "stamp failed", e)
            dest.delete()
            throw e
        }
        return dest
    }

    private class Crop(val left: Int, val top: Int, val w: Int, val h: Int)

    private fun cropOf(fmt: MediaFormat): Crop {
        val left = fmt.getIntOrNull("crop-left") ?: 0
        val right = fmt.getIntOrNull("crop-right")
        val top = fmt.getIntOrNull("crop-top") ?: 0
        val bottom = fmt.getIntOrNull("crop-bottom")
        val w = if (right != null) right - left + 1 else fmt.getInteger(MediaFormat.KEY_WIDTH)
        val h = if (bottom != null) bottom - top + 1 else fmt.getInteger(MediaFormat.KEY_HEIGHT)
        return Crop(left, top, w, h)
    }

    private fun MediaFormat.getIntOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun process(src: File, dest: File, info: StampInfo, style: StampStyle, opts: StampOptions) {
        val extractor = MediaExtractor()
        extractor.setDataSource(src.absolutePath)

        var videoTrack = -1
        var audioTrack = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            when {
                mime.startsWith("video/") && videoTrack < 0 -> { videoTrack = i; videoFormat = fmt }
                mime.startsWith("audio/") && audioTrack < 0 -> { audioTrack = i; audioFormat = fmt }
            }
        }
        check(videoTrack >= 0 && videoFormat != null) { "No video track" }
        val mime = videoFormat.getString(MediaFormat.KEY_MIME)!!
        val rotation = videoFormat.getIntOrNull(MediaFormat.KEY_ROTATION) ?: 0
        val srcBitrate = videoFormat.getIntOrNull(MediaFormat.KEY_BIT_RATE) ?: 0
        val fps = videoFormat.getIntOrNull(MediaFormat.KEY_FRAME_RATE) ?: 30

        extractor.selectTrack(videoTrack)

        val decoder = MediaCodec.createByCodecName(findDecoder(mime))
        decoder.configure(videoFormat, null, null, 0)
        decoder.start()

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1

        var overlayYuv: OverlayYuv? = null
        var overlaySecond = -1L
        var inputDone = false
        var decoderEos = false
        var encoderEos = false
        var encoderSignaled = false
        var frameCount = 0
        val bi = MediaCodec.BufferInfo()

        fun drainEncoder() {
            val enc = encoder ?: return
            while (true) {
                when (val idx = enc.dequeueOutputBuffer(bi, 0)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                            muxerVideoTrack = muxer!!.addTrack(enc.outputFormat)
                            if (audioFormat != null) muxerAudioTrack = muxer!!.addTrack(audioFormat)
                            muxer!!.setOrientationHint(rotation)
                            muxer!!.start()
                            muxerStarted = true
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    else -> {
                        val data = enc.getOutputBuffer(idx)
                        if (muxerStarted && data != null && bi.size > 0 &&
                            bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            muxer!!.writeSampleData(muxerVideoTrack, data, bi)
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderEos = true
                    }
                }
            }
        }

        try {
            while (!encoderEos) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIdx = decoder.dequeueOutputBuffer(bi, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (encoder == null) {
                            encoder = createEncoder(decoder.outputFormat, srcBitrate, fps)
                            encoder!!.start()
                        }
                    }
                    in 0..Int.MAX_VALUE -> {
                        val isEos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (!isEos) {
                            val image = decoder.getOutputImage(outIdx)
                            if (image != null) {
                                val crop = cropOf(decoder.outputFormat)
                                val stampMs = info.timestampMillis + bi.presentationTimeUs / 1000
                                val sec = stampMs / 1000
                                if (sec != overlaySecond) {
                                    overlaySecond = sec
                                    // Render in display orientation, then rotate back
                                    // into the coded frame's orientation.
                                    val portrait = rotation == 90 || rotation == 270
                                    val bmp = StampRenderer.renderOverlayBitmap(
                                        if (portrait) crop.h else crop.w,
                                        if (portrait) crop.w else crop.h,
                                        info.copy(timestampMillis = stampMs), style, opts,
                                    )
                                    val frameBmp = rotateForFrame(bmp, rotation)
                                    overlayYuv = OverlayYuv.of(frameBmp)
                                    if (frameCount == 0) {
                                        Log.d(
                                            TAG,
                                            "overlay box ${overlayYuv!!.w}x${overlayYuv!!.h} at ${overlayYuv!!.left},${overlayYuv!!.top} " +
                                                "frame ${crop.w}x${crop.h} crop ${crop.left},${crop.top} " +
                                                "srcImg ${image.width}x${image.height} fmt ${image.format}",
                                        )
                                    }
                                }
                                encodeFrame(encoder!!, image, crop, overlayYuv, bi.presentationTimeUs)
                                frameCount++
                            } else {
                                Log.w(TAG, "decoder output image null at pts ${bi.presentationTimeUs}")
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (isEos) decoderEos = true
                    }
                    else -> {}
                }

                if (decoderEos && encoder != null && !encoderSignaled) {
                    val idx = encoder!!.dequeueInputBuffer(30_000)
                    if (idx >= 0) {
                        encoder!!.queueInputBuffer(
                            idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        encoderSignaled = true
                    }
                }
                drainEncoder()

                // Decoder hit EOS but never emitted a format/frame — nothing to encode.
                if (decoderEos && encoder == null) break
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            extractor.release()
        }

        check(frameCount > 0) { "No frames decoded" }

        if (muxerStarted && audioTrack >= 0 && audioFormat != null && muxerAudioTrack >= 0) {
            val audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(src.absolutePath)
            audioExtractor.selectTrack(audioTrack)
            val buf = ByteBuffer.allocate(512 * 1024)
            val aInfo = MediaCodec.BufferInfo()
            while (true) {
                val size = audioExtractor.readSampleData(buf, 0)
                if (size < 0) break
                aInfo.set(0, size, audioExtractor.sampleTime, audioExtractor.sampleFlags)
                muxer!!.writeSampleData(muxerAudioTrack, buf, aInfo)
                audioExtractor.advance()
            }
            audioExtractor.release()
        }

        if (muxerStarted) {
            runCatching { muxer!!.stop() }
            runCatching { muxer!!.release() }
        }
        Log.d(TAG, "Stamped $frameCount frames -> ${dest.name}")
    }

    private fun createEncoder(srcFmt: MediaFormat, srcBitrate: Int, fps: Int): MediaCodec {
        val crop = cropOf(srcFmt)
        val w = crop.w
        val h = crop.h
        val bitrate = if (srcBitrate > 0) srcBitrate else max(2_000_000, w * h * 4)
        val fmt = MediaFormat.createVideoFormat(OUT_MIME, w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, max(1, fps))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_SECS)
        }
        val codec = MediaCodec.createByCodecName(findEncoder(OUT_MIME))
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    /** Rotate a display-space overlay into frame space ([rot] = muxer hint). */
    private fun rotateForFrame(bmp: Bitmap, rot: Int): Bitmap {
        val deg = (360 - rot % 360) % 360
        if (deg == 0) return bmp
        val m = android.graphics.Matrix().apply {
            postRotate(deg.toFloat(), bmp.width / 2f, bmp.height / 2f)
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, false)
        if (out !== bmp) bmp.recycle()
        return out
    }

    private fun encodeFrame(
        encoder: MediaCodec,
        srcImage: MediaImage,
        crop: Crop,
        overlay: OverlayYuv?,
        ptsUs: Long,
    ) {
        val inIdx = encoder.dequeueInputBuffer(30_000)
        check(inIdx >= 0) { "Encoder input starved" }
        val dstImage = encoder.getInputImage(inIdx)
            ?: error("Encoder has no readable input Image")
        try {
            copyYuv420(srcImage, dstImage, crop)
            overlay?.blendInto(dstImage)
        } finally {
            srcImage.close()
        }
        encoder.queueInputBuffer(inIdx, 0, crop.w * crop.h * 3 / 2, ptsUs, 0)
    }

    private fun copyYuv420(src: MediaImage, dst: MediaImage, crop: Crop) {
        check(src.format == android.graphics.ImageFormat.YUV_420_888) {
            "Decoder image not YUV_420_888 (${src.format})"
        }
        check(dst.format == android.graphics.ImageFormat.YUV_420_888) {
            "Encoder image not YUV_420_888 (${dst.format})"
        }
        for (plane in 0..2) {
            val sp = src.planes[plane]
            val dp = dst.planes[plane]
            val subsample = if (plane == 0) 1 else 2
            val planeW = (crop.w + subsample - 1) / subsample
            val planeH = (crop.h + subsample - 1) / subsample
            val xOff = crop.left / subsample
            val yOff = crop.top / subsample
            val sPix = sp.pixelStride
            val dPix = dp.pixelStride
            val sBuf = sp.buffer.duplicate()
            val dBuf = dp.buffer.duplicate()
            for (row in 0 until planeH) {
                val sRow = (yOff + row) * sp.rowStride + xOff * sPix
                val dRow = row * dp.rowStride
                if (sPix == 1 && dPix == 1) {
                    val sSlice = sBuf.duplicate().apply { position(sRow); limit(sRow + planeW) }
                    val dSlice = dBuf.duplicate().apply { position(dRow); limit(dRow + planeW) }
                    dSlice.put(sSlice)
                } else {
                    for (col in 0 until planeW) {
                        dBuf.put(dRow + col * dPix, sBuf.get(sRow + col * sPix))
                    }
                }
            }
        }
    }

    /**
     * The stamp rendered as ARGB, converted to YUV planes once per displayed
     * second, then alpha-blended into each destination frame's planes.
     */
    private class OverlayYuv(
        val y: ByteArray,
        val u: ByteArray,
        val v: ByteArray,
        val alpha: ByteArray,
        val w: Int,
        val h: Int,
        val left: Int,
        val top: Int,
    ) {
        fun blendInto(image: MediaImage) {
            if (w <= 0 || h <= 0) return
            val planes = image.planes
            val yBuf = planes[0].buffer
            val yStride = planes[0].rowStride
            val yPix = planes[0].pixelStride
            for (row in 0 until h) {
                val dstRow = (top + row) * yStride
                val srcRow = row * w
                for (col in 0 until w) {
                    val a = alpha[srcRow + col].toInt() and 0xFF
                    if (a == 0) continue
                    val dstIdx = dstRow + (left + col) * yPix
                    if (a >= 255) {
                        yBuf.put(dstIdx, y[srcRow + col])
                    } else {
                        val dy = yBuf.get(dstIdx).toInt() and 0xFF
                        val oy = y[srcRow + col].toInt() and 0xFF
                        yBuf.put(dstIdx, ((dy * (255 - a) + oy * a) / 255).toByte())
                    }
                }
            }
            // Chroma: blend per texel where any stamp pixel covers the 2x2 block.
            for (plane in 1..2) {
                val buf = planes[plane].buffer
                val stride = planes[plane].rowStride
                val pix = planes[plane].pixelStride
                val src = if (plane == 1) u else v
                val cw = (w + 1) / 2
                val ch = (h + 1) / 2
                for (row in 0 until ch) {
                    val dstRow = (top / 2 + row) * stride
                    val srcRow = row * cw
                    for (col in 0 until cw) {
                        val a = maxOf(
                            alphaIdx(row * 2, col * 2),
                            alphaIdx(row * 2, col * 2 + 1),
                            alphaIdx(row * 2 + 1, col * 2),
                            alphaIdx(row * 2 + 1, col * 2 + 1),
                        )
                        if (a == 0) continue
                        val dstIdx = dstRow + (left / 2 + col) * pix
                        if (a >= 255) {
                            buf.put(dstIdx, src[srcRow + col])
                        } else {
                            val d = buf.get(dstIdx).toInt() and 0xFF
                            val o = src[srcRow + col].toInt() and 0xFF
                            buf.put(dstIdx, ((d * (255 - a) + o * a) / 255).toByte())
                        }
                    }
                }
            }
        }

        private fun alphaIdx(row: Int, col: Int): Int =
            if (row < h && col < w) alpha[row * w + col].toInt() and 0xFF else 0

        companion object {
            fun of(bitmap: Bitmap): OverlayYuv {
                val w = bitmap.width
                val h = bitmap.height
                val px = IntArray(w * h)
                bitmap.getPixels(px, 0, w, 0, 0, w, h)
                var minX = w; var minY = h; var maxX = -1; var maxY = -1
                for (row in 0 until h) {
                    for (col in 0 until w) {
                        if ((px[row * w + col] ushr 24) != 0) {
                            if (col < minX) minX = col
                            if (col > maxX) maxX = col
                            if (row < minY) minY = row
                            if (row > maxY) maxY = row
                        }
                    }
                }
                bitmap.recycle()
                if (maxX < 0) {
                    return OverlayYuv(ByteArray(0), ByteArray(0), ByteArray(0), ByteArray(0), 0, 0, 0, 0)
                }
                // Chroma offsets are halved, so align the box to even pixels.
                minX = minX and 1.inv()
                minY = minY and 1.inv()
                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                val cw = (bw + 1) / 2
                val ch = (bh + 1) / 2
                val yArr = ByteArray(bw * bh)
                val aArr = ByteArray(bw * bh)
                val uArr = ByteArray(cw * ch)
                val vArr = ByteArray(cw * ch)
                val uSum = IntArray(cw * ch)
                val vSum = IntArray(cw * ch)
                val uCnt = IntArray(cw * ch)
                for (row in 0 until bh) {
                    for (col in 0 until bw) {
                        val argb = px[(minY + row) * w + minX + col]
                        val a = argb ushr 24
                        aArr[row * bw + col] = a.toByte()
                        if (a == 0) continue
                        val r = (argb shr 16) and 0xFF
                        val g = (argb shr 8) and 0xFF
                        val b = argb and 0xFF
                        yArr[row * bw + col] =
                            clamp255(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)
                        val ci = (row / 2) * cw + col / 2
                        uSum[ci] += ((-38 * r - 74 * g + 112 * b + 128) shr 8)
                        vSum[ci] += ((112 * r - 94 * g - 18 * b + 128) shr 8)
                        uCnt[ci]++
                    }
                }
                for (i in 0 until cw * ch) {
                    if (uCnt[i] > 0) {
                        uArr[i] = clamp255(uSum[i] / uCnt[i] + 128)
                        vArr[i] = clamp255(vSum[i] / uCnt[i] + 128)
                    } else {
                        uArr[i] = 128.toByte()
                        vArr[i] = 128.toByte()
                    }
                }
                return OverlayYuv(yArr, uArr, vArr, aArr, bw, bh, minX, minY)
            }

            private fun clamp255(v: Int): Byte = v.coerceIn(0, 255).toByte()
        }
    }

    private fun findDecoder(mime: String): String {
        val all = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        // Prefer platform software decoders — their buffer output is readable YUV_420_888.
        val candidates = all.filter {
            !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) }
        }.sortedBy {
            if (it.name.startsWith("OMX.google") || it.name.startsWith("c2.android")) 0 else 1
        }
        return candidates.firstOrNull()?.name
            ?: throw IllegalStateException("No decoder for $mime")
    }

    private fun findEncoder(mime: String): String {
        val all = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        val candidates = all.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, true) }
        }
        val ranked = candidates.sortedBy { info ->
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
            val hasYuv = caps?.colorFormats?.any {
                it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible ||
                    it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            } == true
            when {
                !hasYuv -> 2
                info.name.startsWith("OMX.google") || info.name.startsWith("c2.android") -> 0
                else -> 1
            }
        }
        return ranked.firstOrNull()?.name
            ?: throw IllegalStateException("No encoder for $mime")
    }
}
