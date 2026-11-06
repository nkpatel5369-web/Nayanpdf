package npdf.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.max
import kotlin.math.min

/**
 * Saved signature pictures, encrypted with a key that never leaves Android's
 * Keystore (AES-256-GCM). They are pictures of a signature, not digital
 * signatures.
 */
class SignatureStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "signatures").apply { mkdirs() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    fun save(png: ByteArray): File {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val f = File(dir, "${System.currentTimeMillis()}.sig")
        f.outputStream().use { o ->
            o.write(c.iv.size); o.write(c.iv); o.write(c.doFinal(png))
        }
        return f
    }

    fun list(): List<File> = dir.listFiles { f -> f.name.endsWith(".sig") }?.sortedByDescending { it.name } ?: emptyList()

    fun load(f: File): ByteArray? = try {
        val data = f.readBytes()
        val n = data[0].toInt()
        val iv = data.copyOfRange(1, 1 + n)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        c.doFinal(data, 1 + n, data.size - 1 - n)
    } catch (e: Exception) {
        null
    }

    fun delete(f: File) = f.delete()

    companion object {
        private const val ALIAS = "npdf-signatures"

        /** A typed name drawn in a handwriting-style font as a transparent PNG. */
        fun typed(text: String, color: Int = Color.rgb(20, 30, 90)): ByteArray {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = 140f
                typeface = Typeface.create("cursive", Typeface.NORMAL)
            }
            val w = max(1, paint.measureText(text).toInt() + 40)
            val fm = paint.fontMetrics
            val h = (fm.descent - fm.ascent).toInt() + 40
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawText(text, 20f, 20f - fm.ascent, paint)
            return png(bmp)
        }

        fun png(bmp: Bitmap): ByteArray = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}

/** A pad to draw a signature with a finger; [toPng] crops it to the ink. */
class SignaturePad @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val strokes = mutableListOf<Path>()
    private var current: Path? = null
    private val bounds = RectF()
    private var hasBounds = false
    private var empty = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 30, 90); style = Paint.Style.STROKE; strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val guide = Paint().apply { color = 0x33000000; strokeWidth = 2f }
    var onChange: (() -> Unit)? = null

    val isEmpty get() = empty

    fun clear() { strokes.clear(); current = null; empty = true; hasBounds = false; invalidate(); onChange?.invoke() }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val y = height * 0.75f
        canvas.drawLine(24f, y, width - 24f, y, guide)
        strokes.forEach { canvas.drawPath(it, paint) }
        current?.let { canvas.drawPath(it, paint) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { current = Path().apply { moveTo(e.x, e.y) }; grow(e.x, e.y) }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) { current?.lineTo(e.getHistoricalX(i), e.getHistoricalY(i)); grow(e.getHistoricalX(i), e.getHistoricalY(i)) }
                current?.lineTo(e.x, e.y); grow(e.x, e.y)
            }
            MotionEvent.ACTION_UP -> { current?.let { it.lineTo(e.x + 0.1f, e.y); strokes.add(it) }; current = null; empty = false; onChange?.invoke() }
            MotionEvent.ACTION_CANCEL -> current = null
        }
        invalidate()
        return true
    }

    private fun grow(x: Float, y: Float) {
        if (!hasBounds) { bounds.set(x, y, x, y); hasBounds = true } else bounds.union(x, y)
    }

    fun toPng(): ByteArray? {
        if (strokes.isEmpty()) return null
        val pad = paint.strokeWidth * 2
        val l = max(0f, bounds.left - pad); val t = max(0f, bounds.top - pad)
        val r = min(width.toFloat(), bounds.right + pad); val b = min(height.toFloat(), bounds.bottom + pad)
        val w = max(1, (r - l).toInt()); val h = max(1, (b - t).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(-l, -t)
        strokes.forEach { c.drawPath(it, paint) }
        return SignatureStore.png(bmp)
    }
}
