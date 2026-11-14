package com.shipspms.docs

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.GravityCompat
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Tab { DOCS, SCHEDULE, HISTORY }
    private enum class JobFilter { ALL, OVERDUE, SOON }

    private lateinit var store: PmsStore
    private lateinit var treeAdapter: TreeAdapter
    private lateinit var rowAdapter: RowAdapter
    private var drawer: DrawerLayout? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var headerPath: TextView
    private lateinit var headerInfo: TextView
    private lateinit var headerHours: MaterialButton
    private lateinit var tabs: TabLayout
    private lateinit var chips: ChipGroup
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var fab: ExtendedFloatingActionButton

    private var selectedId: String? = null
    private val expanded = mutableSetOf<String>()
    private var tab = Tab.SCHEDULE
    private var query = ""
    private var jobFilter = JobFilter.ALL
    private var historyJobId: String? = null
    private var pendingImportNode: String? = null

    private val dateFmt: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    private val numFmt: NumberFormat = NumberFormat.getIntegerInstance()

    private val prefs by lazy { getSharedPreferences("ui", Context.MODE_PRIVATE) }

    private val pickPdfs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val node = pendingImportNode
        if (uris.isNotEmpty() && node != null) importAll(uris, node)
    }

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runIo("Backup saved", "Backup failed") {
            contentResolver.openOutputStream(uri).use { out -> store.backup(requireNotNull(out)) }
        }
    }

    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runIo("Backup restored", "Restore failed") {
            contentResolver.openInputStream(uri).use { input -> store.restore(requireNotNull(input)) }
            withContext(Dispatchers.Main) { selectedId = null; expanded.clear() }
        }
    }

    // =====================================================================
    // Setup
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = PmsStore.get(this)

        selectedId = prefs.getString("selected", null)?.takeIf { store.node(it) != null }
        expanded += prefs.getStringSet("expanded", emptySet()).orEmpty()
        tab = Tab.entries.getOrElse(prefs.getInt("tab", Tab.SCHEDULE.ordinal)) { Tab.SCHEDULE }

        bindViews()
        setupToolbar()
        setupTree()
        setupTabs()
        setupList()

        findViewById<EditText>(R.id.search).doAfterTextChanged {
            query = it?.toString().orEmpty().trim()
            refreshList()
        }
        headerHours.setOnClickListener { store.node(selectedId)?.let(::editRunningHours) }
        fab.setOnClickListener { onFab() }

        if (savedInstanceState == null) {
            if (store.nodes.isEmpty() && !prefs.getBoolean("seed_asked", false)) askSeed()
            else if (selectedId == null) drawer?.openDrawer(GravityCompat.START)
            handleIncoming(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        prefs.edit()
            .putString("selected", selectedId)
            .putStringSet("expanded", expanded.toSet())
            .putInt("tab", tab.ordinal)
            .apply()
    }

    private fun bindViews() {
        drawer = findViewById(R.id.drawer)
        toolbar = findViewById(R.id.toolbar)
        headerPath = findViewById(R.id.header_path)
        headerInfo = findViewById(R.id.header_info)
        headerHours = findViewById(R.id.header_hours)
        tabs = findViewById(R.id.tabs)
        chips = findViewById(R.id.chips)
        chipsScroll = chips.parent as HorizontalScrollView
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        progress = findViewById(R.id.progress)
        fab = findViewById(R.id.fab)
    }

    private fun setupToolbar() {
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_backup -> {
                    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    createBackup.launch("ShipPMS-backup-$stamp.zip")
                }
                R.id.action_restore -> confirmRestore()
                R.id.action_example -> { store.seedExample(); refresh(); drawer?.openDrawer(GravityCompat.START) }
            }
            true
        }
        drawer?.let { d ->
            toolbar.setNavigationIcon(R.drawable.ic_menu)
            toolbar.setNavigationContentDescription(R.string.ship_structure)
            toolbar.setNavigationOnClickListener { d.openDrawer(GravityCompat.START) }
            val back = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() = d.closeDrawer(GravityCompat.START)
            }
            onBackPressedDispatcher.addCallback(this, back)
            d.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerOpened(drawerView: View) { back.isEnabled = true }
                override fun onDrawerClosed(drawerView: View) { back.isEnabled = false }
            })
        }
    }

    private fun setupTree() {
        treeAdapter = TreeAdapter(
            onSelect = { node -> selectNode(node) },
            onToggle = { node ->
                if (!expanded.add(node.id)) expanded.remove(node.id)
                refreshTree()
            },
            onMore = ::showNodeMenu,
        )
        findViewById<RecyclerView>(R.id.tree).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = treeAdapter
            itemAnimator = null
        }
        findViewById<View>(R.id.tree_add).setOnClickListener {
            promptText("New main group", "", "e.g. Engine Room, Deck Machinery") { name ->
                val n = store.addNode(null, name)
                selectNode(n, closeDrawer = false)
            }
        }
    }

    private fun setupTabs() {
        listOf(R.string.tab_docs, R.string.tab_schedule, R.string.tab_history).forEach {
            tabs.addTab(tabs.newTab().setText(it), false)
        }
        tabs.getTabAt(tab.ordinal)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tab = Tab.entries[t.position]
                refreshChips(); refreshFab(); refreshList()
                list.scrollToPosition(0)
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
    }

    private fun setupList() {
        rowAdapter = RowAdapter(
            onClick = { payload ->
                when (payload) {
                    is Doc -> openDoc(payload)
                    is Job -> showJobMenu(payload, null)
                    is WorkRecord -> showRecordDialog(payload.nodeId, store.jobs.firstOrNull { it.id == payload.jobId }, payload)
                }
            },
            onMore = { payload, anchor ->
                when (payload) {
                    is Doc -> showDocMenu(payload, anchor)
                    is Job -> showJobMenu(payload, anchor)
                    is WorkRecord -> showRecordMenu(payload, anchor)
                }
            },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        list.adapter = rowAdapter
    }

    private fun askSeed() {
        prefs.edit().putBoolean("seed_asked", true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle("Welcome to Ship PMS")
            .setMessage(
                "Build your ship structure on the left: main groups (Engine Room), sub-groups (Main Engine) " +
                    "and items (Fuel Pump). Then add documents, maintenance jobs and work done to each item.\n\n" +
                    "Start with an example structure you can edit, or start empty?"
            )
            .setPositiveButton("Use example") { _, _ ->
                store.seedExample(); refresh(); drawer?.openDrawer(GravityCompat.START)
            }
            .setNegativeButton("Start empty") { _, _ -> drawer?.openDrawer(GravityCompat.START) }
            .show()
    }

    // =====================================================================
    // Refresh
    // =====================================================================

    private fun refresh() {
        if (selectedId != null && store.node(selectedId) == null) selectedId = null
        refreshTree(); refreshHeader(); refreshChips(); refreshFab(); refreshList()
    }

    private fun refreshTree() {
        val overdue = store.nodesWithOverdue()
        val rows = mutableListOf(TreeRow(null, 0, false, false, selectedId == null, overdue.isNotEmpty()))
        fun add(parentId: String?, depth: Int) {
            store.children(parentId).forEach { n ->
                val hasKids = store.nodes.any { it.parentId == n.id }
                val open = n.id in expanded
                rows += TreeRow(n, depth, hasKids, open, n.id == selectedId, n.id in overdue)
                if (hasKids && open) add(n.id, depth + 1)
            }
        }
        add(null, 0)
        treeAdapter.submitList(rows)
    }

    private fun refreshHeader() {
        val node = store.node(selectedId)
        val ids = store.subtreeIds(selectedId)
        val jobs = store.jobs.filter { it.nodeId in ids }
        val overdue = jobs.count { store.status(it) == JobStatus.OVERDUE }
        val soon = jobs.count { store.status(it) == JobStatus.DUE_SOON }

        headerPath.text = if (node == null) getString(R.string.whole_ship) else store.path(node.id)
        val parts = mutableListOf<String>()
        if (node == null) {
            parts += "${store.nodes.size} items"
        } else if (node.runningHours != null) {
            parts += "${numFmt.format(node.runningHours)} running hours" +
                (node.hoursUpdated?.let { " (${dateFmt.format(Date(it))})" } ?: "")
        }
        parts += "${jobs.size} jobs"
        if (overdue > 0) parts += "$overdue overdue"
        if (soon > 0) parts += "$soon due soon"
        headerInfo.text = parts.joinToString(" · ")
        headerHours.visibility = if (node == null) View.GONE else View.VISIBLE

        tabs.getTabAt(Tab.SCHEDULE.ordinal)?.let { t ->
            val badge = t.orCreateBadge
            badge.isVisible = overdue > 0
            badge.number = overdue
        }
    }

    private fun refreshChips() {
        chips.setOnCheckedStateChangeListener(null)
        chips.removeAllViews()
        when (tab) {
            Tab.SCHEDULE -> {
                chips.isSingleSelection = true
                chips.isSelectionRequired = true
                JobFilter.entries.forEach { f ->
                    chips.addView(Chip(this).apply {
                        id = View.generateViewId()
                        text = when (f) { JobFilter.ALL -> "All jobs"; JobFilter.OVERDUE -> "Overdue"; JobFilter.SOON -> "Due soon" }
                        isCheckable = true
                        isChecked = f == jobFilter
                        tag = f
                    })
                }
                chips.setOnCheckedStateChangeListener { group, ids ->
                    jobFilter = ids.firstOrNull()?.let { group.findViewById<Chip>(it)?.tag as? JobFilter } ?: JobFilter.ALL
                    refreshList()
                }
            }
            Tab.HISTORY -> {
                val job = store.jobs.firstOrNull { it.id == historyJobId }
                if (job == null) historyJobId = null
                job?.let {
                    chips.addView(Chip(this).apply {
                        text = "Job: ${it.title}"
                        isCloseIconVisible = true
                        setOnCloseIconClickListener { historyJobId = null; refreshChips(); refreshList() }
                    })
                }
            }
            Tab.DOCS -> {}
        }
        chipsScroll.visibility = if (chips.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun refreshFab() {
        when (tab) {
            Tab.DOCS -> fab.setText(R.string.upload_pdf)
            Tab.SCHEDULE -> fab.setText(R.string.add_job)
            Tab.HISTORY -> fab.setText(R.string.log_work)
        }
    }

    private fun matches(vararg fields: String?): Boolean {
        if (query.isEmpty()) return true
        val hay = fields.filterNotNull().joinToString(" ").lowercase()
        return query.lowercase().split(Regex("\\s+")).all { hay.contains(it) }
    }

    private fun refreshList() {
        val ids = store.subtreeIds(selectedId)
        val items: List<RowItem> = when (tab) {
            Tab.DOCS -> store.docs(ids)
                .filter { matches(it.name, store.path(it.nodeId)) }
                .sortedBy { it.name.lowercase() }
                .map { d ->
                    RowItem(
                        key = d.file.absolutePath, icon = R.drawable.ic_pdf, iconTint = null,
                        title = d.name,
                        line2 = store.path(d.nodeId),
                        line3 = android.text.format.Formatter.formatShortFileSize(this, d.file.length()) +
                            " · added " + dateFmt.format(Date(d.file.lastModified())),
                        payload = d,
                    )
                }

            Tab.SCHEDULE -> store.jobs
                .filter { it.nodeId in ids }
                .filter {
                    when (jobFilter) {
                        JobFilter.ALL -> true
                        JobFilter.OVERDUE -> store.status(it) == JobStatus.OVERDUE
                        JobFilter.SOON -> store.status(it) == JobStatus.DUE_SOON
                    }
                }
                .filter { matches(it.title, store.path(it.nodeId), it.instructions) }
                .sortedByDescending { store.progress(it) }
                .map { j ->
                    val status = store.status(j)
                    RowItem(
                        key = j.id, icon = R.drawable.ic_build, iconTint = statusColor(status),
                        title = j.title,
                        line2 = store.path(j.nodeId),
                        line3 = describeJob(j),
                        badge = when (status) { JobStatus.OVERDUE -> "OVERDUE"; JobStatus.DUE_SOON -> "DUE SOON"; JobStatus.OK -> null },
                        color = statusColor(status),
                        payload = j,
                    )
                }

            Tab.HISTORY -> store.records
                .filter { it.nodeId in ids && (historyJobId == null || it.jobId == historyJobId) }
                .filter { matches(it.title, store.path(it.nodeId), it.doneBy, it.remarks) }
                .sortedByDescending { it.date }
                .map { r ->
                    val meta = listOfNotNull(
                        dateFmt.format(Date(r.date)),
                        r.doneBy.takeIf { it.isNotBlank() }?.let { "by $it" },
                        r.hours?.let { "at ${numFmt.format(it)} h" },
                        if (r.jobId == null) "unplanned" else null,
                    ).joinToString(" · ")
                    RowItem(
                        key = r.id, icon = R.drawable.ic_build, iconTint = getColor(R.color.sea),
                        title = r.title,
                        line2 = store.path(r.nodeId),
                        line3 = meta + if (r.remarks.isNotBlank()) "\n${r.remarks}" else "",
                        payload = r,
                    )
                }
        }
        rowAdapter.submitList(items)

        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            store.nodes.isEmpty() -> "Start by adding your machinery in the Ship structure (☰ menu, top left)."
            query.isNotEmpty() -> "Nothing matches “$query”."
            tab == Tab.DOCS -> "No documents here yet.\nTap “Upload PDF” to add manuals, drawings or certificates."
            tab == Tab.SCHEDULE && jobFilter != JobFilter.ALL -> "No jobs in this list. 👍"
            tab == Tab.SCHEDULE -> "No planned maintenance yet.\nTap “Add job”, e.g. “Clean filters every 3 months” or “Overhaul every 8,000 running hours”."
            else -> "No work recorded yet.\nOpen a job in Schedule and tap “Record work done”, or tap “Log work” for unplanned work."
        }
    }

    private fun statusColor(s: JobStatus) = getColor(
        when (s) {
            JobStatus.OVERDUE -> R.color.status_overdue
            JobStatus.DUE_SOON -> R.color.status_soon
            JobStatus.OK -> R.color.status_ok
        }
    )

    private fun describeJob(j: Job): String {
        val every = if (j.unit == IntervalUnit.HOURS) "Every ${numFmt.format(j.intervalValue)} h"
        else "Every ${j.intervalValue} ${j.unit.short}"
        return if (j.unit == IntervalUnit.HOURS) {
            val last = j.lastDoneHours?.let { "Last at ${numFmt.format(it)} h" } ?: "Last ${dateFmt.format(Date(j.lastDoneDate))}"
            val due = "Due at ${numFmt.format(store.dueHours(j))} h"
            val left = store.hoursLeft(j)?.let { if (it >= 0) "${numFmt.format(it)} h left" else "${numFmt.format(-it)} h over" }
                ?: "enter running hours"
            "$every · $last\n$due · $left"
        } else {
            val due = store.dueDate(j)!!
            val days = Math.floorDiv(due - startOfToday(), 24L * 60 * 60 * 1000)
            val left = when {
                days > 1 -> "in $days days"
                days == 1L -> "tomorrow"
                days == 0L -> "today"
                else -> "${-days} days overdue"
            }
            "$every · Last ${dateFmt.format(Date(j.lastDoneDate))}\nDue ${dateFmt.format(Date(due))} ($left)"
        }
    }

    private fun startOfToday() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // =====================================================================
    // Tree actions
    // =====================================================================

    private fun selectNode(node: Node?, closeDrawer: Boolean = true) {
        selectedId = node?.id
        historyJobId = null
        if (node != null) {
            // Make sure the selected item is visible in the tree.
            var p = store.node(node.parentId)
            while (p != null) { expanded += p.id; p = store.node(p.parentId) }
        }
        val isLeaf = node == null || store.nodes.none { it.parentId == node.id }
        if (node != null && !isLeaf) expanded += node.id
        refresh()
        if (closeDrawer && isLeaf) drawer?.closeDrawer(GravityCompat.START)
    }

    private fun showNodeMenu(node: Node, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Add sub-item")
            menu.add(0, 2, 1, "Rename")
            menu.add(0, 3, 2, "Running hours")
            menu.add(0, 4, 3, "Delete")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> promptText("Add under “${node.name}”", "", "e.g. Main Engine, Fuel Pump No.1") { name ->
                        store.addNode(node.id, name)
                        expanded += node.id
                        refresh()
                    }
                    2 -> promptText("Rename", node.name, "") { name ->
                        node.name = name; store.save(); refresh()
                    }
                    3 -> editRunningHours(node)
                    4 -> confirmDeleteNode(node)
                }
                true
            }
            show()
        }
    }

    private fun editRunningHours(node: Node) {
        promptText(
            "Running hours – ${node.name}", node.runningHours?.toString().orEmpty(),
            "Current counter reading", InputType.TYPE_CLASS_NUMBER, allowEmpty = true,
        ) { text ->
            node.runningHours = text.toLongOrNull()
            node.hoursUpdated = if (node.runningHours != null) System.currentTimeMillis() else null
            store.save(); refresh()
        }
    }

    private fun confirmDeleteNode(node: Node) {
        val ids = store.subtreeIds(node.id)
        val sub = ids.size - 1
        val jobs = store.jobs.count { it.nodeId in ids }
        val recs = store.records.count { it.nodeId in ids }
        val docs = store.docs(ids).size
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete “${node.name}”?")
            .setMessage("This also deletes $sub sub-items, $jobs jobs, $recs work records and $docs documents. This cannot be undone.")
            .setPositiveButton(R.string.delete) { _, _ ->
                if (selectedId in ids) selectedId = node.parentId
                store.deleteNode(node.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Runs [action] with the selected item, or asks which item when "Whole ship" is selected. */
    private fun withItem(title: String, action: (String) -> Unit) {
        val sel = selectedId
        when {
            sel != null -> action(sel)
            store.nodes.isEmpty() -> MaterialAlertDialogBuilder(this)
                .setMessage("First add your machinery in the Ship structure on the left.")
                .setPositiveButton(R.string.ok) { _, _ -> drawer?.openDrawer(GravityCompat.START) }
                .show()
            else -> chooseNode(title, action)
        }
    }

    private fun chooseNode(title: String, action: (String) -> Unit) {
        val ordered = mutableListOf<Pair<Node, Int>>()
        fun walk(parent: String?, depth: Int) {
            store.children(parent).forEach { ordered += it to depth; walk(it.id, depth + 1) }
        }
        walk(null, 0)
        val labels = ordered.map { (n, d) -> " ".repeat(d) + n.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(labels) { _, i -> action(ordered[i].first.id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onFab() {
        when (tab) {
            Tab.DOCS -> withItem("Upload to which item?") { id ->
                pendingImportNode = id
                pickPdfs.launch(arrayOf("application/pdf"))
            }
            Tab.SCHEDULE -> withItem("Add job to which item?") { id -> showJobDialog(null, id) }
            Tab.HISTORY -> withItem("Log work on which item?") { id -> showRecordDialog(id, null, null) }
        }
    }

    // =====================================================================
    // Jobs
    // =====================================================================

    private fun showJobMenu(job: Job, anchor: View?) {
        val options = arrayOf("✔ Record work done", "Edit job", "Work history", "Delete job")
        val handle = { which: Int ->
            when (which) {
                0 -> showRecordDialog(job.nodeId, job, null)
                1 -> showJobDialog(job, job.nodeId)
                2 -> {
                    historyJobId = job.id
                    tabs.getTabAt(Tab.HISTORY.ordinal)?.select()
                    refreshChips(); refreshList()
                }
                3 -> MaterialAlertDialogBuilder(this)
                    .setTitle("Delete job “${job.title}”?")
                    .setMessage("Work already recorded for it is kept in Work done.")
                    .setPositiveButton(R.string.delete) { _, _ -> store.deleteJob(job); refresh() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        if (anchor == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(job.title)
                .setMessage(store.path(job.nodeId) + "\n" + describeJob(job) +
                    if (job.instructions.isNotBlank()) "\n\n${job.instructions}" else "")
                .setPositiveButton(options[0]) { _, _ -> handle(0) }
                .setNeutralButton("More…") { _, _ -> showJobMenu(job, list) }
                .setNegativeButton("Close", null)
                .show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(job.title)
                .setItems(options) { _, which -> handle(which) }
                .show()
        }
    }

    private fun showJobDialog(existing: Job?, nodeId: String) {
        val v = layoutInflater.inflate(R.layout.dialog_job, null)
        val node = store.node(nodeId)
        v.findViewById<TextView>(R.id.item_path).text = store.path(nodeId)
        val titleLayout = v.findViewById<TextInputLayout>(R.id.job_title_layout)
        val title = v.findViewById<TextInputEditText>(R.id.job_title)
        val interval = v.findViewById<TextInputEditText>(R.id.interval)
        val unit = v.findViewById<Spinner>(R.id.unit)
        val lastDate = v.findViewById<TextInputEditText>(R.id.last_date)
        val lastHoursLayout = v.findViewById<TextInputLayout>(R.id.last_hours_layout)
        val lastHours = v.findViewById<TextInputEditText>(R.id.last_hours)
        val instructions = v.findViewById<TextInputEditText>(R.id.instructions)

        unit.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, IntervalUnit.entries.map { it.label })
        unit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                lastHoursLayout.visibility = if (IntervalUnit.entries[pos] == IntervalUnit.HOURS) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        var date = existing?.lastDoneDate ?: System.currentTimeMillis()
        title.setText(existing?.title)
        interval.setText(existing?.intervalValue?.toString())
        unit.setSelection((existing?.unit ?: IntervalUnit.MONTHS).ordinal)
        lastHours.setText((existing?.lastDoneHours ?: node?.runningHours)?.toString())
        instructions.setText(existing?.instructions)
        lastDate.setText(dateFmt.format(Date(date)))
        lastDate.setOnClickListener { pickDate(date) { date = it; lastDate.setText(dateFmt.format(Date(it))) } }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "New maintenance job" else "Edit job")
            .setView(v)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = title.text.toString().trim()
                val n = interval.text.toString().toIntOrNull()
                titleLayout.error = if (t.isEmpty()) "Enter the job" else null
                interval.error = if (n == null || n <= 0) "Required" else null
                if (t.isEmpty() || n == null || n <= 0) return@setOnClickListener
                val u = IntervalUnit.entries[unit.selectedItemPosition]
                val job = (existing ?: Job(PmsStore.newId(), nodeId, t, n, u, date, null, "")).apply {
                    this.title = t
                    intervalValue = n
                    this.unit = u
                    lastDoneDate = date
                    lastDoneHours = if (u == IntervalUnit.HOURS) lastHours.text.toString().toLongOrNull() else lastDoneHours
                    this.instructions = instructions.text.toString().trim()
                }
                store.upsertJob(job)
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
        if (existing == null) title.requestFocus()
    }

    // =====================================================================
    // Work records
    // =====================================================================

    private fun showRecordMenu(rec: WorkRecord, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Edit")
            menu.add(0, 2, 1, "Delete")
            setOnMenuItemClickListener { item ->
                if (item.itemId == 1) {
                    showRecordDialog(rec.nodeId, store.jobs.firstOrNull { it.id == rec.jobId }, rec)
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Delete this work record?")
                        .setMessage(rec.title)
                        .setPositiveButton(R.string.delete) { _, _ -> store.deleteRecord(rec); refresh() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
                true
            }
            show()
        }
    }

    private fun showRecordDialog(nodeId: String, job: Job?, existing: WorkRecord?) {
        val v = layoutInflater.inflate(R.layout.dialog_record, null)
        val node = store.node(nodeId)
        v.findViewById<TextView>(R.id.item_path).text = store.path(nodeId)
        val titleLayout = v.findViewById<TextInputLayout>(R.id.work_title_layout)
        val title = v.findViewById<TextInputEditText>(R.id.work_title)
        val dateField = v.findViewById<TextInputEditText>(R.id.date)
        val doneBy = v.findViewById<TextInputEditText>(R.id.done_by)
        val hours = v.findViewById<TextInputEditText>(R.id.hours)
        val remarks = v.findViewById<TextInputEditText>(R.id.remarks)

        var date = existing?.date ?: System.currentTimeMillis()
        title.setText(existing?.title ?: job?.title)
        dateField.setText(dateFmt.format(Date(date)))
        dateField.setOnClickListener { pickDate(date) { date = it; dateField.setText(dateFmt.format(Date(it))) } }
        doneBy.setText(existing?.doneBy ?: prefs.getString("done_by", ""))
        hours.setText((existing?.hours ?: node?.runningHours)?.toString())
        remarks.setText(existing?.remarks)

        val heading = when {
            existing != null -> "Edit work record"
            job != null -> "Record work done"
            else -> "Log unplanned work"
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(heading)
            .setView(v)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = title.text.toString().trim()
                titleLayout.error = if (t.isEmpty()) "Describe the work" else null
                if (t.isEmpty()) return@setOnClickListener
                val by = doneBy.text.toString().trim()
                prefs.edit().putString("done_by", by).apply()
                val rec = WorkRecord(
                    id = existing?.id ?: PmsStore.newId(),
                    nodeId = nodeId,
                    jobId = job?.id ?: existing?.jobId,
                    title = t,
                    date = date,
                    doneBy = by,
                    hours = hours.text.toString().toLongOrNull(),
                    remarks = remarks.text.toString().trim(),
                )
                store.upsertRecord(rec)
                dialog.dismiss()
                refresh()
                if (job != null && existing == null) {
                    val next = if (job.unit == IntervalUnit.HOURS) "at ${numFmt.format(store.dueHours(job))} h"
                    else dateFmt.format(Date(store.dueDate(job)!!))
                    toast("Saved. Next due $next")
                }
            }
        }
        dialog.show()
    }

    // =====================================================================
    // Documents
    // =====================================================================

    private fun openDoc(doc: Doc) {
        startActivity(Intent(this, PdfViewerActivity::class.java).putExtra(PdfViewerActivity.EXTRA_PATH, doc.file.absolutePath))
    }

    private fun showDocMenu(doc: Doc, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.share)
            menu.add(0, 2, 1, R.string.open_with)
            menu.add(0, 3, 2, "Rename")
            menu.add(0, 4, 3, "Move to another item")
            menu.add(0, 5, 4, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> shareFile(this@MainActivity, doc.file)
                    2 -> openWithOtherApp(this@MainActivity, doc.file)
                    3 -> promptText("Rename", doc.name, "") { name ->
                        if (!store.renameDoc(doc, name)) toast("Could not rename")
                        refreshList()
                    }
                    4 -> chooseNode("Move to") { id ->
                        if (!store.moveDoc(doc, id)) toast("Could not move")
                        refreshList()
                    }
                    5 -> MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Delete “${doc.name}”?")
                        .setPositiveButton(R.string.delete) { _, _ -> doc.file.delete(); refreshList() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
                true
            }
            show()
        }
    }

    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        intent.action = Intent.ACTION_MAIN
        if (uris.isEmpty()) return
        tabs.getTabAt(Tab.DOCS.ordinal)?.select()
        if (store.nodes.isEmpty()) store.addNode(null, "General documents")
        chooseNode("Save ${uris.size} PDF${if (uris.size > 1) "s" else ""} to") { id -> importAll(uris, id) }
    }

    private fun importAll(uris: List<Uri>, nodeId: String) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            var ok = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                uris.forEach { uri -> try { store.importDoc(uri, nodeId); ok++ } catch (e: Exception) { failed++ } }
            }
            progress.visibility = View.GONE
            toast("Saved $ok file${if (ok == 1) "" else "s"} to ${store.node(nodeId)?.name}" + if (failed > 0) " · $failed failed" else "")
            refresh()
        }
    }

    // =====================================================================
    // Backup
    // =====================================================================

    private fun confirmRestore() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore from backup?")
            .setMessage("Everything currently in the app (structure, jobs, work done and documents) will be replaced by the backup file.")
            .setPositiveButton("Choose backup file") { _, _ -> openBackup.launch(arrayOf("*/*")) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runIo(okMsg: String, failMsg: String, block: suspend () -> Unit) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try { block(); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            }
            progress.visibility = View.GONE
            toast(if (error == null) okMsg else "$failMsg: $error")
            refresh()
        }
    }

    // =====================================================================
    // Small helpers
    // =====================================================================

    private fun pickDate(initial: Long, onPicked: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = initial }
        DatePickerDialog(this, { _, y, m, d ->
            onPicked(Calendar.getInstance().apply { set(y, m, d, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun promptText(
        title: String,
        initial: String,
        hint: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
        allowEmpty: Boolean = false,
        onOk: (String) -> Unit,
    ) {
        val input = EditText(this).apply {
            setText(initial)
            this.hint = hint
            this.inputType = inputType
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty() || allowEmpty) onOk(text)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

// ---- Shared helpers (also used by the PDF viewer) ----

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
