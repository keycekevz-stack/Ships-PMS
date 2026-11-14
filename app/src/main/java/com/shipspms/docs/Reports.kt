package com.shipspms.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date

/**
 * Writes simple A4 PDF reports (text, tables and photos) with Android's built-in PdfDocument.
 * Units are PDF points (1/72 inch). A4 = 595 x 842 pt.
 */
class PdfBuilder(private val title: String, private val subtitle: String) {

    private val pageW = 595
    private val pageH = 842
    private val margin = 40f
    private val contentW = pageW - 2 * margin
    private val bottom = pageH - margin - 18f

    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = 0f
    var pageCount = 0
        private set

    private val navy = Color.rgb(11, 61, 92)
    private val linePaint = Paint().apply { color = Color.rgb(180, 180, 180); strokeWidth = 0.6f; style = Paint.Style.STROKE }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }

    private fun textPaint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        this.color = color
    }

    private fun layout(text: String, paint: TextPaint, width: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

    private fun newPage() {
        finishPage()
        pageCount++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageCount).create())
        page = p
        canvas = p.canvas
        y = margin
        // Header band
        val c = p.canvas
        val tp = textPaint(9f, color = Color.GRAY)
        c.drawText("$title – $subtitle", margin, margin - 14f, tp)
        c.drawLine(margin, margin - 8f, pageW - margin, margin - 8f, linePaint)
    }

    private fun finishPage() {
        val p = page ?: return
        val c = p.canvas
        val tp = textPaint(8f, color = Color.GRAY)
        c.drawLine(margin, pageH - margin + 2f, pageW - margin, pageH - margin + 2f, linePaint)
        c.drawText("Ship PMS · printed ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())}", margin, pageH - margin + 14f, tp)
        val pn = "Page $pageCount"
        c.drawText(pn, pageW - margin - tp.measureText(pn), pageH - margin + 14f, tp)
        doc.finishPage(p)
        page = null
        canvas = null
    }

    private fun ensure(h: Float) {
        if (page == null || y + h > bottom) newPage()
    }

    fun title(text: String) {
        ensure(40f)
        val l = layout(text, textPaint(18f, true, navy), contentW)
        draw(l, margin); y += l.height + 6f
    }

    fun heading(text: String) {
        val l = layout(text, textPaint(12.5f, true, navy), contentW)
        ensure(l.height + 30f) // keep a heading together with what follows
        y += 6f
        draw(l, margin); y += l.height + 4f
    }

    fun text(text: String, size: Float = 10f, bold: Boolean = false, color: Int = Color.BLACK) {
        if (text.isBlank()) return
        val l = layout(text, textPaint(size, bold, color), contentW)
        ensure(l.height.toFloat())
        draw(l, margin); y += l.height + 3f
    }

    fun gap(h: Float = 8f) { y += h }

    private fun draw(l: StaticLayout, x: Float) {
        val c = canvas ?: return
        c.save(); c.translate(x, y); l.draw(c); c.restore()
    }

    /** Two-column table of label/value pairs. */
    fun fields(rows: List<Pair<String, String>>) {
        table(listOf("" to 0.3f, "" to 0.7f), rows.filter { it.second.isNotBlank() }.map { listOf(it.first, it.second) }, header = false, boldFirstCol = true)
    }

    /**
     * Table with the given columns (title to relative width). The header repeats on every new page.
     * [accent] can colour a row's text (e.g. red for overdue).
     */
    fun table(
        columns: List<Pair<String, Float>>,
        rows: List<List<String>>,
        header: Boolean = true,
        boldFirstCol: Boolean = false,
        fontSize: Float = 9f,
        accent: (Int) -> Int? = { null },
    ) {
        val total = columns.sumOf { it.second.toDouble() }.toFloat()
        val widths = columns.map { contentW * it.second / total }
        val pad = 4f
        val headPaint = textPaint(fontSize, true, Color.WHITE)

        fun drawHeader() {
            val layouts = columns.mapIndexed { i, c -> layout(c.first, headPaint, widths[i] - 2 * pad) }
            val h = layouts.maxOf { it.height } + 2 * pad
            ensure(h + 20f)
            val c = canvas!!
            fillPaint.color = navy
            c.drawRect(margin, y, margin + contentW, y + h, fillPaint)
            var x = margin
            layouts.forEachIndexed { i, l ->
                c.save(); c.translate(x + pad, y + pad); l.draw(c); c.restore()
                x += widths[i]
            }
            y += h
        }

        if (header) drawHeader() else ensure(20f)
        rows.forEachIndexed { r, row ->
            val color = accent(r) ?: Color.BLACK
            val layouts = row.mapIndexed { i, cell ->
                layout(cell.ifEmpty { "–" }, textPaint(fontSize, boldFirstCol && i == 0, if (i == 0 && !boldFirstCol) color else if (boldFirstCol) Color.DKGRAY else color), widths[i] - 2 * pad)
            }
            val h = layouts.maxOf { it.height } + 2 * pad
            if (y + h > bottom) { newPage(); if (header) drawHeader() }
            val c = canvas!!
            if (r % 2 == 1) { fillPaint.color = Color.rgb(242, 245, 248); c.drawRect(margin, y, margin + contentW, y + h, fillPaint) }
            var x = margin
            layouts.forEachIndexed { i, l ->
                c.save(); c.translate(x + pad, y + pad); l.draw(c); c.restore()
                c.drawRect(RectF(x, y, x + widths[i], y + h), linePaint)
                x += widths[i]
            }
            y += h
        }
        y += 6f
    }

    /** A photo scaled to fit the page width (max [maxH] pt tall) with an optional caption below. */
    fun photo(file: File, caption: String, maxH: Float = 330f) {
        val bmp: Bitmap = Images.load(file, 1400) ?: return
        val scale = minOf(contentW / bmp.width, maxH / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val capLayout = if (caption.isNotBlank()) layout(caption, textPaint(9.5f), contentW) else null
        ensure(h + (capLayout?.height ?: 0) + 12f)
        val c = canvas!!
        val x = margin + (contentW - w) / 2
        c.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
        c.drawRect(RectF(x, y, x + w, y + h), linePaint)
        y += h + 4f
        capLayout?.let { draw(it, margin); y += it.height + 4f }
        y += 8f
        bmp.recycle()
    }

    /**
     * Photos in a two-column table: the photo on the left, its title and explanation on the right.
     * Each item is (photo file, title/description, explanation).
     */
    fun photoTable(items: List<Triple<File, String, String>>) {
        if (items.isEmpty()) return
        val pad = 6f
        val imgW = contentW * 0.52f
        val txtW = contentW - imgW
        val maxImgH = 250f
        val headPaint = textPaint(9.5f, true, Color.WHITE)
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        fun header() {
            val h = 20f
            ensure(h + 120f)
            val c = canvas!!
            fillPaint.color = navy
            c.drawRect(margin, y, margin + contentW, y + h, fillPaint)
            c.drawText("Photo", margin + pad, y + 14f, headPaint)
            c.drawText("Description / explanation", margin + imgW + pad, y + 14f, headPaint)
            y += h
        }

        header()
        items.forEach { (file, title, explanation) ->
            val bmp = Images.load(file, 1200) ?: return@forEach
            val scale = minOf((imgW - 2 * pad) / bmp.width, maxImgH / bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            val titleL = layout(title, textPaint(10f, true, navy), txtW - 2 * pad)
            val bodyL = layout(
                explanation.ifBlank { "–" },
                textPaint(9.5f, color = if (explanation.isBlank()) Color.GRAY else Color.BLACK),
                txtW - 2 * pad,
            )
            val rowH = maxOf(h, (titleL.height + 6 + bodyL.height).toFloat()) + 2 * pad
            if (y + rowH > bottom) { newPage(); header() }
            val c = canvas!!
            val x = margin + (imgW - w) / 2
            c.drawBitmap(bmp, null, RectF(x, y + pad, x + w, y + pad + h), bmpPaint)
            c.save(); c.translate(margin + imgW + pad, y + pad); titleL.draw(c); c.restore()
            c.save(); c.translate(margin + imgW + pad, y + pad + titleL.height + 6); bodyL.draw(c); c.restore()
            c.drawRect(RectF(margin, y, margin + imgW, y + rowH), linePaint)
            c.drawRect(RectF(margin + imgW, y, margin + contentW, y + rowH), linePaint)
            y += rowH
            bmp.recycle()
        }
        y += 8f
    }

    /** Signature lines at the end of a report. */
    fun signatures(vararg labels: String) {
        ensure(70f)
        y += 30f
        val c = canvas!!
        val w = (contentW - (labels.size - 1) * 20f) / labels.size
        val tp = textPaint(9f, color = Color.DKGRAY)
        labels.forEachIndexed { i, label ->
            val x = margin + i * (w + 20f)
            c.drawLine(x, y, x + w, y, Paint().apply { color = Color.BLACK; strokeWidth = 0.8f })
            c.drawText(label, x, y + 12f, tp)
        }
        y += 24f
    }

    fun writeTo(file: File) {
        if (page == null && pageCount == 0) newPage()
        finishPage()
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
    }
}

/** Sends a PDF file to the Android print system (A4 by default). */
fun printPdf(context: Context, file: File, jobName: String) {
    val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    val adapter = object : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
            callback.onLayoutFinished(
                PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true,
            )
        }

        override fun onWrite(
            pages: Array<out PageRange>?, destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?, callback: WriteResultCallback,
        ) {
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
    pm.print(jobName, adapter, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
}
