package npdf.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TextInfoQrTest {

    @Test
    fun businessCardFields() {
        val lines = listOf(
            TextLine("Mehta & Associates", 30f), TextLine("Chartered Accountants", 18f),
            TextLine("Rohan Mehta", 40f), TextLine("Partner, FCA", 16f),
            TextLine("+91 98765 43210", 14f), TextLine("Office: 079 2345 6789", 14f),
            TextLine("rohan@mehtaassociates.in", 14f), TextLine("www.mehtaassociates.in", 14f),
            TextLine("301, Shivalik Tower, CG Road, Ahmedabad 380009", 12f),
        )
        val c = TextInfo.parseCard(lines)
        assertEquals("Rohan Mehta", c.name)
        assertEquals("Mehta & Associates", c.company)
        assertEquals("Partner, FCA", c.title)
        assertEquals(listOf("rohan@mehtaassociates.in"), c.emails)
        assertEquals("www.mehtaassociates.in", c.website)
        assertTrue(c.phones.toString(), c.phones.contains("+91 98765 43210") && c.phones.any { it.contains("2345 6789") })
        assertTrue(c.address!!.contains("380009"))
    }

    @Test
    fun datesAreReadDayFirst() {
        assertEquals(LocalDate.of(2026, 3, 31), TextInfo.firstDate("Invoice date 31/03/2026, due 15/04/2026"))
        assertEquals(LocalDate.of(2026, 4, 5), TextInfo.firstDate("Dated 05-04-2026"))
        assertEquals(LocalDate.of(2025, 11, 2), TextInfo.firstDate("on 2nd November, 2025"))
        assertEquals(LocalDate.of(2025, 1, 9), TextInfo.firstDate("January 9, 2025"))
        assertNull(TextInfo.firstDate("Amount 1,23,456.78"))
        assertNull(TextInfo.firstDate("31/02/2026"))
    }

    @Test
    fun fileNameSuggestions() {
        val lines = listOf(TextLine("ACME Traders Private Limited", 40f), TextLine("TAX INVOICE", 30f), TextLine("Invoice No 123 Date 31/03/2026", 14f))
        val s = TextInfo.nameSuggestions(lines, "Scan 2026-11-15 10.32")
        assertEquals("Tax Invoice 2026-03-31", s[0])
        assertTrue(s.contains("ACME Traders Private Limited"))
        assertEquals("Scan 2026-11-15 10.32", s.last())
        assertEquals("a b c", TextInfo.safeName("a/b:c"))
        assertEquals("Scan 2026-11-15 10.32", TextInfo.defaultName("Scan", LocalDateTime.of(2026, 11, 15, 10, 32)))
    }

    @Test
    fun qrCodeIsReadFromALuminanceFrame() {
        val text = "https://example.com/invoice/123"
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300)
        // Place it in a 640 x 480 grey frame.
        val w = 640; val h = 480
        val y = ByteArray(w * h) { 150.toByte() }
        for (j in 0 until 300) for (i in 0 until 300) y[(j + 90) * w + (i + 170)] = if (m[i, j]) 20 else 235.toByte()
        assertEquals(text, Qr.decode(y, w, h))
        assertTrue(Qr.isWebLink(text))
        assertNull(Qr.decode(ByteArray(w * h) { 128.toByte() }, w, h))
    }
}
