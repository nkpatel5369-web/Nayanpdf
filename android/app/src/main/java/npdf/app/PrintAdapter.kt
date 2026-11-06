package npdf.app

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.FileOutputStream

/**
 * Hands a finished PDF to Android's print system, which shows the printer,
 * page range, copies and paper options. Sending the job to a printer is done
 * by the phone's print service, not by N PDF.
 */
class PrintAdapter(private val file: File, private val name: String, private val pages: Int) : PrintDocumentAdapter() {

    override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
        if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pages)
            .build()
        cb.onLayoutFinished(info, old != new)
    }

    override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
        try {
            file.inputStream().use { i -> FileOutputStream(dest.fileDescriptor).use { o -> i.copyTo(o) } }
            if (cancel?.isCanceled == true) cb.onWriteCancelled() else cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            cb.onWriteFailed(e.message)
        }
    }
}
