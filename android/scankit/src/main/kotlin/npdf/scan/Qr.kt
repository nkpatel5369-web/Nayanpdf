package npdf.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** QR code reading on a luminance image (a camera frame's Y plane), entirely on the device. */
object Qr {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to false,
    )

    /** The text of a QR code in the frame, or null. */
    fun decode(y: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        val src = PlanarYUVLuminanceSource(y, rowStride, height, 0, 0, width, height, false)
        val reader = MultiFormatReader().apply { setHints(hints) }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text
        } catch (e: NotFoundException) {
            null
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    /** True when [s] looks like a web address (opening it is left to the browser the user chooses). */
    fun isWebLink(s: String) = Regex("(?i)^https?://\\S+$").matches(s.trim())
}
