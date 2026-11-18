package ai.forge.app.motion

import ai.forge.editorial.MotionFrame
import ai.forge.editorial.MotionSpec
import ai.forge.editorial.MotionStyle
import ai.forge.editorial.MotionTimeline
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.cos
import kotlin.math.sin

/** Dessine une image de l'animation : aurore, texte doré, effets (charte Forge, 100 % original). */
object MotionRenderer {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = Color.WHITE }

    fun draw(c: Canvas, f: MotionFrame, w: Int, h: Int) {
        c.drawColor(Color.rgb(4, 4, 6))
        fun blob(color: Int, cx: Float, cy: Float, r: Float) {
            bg.shader = RadialGradient(cx, cy, r, color, Color.TRANSPARENT, Shader.TileMode.CLAMP); c.drawCircle(cx, cy, r, bg)
        }
        val p = f.backgroundPhase; val r = maxOf(w, h).toFloat()
        blob(Color.argb(140, 59, 47, 158), w * (0.2f + 0.1f * sin(p)), h * (0.2f + 0.05f * cos(p)), r * 0.55f)
        blob(Color.argb(110, 232, 169, 58), w * (0.85f + 0.08f * cos(p * 0.8f)), h * (0.35f + 0.08f * sin(p)), r * 0.45f)
        blob(Color.argb(90, 180, 68, 28), w * (0.7f + 0.1f * sin(p * 0.5f)), h * (0.85f), r * 0.5f)

        val size = (if (w < h) w * 0.105f else h * 0.11f) * (if (f.style == MotionStyle.IMPACT) 1.15f else 1f)
        text.textSize = size
        text.shader = LinearGradient(0f, h / 2f - size, 0f, h / 2f + size, Color.rgb(247, 217, 139), Color.rgb(242, 193, 78), Shader.TileMode.CLAMP)
        text.alpha = (f.opacity * 255).toInt().coerceIn(0, 255)
        val layout = StaticLayout.Builder.obtain(f.text, 0, f.text.length, text, (w * 0.84f).toInt()).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        c.save()
        c.translate(w * 0.08f, (h - layout.height) / 2f + f.offsetY)
        c.scale(f.scale, f.scale, w * 0.42f, layout.height / 2f)
        layout.draw(c)
        c.restore()
        // Barre de progression discrète (repère visuel de rythme).
        bg.shader = null; bg.color = Color.argb((f.opacity * 160).toInt(), 242, 193, 78)
        c.drawRect(w * 0.08f, h * 0.9f, w * 0.08f + w * 0.84f * f.progress, h * 0.9f + h * 0.004f, bg)
    }
}

/**
 * Export MP4 H.264 : image par image (dessinée dans un Bitmap), conversion en YUV et encodage
 * matériel avec horodatage exact. Le fichier est enregistré dans Films/Forge.
 */
object MotionExporter {
    suspend fun export(context: Context, spec: MotionSpec, name: String, onProgress: (Float) -> Unit = {}): Uri = withContext(Dispatchers.Default) {
        val tmp = File(context.cacheDir, "forge-motion.mp4")
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000); setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bmp = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val argb = IntArray(spec.width * spec.height)
        val info = MediaCodec.BufferInfo()
        var track = -1; var muxing = false; var frame = 0; var inputDone = false; var outputDone = false
        try {
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        if (frame >= spec.frameCount) { codec.queueInputBuffer(inIdx, 0, 0, frame * 1_000_000L / spec.fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else {
                            MotionRenderer.draw(canvas, MotionTimeline.frameAt(spec, frame * 1000L / spec.fps), spec.width, spec.height)
                            bmp.getPixels(argb, 0, spec.width, 0, 0, spec.width, spec.height)
                            val image = codec.getInputImage(inIdx)!!
                            writeYuv(argb, spec.width, spec.height, image)
                            codec.queueInputBuffer(inIdx, 0, spec.width * spec.height * 3 / 2, frame * 1_000_000L / spec.fps, 0)
                            frame++; if (frame % 15 == 0) onProgress(frame.toFloat() / spec.frameCount)
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing) { buf.position(info.offset); buf.limit(info.offset + info.size); muxer.writeSampleData(track, buf, info) }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release(); if (muxing) runCatching { muxer.stop() }; muxer.release(); bmp.recycle()
        }
        onProgress(1f)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4"); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Forge")
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        tmp.delete()
        uri
    }

    /** ARGB → YUV 4:2:0 (BT.601) dans les plans fournis par l'encodeur, quel que soit leur agencement. */
    private fun writeYuv(argb: IntArray, w: Int, h: Int, image: android.media.Image) {
        val (yP, uP, vP) = image.planes.let { Triple(it[0], it[1], it[2]) }
        val yBuf = yP.buffer; val uBuf = uP.buffer; val vBuf = vP.buffer
        for (j in 0 until h) {
            val row = j * yP.rowStride
            for (i in 0 until w) {
                val c = argb[j * w + i]; val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                yBuf.put(row + i * yP.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128; val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val ci = (j / 2) * uP.rowStride + (i / 2) * uP.pixelStride
                    val cv = (j / 2) * vP.rowStride + (i / 2) * vP.pixelStride
                    uBuf.put(ci, u.toByte()); vBuf.put(cv, v.toByte())
                }
            }
        }
    }
}
