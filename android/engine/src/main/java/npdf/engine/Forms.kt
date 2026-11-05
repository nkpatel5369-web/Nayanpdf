package npdf.engine

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.PDFWidget
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Rect

/** Filling interactive (AcroForm) form fields. Coordinates are in page space. */
object Forms {

    enum class Kind { TEXT, CHECKBOX, RADIO, COMBO, LIST, SIGNATURE, BUTTON, OTHER }

    class Field(
        val id: Int, val page: Int, val name: String, val label: String, val kind: Kind, val value: String,
        val options: List<String>, val rect: Rect, val readOnly: Boolean, val multiline: Boolean, val maxLen: Int,
    )

    private fun kindOf(w: PDFWidget) = when (w.fieldType) {
        PDFWidget.TYPE_TEXT -> Kind.TEXT
        PDFWidget.TYPE_CHECKBOX -> Kind.CHECKBOX
        PDFWidget.TYPE_RADIOBUTTON -> Kind.RADIO
        PDFWidget.TYPE_COMBOBOX -> Kind.COMBO
        PDFWidget.TYPE_LISTBOX -> Kind.LIST
        PDFWidget.TYPE_SIGNATURE -> Kind.SIGNATURE
        PDFWidget.TYPE_BUTTON -> Kind.BUTTON
        else -> Kind.OTHER
    }

    private fun field(w: PDFWidget, page: Int) = Field(
        w.getObject().asIndirect(), page, w.name ?: "", (w.label ?: w.name ?: "").ifEmpty { w.name ?: "" }, kindOf(w),
        w.value ?: "", if (w.isChoice) (w.options?.toList() ?: emptyList()) else emptyList(), w.bounds,
        w.isReadOnly, w.isText && w.isMultiline, if (w.isText) w.maxLen else 0,
    )

    fun hasXfa(doc: PDFDocument): Boolean = !doc.getTrailer().get("Root").get("AcroForm").get("XFA").isNull

    fun list(doc: PDFDocument, pageNumber: Int? = null): List<Field> {
        val pages = if (pageNumber != null) listOf(pageNumber - 1) else (0 until doc.countPages()).toList()
        val out = mutableListOf<Field>()
        for (i in pages) {
            val page = doc.loadPage(i) as PDFPage
            for (w in page.widgets ?: emptyArray()) out.add(field(w, i + 1))
        }
        return out
    }

    fun at(doc: PDFDocument, pageNumber: Int, p: Point): Field? =
        list(doc, pageNumber).lastOrNull { Pdf.contains(it.rect, p.x, p.y) }

    private fun widget(doc: PDFDocument, pageNumber: Int, id: Int): PDFWidget {
        Pdf.require(doc, Document.PERMISSION_FORM, "filling in forms")
        val page = Pdf.page(doc, pageNumber)
        val w = (page.widgets ?: emptyArray()).firstOrNull { it.getObject().asIndirect() == id }
            ?: throw PdfError(Codes.INVALID, "That form field no longer exists.")
        if (w.isReadOnly) throw PdfError(Codes.NOT_SUPPORTED, "This field is read-only.")
        return w
    }

    /** Sets a text field; the value is read back to confirm it was stored. */
    fun setText(doc: PDFDocument, pageNumber: Int, id: Int, value: String) {
        val w = widget(doc, pageNumber, id)
        if (!w.isText) throw PdfError(Codes.INVALID, "This is not a text field.")
        val v = if (w.maxLen > 0 && value.length > w.maxLen) value.substring(0, w.maxLen) else value
        w.setTextValue(v)
        w.update()
        if ((w.value ?: "") != v) throw PdfError(Codes.VERIFY_FAILED, "The field did not accept the value (it may have a format rule this app cannot run).")
    }

    /** Ticks or clears a check box, or selects a radio button (its group is updated). */
    fun setChecked(doc: PDFDocument, pageNumber: Int, id: Int, checked: Boolean) {
        val w = widget(doc, pageNumber, id)
        if (!w.isCheckbox && !w.isRadioButton) throw PdfError(Codes.INVALID, "This is not a check box or radio button.")
        if (isOn(w) != checked) w.toggle()
        w.update()
        if (isOn(w) != checked) throw PdfError(Codes.VERIFY_FAILED, "The box could not be changed.")
    }

    /** Whether this check box or radio button itself is on: its own appearance state, not the group's value. */
    fun isOn(w: PDFWidget): Boolean {
        val state = w.getObject().get("AS")
        if (state.isName) return state.asName() != "Off"
        val v = w.value ?: return false
        return v.isNotEmpty() && v != "Off"
    }

    fun setChoice(doc: PDFDocument, pageNumber: Int, id: Int, value: String) {
        val w = widget(doc, pageNumber, id)
        if (!w.isChoice) throw PdfError(Codes.INVALID, "This is not a list or drop-down field.")
        w.setChoiceValue(value)
        w.update()
        if ((w.value ?: "") != value) throw PdfError(Codes.VERIFY_FAILED, "The choice could not be stored.")
    }

    /** Converts every field into ordinary page content (values can no longer be changed). */
    fun flatten(doc: PDFDocument) {
        Pdf.require(doc, Document.PERMISSION_EDIT, "flattening the form")
        doc.bake(false, true)
    }
}
