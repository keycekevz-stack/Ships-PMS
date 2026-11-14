package com.shipspms.docs

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private enum class Sort(val label: String) {
        NAME("Name (A–Z)"),
        ADDED("Recently added"),
        OPENED("Recently opened"),
    }

    private lateinit var repo: DocumentRepository
    private lateinit var adapter: DocumentAdapter
    private lateinit var emptyView: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyBody: TextView
    private lateinit var progress: ProgressBar
    private lateinit var chips: ChipGroup

    private var query = ""
    private var filter: String = FILTER_ALL
    private var sort = Sort.NAME

    private val pickPdfs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) chooseCategoryAndImport(uris)
    }

    companion object {
        private const val FILTER_ALL = "__all__"
        private const val FILTER_FAV = "__fav__"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = DocumentRepository(this)

        val prefs = getPreferences(Context.MODE_PRIVATE)
        sort = Sort.entries.getOrElse(prefs.getInt("sort", 0)) { Sort.NAME }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.menu.findItem(R.id.action_sort).icon?.setTint(getColor(android.R.color.white))
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_sort) { chooseSort(); true } else false
        }

        emptyView = findViewById(R.id.empty)
        emptyTitle = findViewById(R.id.empty_title)
        emptyBody = findViewById(R.id.empty_body)
        progress = findViewById(R.id.progress)
        chips = findViewById(R.id.chips)

        adapter = DocumentAdapter(
            onOpen = ::openDocument,
            onToggleFavorite = { doc -> repo.setFavorite(doc, !doc.favorite); refresh() },
            onMore = ::showDocumentMenu,
        )
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            addItemDecoration(DividerItemDecoration(this@MainActivity, DividerItemDecoration.VERTICAL))
            adapter = this@MainActivity.adapter
        }

        findViewById<EditText>(R.id.search).doAfterTextChanged {
            query = it?.toString().orEmpty().trim()
            refresh()
        }

        setupChips()

        findViewById<ExtendedFloatingActionButton>(R.id.fab).setOnClickListener {
            pickPdfs.launch(arrayOf("application/pdf"))
        }

        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun setupChips() {
        fun addChip(label: String, tag: String) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                this.tag = tag
                id = View.generateViewId()
            }
            chips.addView(chip)
            if (tag == filter) chip.isChecked = true
        }
        addChip(getString(R.string.all), FILTER_ALL)
        addChip(getString(R.string.favorites), FILTER_FAV)
        DocumentRepository.CATEGORIES.forEach { addChip(it, it) }

        chips.setOnCheckedStateChangeListener { group, ids ->
            filter = ids.firstOrNull()?.let { group.findViewById<Chip>(it)?.tag as? String } ?: FILTER_ALL
            refresh()
        }
    }

    private fun refresh() {
        val all = repo.all()
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val shown = all
            .filter {
                when (filter) {
                    FILTER_ALL -> true
                    FILTER_FAV -> it.favorite
                    else -> it.category == filter
                }
            }
            .filter { doc ->
                val haystack = (doc.name + " " + doc.category).lowercase()
                words.all { haystack.contains(it) }
            }
            .let { list ->
                when (sort) {
                    Sort.NAME -> list.sortedBy { it.name.lowercase() }
                    Sort.ADDED -> list.sortedByDescending { it.file.lastModified() }
                    Sort.OPENED -> list.sortedByDescending { it.lastOpened }
                }
            }
        adapter.submitList(shown)

        emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        if (all.isEmpty()) {
            emptyTitle.setText(R.string.empty_title)
            emptyBody.visibility = View.VISIBLE
        } else {
            emptyTitle.setText(R.string.no_results)
            emptyBody.visibility = View.GONE
        }
    }

    private fun chooseSort() {
        val labels = Sort.entries.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort)
            .setSingleChoiceItems(labels, sort.ordinal) { dialog, which ->
                sort = Sort.entries[which]
                getPreferences(Context.MODE_PRIVATE).edit().putInt("sort", which).apply()
                refresh()
                dialog.dismiss()
            }
            .show()
    }

    // ---- Importing ----

    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        // Don't import the same shared file twice (e.g. after rotation).
        intent.action = Intent.ACTION_MAIN
        if (uris.isNotEmpty()) chooseCategoryAndImport(uris)
    }

    private fun chooseCategoryAndImport(uris: List<Uri>) {
        val categories = DocumentRepository.CATEGORIES
        var selected = categories.indexOf(filter).takeIf { it >= 0 } ?: categories.lastIndex
        val title = getString(R.string.choose_category) + if (uris.size > 1) " (${uris.size} files)" else ""
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(categories.toTypedArray(), selected) { _, which -> selected = which }
            .setPositiveButton(R.string.ok) { _, _ -> importAll(uris, categories[selected]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importAll(uris: List<Uri>, category: String) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            var ok = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    try {
                        repo.import(uri, category); ok++
                    } catch (e: Exception) {
                        failed++
                    }
                }
            }
            progress.visibility = View.GONE
            val msg = buildString {
                append("Saved $ok file${if (ok == 1) "" else "s"} to $category")
                if (failed > 0) append(" · $failed failed")
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    // ---- Document actions ----

    private fun openDocument(doc: ShipDocument) {
        repo.markOpened(doc)
        startActivity(
            Intent(this, PdfViewerActivity::class.java)
                .putExtra(PdfViewerActivity.EXTRA_PATH, doc.file.absolutePath)
        )
    }

    private fun showDocumentMenu(doc: ShipDocument, anchor: View) {
        PopupMenu(this, anchor).apply {
            inflate(R.menu.document_menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.doc_share -> shareFile(this@MainActivity, doc.file)
                    R.id.doc_open_with -> openWithOtherApp(this@MainActivity, doc.file)
                    R.id.doc_rename -> rename(doc)
                    R.id.doc_move -> move(doc)
                    R.id.doc_delete -> confirmDelete(doc)
                }
                true
            }
            show()
        }
    }

    private fun rename(doc: ShipDocument) {
        val input = EditText(this).apply {
            setText(doc.name)
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (repo.rename(doc, input.text.toString()) == null) toast("Could not rename")
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun move(doc: ShipDocument) {
        val categories = DocumentRepository.CATEGORIES
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.move)
            .setSingleChoiceItems(categories.toTypedArray(), categories.indexOf(doc.category)) { dialog, which ->
                if (repo.move(doc, categories[which]) == null) toast("Could not move")
                dialog.dismiss()
                refresh()
            }
            .show()
    }

    private fun confirmDelete(doc: ShipDocument) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete “${doc.name}”?")
            .setMessage("The file will be removed from this device.")
            .setPositiveButton(R.string.delete) { _, _ ->
                if (!repo.delete(doc)) toast("Could not delete")
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

// ---- Shared helpers (also used by the viewer) ----

private fun uriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

fun shareFile(context: Context, file: File) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
        .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share ${file.nameWithoutExtension}"))
}

fun openWithOtherApp(context: Context, file: File) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uriFor(context, file), "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(view, "Open with"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No other PDF app installed", Toast.LENGTH_SHORT).show()
    }
}
