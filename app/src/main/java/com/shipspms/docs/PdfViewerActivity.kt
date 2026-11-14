package com.shipspms.docs

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Offline PDF viewer built on Android's own PdfRenderer (no extra libraries, no internet).
 * All PdfRenderer calls run on one background thread because the renderer isn't thread-safe.
 */
class PdfViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val STATE_PAGE = "page"
    }

    private lateinit var file: File
    private lateinit var toolbar: MaterialToolbar
    private lateinit var pages: ZoomRecyclerView
    private lateinit var layoutManager: LinearLayoutManager

    private val renderThread: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    @Volatile private var closed = false

    /** Width/height ratio of each page (height = width * ratio). */
    private var pageRatios = FloatArray(0)

    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        file = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })
        toolbar = findViewById(R.id.toolbar)
        toolbar.title = file.nameWithoutExtension
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.viewer_menu)
        for (i in 0 until toolbar.menu.size()) toolbar.menu.getItem(i).icon?.setTint(Color.WHITE)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_goto -> askPage()
                R.id.action_share -> shareFile(this, file)
                R.id.action_open_with -> openWithOtherApp(this, file)
            }
            true
        }

        pages = findViewById(R.id.pages)
        layoutManager = LinearLayoutManager(this)
        pages.layoutManager = layoutManager
        pages.setItemViewCacheSize(2)
        pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateSubtitle()
        })

        val startPage = savedInstanceState?.getInt(STATE_PAGE) ?: 0
        val progress = findViewById<ProgressBar>(R.id.progress)

        renderThread.execute {
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                fd = pfd
                val r = PdfRenderer(pfd)
                renderer = r
                val ratios = FloatArray(r.pageCount) { i ->
                    r.openPage(i).use { p -> p.height.toFloat() / p.width.coerceAtLeast(1) }
                }
                main.post {
                    if (isDestroyed) return@post
                    progress.visibility = View.GONE
                    pageRatios = ratios
                    pages.adapter = PageAdapter()
                    layoutManager.scrollToPositionWithOffset(startPage.coerceIn(0, (ratios.size - 1).coerceAtLeast(0)), 0)
                    pages.post { updateSubtitle() }
                }
            } catch (e: Exception) {
                main.post {
                    if (isDestroyed) return@post
                    progress.visibility = View.GONE
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Can't open this PDF")
                        .setMessage(
                            if (e is SecurityException) "This PDF is password protected. Try opening it with another PDF app."
                            else "The file may be damaged or use an unsupported format. Try opening it with another PDF app."
                        )
                        .setPositiveButton(R.string.open_with) { _, _ -> openWithOtherApp(this, file); finish() }
                        .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                        .setOnCancelListener { finish() }
                        .show()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::layoutManager.isInitialized) outState.putInt(STATE_PAGE, currentPage())
    }

    override fun onDestroy() {
        super.onDestroy()
        closed = true
        main.removeCallbacksAndMessages(null)
        renderThread.execute {
            try { renderer?.close() } catch (_: Exception) {}
            try { fd?.close() } catch (_: Exception) {}
        }
        renderThread.shutdown()
        cache.evictAll()
    }

    private fun currentPage(): Int = layoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)

    private fun updateSubtitle() {
        if (pageRatios.isEmpty()) return
        toolbar.subtitle = "Page ${currentPage() + 1} of ${pageRatios.size}"
    }

    private fun askPage() {
        if (pageRatios.isEmpty()) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 – ${pageRatios.size}"
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.go_to_page)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val n = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                pages.resetZoom()
                layoutManager.scrollToPositionWithOffset((n - 1).coerceIn(0, pageRatios.size - 1), 0)
                pages.post { updateSubtitle() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private class PageHolder(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var boundPage = -1
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = pageRatios.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false) as ImageView
        )

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val viewWidth = pages.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            holder.image.layoutParams = holder.image.layoutParams.apply {
                height = (viewWidth * pageRatios[position]).toInt()
            }
            holder.boundPage = position
            holder.image.contentDescription = "Page ${position + 1}"

            cache.get(position)?.let {
                holder.image.setImageBitmap(it)
                return
            }
            holder.image.setImageDrawable(null)

            // Render a bit sharper than the screen so zooming in stays readable.
            val renderWidth = (viewWidth * 1.6f).toInt().coerceAtMost(2400)
            val renderHeight = (renderWidth * pageRatios[position]).toInt().coerceAtLeast(1)
            renderThread.execute {
                if (closed) return@execute
                if (holder.boundPage != position) return@execute // scrolled away already
                val bitmap = try {
                    val r = renderer ?: return@execute
                    Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888).also { bmp ->
                        bmp.eraseColor(Color.WHITE)
                        r.openPage(position).use { page ->
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                } catch (e: Throwable) {
                    null
                } ?: return@execute
                main.post {
                    if (closed) return@post
                    cache.put(position, bitmap)
                    if (holder.boundPage == position) holder.image.setImageBitmap(bitmap)
                }
            }
        }

        override fun onViewRecycled(holder: PageHolder) {
            holder.boundPage = -1
            holder.image.setImageDrawable(null)
        }
    }
}
