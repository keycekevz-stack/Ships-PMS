package com.shipspms.docs

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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

    private enum class Tab(val label: String) { DOCS("Documents"), SCHEDULE("Schedule"), HISTORY("Work done"), CERTS("Certificates"), PARTS("Spare parts"), CONSUMABLES("Consumables") }

    /** "Due by" filters. Each one includes overdue work, so it is the full list of what must be done by then. */
    private enum class DueFilter(val label: String, val maxDays: Long?) {
        ALL("Any time", null),
        OVERDUE("Overdue", -1),
        TODAY("Due today", 0),
        WEEK("Within a week", 7),
        MONTH("Within a month", 30),
        YEAR("Within a year", 365),
        RANGE("Date range…", null),
    }

    /** Frequency groups for the schedule filter. */
    private enum class FreqFilter(val label: String) {
        ALL("All frequencies"), WEEKLY("Daily / weekly"), MONTHLY("Monthly"), QUARTERLY("Quarterly"),
        HALF("Half-yearly"), ANNUAL("Annual"), MULTI("2 years +"), HOURS("Running hours"), ONCE("One-time"),
    }

    /** Columns of the schedule table. */
    private enum class SchedCol(val label: String, val widthDp: Int) {
        STATUS("Status", 96), JOB("Job", 200), UNIT("Unit", 180), FREQ("Frequency", 170),
        LAST("Last done", 120), DUE("Due", 130), LEFT("Left", 120),
    }

    private enum class TypeFilter(val label: String) {
        OPEN("All open jobs"), RECURRING("Recurring"), ONE_TIME("One-time"), DONE("Completed one-time"),
    }

    private enum class CertFilter(val label: String, val maxDays: Long?) {
        ALL("All", null), EXPIRED("Expired / overdue", -1), D30("Within 30 days", 30), D90("Within 90 days", 90),
    }

    private enum class PartFilter(val label: String) {
        ALL("All parts"), LOW("Low stock"), OUT("Out of stock"), UNLINKED("Not linked to a unit"),
    }

    /** A preset maintenance interval for the "How often" dropdown. */
    private data class Freq(val label: String, val value: Int, val unit: IntervalUnit)

    private val calendarFreqs = listOf(
        Freq("Daily", 1, IntervalUnit.DAYS),
        Freq("Weekly", 1, IntervalUnit.WEEKS),
        Freq("Every 2 weeks", 2, IntervalUnit.WEEKS),
        Freq("Monthly", 1, IntervalUnit.MONTHS),
        Freq("Every 2 months", 2, IntervalUnit.MONTHS),
        Freq("Quarterly (every 3 months)", 3, IntervalUnit.MONTHS),
        Freq("Every 4 months", 4, IntervalUnit.MONTHS),
        Freq("Half-yearly (every 6 months)", 6, IntervalUnit.MONTHS),
        Freq("Annually", 1, IntervalUnit.YEARS),
        Freq("Every 18 months", 18, IntervalUnit.MONTHS),
        Freq("Every 2 years", 2, IntervalUnit.YEARS),
        Freq("Every 2½ years (30 months)", 30, IntervalUnit.MONTHS),
        Freq("Every 3 years", 3, IntervalUnit.YEARS),
        Freq("Every 4 years", 4, IntervalUnit.YEARS),
        Freq("Every 5 years", 5, IntervalUnit.YEARS),
        Freq("Every 10 years", 10, IntervalUnit.YEARS),
    )

    private val hourFreqs by lazy {
        listOf(50, 100, 250, 500, 750, 1000, 1500, 2000, 3000, 4000, 5000, 6000, 8000, 10000, 12000, 15000, 16000, 20000, 24000, 30000)
            .map { Freq("Every ${numFmt.format(it)} running hours", it, IntervalUnit.HOURS) }
    }

    private val calendarUnits = listOf(IntervalUnit.DAYS, IntervalUnit.WEEKS, IntervalUnit.MONTHS, IntervalUnit.YEARS)

    private enum class PartCol(val label: String, val widthDp: Int) {
        NAME("Part name", 170), NUMBER("Part no.", 120), STOCK("In stock", 84), MIN("Min.", 64), UNIT("Unit", 60),
        LOCATION("Location", 140), MAKER("Maker / ref.", 130), UNITS("Used on", 220),
    }

    private var partSort = PartCol.NAME
    private var partSortAsc = true
    private lateinit var tableAdapter: TableAdapter
    private lateinit var partsTable: View
    private lateinit var partsHeader: LinearLayout

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
    private lateinit var chips2: ChipGroup
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var fab: ExtendedFloatingActionButton

    private var shipId: String = ""
    private var selectedId: String? = null
    private val expanded = mutableSetOf<String>()
    private var tab = Tab.SCHEDULE
    private var query = ""
    private var dueFilter = DueFilter.ALL
    private var typeFilter = TypeFilter.OPEN
    private var certFilter = CertFilter.ALL
    private var partFilter = PartFilter.ALL
    private var historyJobId: String? = null
    private var pendingImportNode: String? = null
    private val dueChips = mutableMapOf<DueFilter, Chip>()
    private val freqChips = mutableMapOf<FreqFilter, Chip>()
    private var freqFilter = FreqFilter.ALL
    private var rangeFrom: Long? = null
    private var rangeTo: Long? = null
    private var schedSort = SchedCol.DUE
    private var schedSortAsc = true
    private var tableKey: String? = null
    private lateinit var chips3: ChipGroup
    private lateinit var tableList: RecyclerView
    private var pendingPdf: File? = null

    private val annotate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) session?.render()
    }

    private val savePdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = pendingPdf
        if (uri != null && f != null) runIo("PDF saved", "Save failed") {
            contentResolver.openOutputStream(uri).use { out -> f.inputStream().use { it.copyTo(requireNotNull(out)) } }
        }
    }

    /** Photos / files being added in the dialog that is currently open. */
    private var session: FileSession? = null
    private var cameraFile: File? = null

    private val dateFmt: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    private val numFmt: NumberFormat = NumberFormat.getIntegerInstance()

    private val prefs by lazy { getSharedPreferences("ui", Context.MODE_PRIVATE) }

    private val pickPdfs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val node = pendingImportNode
        if (uris.isNotEmpty() && node != null) importAll(uris, node)
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null && f.length() > 0) {
            addToSession(listOf(uriFor(this, f))) { f.delete() }
        }
    }

    private val pickImages = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) addToSession(uris)
    }

    private val pickAttachments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addToSession(uris)
    }

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runIo("Backup saved", "Backup failed") {
            contentResolver.openOutputStream(uri).use { out -> store.backup(requireNotNull(out)) }
        }
    }

    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runIo("Backup restored", "Restore failed") {
            contentResolver.openInputStream(uri).use { input -> store.restore(requireNotNull(input)) }
            withContext(Dispatchers.Main) { selectedId = null; expanded.clear(); shipId = store.ships.first().id }
        }
    }

    // =====================================================================
    // Setup
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = PmsStore.get(this)

        shipId = prefs.getString("ship", null)?.takeIf { store.ship(it) != null } ?: store.ships.first().id
        selectedId = prefs.getString("selected", null)?.takeIf { store.node(it)?.shipId == shipId }
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
            if (store.nodes.isEmpty() && !prefs.getBoolean("seed_asked", false)) welcome()
            else if (!prefs.getBoolean("ship_name_asked", false) && store.ships.size == 1 && ship().name == "My Ship") {
                prefs.edit().putBoolean("ship_name_asked", true).apply()
                editShip(ship(), firstTime = true)
            }
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
            .putString("ship", shipId)
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
        chips2 = findViewById(R.id.chips2)
        chips3 = findViewById(R.id.chips3)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        progress = findViewById(R.id.progress)
        fab = findViewById(R.id.fab)
    }

    private fun setupToolbar() {
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.menu.findItem(R.id.action_share_list)?.icon?.setTint(getColor(android.R.color.white))
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_share_list -> shareCurrentList()
                R.id.action_pdf -> pdfOfCurrentList()
                R.id.action_backup -> {
                    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    createBackup.launch("ShipPMS-backup-$stamp.zip")
                }
                R.id.action_restore -> confirmRestore()
                R.id.action_example -> if (canEdit()) { store.seedExample(shipId); refresh(); drawer?.openDrawer(GravityCompat.START) }
                R.id.action_ships -> showShips()
                R.id.action_edit_ship -> if (canEdit()) editShip(ship())
                R.id.action_lock -> if (ship().locked) unlockShip(ship()) else lockShip(ship())
                R.id.action_delete_ship -> if (canEdit()) confirmDeleteShip(ship())
            }
            true
        }
        toolbar.setOnClickListener { showShips() }
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
            if (!canEdit()) return@setOnClickListener
            promptText("New main group", "", "e.g. Engine Room, Deck Machinery") { name ->
                val n = store.addNode(null, name, shipId)
                selectNode(n, closeDrawer = false)
            }
        }
    }

    private fun setupTabs() {
        Tab.entries.forEach { tabs.addTab(tabs.newTab().setText(it.label), false) }
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

    private fun openPayload(payload: Any) {
        when (payload) {
            is Doc -> openFiles(listOf(payload.file), 0, payload.name)
            is Job -> showJobDetails(payload)
            is WorkRecord -> if (ship().locked) showRecordDetails(payload)
                else showRecordDialog(payload.nodeId, store.jobs.firstOrNull { it.id == payload.jobId }, payload)
            is Certificate -> if (ship().locked) showCertDetails(payload) else showCertDialog(payload, payload.nodeId)
            is SparePart -> showPartDetails(payload)
        }
    }

    private fun morePayload(payload: Any, anchor: View) {
        when (payload) {
            is Doc -> showDocMenu(payload, anchor)
            is Job -> showJobMenu(payload)
            is WorkRecord -> showRecordMenu(payload, anchor)
            is Certificate -> showCertMenu(payload, anchor)
            is SparePart -> showPartMenu(payload, anchor)
        }
    }

    private fun setupList() {
        rowAdapter = RowAdapter(onClick = ::openPayload, onMore = ::morePayload)
        list.layoutManager = LinearLayoutManager(this)
        list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        list.adapter = rowAdapter

        // Tables (schedule, spare parts, consumables)
        partSort = PartCol.entries.getOrElse(prefs.getInt("part_sort", 0)) { PartCol.NAME }
        partSortAsc = prefs.getBoolean("part_sort_asc", true)
        schedSort = SchedCol.entries.getOrElse(prefs.getInt("sched_sort", SchedCol.DUE.ordinal)) { SchedCol.DUE }
        schedSortAsc = prefs.getBoolean("sched_sort_asc", true)
        partsTable = findViewById(R.id.parts_table)
        partsHeader = findViewById(R.id.parts_header)
        tableList = findViewById(R.id.parts_list)
        tableList.layoutManager = LinearLayoutManager(this)
        tableAdapter = TableAdapter(emptyList(), {}, { _, _ -> })
    }

    /** Sets up the table for a set of columns (only rebuilt when the columns change). */
    private fun configureTable(key: String, widthsDp: List<Int>) {
        if (tableKey == key) return
        tableKey = key
        val d = resources.displayMetrics.density
        val widths = widthsDp.map { (it * d).toInt() }
        tableAdapter = TableAdapter(widths, onClick = ::openPayload, onLongClick = ::morePayload)
        tableList.adapter = tableAdapter
        tableList.layoutParams = tableList.layoutParams.apply { width = widths.sum() }
    }

    /** Column headings; tap one to sort by it, tap again to reverse. */
    private fun renderTableHeader(labels: List<String>, widthsDp: List<Int>, sortIdx: Int, asc: Boolean, onSort: (Int) -> Unit) {
        partsHeader.removeAllViews()
        val density = resources.displayMetrics.density
        labels.forEachIndexed { i, label ->
            partsHeader.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams((widthsDp[i] * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                setPadding((10 * density).toInt(), (12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt())
                text = label + if (i == sortIdx) (if (asc) "  ▲" else "  ▼") else ""
                setTypeface(null, android.graphics.Typeface.BOLD)
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { onSort(i) }
            })
        }
    }

    private fun welcome() {
        prefs.edit().putBoolean("seed_asked", true).putBoolean("ship_name_asked", true).apply()
        editShip(ship(), firstTime = true) { askSeed() }
    }

    private fun askSeed() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Set up ${ship().name}")
            .setMessage(
                "Build the ship structure on the left: main groups (Engine Room), sub-groups (Main Engine) " +
                    "and items (Fuel Pump). Then add documents, maintenance jobs, work done and certificates.\n\n" +
                    "Start with an example structure you can edit, or start empty?"
            )
            .setPositiveButton("Use example") { _, _ ->
                store.seedExample(shipId); refresh(); drawer?.openDrawer(GravityCompat.START)
            }
            .setNegativeButton("Start empty") { _, _ -> drawer?.openDrawer(GravityCompat.START) }
            .show()
    }

    // =====================================================================
    // Refresh
    // =====================================================================

    private fun ship(): Ship = store.ship(shipId) ?: store.ships.first().also { shipId = it.id }

    private fun refresh() {
        if (selectedId != null && store.node(selectedId)?.shipId != ship().id) selectedId = null
        refreshShip(); refreshTree(); refreshHeader(); refreshChips(); refreshFab(); refreshList()
    }

    private fun refreshShip() {
        val s = ship()
        toolbar.title = (if (s.locked) "🔒 " else "") + s.name + "  ▾"
        toolbar.subtitle = listOfNotNull(
            s.imo.takeIf { it.isNotBlank() }?.let { "IMO $it" },
            if (s.locked) "Locked – read only" else null,
            if (store.ships.size > 1) "${store.ships.size} ships" else null,
        ).joinToString(" · ").ifEmpty { null }
        toolbar.menu.findItem(R.id.action_lock)?.title = if (s.locked) "Unlock this ship" else "Lock this ship"
    }

    private fun refreshTree() {
        val overdue = store.nodesWithOverdue(shipId)
        val s = ship()
        val rows = mutableListOf(
            TreeRow(null, 0, false, false, selectedId == null, overdue.isNotEmpty(), (if (s.locked) "🔒 " else "") + s.name)
        )
        fun add(parentId: String?, depth: Int) {
            store.children(parentId, shipId).forEach { n ->
                val hasKids = store.nodes.any { it.parentId == n.id }
                val open = n.id in expanded
                rows += TreeRow(n, depth, hasKids, open, n.id == selectedId, n.id in overdue)
                if (hasKids && open) add(n.id, depth + 1)
            }
        }
        add(null, 0)
        treeAdapter.submitList(rows)
    }

    private fun certsInScope(): List<Certificate> {
        if (selectedId == null) return store.certs.filter { it.shipId == shipId }
        val ids = store.subtreeIds(selectedId, shipId)
        return store.certs.filter { it.nodeId in ids }
    }

    private fun refreshHeader() {
        val node = store.node(selectedId)
        val ids = store.subtreeIds(selectedId, shipId)
        val jobs = store.jobs.filter { it.nodeId in ids }
        val overdue = jobs.count { store.status(it) == JobStatus.OVERDUE }
        val soon = jobs.count { store.status(it) == JobStatus.DUE_SOON }
        val certAlerts = certsInScope().count { store.certStatus(it) != CertStatus.OK }
        val certBad = certsInScope().count {
            store.certStatus(it).let { s -> s == CertStatus.EXPIRED || s == CertStatus.SURVEY_OVERDUE }
        }

        headerPath.text = if (node == null) ship().name else ship().name + " › " + store.path(node.id)
        val parts = mutableListOf<String>()
        if (node == null) {
            parts += "${ids.size} items"
        } else if (node.runningHours != null) {
            parts += "${numFmt.format(node.runningHours)} running hours" +
                (node.hoursUpdated?.let { " (${dateFmt.format(Date(it))})" } ?: "")
        }
        parts += "${jobs.count { store.status(it) != JobStatus.DONE }} open jobs"
        if (overdue > 0) parts += "$overdue overdue"
        if (soon > 0) parts += "$soon due soon"
        if (certAlerts > 0) parts += "$certAlerts certificate alerts"
        val lowParts = partsInScope(false).count { store.partStatus(it) != PartStatus.OK }
        val lowCons = partsInScope(true).count { store.partStatus(it) != PartStatus.OK }
        if (lowParts > 0) parts += "$lowParts spare parts low/out"
        if (lowCons > 0) parts += "$lowCons consumables low/out"
        headerInfo.text = parts.joinToString(" · ")
        headerHours.visibility = if (node == null || ship().locked) View.GONE else View.VISIBLE
        if (ship().locked) parts.add(0, "🔒 Locked")
        headerInfo.text = parts.joinToString(" · ")

        tabs.getTabAt(Tab.SCHEDULE.ordinal)?.orCreateBadge?.apply { isVisible = overdue > 0; number = overdue }
        tabs.getTabAt(Tab.CERTS.ordinal)?.orCreateBadge?.apply { isVisible = certBad > 0; number = certBad }
        tabs.getTabAt(Tab.PARTS.ordinal)?.orCreateBadge?.apply { isVisible = lowParts > 0; number = lowParts }
        tabs.getTabAt(Tab.CONSUMABLES.ordinal)?.orCreateBadge?.apply { isVisible = lowCons > 0; number = lowCons }
    }

    private fun <T> fillChips(group: ChipGroup, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit): Map<T, Chip> {
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
        group.isSingleSelection = true
        group.isSelectionRequired = true
        val map = mutableMapOf<T, Chip>()
        options.forEach { opt ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = label(opt)
                isCheckable = true
                isChecked = opt == selected
            }
            map[opt] = chip
            group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { _, ids ->
            map.entries.firstOrNull { it.value.id == ids.firstOrNull() }?.let { onPick(it.key) }
        }
        return map
    }

    private fun clearChips(group: ChipGroup) {
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
    }

    private fun refreshChips() {
        clearChips(chips); clearChips(chips2); clearChips(chips3)
        dueChips.clear(); freqChips.clear()
        when (tab) {
            Tab.SCHEDULE -> {
                fillChips(chips, TypeFilter.entries, typeFilter, { it.label }) {
                    typeFilter = it
                    chips.post { refreshChips(); refreshList() }
                }
                if (typeFilter != TypeFilter.DONE) {
                    dueChips += fillChips(chips2, DueFilter.entries, dueFilter, { dueLabel(it) }) { f ->
                        if (f == DueFilter.RANGE) pickRange() else { dueFilter = f; refreshList() }
                    }
                    // Tapping the date range again lets you change it.
                    dueChips[DueFilter.RANGE]?.setOnClickListener { if (dueFilter == DueFilter.RANGE) pickRange() }
                }
                freqChips += fillChips(chips3, FreqFilter.entries, freqFilter, { it.label }) { f ->
                    freqFilter = f; refreshList()
                }
            }
            Tab.CERTS -> fillChips(chips, CertFilter.entries, certFilter, { it.label }) {
                certFilter = it; refreshList()
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
            Tab.PARTS, Tab.CONSUMABLES -> fillChips(chips, PartFilter.entries, partFilter, { it.label }) {
                partFilter = it; refreshList()
            }
            Tab.DOCS -> {}
        }
        (chips.parent as View).visibility = if (chips.childCount > 0) View.VISIBLE else View.GONE
        (chips2.parent as View).visibility = if (chips2.childCount > 0) View.VISIBLE else View.GONE
        (chips3.parent as View).visibility = if (chips3.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun refreshFab() {
        fab.visibility = if (ship().locked) View.GONE else View.VISIBLE
        fab.text = when (tab) {
            Tab.DOCS -> getString(R.string.upload_pdf)
            Tab.SCHEDULE -> getString(R.string.add_job)
            Tab.HISTORY -> getString(R.string.log_work)
            Tab.CERTS -> getString(R.string.add_cert)
            Tab.PARTS -> getString(R.string.add_part)
            Tab.CONSUMABLES -> "Add consumable"
        }
    }

    private fun matches(vararg fields: String?): Boolean {
        if (query.isEmpty()) return true
        val hay = fields.filterNotNull().joinToString(" ").lowercase()
        return query.lowercase().split(Regex("\\s+")).all { hay.contains(it) }
    }

    private fun matchesType(j: Job) = when (typeFilter) {
        TypeFilter.OPEN -> store.status(j) != JobStatus.DONE
        TypeFilter.RECURRING -> j.recurring
        TypeFilter.ONE_TIME -> !j.recurring && j.completed == null
        TypeFilter.DONE -> !j.recurring && j.completed != null
    }

    private fun matchesDue(j: Job, f: DueFilter): Boolean = when {
        typeFilter == TypeFilter.DONE || f == DueFilter.ALL -> true
        f == DueFilter.OVERDUE -> store.status(j) == JobStatus.OVERDUE
        f == DueFilter.RANGE -> {
            val from = rangeFrom; val to = rangeTo
            if (from == null || to == null) true
            else store.daysLeft(j)?.let { it in PmsStore.daysUntil(from)..PmsStore.daysUntil(to) } == true
        }
        else -> store.daysLeft(j)?.let { it <= f.maxDays!! } == true
    }

    private fun refreshList() {
        val ids = store.subtreeIds(selectedId, shipId)
        val items: List<RowItem> = when (tab) {
            Tab.DOCS -> store.docs(ids)
                .filter { matches(it.name, store.path(it.nodeId)) }
                .sortedBy { it.name.lowercase() }
                .map { d ->
                    RowItem(
                        key = d.file.absolutePath,
                        icon = if (d.isVideo) R.drawable.ic_video else R.drawable.ic_pdf,
                        iconTint = if (d.isVideo) getColor(R.color.sea) else null,
                        title = d.name,
                        line2 = store.path(d.nodeId),
                        line3 = android.text.format.Formatter.formatShortFileSize(this, d.file.length()) +
                            " · added " + dateFmt.format(Date(d.file.lastModified())),
                        payload = d,
                    )
                }

            Tab.SCHEDULE -> emptyList()

            Tab.HISTORY -> historyRecords()
                .map { r ->
                    val photos = store.photos(r.id).size
                    val meta = listOfNotNull(
                        dateFmt.format(Date(r.date)),
                        r.doneBy.takeIf { it.isNotBlank() }?.let { "by $it" },
                        r.hours?.let { "at ${numFmt.format(it)} h" },
                        if (photos > 0) "📷 $photos" else null,
                        if (r.partsUsed.isNotEmpty()) "🔩 ${r.partsUsed.sumOf { it.qty }} parts" else null,
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

            Tab.PARTS, Tab.CONSUMABLES -> emptyList()

            Tab.CERTS -> certsInScope()
                .filter { c ->
                    val days = store.certNextDate(c)?.let { PmsStore.daysUntil(it) }
                    when (certFilter) {
                        CertFilter.ALL -> true
                        CertFilter.EXPIRED -> store.certStatus(c).let { it == CertStatus.EXPIRED || it == CertStatus.SURVEY_OVERDUE }
                        else -> days != null && days <= certFilter.maxDays!!
                    }
                }
                .filter { matches(it.name, it.number, it.issuedBy, it.remarks, store.path(it.nodeId)) }
                .sortedWith(compareBy(nullsLast<Long>()) { store.certNextDate(it) })
                .map { c ->
                    val status = store.certStatus(c)
                    val color = when (status) {
                        CertStatus.EXPIRED, CertStatus.SURVEY_OVERDUE -> getColor(R.color.status_overdue)
                        CertStatus.DUE_SOON -> getColor(R.color.status_soon)
                        CertStatus.OK -> getColor(R.color.status_ok)
                    }
                    val files = store.certFiles(c.id).size
                    RowItem(
                        key = c.id, icon = R.drawable.ic_cert, iconTint = color,
                        title = c.name,
                        line2 = listOfNotNull(
                            if (c.nodeId == null) "Ship certificate" else store.path(c.nodeId),
                            c.number.takeIf { it.isNotBlank() }?.let { "No. $it" },
                            if (files > 0) "📎 $files" else null,
                        ).joinToString(" · "),
                        line3 = describeCert(c),
                        badge = when (status) {
                            CertStatus.EXPIRED -> "EXPIRED"; CertStatus.SURVEY_OVERDUE -> "SURVEY OVERDUE"
                            CertStatus.DUE_SOON -> "DUE SOON"; CertStatus.OK -> null
                        },
                        color = color,
                        payload = c,
                    )
                }
        }
        rowAdapter.submitList(items)
        val tableRows: List<TableRow>? = when (tab) {
            Tab.SCHEDULE -> buildScheduleRows()
            Tab.PARTS -> buildPartRows(false)
            Tab.CONSUMABLES -> buildPartRows(true)
            else -> null
        }
        tableAdapter.submitList(tableRows.orEmpty())
        list.visibility = if (tableRows != null) View.GONE else View.VISIBLE
        partsTable.visibility = if (!tableRows.isNullOrEmpty()) View.VISIBLE else View.GONE

        val shown = tableRows?.size ?: items.size
        empty.visibility = if (shown == 0) View.VISIBLE else View.GONE
        empty.text = when {
            store.subtreeIds(null, shipId).isEmpty() && tab != Tab.CERTS -> "Start by adding your machinery in the Ship structure (☰ menu, top left)."
            query.isNotEmpty() -> "Nothing matches “$query”."
            tab == Tab.DOCS -> "No documents here yet.\nTap “Upload PDF / video” to add manuals, drawings or instruction videos."
            tab == Tab.SCHEDULE && (dueFilter != DueFilter.ALL || typeFilter != TypeFilter.OPEN) -> "No jobs in this list. 👍"
            tab == Tab.SCHEDULE -> "No planned maintenance yet.\nTap “Add job”: recurring (e.g. “Clean filters every 3 months”) or one-time (e.g. a defect found during inspection)."
            tab == Tab.SCHEDULE && freqFilter != FreqFilter.ALL -> "No jobs in this list. 👍"
            tab == Tab.CONSUMABLES && (partFilter != PartFilter.ALL) -> "No consumables in this list. 👍"
            tab == Tab.CONSUMABLES -> "No consumables here yet.\nTap “Add consumable” (e.g. lub oil, chemicals, rags, gasket sheet).\nTip: select the ship at the top of the tree to see all consumables."
            tab == Tab.PARTS && (partFilter != PartFilter.ALL) -> "No spare parts in this list. 👍"
            tab == Tab.PARTS -> "No spare parts here yet.\nTap “Add spare part”. One part can be linked to several units, e.g. the same filter on all generators."
            tab == Tab.CERTS && certFilter != CertFilter.ALL -> "No certificates in this list. 👍"
            tab == Tab.CERTS -> "No certificates yet.\nTap “Add certificate” and enter its expiry and survey dates. You can attach a scan or photo."
            else -> "No work recorded yet.\nOpen a job in Schedule and tap “Record work done”, or tap “Log work” for unplanned work."
        }
    }

    private fun statusColor(s: JobStatus) = getColor(
        when (s) {
            JobStatus.OVERDUE -> R.color.status_overdue
            JobStatus.DUE_SOON -> R.color.status_soon
            JobStatus.OK -> R.color.status_ok
            JobStatus.DONE -> R.color.sea
        }
    )

    private fun inDays(days: Long) = when {
        days > 1 -> "in $days days"
        days == 1L -> "tomorrow"
        days == 0L -> "today"
        days == -1L -> "1 day overdue"
        else -> "${-days} days overdue"
    }

    private fun describeJob(j: Job): String {
        if (!j.recurring) {
            j.completed?.let { return "One-time · Completed ${dateFmt.format(Date(it))}" }
            val due = j.dueOnce ?: return "One-time · No due date"
            return "One-time · Due ${dateFmt.format(Date(due))} (${inDays(PmsStore.daysUntil(due))})"
        }
        val every = freqLabel(j.intervalValue, j.unit)
        return if (j.unit == IntervalUnit.HOURS) {
            val last = j.lastDoneHours?.let { "Last at ${numFmt.format(it)} h" } ?: "Last ${dateFmt.format(Date(j.lastDoneDate))}"
            val due = "Due at ${numFmt.format(store.dueHours(j))} h"
            val left = store.hoursLeft(j)?.let { if (it >= 0) "${numFmt.format(it)} h left" else "${numFmt.format(-it)} h over" }
                ?: "enter running hours"
            "$every · $last\n$due · $left"
        } else {
            val due = store.dueDate(j)!!
            "$every · Last ${dateFmt.format(Date(j.lastDoneDate))}\nDue ${dateFmt.format(Date(due))} (${inDays(PmsStore.daysUntil(due))})"
        }
    }

    private fun describeCert(c: Certificate): String {
        val parts = mutableListOf<String>()
        val exp = c.expiryDate
        if (exp != null) parts.add("Expires ${dateFmt.format(Date(exp))} (${inDays(PmsStore.daysUntil(exp))})")
        else parts.add("No expiry date")
        c.surveyDate?.let { parts.add("Survey ${dateFmt.format(Date(it))} (${inDays(PmsStore.daysUntil(it))})") }
        return parts.joinToString("\n")
    }

    // =====================================================================
    // Tree actions
    // =====================================================================

    private fun selectNode(node: Node?, closeDrawer: Boolean = true) {
        selectedId = node?.id
        historyJobId = null
        if (node != null) {
            var p = store.node(node.parentId)
            while (p != null) { expanded += p.id; p = store.node(p.parentId) }
        }
        val isLeaf = node == null || store.nodes.none { it.parentId == node.id }
        if (node != null && !isLeaf) expanded += node.id
        refresh()
        if (closeDrawer && isLeaf) drawer?.closeDrawer(GravityCompat.START)
    }

    private fun showNodeMenu(node: Node, anchor: View) {
        if (!canEdit()) return
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Add sub-item")
            menu.add(0, 2, 1, "Rename")
            menu.add(0, 3, 2, "Running hours")
            menu.add(0, 4, 3, "Delete")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> promptText("Add under “${node.name}”", "", "e.g. Main Engine, Fuel Pump No.1") { name ->
                        store.addNode(node.id, name, shipId)
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
        if (!canEdit()) return
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
        val ids = store.subtreeIds(node.id, shipId)
        val sub = ids.size - 1
        val jobs = store.jobs.count { it.nodeId in ids }
        val recs = store.records.count { it.nodeId in ids }
        val docs = store.docs(ids).size
        val certs = store.certs.count { it.nodeId in ids }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete “${node.name}”?")
            .setMessage("This also deletes $sub sub-items, $jobs jobs, $recs work records, $docs documents and $certs certificates. This cannot be undone.")
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
            store.subtreeIds(null, shipId).isEmpty() -> MaterialAlertDialogBuilder(this)
                .setMessage("First add your machinery in the Ship structure on the left.")
                .setPositiveButton(R.string.ok) { _, _ -> drawer?.openDrawer(GravityCompat.START) }
                .show()
            else -> chooseNode(title, action)
        }
    }

    private fun chooseNode(title: String, action: (String) -> Unit) {
        val ordered = mutableListOf<Pair<Node, Int>>()
        fun walk(parent: String?, depth: Int) {
            store.children(parent, shipId).forEach { ordered += it to depth; walk(it.id, depth + 1) }
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
        if (!canEdit()) return
        when (tab) {
            Tab.DOCS -> withItem("Upload to which item?") { id ->
                pendingImportNode = id
                pickPdfs.launch(arrayOf("application/pdf", "video/*"))
            }
            Tab.SCHEDULE -> withItem("Add job to which item?") { id -> showJobDialog(null, id) }
            Tab.HISTORY -> withItem("Log work on which item?") { id -> showRecordDialog(id, null, null) }
            Tab.CERTS -> showCertDialog(null, selectedId)
            Tab.PARTS -> showPartDialog(null, consumable = false)
            Tab.CONSUMABLES -> showPartDialog(null, consumable = true)
        }
    }

    // =====================================================================
    // Jobs
    // =====================================================================

    private fun showJobDetails(job: Job) {
        val done = store.status(job) == JobStatus.DONE
        MaterialAlertDialogBuilder(this)
            .setTitle(job.title)
            .setMessage(store.path(job.nodeId) + "\n" + describeJob(job) +
                if (job.instructions.isNotBlank()) "\n\n${job.instructions}" else "")
            .setPositiveButton(if (done) "Reopen" else "✔ Record work done") { _, _ ->
                if (canEdit()) { if (done) reopen(job) else showRecordDialog(job.nodeId, job, null) }
            }
            .setNeutralButton("More…") { _, _ -> showJobMenu(job) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun reopen(job: Job) {
        job.completed = null
        store.upsertJob(job)
        refresh()
    }

    private fun showJobMenu(job: Job) {
        val done = store.status(job) == JobStatus.DONE
        val options = arrayOf(if (done) "Reopen job" else "✔ Record work done", "Edit job", "Work history", "Delete job")
        MaterialAlertDialogBuilder(this)
            .setTitle(job.title)
            .setItems(options) { _, which ->
                if (which != 2 && !canEdit()) return@setItems
                when (which) {
                    0 -> if (done) reopen(job) else showRecordDialog(job.nodeId, job, null)
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
            .show()
    }

    /** "Quarterly (every 3 months)", "Every 4,000 running hours", or e.g. "Every 7 weeks" for custom intervals. */
    private fun freqLabel(value: Int, unit: IntervalUnit): String =
        (if (unit == IntervalUnit.HOURS) hourFreqs else calendarFreqs)
            .firstOrNull { it.value == value && it.unit == unit }?.label
            ?: if (unit == IntervalUnit.HOURS) "Every ${numFmt.format(value)} running hours"
            else "Every $value ${if (value == 1) unit.short.removeSuffix("s") else unit.short}"

    private fun showJobDialog(existing: Job?, nodeId: String) {
        val v = layoutInflater.inflate(R.layout.dialog_job, null)
        val node = store.node(nodeId)
        v.findViewById<TextView>(R.id.item_path).text = store.path(nodeId)
        val titleLayout = v.findViewById<TextInputLayout>(R.id.job_title_layout)
        val title = v.findViewById<TextInputEditText>(R.id.job_title)
        val repeatField = v.findViewById<MaterialAutoCompleteTextView>(R.id.repeat_type)
        val freqLayout = v.findViewById<TextInputLayout>(R.id.frequency_layout)
        val freqField = v.findViewById<MaterialAutoCompleteTextView>(R.id.frequency)
        val customGroup = v.findViewById<View>(R.id.custom_group)
        val intervalLayout = v.findViewById<TextInputLayout>(R.id.interval_layout)
        val interval = v.findViewById<TextInputEditText>(R.id.interval)
        val customUnitLayout = v.findViewById<TextInputLayout>(R.id.custom_unit_layout)
        val customUnitField = v.findViewById<MaterialAutoCompleteTextView>(R.id.custom_unit)
        val lastDateLayout = v.findViewById<TextInputLayout>(R.id.last_date_layout)
        val lastDate = v.findViewById<TextInputEditText>(R.id.last_date)
        val lastHoursLayout = v.findViewById<TextInputLayout>(R.id.last_hours_layout)
        val lastHours = v.findViewById<TextInputEditText>(R.id.last_hours)
        val dueLayout = v.findViewById<TextInputLayout>(R.id.due_once_layout)
        val dueField = v.findViewById<TextInputEditText>(R.id.due_once)
        val instructions = v.findViewById<TextInputEditText>(R.id.instructions)

        // 0 = one-time, 1 = calendar, 2 = running hours
        val repeatLabels = arrayOf("One-time (not recurring)", "Recurring – by calendar", "Recurring – by running hours")
        var repeat = when {
            existing == null -> 1
            !existing.recurring -> 0
            existing.unit == IntervalUnit.HOURS -> 2
            else -> 1
        }
        fun presets() = if (repeat == 2) hourFreqs else calendarFreqs
        // Index into presets(); presets().size means "Custom".
        var freqIndex = 0
        var customUnit = IntervalUnit.MONTHS
        fun initFreq(value: Int?, unit: IntervalUnit?) {
            val list = presets()
            val i = list.indexOfFirst { it.value == value && it.unit == unit }
            freqIndex = when {
                i >= 0 -> i
                value != null && unit != null && (unit == IntervalUnit.HOURS) == (repeat == 2) -> list.size
                repeat == 2 -> list.indexOfFirst { it.value == 4000 }.coerceAtLeast(0)
                else -> list.indexOfFirst { it.label == "Monthly" }.coerceAtLeast(0)
            }
            if (freqIndex == list.size) {
                interval.setText(value?.toString())
                if (unit != null && unit != IntervalUnit.HOURS) customUnit = unit
            }
        }
        initFreq(existing?.takeIf { it.recurring }?.intervalValue, existing?.takeIf { it.recurring }?.unit)

        fun updateUi() {
            val list = presets()
            freqField.setSimpleItems((list.map { it.label } + "Custom…").toTypedArray())
            freqField.setText(if (freqIndex >= list.size) "Custom…" else list[freqIndex].label, false)
            val custom = repeat != 0 && freqIndex >= list.size
            freqLayout.visibility = if (repeat == 0) View.GONE else View.VISIBLE
            customGroup.visibility = if (custom) View.VISIBLE else View.GONE
            customUnitLayout.visibility = if (repeat == 1) View.VISIBLE else View.GONE
            intervalLayout.hint = if (repeat == 2) "Every … running hours" else "Every"
            lastDateLayout.visibility = if (repeat == 0) View.GONE else View.VISIBLE
            lastHoursLayout.visibility = if (repeat == 2) View.VISIBLE else View.GONE
            dueLayout.visibility = if (repeat == 0) View.VISIBLE else View.GONE
        }

        repeatField.setSimpleItems(repeatLabels)
        repeatField.setText(repeatLabels[repeat], false)
        repeatField.setOnItemClickListener { _, _, pos, _ ->
            if (pos != repeat) {
                repeat = pos
                if (repeat != 0) initFreq(null, null)
                updateUi()
            }
        }
        freqField.setOnItemClickListener { _, _, pos, _ -> freqIndex = pos; updateUi() }
        customUnitField.setSimpleItems(calendarUnits.map { it.label }.toTypedArray())
        customUnitField.setText(customUnit.label, false)
        customUnitField.setOnItemClickListener { _, _, pos, _ -> customUnit = calendarUnits[pos] }

        var date = existing?.lastDoneDate ?: System.currentTimeMillis()
        var due = existing?.dueOnce ?: (PmsStore.startOfToday() + 7 * PmsStore.DAY + 12 * 3600_000L)
        title.setText(existing?.title)
        lastHours.setText((existing?.lastDoneHours ?: node?.runningHours)?.toString())
        instructions.setText(existing?.instructions)
        lastDate.setText(dateFmt.format(Date(date)))
        lastDate.setOnClickListener { pickDate(date) { date = it!!; lastDate.setText(dateFmt.format(Date(date))) } }
        dueField.setText(dateFmt.format(Date(due)))
        dueField.setOnClickListener { pickDate(due) { due = it!!; dueField.setText(dateFmt.format(Date(due))) } }
        updateUi()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "New maintenance job" else "Edit job")
            .setView(v)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = title.text.toString().trim()
                titleLayout.error = if (t.isEmpty()) "Enter the job" else null
                val list = presets()
                val custom = repeat != 0 && freqIndex >= list.size
                val n: Int
                val u: IntervalUnit
                if (repeat == 0) { n = 1; u = IntervalUnit.MONTHS }
                else if (custom) {
                    n = interval.text.toString().toIntOrNull() ?: 0
                    u = if (repeat == 2) IntervalUnit.HOURS else customUnit
                } else { n = list[freqIndex].value; u = list[freqIndex].unit }
                intervalLayout.error = if (custom && n <= 0) "Required" else null
                if (t.isEmpty() || (custom && n <= 0)) return@setOnClickListener

                val job = (existing ?: Job(PmsStore.newId(), nodeId, t, n, u, date, null, "")).apply {
                    this.title = t
                    this.recurring = repeat != 0
                    this.instructions = instructions.text.toString().trim()
                    if (repeat != 0) {
                        intervalValue = n
                        unit = u
                        lastDoneDate = date
                        lastDoneHours = if (u == IntervalUnit.HOURS) lastHours.text.toString().toLongOrNull() else lastDoneHours
                        completed = null
                    } else {
                        dueOnce = due
                    }
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
    // Work records (photos required)
    // =====================================================================

    private fun showRecordMenu(rec: WorkRecord, anchor: View) {
        PopupMenu(this, anchor).apply {
            val photos = store.photos(rec.id)
            if (photos.isNotEmpty()) menu.add(0, 3, 0, "View photos (${photos.size})")
            menu.add(0, 4, 0, "PDF report (print / save / share)")
            menu.add(0, 1, 1, "Edit")
            menu.add(0, 2, 2, "Delete")
            setOnMenuItemClickListener { item ->
                if (item.itemId == 4) { recordPdf(rec); return@setOnMenuItemClickListener true }
                if (item.itemId != 3 && !canEdit()) return@setOnMenuItemClickListener true
                when (item.itemId) {
                    3 -> openFiles(photos, 0, rec.title)
                    1 -> showRecordDialog(rec.nodeId, store.jobs.firstOrNull { it.id == rec.jobId }, rec)
                    2 -> MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Delete this work record?")
                        .setMessage(rec.title + "\nIts photos are deleted too.")
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
        val partsGroup = v.findViewById<ChipGroup>(R.id.parts_used)

        val recordId = existing?.id ?: PmsStore.newId()
        val used = existing?.partsUsed.orEmpty().toMutableList()
        val originallyUsed = existing?.partsUsed.orEmpty()
        fun renderParts() {
            partsGroup.removeAllViews()
            used.forEach { u ->
                val part = store.part(u.partId)
                partsGroup.addView(Chip(this).apply {
                    text = "${u.qty} × ${part?.name ?: "deleted part"}"
                    isCloseIconVisible = true
                    setOnCloseIconClickListener { used.remove(u); renderParts() }
                })
            }
        }
        renderParts()
        v.findViewById<View>(R.id.add_part_used).setOnClickListener {
            choosePartForUnit(nodeId) { part ->
                // Stock available for this record = current stock + what this record had already taken out.
                val available = part.quantity + originallyUsed.filter { it.partId == part.id }.sumOf { it.qty } -
                    used.filter { it.partId == part.id }.sumOf { it.qty }
                promptText("How many “${part.name}” used?", "1", "${available} ${part.unit} available", InputType.TYPE_CLASS_NUMBER) { t ->
                    val q = t.toIntOrNull() ?: 0
                    when {
                        q <= 0 -> toast("Enter a quantity")
                        q > available -> toast("Only $available ${part.unit} in stock. Receive stock first (Spare parts tab).")
                        else -> { used += PartUse(part.id, q); renderParts() }
                    }
                }
            }
        }
        val photos = FileSession(v, store.photoDir(recordId), store.photos(recordId), "Photos of the work (required)", allowPdf = false, title = "Work photos")

        var date = existing?.date ?: System.currentTimeMillis()
        title.setText(existing?.title ?: job?.title)
        dateField.setText(dateFmt.format(Date(date)))
        dateField.setOnClickListener { pickDate(date) { date = it!!; dateField.setText(dateFmt.format(Date(date))) } }
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
        photos.attachTo(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = title.text.toString().trim()
                titleLayout.error = if (t.isEmpty()) "Describe the work" else null
                val hasPhotos = photos.files.isNotEmpty()
                if (!hasPhotos) photos.showError("Add at least one photo before saving.")
                if (t.isEmpty() || !hasPhotos) return@setOnClickListener
                val by = doneBy.text.toString().trim()
                prefs.edit().putString("done_by", by).apply()
                val rec = WorkRecord(
                    id = recordId,
                    nodeId = nodeId,
                    jobId = job?.id ?: existing?.jobId,
                    title = t,
                    date = date,
                    doneBy = by,
                    hours = hours.text.toString().toLongOrNull(),
                    remarks = remarks.text.toString().trim(),
                    partsUsed = used.groupBy { it.partId }.map { (pid, l) -> PartUse(pid, l.sumOf { it.qty }) },
                )
                photos.commit()
                store.upsertRecord(rec)
                dialog.dismiss()
                refresh()
                if (job != null && existing == null) {
                    if (!job.recurring) toast("Saved. One-time job completed ✔")
                    else {
                        val next = if (job.unit == IntervalUnit.HOURS) "at ${numFmt.format(store.dueHours(job))} h"
                        else dateFmt.format(Date(store.dueDate(job)!!))
                        toast("Saved. Next due $next")
                    }
                }
            }
        }
        dialog.show()
    }

    // =====================================================================
    // Certificates
    // =====================================================================

    private fun showCertMenu(c: Certificate, anchor: View) {
        PopupMenu(this, anchor).apply {
            val files = store.certFiles(c.id)
            if (files.isNotEmpty()) menu.add(0, 3, 0, "View scan / files (${files.size})")
            menu.add(0, 1, 1, "Edit")
            menu.add(0, 2, 2, "Delete")
            setOnMenuItemClickListener { item ->
                if (item.itemId != 3 && !canEdit()) return@setOnMenuItemClickListener true
                when (item.itemId) {
                    3 -> openFiles(files, 0, c.name)
                    1 -> showCertDialog(c, c.nodeId)
                    2 -> MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Delete “${c.name}”?")
                        .setMessage("Its attached files are deleted too.")
                        .setPositiveButton(R.string.delete) { _, _ -> store.deleteCert(c); refresh() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
                true
            }
            show()
        }
    }

    private fun showCertDialog(existing: Certificate?, nodeId: String?) {
        val v = layoutInflater.inflate(R.layout.dialog_cert, null)
        v.findViewById<TextView>(R.id.item_path).text =
            if (nodeId == null) "Ship certificate – ${ship().name}" else "Equipment certificate: " + store.path(nodeId)
        val nameLayout = v.findViewById<TextInputLayout>(R.id.cert_name_layout)
        val name = v.findViewById<TextInputEditText>(R.id.cert_name)
        val number = v.findViewById<TextInputEditText>(R.id.cert_number)
        val issuedBy = v.findViewById<TextInputEditText>(R.id.issued_by)
        val issueField = v.findViewById<TextInputEditText>(R.id.issue_date)
        val expiryField = v.findViewById<TextInputEditText>(R.id.expiry_date)
        val surveyField = v.findViewById<TextInputEditText>(R.id.survey_date)
        val remarks = v.findViewById<TextInputEditText>(R.id.cert_remarks)

        val certId = existing?.id ?: PmsStore.newId()
        val files = FileSession(v, store.certDir(certId), store.certFiles(certId), "Certificate scan / photo (optional)", allowPdf = true, title = "Certificate")

        var issue = existing?.issueDate
        var expiry = existing?.expiryDate
        var survey = existing?.surveyDate
        fun show(field: TextInputEditText, value: Long?) = field.setText(value?.let { dateFmt.format(Date(it)) } ?: "")
        name.setText(existing?.name)
        number.setText(existing?.number)
        issuedBy.setText(existing?.issuedBy)
        remarks.setText(existing?.remarks)
        show(issueField, issue); show(expiryField, expiry); show(surveyField, survey)
        issueField.setOnClickListener { pickDate(issue, allowClear = true) { issue = it; show(issueField, it) } }
        expiryField.setOnClickListener { pickDate(expiry, allowClear = true) { expiry = it; show(expiryField, it) } }
        surveyField.setOnClickListener { pickDate(survey, allowClear = true) { survey = it; show(surveyField, it) } }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "New certificate" else "Edit certificate")
            .setView(v)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        files.attachTo(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                nameLayout.error = if (n.isEmpty()) "Enter the certificate name" else null
                if (n.isEmpty()) return@setOnClickListener
                files.commit()
                store.upsertCert(
                    Certificate(
                        id = certId, nodeId = nodeId, name = n, shipId = shipId,
                        number = number.text.toString().trim(),
                        issuedBy = issuedBy.text.toString().trim(),
                        issueDate = issue, expiryDate = expiry, surveyDate = survey,
                        remarks = remarks.text.toString().trim(),
                    )
                )
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
        if (existing == null) name.requestFocus()
    }

    // =====================================================================
    // Ships & locking
    // =====================================================================

    /** Returns true if the current ship can be changed; otherwise explains that it is locked. */
    private fun canEdit(): Boolean {
        val s = ship()
        if (!s.locked) return true
        MaterialAlertDialogBuilder(this)
            .setTitle("🔒 ${s.name} is locked")
            .setMessage("The records of this vessel are read-only. You can still view and share them.\n\nUnlock the ship to make changes.")
            .setPositiveButton("Unlock") { _, _ -> unlockShip(s) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return false
    }

    private fun switchShip(id: String) {
        if (id == shipId) return
        shipId = id
        selectedId = null
        historyJobId = null
        refresh()
        toast("Now showing ${ship().name}")
    }

    private fun showShips() {
        val ships = store.ships.sortedBy { it.name.lowercase() }
        val labels = ships.map { s ->
            (if (s.locked) "🔒 " else "") + s.name + (if (s.id == shipId) "  ✓" else "")
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Ships")
            .setItems(labels) { _, i -> switchShip(ships[i].id) }
            .setPositiveButton("Add ship") { _, _ ->
                editShip(null) { askSeed() }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Add (existing == null) or rename a ship. */
    private fun editShip(existing: Ship?, firstTime: Boolean = false, after: () -> Unit = {}) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val name = EditText(this).apply {
            hint = "Ship name (e.g. MV Ocean Star)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
            setText(existing?.name?.takeIf { !(firstTime && it == "My Ship") })
        }
        val imo = EditText(this).apply {
            hint = "IMO number (optional)"
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setText(existing?.imo)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(name); addView(imo)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(when {
                firstTime -> "Welcome to Ship PMS – which ship?"
                existing == null -> "Add ship"
                else -> "Edit ship"
            })
            .setView(box)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(if (firstTime) "Later" else getString(R.string.cancel)) { _, _ -> if (firstTime) after() }
            .setCancelable(!firstTime)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                if (n.isEmpty()) { name.error = "Enter the ship name"; return@setOnClickListener }
                if (existing == null) {
                    val s = store.addShip(n, imo.text.toString())
                    shipId = s.id; selectedId = null
                } else {
                    existing.name = n
                    existing.imo = imo.text.toString().trim()
                    store.save()
                }
                dialog.dismiss()
                refresh()
                after()
            }
        }
        dialog.show()
        name.requestFocus()
    }

    private fun lockShip(s: Ship) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val pin = EditText(this).apply {
            hint = "PIN to unlock (optional, 4+ digits)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSingleLine = true
        }
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(pin) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Lock ${s.name}?")
            .setMessage(
                "Use this when you sign off. All records of this vessel – structure, jobs, work done, photos, " +
                    "certificates and documents – become read-only. They can still be viewed and shared.\n\n" +
                    "Set a PIN if only you should be able to unlock it."
            )
            .setView(box)
            .setPositiveButton("Lock", null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = pin.text.toString()
                if (p.isNotEmpty() && p.length < 4) { pin.error = "Use at least 4 digits, or leave empty"; return@setOnClickListener }
                store.lockShip(s, p.ifEmpty { null })
                dialog.dismiss()
                refresh()
                toast("🔒 ${s.name} locked" + if (p.isNotEmpty()) " with PIN" else "")
            }
        }
        dialog.show()
    }

    private fun unlockShip(s: Ship) {
        if (s.pinHash == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Unlock ${s.name}?")
                .setMessage("Its records can be changed again.")
                .setPositiveButton("Unlock") { _, _ -> store.unlockShip(s, null); refresh() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        promptText("Enter PIN to unlock ${s.name}", "", "PIN",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD) { p ->
            if (store.unlockShip(s, p)) { refresh(); toast("${s.name} unlocked") } else toast("Wrong PIN")
        }
    }

    private fun confirmDeleteShip(s: Ship) {
        val ids = store.subtreeIds(null, s.id)
        val jobs = store.jobs.count { it.nodeId in ids }
        val recs = store.records.count { it.nodeId in ids }
        val certs = store.certs.count { it.shipId == s.id }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ship “${s.name}”?")
            .setMessage(
                "This permanently deletes its ${ids.size} structure items, $jobs jobs, $recs work records (with photos), " +
                    "$certs certificates and all its documents.\n\nTip: make a backup first (⋮ → Backup to file)."
            )
            .setPositiveButton("Delete ship") { _, _ ->
                promptText("Type the ship name to confirm", "", s.name) { typed ->
                    if (typed.equals(s.name, ignoreCase = true)) {
                        store.deleteShip(s.id)
                        shipId = store.ships.first().id
                        selectedId = null
                        refresh()
                        toast("Ship deleted")
                    } else toast("Name did not match – nothing deleted")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Read-only view of a work record (used when the ship is locked). */
    private fun showRecordDetails(r: WorkRecord) {
        val photos = store.photos(r.id)
        val msg = listOfNotNull(
            store.path(r.nodeId),
            dateFmt.format(Date(r.date)),
            r.doneBy.takeIf { it.isNotBlank() }?.let { "Done by $it" },
            r.hours?.let { "At ${numFmt.format(it)} running hours" },
            r.remarks.takeIf { it.isNotBlank() },
            r.partsUsed.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "Spare parts used:\n") { u ->
                "• ${u.qty} × ${store.part(u.partId)?.name ?: "deleted part"}"
            },
        ).joinToString("\n")
        MaterialAlertDialogBuilder(this)
            .setTitle(r.title)
            .setMessage(msg)
            .apply { if (photos.isNotEmpty()) setPositiveButton("Photos (${photos.size})") { _, _ -> openFiles(photos, 0, r.title) } }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Read-only view of a certificate (used when the ship is locked). */
    private fun showCertDetails(c: Certificate) {
        val files = store.certFiles(c.id)
        val msg = listOfNotNull(
            if (c.nodeId == null) "Ship certificate" else store.path(c.nodeId),
            c.number.takeIf { it.isNotBlank() }?.let { "No. $it" },
            c.issuedBy.takeIf { it.isNotBlank() }?.let { "Issued by $it" },
            c.issueDate?.let { "Issued ${dateFmt.format(Date(it))}" },
            describeCert(c),
            c.remarks.takeIf { it.isNotBlank() },
        ).joinToString("\n")
        MaterialAlertDialogBuilder(this)
            .setTitle(c.name)
            .setMessage(msg)
            .apply { if (files.isNotEmpty()) setPositiveButton("View files (${files.size})") { _, _ -> openFiles(files, 0, c.name) } }
            .setNegativeButton("Close", null)
            .show()
    }

    // =====================================================================
    // Schedule table, date range, frequency groups
    // =====================================================================

    private fun freqGroup(j: Job): FreqFilter {
        if (!j.recurring) return FreqFilter.ONCE
        if (j.unit == IntervalUnit.HOURS) return FreqFilter.HOURS
        val days = approxDays(j)
        return when {
            days <= 14 -> FreqFilter.WEEKLY
            days <= 62 -> FreqFilter.MONTHLY
            days <= 125 -> FreqFilter.QUARTERLY
            days <= 190 -> FreqFilter.HALF
            days <= 370 -> FreqFilter.ANNUAL
            else -> FreqFilter.MULTI
        }
    }

    /** Interval length in days (approximate), for sorting by frequency. Running hours go last. */
    private fun approxDays(j: Job): Int = when {
        !j.recurring -> 0
        j.unit == IntervalUnit.HOURS -> 100_000 + j.intervalValue
        else -> j.intervalValue * when (j.unit) {
            IntervalUnit.DAYS -> 1
            IntervalUnit.WEEKS -> 7
            IntervalUnit.MONTHS -> 30
            else -> 365
        }
    }

    private fun shortDate(t: Long): String = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(t))

    private fun dueLabel(f: DueFilter): String {
        if (f != DueFilter.RANGE) return f.label
        val from = rangeFrom
        val to = rangeTo
        return if (from == null || to == null) "📅 Date range…" else "📅 ${shortDate(from)} – ${shortDate(to)}"
    }

    /** Material date-range picker. The picker works in UTC days, the app in local time. */
    private fun pickRange() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        fun toUtc(local: Long): Long {
            val l = Calendar.getInstance().apply { timeInMillis = local }
            return Calendar.getInstance(utc).apply {
                clear(); set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }
        fun toLocal(u: Long): Long {
            val c = Calendar.getInstance(utc).apply { timeInMillis = u }
            return Calendar.getInstance().apply {
                set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH), 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        val from = rangeFrom
        val to = rangeTo
        val selection = if (from != null && to != null) androidx.core.util.Pair(toUtc(from), toUtc(to))
        else androidx.core.util.Pair(toUtc(System.currentTimeMillis()), toUtc(System.currentTimeMillis() + 30 * PmsStore.DAY))
        val picker = com.google.android.material.datepicker.MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Jobs due between")
            .setSelection(selection)
            .build()
        picker.addOnPositiveButtonClickListener { sel ->
            val a = sel.first
            val b = sel.second
            if (a != null && b != null) {
                rangeFrom = toLocal(a)
                rangeTo = toLocal(b)
                dueFilter = DueFilter.RANGE
            }
        }
        picker.addOnDismissListener { refreshChips(); refreshList() }
        picker.show(supportFragmentManager, "range")
    }

    private fun schedCell(j: Job, c: SchedCol): String {
        val status = store.status(j)
        return when (c) {
            SchedCol.STATUS -> when (status) {
                JobStatus.OVERDUE -> "OVERDUE"
                JobStatus.DUE_SOON -> "DUE SOON"
                JobStatus.OK -> "OK"
                JobStatus.DONE -> "DONE"
            }
            SchedCol.JOB -> j.title
            SchedCol.UNIT -> store.path(j.nodeId)
            SchedCol.FREQ -> if (j.recurring) freqLabel(j.intervalValue, j.unit) else "One-time"
            SchedCol.LAST -> when {
                !j.recurring -> j.completed?.let { "Done ${shortDate(it)}" } ?: "–"
                j.unit == IntervalUnit.HOURS -> shortDate(j.lastDoneDate) + (j.lastDoneHours?.let { "\n@ ${numFmt.format(it)} h" } ?: "")
                else -> shortDate(j.lastDoneDate)
            }
            SchedCol.DUE -> when {
                status == JobStatus.DONE -> "–"
                store.isHours(j) -> "${numFmt.format(store.dueHours(j))} h"
                else -> store.dueDate(j)?.let { shortDate(it) } ?: "–"
            }
            SchedCol.LEFT -> when {
                status == JobStatus.DONE -> "–"
                store.isHours(j) -> store.hoursLeft(j)?.let { if (it >= 0) "${numFmt.format(it)} h left" else "${numFmt.format(-it)} h over" } ?: "no hours"
                else -> store.dueDate(j)?.let { inDays(PmsStore.daysUntil(it)) } ?: "–"
            }
        }
    }

    private fun schedComparator(): Comparator<Job> {
        val byDays: Comparator<Job> = compareBy(nullsLast<Long>()) { store.daysLeft(it) }
        val base: Comparator<Job> = when (schedSort) {
            SchedCol.STATUS -> compareBy<Job> { store.status(it).ordinal }.then(byDays)
            SchedCol.JOB -> compareBy<Job> { it.title.lowercase() }
            SchedCol.UNIT -> compareBy<Job> { store.path(it.nodeId).lowercase() }
            SchedCol.FREQ -> compareBy<Job> { approxDays(it) }
            SchedCol.LAST -> compareBy<Job> { if (it.recurring) it.lastDoneDate else (it.completed ?: 0L) }
            SchedCol.DUE, SchedCol.LEFT -> if (typeFilter == TypeFilter.DONE) compareBy<Job> { it.completed ?: 0L } else byDays
        }
        return (if (schedSortAsc) base else base.reversed()).thenBy { it.title.lowercase() }
    }

    private fun buildScheduleRows(): List<TableRow> {
        val cols = SchedCol.entries
        configureTable("schedule", cols.map { it.widthDp })
        renderTableHeader(cols.map { it.label }, cols.map { it.widthDp }, schedSort.ordinal, schedSortAsc) { i ->
            val c = cols[i]
            if (schedSort == c) schedSortAsc = !schedSortAsc else { schedSort = c; schedSortAsc = true }
            prefs.edit().putInt("sched_sort", schedSort.ordinal).putBoolean("sched_sort_asc", schedSortAsc).apply()
            refreshList()
        }
        val ids = store.subtreeIds(selectedId, shipId)
        val typed = store.jobs.filter { it.nodeId in ids && matchesType(it) }
        // Counts shown on the filter chips.
        dueChips.forEach { (f, chip) ->
            val n = typed.count { (freqFilter == FreqFilter.ALL || freqGroup(it) == freqFilter) && matchesDue(it, f) }
            chip.text = if (f == DueFilter.ALL || (f == DueFilter.RANGE && rangeFrom == null)) dueLabel(f) else "${dueLabel(f)} ($n)"
        }
        freqChips.forEach { (f, chip) ->
            val n = typed.count { matchesDue(it, dueFilter) && (f == FreqFilter.ALL || freqGroup(it) == f) }
            chip.text = if (f == FreqFilter.ALL) f.label else "${f.label} ($n)"
        }
        return typed
            .filter { matchesDue(it, dueFilter) && (freqFilter == FreqFilter.ALL || freqGroup(it) == freqFilter) }
            .filter { matches(it.title, store.path(it.nodeId), it.instructions) }
            .sortedWith(schedComparator())
            .map { j ->
                TableRow(
                    key = j.id,
                    cells = cols.map { schedCell(j, it) },
                    accent = statusColor(store.status(j)),
                    accentCol = SchedCol.STATUS.ordinal,
                    payload = j,
                )
            }
    }

    // =====================================================================
    // PDF reports (A4): work done, schedule, inventory
    // =====================================================================

    private fun historyRecords(): List<WorkRecord> {
        val ids = store.subtreeIds(selectedId, shipId)
        return store.records
            .filter { it.nodeId in ids && (historyJobId == null || it.jobId == historyJobId) }
            .filter { matches(it.title, store.path(it.nodeId), it.doneBy, it.remarks) }
            .sortedByDescending { it.date }
    }

    private fun shipLine(): String = ship().name + (ship().imo.takeIf { it.isNotBlank() }?.let { "  ·  IMO $it" } ?: "")

    private fun stamp(t: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    private fun PdfBuilder.recordSection(r: WorkRecord, withPhotos: Boolean) {
        val job = store.jobs.firstOrNull { it.id == r.jobId }
        heading(r.title)
        fields(
            listOf(
                "Ship" to shipLine(),
                "Unit" to store.path(r.nodeId),
                "Job" to (job?.let { it.title + " – " + (if (it.recurring) freqLabel(it.intervalValue, it.unit) else "one-time") }
                    ?: "Unplanned / corrective work"),
                "Date" to dateFmt.format(Date(r.date)),
                "Done by" to r.doneBy,
                "Running hours" to (r.hours?.let { numFmt.format(it) + " h" } ?: ""),
                "Remarks" to r.remarks,
            )
        )
        if (r.partsUsed.isNotEmpty()) {
            text("Spare parts / consumables used", 10.5f, bold = true)
            table(
                listOf("Item" to 3f, "Part no. / code" to 2f, "Qty" to 0.8f, "Unit" to 0.8f),
                r.partsUsed.map { u ->
                    val p = store.part(u.partId)
                    listOf(p?.name ?: "deleted item", p?.partNumber.orEmpty(), u.qty.toString(), p?.unit.orEmpty())
                },
            )
        }
        if (withPhotos) {
            val caps = store.captions(store.photoDir(r.id))
            store.photos(r.id).filter { Images.isImage(it) }.forEachIndexed { i, f ->
                photo(f, "Photo ${i + 1}" + (caps[f.name]?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
            }
        }
    }

    private fun recordPdf(r: WorkRecord) {
        generatePdf("Work report ${stamp(r.date)} ${r.title}", "Work Done Report") {
            title("Work Done Report")
            recordSection(r, withPhotos = true)
            signatures("Done by", "Checked by (Chief Engineer)", "Master")
        }
    }

    /** "PDF report" toolbar action: a printable A4 version of the list on screen. */
    private fun pdfOfCurrentList() {
        when (tab) {
            Tab.HISTORY -> {
                val recs = historyRecords()
                if (recs.isEmpty()) { toast("No work records in this list"); return }
                MaterialAlertDialogBuilder(this)
                    .setTitle("Work done report – ${recs.size} records")
                    .setMessage("Include the photos of each job? (Larger file, more pages.)")
                    .setPositiveButton("With photos") { _, _ -> historyPdf(recs, true) }
                    .setNegativeButton("Table only") { _, _ -> historyPdf(recs, false) }
                    .setNeutralButton(R.string.cancel, null)
                    .show()
            }
            Tab.SCHEDULE, Tab.PARTS, Tab.CONSUMABLES -> {
                val (heads, filters, rows) = currentTable()
                if (rows.isEmpty()) { toast("The list is empty"); return }
                val widths = if (tab == Tab.SCHEDULE) SchedCol.entries.map { it.widthDp } else PartCol.entries.map { it.widthDp }
                val name = if (tab == Tab.SCHEDULE) "Maintenance Schedule" else tab.label
                val scope = headerPath.text.toString()
                generatePdf("$name ${stamp()}", name) {
                    title(name)
                    text(shipLine(), 11f, bold = true)
                    text("$scope  ·  $filters  ·  ${rows.size} entries", 9.5f, color = android.graphics.Color.DKGRAY)
                    gap(4f)
                    table(
                        heads.mapIndexed { i, h -> h to widths[i].toFloat() },
                        rows.map { r -> r.cells.map { it.replace("\n", " ") } },
                        fontSize = 8f,
                        accent = { i ->
                            val p = rows[i].payload
                            when {
                                p is Job && store.status(p).let { it == JobStatus.OVERDUE || it == JobStatus.DUE_SOON } -> rows[i].accent
                                p is SparePart -> rows[i].accent
                                else -> null
                            }
                        },
                    )
                }
            }
            else -> toast("PDF reports are available on Schedule, Work done, Spare parts and Consumables. For one work record use its ⋮ menu.")
        }
    }

    private fun historyPdf(recs: List<WorkRecord>, withPhotos: Boolean) {
        val scope = headerPath.text.toString()
        generatePdf("Work done ${stamp()}", "Work Done Report") {
            title("Work Done Report")
            text(shipLine(), 11f, bold = true)
            val first = recs.minOf { it.date }
            val last = recs.maxOf { it.date }
            text("$scope  ·  ${recs.size} records  ·  ${dateFmt.format(Date(first))} – ${dateFmt.format(Date(last))}", 9.5f, color = android.graphics.Color.DKGRAY)
            gap(4f)
            table(
                listOf("Date" to 1.1f, "Unit" to 2f, "Work done" to 2.6f, "Done by" to 1.2f, "Hours" to 0.9f, "Parts used" to 1.8f),
                recs.map { r ->
                    listOf(
                        shortDate(r.date),
                        store.path(r.nodeId),
                        r.title + if (r.remarks.isNotBlank()) "\n" + r.remarks else "",
                        r.doneBy,
                        r.hours?.let { numFmt.format(it) }.orEmpty(),
                        r.partsUsed.joinToString("\n") { u -> "${u.qty} × ${store.part(u.partId)?.name ?: "?"}" },
                    )
                },
                fontSize = 8.5f,
            )
            if (withPhotos) recs.forEach { r -> if (store.photos(r.id).isNotEmpty()) recordSection(r, withPhotos = true) }
            signatures("Prepared by", "Chief Engineer", "Master")
        }
    }

    /** Builds a PDF in the background, then offers View / Print / Save / Share. */
    private fun generatePdf(fileName: String, title: String, build: PdfBuilder.() -> Unit) {
        progress.visibility = View.VISIBLE
        val safe = fileName.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").take(80)
        val shipName = ship().name
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(File(cacheDir, "reports").apply { mkdirs() }, "$safe.pdf")
                    val b = PdfBuilder(title, shipName)
                    b.build()
                    b.writeTo(f)
                    f to b.pageCount
                }
            }
            progress.visibility = View.GONE
            result.onSuccess { (f, pages) -> showPdfReady(f, pages, title) }
                .onFailure { toast("Could not create the PDF: ${it.message}") }
        }
    }

    private fun showPdfReady(f: File, pages: Int, title: String) {
        val options = arrayOf("👁  View", "🖨  Print", "💾  Save to phone…", "↗  Share")
        MaterialAlertDialogBuilder(this)
            .setTitle("$title ready · $pages page${if (pages == 1) "" else "s"} (A4)")
            .setItems(options) { _, i ->
                when (i) {
                    0 -> startActivity(Intent(this, PdfViewerActivity::class.java).putExtra(PdfViewerActivity.EXTRA_PATH, f.absolutePath))
                    1 -> printPdf(this, f, f.nameWithoutExtension)
                    2 -> { pendingPdf = f; savePdf.launch(f.name) }
                    3 -> shareFile(this, f)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // =====================================================================
    // Spare parts
    // =====================================================================

    private fun partCell(p: SparePart, c: PartCol): String = when (c) {
        PartCol.NAME -> p.name
        PartCol.NUMBER -> p.partNumber
        PartCol.STOCK -> when (store.partStatus(p)) {
            PartStatus.OUT -> "${p.quantity} · OUT"
            PartStatus.LOW -> "${p.quantity} · LOW"
            PartStatus.OK -> p.quantity.toString()
        }
        PartCol.MIN -> if (p.minQuantity > 0) p.minQuantity.toString() else "–"
        PartCol.UNIT -> p.unit
        PartCol.LOCATION -> p.location
        PartCol.MAKER -> p.maker
        PartCol.UNITS -> store.partUnits(p).joinToString(", ").ifEmpty { "Not linked" }
    }

    private fun partComparator(): Comparator<SparePart> {
        val base: Comparator<SparePart> = when (partSort) {
            PartCol.STOCK -> compareBy<SparePart> { it.quantity }
            PartCol.MIN -> compareBy<SparePart> { it.minQuantity }
            else -> compareBy<SparePart> { partCell(it, partSort).lowercase() }
        }
        val ordered = if (partSortAsc) base else base.reversed()
        return ordered.thenBy { it.name.lowercase() }
    }

    private fun buildPartRows(consumable: Boolean): List<TableRow> {
        val cols = PartCol.entries
        configureTable("parts", cols.map { it.widthDp })
        renderTableHeader(cols.map { partColLabel(it, consumable) }, cols.map { it.widthDp }, partSort.ordinal, partSortAsc) { i ->
            val c = cols[i]
            if (partSort == c) partSortAsc = !partSortAsc else { partSort = c; partSortAsc = c != PartCol.STOCK }
            prefs.edit().putInt("part_sort", partSort.ordinal).putBoolean("part_sort_asc", partSortAsc).apply()
            refreshList()
        }
        return partRows(consumable)
    }

    private fun partColLabel(c: PartCol, consumable: Boolean) = when {
        consumable && c == PartCol.NAME -> "Consumable"
        consumable && c == PartCol.NUMBER -> "Code / part no."
        else -> c.label
    }

    private fun partRows(consumable: Boolean): List<TableRow> = partsInScope(consumable)
        .filter { p ->
            when (partFilter) {
                PartFilter.ALL -> true
                PartFilter.LOW -> store.partStatus(p) != PartStatus.OK
                PartFilter.OUT -> store.partStatus(p) == PartStatus.OUT
                PartFilter.UNLINKED -> p.nodeIds.none { store.node(it) != null }
            }
        }
        .filter { matches(it.name, it.partNumber, it.maker, it.location, it.remarks, store.partUnits(it).joinToString(" ")) }
        .sortedWith(partComparator())
        .map { p ->
            TableRow(
                key = p.id,
                cells = PartCol.entries.map { partCell(p, it) },
                accent = when (store.partStatus(p)) {
                    PartStatus.OUT -> getColor(R.color.status_overdue)
                    PartStatus.LOW -> getColor(R.color.status_soon)
                    PartStatus.OK -> null
                },
                accentCol = PartCol.STOCK.ordinal,
                payload = p,
            )
        }

    /** Parts linked to the selected part of the ship; with the ship selected, all of its parts. */
    private fun partsInScope(consumable: Boolean): List<SparePart> {
        val shipParts = store.parts.filter { it.shipId == shipId && it.consumable == consumable }
        if (selectedId == null) return shipParts
        val ids = store.subtreeIds(selectedId, shipId)
        return shipParts.filter { p -> p.nodeIds.any { it in ids } }
    }

    private fun describePart(p: SparePart): String = listOfNotNull(
        p.partNumber.takeIf { it.isNotBlank() }?.let { "Part no.: $it" },
        p.maker.takeIf { it.isNotBlank() }?.let { "Maker / ref.: $it" },
        "In stock: ${p.quantity} ${p.unit}" + if (p.minQuantity > 0) " (minimum ${p.minQuantity})" else "",
        p.location.takeIf { it.isNotBlank() }?.let { "Location: $it" },
        store.partUnits(p).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Used on: ") ?: "Not linked to a unit",
        p.remarks.takeIf { it.isNotBlank() },
    ).joinToString("\n")

    private fun showPartDetails(p: SparePart) {
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(p.name)
            .setMessage(describePart(p))
            .setNeutralButton("More…") { _, _ -> showPartMenu(p, null) }
        if (ship().locked) b.setNegativeButton("Close", null)
        else {
            b.setPositiveButton("− Take out") { _, _ -> adjustStock(p, -1) }
            b.setNegativeButton("+ Receive") { _, _ -> adjustStock(p, +1) }
        }
        b.show()
    }

    private fun showPartMenu(p: SparePart, anchor: View?) {
        val files = store.partFiles(p.id)
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += "Stock history" to { showStockHistory(p) }
        if (files.isNotEmpty()) options += "View photos (${files.size})" to { openFiles(files, 0, p.name) }
        options += "− Take out of stock" to { if (canEdit()) adjustStock(p, -1) }
        options += "+ Receive into stock" to { if (canEdit()) adjustStock(p, +1) }
        options += "Edit part" to { if (canEdit()) showPartDialog(p) }
        options += "Link to units…" to {
            if (canEdit()) chooseUnits(p.nodeIds) { ids -> p.nodeIds.clear(); p.nodeIds += ids; store.save(); refresh() }
        }
        options += "Delete" to {
            if (canEdit()) MaterialAlertDialogBuilder(this)
                .setTitle("Delete “${p.name}”?")
                .setMessage("Its stock history and photos are deleted too. Work records that used it keep their text.")
                .setPositiveButton(R.string.delete) { _, _ -> store.deletePart(p); refresh() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        if (anchor != null) {
            PopupMenu(this, anchor).apply {
                options.forEachIndexed { i, (label, _) -> menu.add(0, i, i, label) }
                setOnMenuItemClickListener { options[it.itemId].second(); true }
                show()
            }
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(p.name)
                .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
                .show()
        }
    }

    /** Take out (sign = -1) or receive (sign = +1) a quantity, with an optional note. */
    private fun adjustStock(p: SparePart, sign: Int) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val qty = EditText(this).apply {
            hint = "Quantity (${p.unit})"
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setText("1")
            setSelectAllOnFocus(true)
        }
        val note = EditText(this).apply {
            hint = if (sign < 0) "What for? (optional)" else "Supplier / requisition no. (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(qty); addView(note)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle((if (sign < 0) "Take out: " else "Receive: ") + p.name)
            .setMessage("In stock now: ${p.quantity} ${p.unit}")
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val q = qty.text.toString().toIntOrNull() ?: 0
                if (q <= 0) { toast("Enter a quantity"); return@setPositiveButton }
                if (sign < 0 && q > p.quantity) { toast("Only ${p.quantity} ${p.unit} in stock"); return@setPositiveButton }
                val n = note.text.toString().trim()
                store.adjustStock(p, sign * q, (if (sign < 0) "Taken out" else "Received") + if (n.isNotEmpty()) ": $n" else "")
                refresh()
                toast("${p.name}: ${p.quantity} ${p.unit} in stock")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        qty.requestFocus()
    }

    private fun showStockHistory(p: SparePart) {
        val moves = store.moves.filter { it.partId == p.id }.sortedByDescending { it.date }
        val text = if (moves.isEmpty()) "No stock changes recorded yet." else moves.joinToString("\n") { m ->
            "${dateFmt.format(Date(m.date))}   ${if (m.delta > 0) "+" else ""}${m.delta} ${p.unit}   ${m.note}"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Stock history – ${p.name}")
            .setMessage("In stock: ${p.quantity} ${p.unit}\n\n$text")
            .setPositiveButton("Close", null)
            .show()
    }

    /** Multi-select of units in this ship's structure. */
    private fun chooseUnits(initial: Set<String>, onDone: (Set<String>) -> Unit) {
        val ordered = mutableListOf<Pair<Node, Int>>()
        fun walk(parent: String?, depth: Int) {
            store.children(parent, shipId).forEach { ordered += it to depth; walk(it.id, depth + 1) }
        }
        walk(null, 0)
        if (ordered.isEmpty()) { toast("Add units in the ship structure first"); return }
        val checked = ordered.map { it.first.id in initial }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Used on which units?")
            .setMultiChoiceItems(ordered.map { (n, d) -> "\u2003".repeat(d) + n.name }.toTypedArray(), checked) { _, i, on ->
                checked[i] = on
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                onDone(ordered.filterIndexed { i, _ -> checked[i] }.map { it.first.id }.toSet())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Pick a part for a work record: first the parts linked to this unit (or the units around it),
     * with an option to show every part of the ship.
     */
    private fun choosePartForUnit(nodeId: String, onPick: (SparePart) -> Unit) {
        val all = store.parts.filter { it.shipId == shipId }.sortedBy { it.name.lowercase() }
        if (all.isEmpty()) { toast("No spare parts or consumables yet. Add them in their tabs."); return }
        val related = mutableSetOf(nodeId)
        var p = store.node(nodeId)?.parentId
        while (p != null) { related += p; p = store.node(p)?.parentId }
        related += store.subtreeIds(nodeId, shipId)
        val linked = all.filter { part -> part.nodeIds.any { it in related } }
        fun pickFrom(list: List<SparePart>, showAllOption: Boolean) {
            val labels = list.map { "${it.name}  (${it.quantity} ${it.unit})" + if (it.consumable) " · consumable" else "" } +
                if (showAllOption) listOf("Show all spare parts & consumables…") else emptyList()
            MaterialAlertDialogBuilder(this)
                .setTitle(if (showAllOption) "For ${store.node(nodeId)?.name}" else "All spare parts & consumables")
                .setItems(labels.toTypedArray()) { _, i ->
                    if (i < list.size) onPick(list[i]) else pickFrom(all, false)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        if (linked.isEmpty()) pickFrom(all, false) else pickFrom(linked, true)
    }

    private fun showPartDialog(existing: SparePart?, consumable: Boolean = existing?.consumable ?: false) {
        val v = layoutInflater.inflate(R.layout.dialog_part, null)
        val nameLayout = v.findViewById<TextInputLayout>(R.id.part_name_layout)
        val name = v.findViewById<TextInputEditText>(R.id.part_name)
        val number = v.findViewById<TextInputEditText>(R.id.part_number)
        val maker = v.findViewById<TextInputEditText>(R.id.maker)
        val location = v.findViewById<TextInputEditText>(R.id.location)
        val qty = v.findViewById<TextInputEditText>(R.id.quantity)
        val minQty = v.findViewById<TextInputEditText>(R.id.min_quantity)
        val unit = v.findViewById<TextInputEditText>(R.id.unit)
        val remarks = v.findViewById<TextInputEditText>(R.id.part_remarks)
        val linkedGroup = v.findViewById<ChipGroup>(R.id.linked_units)

        val partId = existing?.id ?: PmsStore.newId()
        val files = FileSession(v, store.partDir(partId), store.partFiles(partId), "Photos (optional)", allowPdf = true, title = if (consumable) "Consumable" else "Spare part")
        if (consumable) {
            nameLayout.hint = "Consumable (e.g. Lub oil SAE 40, rags, gasket sheet)"
            v.findViewById<TextInputLayout>(R.id.part_number_layout).hint = "Code / part no. (optional)"
        }
        val links = (existing?.nodeIds ?: listOfNotNull(selectedId)).toMutableSet()

        fun renderLinks() {
            linkedGroup.removeAllViews()
            links.mapNotNull { store.node(it) }.sortedBy { it.name }.forEach { n ->
                linkedGroup.addView(Chip(this).apply {
                    text = n.name
                    isCloseIconVisible = true
                    setOnCloseIconClickListener { links.remove(n.id); renderLinks() }
                })
            }
        }
        renderLinks()
        v.findViewById<View>(R.id.link_units).setOnClickListener {
            chooseUnits(links) { ids -> links.clear(); links += ids; renderLinks() }
        }

        name.setText(existing?.name)
        number.setText(existing?.partNumber)
        maker.setText(existing?.maker)
        location.setText(existing?.location ?: prefs.getString("part_location", ""))
        qty.setText((existing?.quantity ?: 0).toString())
        minQty.setText((existing?.minQuantity ?: 0).toString())
        unit.setText(existing?.unit ?: "pcs")
        remarks.setText(existing?.remarks)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(when {
                existing == null && consumable -> "New consumable"
                existing == null -> "New spare part"
                consumable -> "Edit consumable"
                else -> "Edit spare part"
            })
            .setView(v)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        files.attachTo(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                nameLayout.error = if (n.isEmpty()) "Enter the name" else null
                if (n.isEmpty()) return@setOnClickListener
                files.commit()
                val loc = location.text.toString().trim()
                prefs.edit().putString("part_location", loc).apply()
                store.upsertPart(
                    SparePart(
                        id = partId, shipId = shipId, name = n,
                        partNumber = number.text.toString().trim(),
                        maker = maker.text.toString().trim(),
                        location = loc,
                        quantity = qty.text.toString().toIntOrNull() ?: 0,
                        minQuantity = minQty.text.toString().toIntOrNull() ?: 0,
                        unit = unit.text.toString().trim().ifEmpty { "pcs" },
                        remarks = remarks.text.toString().trim(),
                        nodeIds = links.toMutableSet(),
                        consumable = consumable,
                    )
                )
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
        if (existing == null) name.requestFocus()
    }

    // =====================================================================
    // Photos & attachments inside dialogs
    // =====================================================================

    /**
     * Manages the photo/file strip of an open dialog. New files are written straight into [dir];
     * if the dialog is cancelled they are deleted again, and removals only happen on save.
     */
    private inner class FileSession(
        root: View,
        val dir: File,
        existing: List<File>,
        private val labelText: String,
        allowPdf: Boolean,
        private val title: String,
    ) {
        val files = existing.toMutableList()
        val captions = store.captions(dir).toMutableMap()
        private val added = mutableListOf<File>()
        private val removed = mutableListOf<File>()
        private var saved = false
        private val strip: LinearLayout = root.findViewById(R.id.att_strip)
        private val label: TextView = root.findViewById(R.id.att_label)
        private val error: TextView = root.findViewById(R.id.att_error)

        init {
            root.findViewById<View>(R.id.att_camera).setOnClickListener { launchCamera() }
            root.findViewById<View>(R.id.att_gallery).setOnClickListener {
                pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            root.findViewById<View>(R.id.att_file).apply {
                visibility = if (allowPdf) View.VISIBLE else View.GONE
                setOnClickListener { pickAttachments.launch(arrayOf("application/pdf", "image/*")) }
            }
            render()
        }

        fun attachTo(dialog: AlertDialog) {
            session = this
            dialog.setOnDismissListener {
                if (!saved) added.forEach { it.delete() }
                if (session === this) session = null
            }
        }

        fun add(file: File) {
            files += file; added += file
            error.visibility = View.GONE
            render()
        }

        fun commit() {
            saved = true
            removed.forEach { it.delete() }
            store.saveCaptions(dir, captions)
        }

        fun showError(msg: String) {
            error.text = msg
            error.visibility = View.VISIBLE
        }

        fun render() {
            label.text = "$labelText · ${files.size}"
            strip.removeAllViews()
            val d = resources.displayMetrics.density
            val size = (84 * d).toInt()
            files.forEachIndexed { index, f ->
                val cell = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = (6 * d).toInt(); bottomMargin = (6 * d).toInt()
                    }
                    setOnClickListener { fileMenu(f, index) }
                }
                cell.addView(ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0x22888888)
                    contentDescription = f.name
                    if (Images.isImage(f)) setImageBitmap(Images.thumbnail(f, size))
                    else { setImageResource(R.drawable.ic_pdf); scaleType = ImageView.ScaleType.CENTER_INSIDE }
                })
                cell.addView(TextView(this@MainActivity).apply {
                    text = captions[f.name]?.takeIf { it.isNotBlank() } ?: "+ description"
                    alpha = if (captions[f.name].isNullOrBlank()) 0.55f else 1f
                    textSize = 11f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                strip.addView(cell)
            }
        }

        /** View, mark up (draw on), describe or remove a photo/file. */
        private fun fileMenu(f: File, index: Int) {
            val image = Images.isImage(f)
            val options = mutableListOf<Pair<String, () -> Unit>>()
            options += "View" to { openFiles(files.toList(), index, title, captions) }
            if (image) options += "✎ Mark up (draw arrows, circles, text)" to {
                annotate.launch(Intent(this@MainActivity, AnnotateActivity::class.java).putExtra(AnnotateActivity.EXTRA_PATH, f.absolutePath))
            }
            options += "Description…" to {
                promptText(
                    "Description", captions[f.name].orEmpty(), "e.g. Worn bearing found on DE side",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, allowEmpty = true,
                ) { t ->
                    if (t.isBlank()) captions.remove(f.name) else captions[f.name] = t
                    render()
                }
            }
            options += "Remove" to {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setMessage("Remove this ${if (image) "photo" else "file"}?")
                    .setPositiveButton("Remove") { _, _ ->
                        files.remove(f)
                        captions.remove(f.name)
                        if (added.remove(f)) f.delete() else removed += f
                        render()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(captions[f.name]?.takeIf { it.isNotBlank() } ?: if (image) "Photo ${index + 1}" else f.name)
                .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
                .show()
        }
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        cameraFile = f
        try {
            takePhoto.launch(uriFor(this, f))
        } catch (e: ActivityNotFoundException) {
            toast("No camera app found. Use Gallery instead.")
        }
    }

    private fun addToSession(uris: List<Uri>, after: () -> Unit = {}) {
        val s = session ?: return
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { uri -> runCatching { store.importAttachment(uri, s.dir) }.getOrNull() }
            }
            after()
            progress.visibility = View.GONE
            results.filterNotNull().forEach { s.add(it) }
            val failed = results.count { it == null }
            if (failed > 0) toast("$failed file${if (failed > 1) "s" else ""} could not be added")
        }
    }

    /** Opens a PDF in the PDF viewer, or photos in the photo viewer. */
    private fun openFiles(files: List<File>, index: Int, title: String, captions: Map<String, String>? = null) {
        val file = files.getOrNull(index) ?: return
        if (file.extension.lowercase() in VIDEO_EXT) {
            startActivity(Intent(this, VideoPlayerActivity::class.java).putExtra(VideoPlayerActivity.EXTRA_PATH, file.absolutePath))
        } else if (Images.isImage(file)) {
            val images = files.filter { Images.isImage(it) }
            val caps = captions ?: file.parentFile?.let { store.captions(it) }.orEmpty()
            startActivity(
                Intent(this, PhotoViewerActivity::class.java)
                    .putStringArrayListExtra(PhotoViewerActivity.EXTRA_PATHS, ArrayList(images.map { it.absolutePath }))
                    .putStringArrayListExtra(PhotoViewerActivity.EXTRA_CAPTIONS, ArrayList(images.map { caps[it.name].orEmpty() }))
                    .putExtra(PhotoViewerActivity.EXTRA_INDEX, images.indexOf(file))
                    .putExtra(PhotoViewerActivity.EXTRA_TITLE, title)
            )
        } else if (file.extension.equals("pdf", true)) {
            startActivity(Intent(this, PdfViewerActivity::class.java).putExtra(PdfViewerActivity.EXTRA_PATH, file.absolutePath))
        } else {
            openWithOtherApp(this, file)
        }
    }

    // =====================================================================
    // Documents
    // =====================================================================

    private fun showDocMenu(doc: Doc, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.share)
            menu.add(0, 2, 1, R.string.open_with)
            menu.add(0, 3, 2, "Rename")
            menu.add(0, 4, 3, "Move to another item")
            menu.add(0, 5, 4, R.string.delete)
            setOnMenuItemClickListener { item ->
                if (item.itemId >= 3 && !canEdit()) return@setOnMenuItemClickListener true
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
        if (uris.isEmpty() || !canEdit()) return
        tabs.getTabAt(Tab.DOCS.ordinal)?.select()
        if (store.subtreeIds(null, shipId).isEmpty()) store.addNode(null, "General documents", shipId)
        chooseNode("Save ${uris.size} file${if (uris.size > 1) "s" else ""} to") { id -> importAll(uris, id) }
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
    // Sharing the current list, backup
    // =====================================================================

    /** Shares what is on screen (e.g. "One-time jobs due within a week") as plain text. */
    private fun shareCurrentList() {
        if (tab == Tab.PARTS || tab == Tab.CONSUMABLES || tab == Tab.SCHEDULE) { shareTable(); return }
        val items = rowAdapter.currentList
        if (items.isEmpty()) { toast("The list is empty"); return }
        val filters = when (tab) {
            Tab.SCHEDULE -> listOfNotNull(typeFilter.label, dueFilter.takeIf { typeFilter != TypeFilter.DONE }?.label)
            Tab.CERTS -> listOf(certFilter.label)
            Tab.PARTS -> listOf(partFilter.label)
            else -> emptyList()
        } + listOfNotNull(query.takeIf { it.isNotEmpty() }?.let { "“$it”" })
        val text = buildString {
            append("${tab.label} – ${headerPath.text}\n")
            if (filters.isNotEmpty()) append(filters.joinToString(" · ")).append('\n')
            append(dateFmt.format(Date())).append(" · ${items.size} entries\n\n")
            items.forEachIndexed { i, it ->
                append("${i + 1}. ${it.title}")
                it.badge?.let { b -> append("  [$b]") }
                append("\n   ${it.line2}\n")
                it.line3?.takeIf { l -> l.isNotBlank() }?.lines()?.forEach { l -> append("   $l\n") }
                append('\n')
            }
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Ship PMS – ${tab.label}")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Share list"))
    }

    /** Headings, filter description and rows of the table on screen. */
    private fun currentTable(): Triple<List<String>, String, List<TableRow>> {
        val rows = tableAdapter.currentList
        return when (tab) {
            Tab.SCHEDULE -> Triple(SchedCol.entries.map { it.label }, scheduleFilterText(), rows)
            else -> Triple(
                PartCol.entries.map { partColLabel(it, tab == Tab.CONSUMABLES) },
                partFilter.label + " · sorted by " + partColLabel(partSort, tab == Tab.CONSUMABLES) + (if (partSortAsc) " ▲" else " ▼") +
                    (query.takeIf { it.isNotEmpty() }?.let { " · “$it”" } ?: ""),
                rows,
            )
        }
    }

    private fun scheduleFilterText(): String = listOfNotNull(
        typeFilter.label,
        dueFilter.takeIf { typeFilter != TypeFilter.DONE && it != DueFilter.ALL }?.let { dueLabel(it) },
        freqFilter.takeIf { it != FreqFilter.ALL }?.label,
        query.takeIf { it.isNotEmpty() }?.let { "“$it”" },
        "sorted by " + schedSort.label + if (schedSortAsc) " ▲" else " ▼",
    ).joinToString(" · ")

    /** Shares the table on screen as text (one line per row, columns separated by "|"). */
    private fun shareTable() {
        val (heads, filters, rows) = currentTable()
        if (rows.isEmpty()) { toast("The list is empty"); return }
        val text = buildString {
            append("${tab.label} – ${headerPath.text}\n")
            append(filters).append('\n')
            append(dateFmt.format(Date())).append(" · ${rows.size} entries\n\n")
            append(heads.joinToString(" | ")).append('\n')
            rows.forEach { r -> append(r.cells.joinToString(" | ") { it.replace('\n', ' ').ifEmpty { "–" } }).append('\n') }
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "${tab.label} – ${ship().name}")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Share list"))
    }

    private fun confirmRestore() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore from backup?")
            .setMessage("Everything currently in the app (structure, jobs, work done, photos, certificates and documents) will be replaced by the backup file.")
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

    /** Date picker. With [allowClear], a "No date" button returns null. */
    private fun pickDate(initial: Long?, allowClear: Boolean = false, onPicked: (Long?) -> Unit) {
        val c = Calendar.getInstance().apply { if (initial != null) timeInMillis = initial }
        val dlg = DatePickerDialog(this, { _, y, m, d ->
            onPicked(Calendar.getInstance().apply { set(y, m, d, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        if (allowClear) dlg.setButton(DialogInterface.BUTTON_NEUTRAL, "No date") { _, _ -> onPicked(null) }
        dlg.show()
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

// ---- Shared helpers (also used by the viewers) ----

fun uriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

fun mimeOf(file: File): String = when (file.extension.lowercase()) {
    "pdf" -> "application/pdf"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    else -> "*/*"
}

fun shareFile(context: Context, file: File) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(mimeOf(file))
        .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
        .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share ${file.nameWithoutExtension}"))
}

fun openWithOtherApp(context: Context, file: File) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uriFor(context, file), mimeOf(file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(view, "Open with"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}
