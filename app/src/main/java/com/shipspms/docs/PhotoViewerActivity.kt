package com.shipspms.docs

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.io.File

/** Shows one or more photos, scrolling vertically, with pinch / double-tap zoom. */
class PhotoViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATHS = "paths"
        const val EXTRA_INDEX = "index"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CAPTIONS = "captions"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        findViewById<ProgressBar>(R.id.progress).visibility = View.GONE

        val files = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty().map(::File).filter { it.exists() }
        if (files.isEmpty()) { finish(); return }
        val start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, files.lastIndex)
        val captions = intent.getStringArrayListExtra(EXTRA_CAPTIONS).orEmpty()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = intent.getStringExtra(EXTRA_TITLE) ?: "Photos"
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.add(0, 1, 0, R.string.share).apply {
            setIcon(R.drawable.ic_share)
            icon?.setTint(Color.WHITE)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }

        val pages = findViewById<ZoomRecyclerView>(R.id.pages)
        val lm = LinearLayoutManager(this)
        pages.layoutManager = lm
        fun current() = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        fun subtitle() { toolbar.subtitle = "${current() + 1} of ${files.size}" }
        toolbar.setOnMenuItemClickListener { shareFile(this, files[current()]); true }

        pages.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = files.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val box = android.widget.LinearLayout(parent.context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                box.addView(layoutInflater.inflate(R.layout.item_page, box, false))
                box.addView(android.widget.TextView(parent.context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    val pad = (12 * resources.displayMetrics.density).toInt()
                    setPadding(pad, 0, pad, pad)
                })
                return object : RecyclerView.ViewHolder(box) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val box = holder.itemView as android.widget.LinearLayout
                val image = box.getChildAt(0) as ImageView
                val caption = box.getChildAt(1) as android.widget.TextView
                val text = captions.getOrNull(position).orEmpty()
                caption.text = "Photo ${position + 1}" + if (text.isNotBlank()) ": $text" else ""
                val width = pages.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(files[position].absolutePath, bounds)
                val ratio = if (bounds.outWidth > 0) bounds.outHeight.toFloat() / bounds.outWidth else 0.75f
                image.layoutParams = image.layoutParams.apply { height = (width * ratio).toInt() }
                image.setImageBitmap(Images.load(files[position], (width * 1.5f).toInt()))
            }
        }
        pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = subtitle()
        })
        lm.scrollToPositionWithOffset(start, 0)
        pages.post { subtitle() }
    }
}
