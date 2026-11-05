package npdf.engine

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import java.io.File

/**
 * Password protection with AES-256, and its authorised removal. Nothing here
 * cracks or bypasses a password: protection is removed only by someone who
 * knows the permissions (owner) password, or the open password when the file
 * has no separate restrictions.
 */
object Security {

    class Permissions(
        val print: Boolean = true, val copy: Boolean = true, val edit: Boolean = true,
        val annotate: Boolean = true, val forms: Boolean = true, val assemble: Boolean = true,
    ) {
        /** The /P value: reserved bits set as the PDF standard requires, plus the allowed actions. */
        fun value(): Int {
            var p = 0xFFFFF0C0.toInt()
            if (print) p = p or 4 or 2048
            if (edit) p = p or 8
            if (copy) p = p or 16
            if (annotate) p = p or 32
            if (forms) p = p or 256
            p = p or 512 // accessibility (screen readers) is always allowed
            if (assemble) p = p or 1024
            return p
        }
    }

    /**
     * Saves [doc] to [dest] encrypted. [openPassword] is needed to open the file;
     * [ownerPassword] (required when [permissions] restrict anything) lifts the
     * restrictions. Passwords are only passed to the PDF library; nothing is logged.
     */
    fun protect(doc: PDFDocument, dest: File, openPassword: String?, ownerPassword: String?, permissions: Permissions = Permissions()) {
        val open = openPassword.orEmpty()
        var owner = ownerPassword.orEmpty()
        if (open.isEmpty() && owner.isEmpty()) throw PdfError(Codes.INVALID, "Enter a password.")
        if (owner.isEmpty()) owner = open
        if (permissions.value() != Permissions().value() && owner == open && open.isNotEmpty() && ownerPassword.isNullOrEmpty()) {
            throw PdfError(Codes.INVALID, "Restrictions need a separate permissions password; otherwise anyone who can open the file can lift them.")
        }
        for (pw in listOf(open, owner)) {
            if (pw.any { it == ',' || it == '=' }) throw PdfError(Codes.INVALID, "Passwords cannot contain commas or equals signs.")
        }
        val opts = buildString {
            append("garbage=compact,compress,encrypt=aes-256")
            if (open.isNotEmpty()) append(",user-password=").append(open)
            append(",owner-password=").append(owner)
            append(",permissions=").append(permissions.value())
        }
        Pdf.save(doc, dest, opts)
        // Read back: the open password must be required (when set) and must work.
        val check = try { Pdf.open(dest.absolutePath, open.ifEmpty { null }) } catch (e: PdfError) {
            dest.delete()
            throw PdfError(Codes.VERIFY_FAILED, "The protected file could not be verified and was not kept.")
        }
        try {
            if (open.isNotEmpty() && !check.needsPassword()) {
                dest.delete()
                throw PdfError(Codes.VERIFY_FAILED, "The protected file did not ask for a password and was not kept.")
            }
        } finally {
            check.destroy()
        }
    }

    /** True when the document was opened with full rights (owner password, or no restrictions). */
    fun hasFullRights(doc: PDFDocument): Boolean = listOf(
        Document.PERMISSION_PRINT, Document.PERMISSION_COPY, Document.PERMISSION_EDIT, Document.PERMISSION_ANNOTATE,
        Document.PERMISSION_FORM, Document.PERMISSION_ASSEMBLE,
    ).all { doc.hasPermission(it) }

    fun isEncrypted(doc: PDFDocument): Boolean = !doc.isUnencryptedPDF

    /** Saves an unprotected copy. [doc] must have been opened with the right password. */
    fun removeProtection(doc: PDFDocument, dest: File) {
        if (!isEncrypted(doc)) throw PdfError(Codes.INVALID, "This PDF is not protected.")
        if (!hasFullRights(doc)) {
            throw PdfError(Codes.RESTRICTED, "This PDF has restrictions. Removing them needs the permissions (owner) password. N PDF does not crack or bypass passwords.")
        }
        Pdf.save(doc, dest, "garbage=compact,compress,encrypt=none")
        val check = Pdf.open(dest.absolutePath, null)
        try {
            if (check.needsPassword() || !check.isUnencryptedPDF) {
                dest.delete()
                throw PdfError(Codes.VERIFY_FAILED, "The unprotected copy could not be verified and was not kept.")
            }
        } finally {
            check.destroy()
        }
    }
}
