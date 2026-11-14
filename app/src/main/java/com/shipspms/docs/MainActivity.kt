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

    private enum class Tab(val label: String) { DOCS("Documents"), SCHEDULE("Schedule"), HISTORY("Work done"), CERTS("Certificates"), PARTS("Spare parts") }

    /** "Due by" filters. Each one includes overdue work, so it is the full list of what must be done by then. */
    private enum class DueFilter(val label: String, val maxDays: Long?) {
        ALL("Any time", null),
        OVERDUE("Overdue", -1),
        TODAY("Due today", 0),
        WEEK("Within a week", 7),
        MONTH("Within a month", 30),
        YEAR("Within a year", 365),
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

    private fun setupList() {
        rowAdapter = RowAdapter(
            onClick = { payload ->
                when (payload) {
                    is Doc -> openFiles(listOf(payload.file), 0, payload.name)
                    is Job -> showJobDetails(payload)
                    is WorkRecord -> if (ship().locked) showRecordDetails(payload)
                        else showRecordDialog(payload.nodeId, store.jobs.firstOrNull { it.id == payload.jobId }, payload)
                    is Certificate -> if (ship().locked) showCertDetails(payload) else showCertDialog(payload, payload.nodeId)
                    is SparePart -> showPartDetails(payload)
                }
            },
            onMore = { payload, anchor ->
                when (payload) {
                    is Doc -> showDocMenu(payload, anchor)
                    is Job -> showJobMenu(payload)
                    is WorkRecord -> showRecordMenu(payload, anchor)
                    is Certificate -> showCertMenu(payload, anchor)
                    is SparePart -> showPartMenu(payload, anchor)
                }
            },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        list.adapter = rowAdapter
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
        val lowParts = partsInScope().count { store.partStatus(it) != PartStatus.OK }
        if (lowParts > 0) parts += "$lowParts parts low/out of stock"
        headerInfo.text = parts.joinToString(" · ")
        headerHours.visibility = if (node == null || ship().locked) View.GONE else View.VISIBLE
        if (ship().locked) parts.add(0, "🔒 Locked")
        headerInfo.text = parts.joinToString(" · ")

        tabs.getTabAt(Tab.SCHEDULE.ordinal)?.orCreateBadge?.apply { isVisible = overdue > 0; number = overdue }
        tabs.getTabAt(Tab.CERTS.ordinal)?.orCreateBadge?.apply { isVisible = certBad > 0; number = certBad }
        tabs.getTabAt(Tab.PARTS.ordinal)?.orCreateBadge?.apply { isVisible = lowParts > 0; number = lowParts }
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
        clearChips(chips); clearChips(chips2)
        dueChips.clear()
        when (tab) {
            Tab.SCHEDULE -> {
                fillChips(chips, TypeFilter.entries, typeFilter, { it.label }) {
                    typeFilter = it
                    chips.post { refreshChips(); refreshList() }
                }
                if (typeFilter != TypeFilter.DONE) {
                    dueChips += fillChips(chips2, DueFilter.entries, dueFilter, { it.label }) {
                        dueFilter = it; refreshList()
                    }
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
            Tab.PARTS -> fillChips(chips, PartFilter.entries, partFilter, { it.label }) {
                partFilter = it; refreshList()
            }
            Tab.DOCS -> {}
        }
        (chips.parent as View).visibility = if (chips.childCount > 0) View.VISIBLE else View.GONE
        (chips2.parent as View).visibility = if (chips2.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun refreshFab() {
        fab.visibility = if (ship().locked) View.GONE else View.VISIBLE
        fab.text = when (tab) {
            Tab.DOCS -> getString(R.string.upload_pdf)
            Tab.SCHEDULE -> getString(R.string.add_job)
            Tab.HISTORY -> getString(R.string.log_work)
            Tab.CERTS -> getString(R.string.add_cert)
            Tab.PARTS -> getString(R.string.add_part)
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
                        key = d.file.absolutePath, icon = R.drawable.ic_pdf, iconTint = null,
                        title = d.name,
                        line2 = store.path(d.nodeId),
                        line3 = android.text.format.Formatter.formatShortFileSize(this, d.file.length()) +
                            " · added " + dateFmt.format(Date(d.file.lastModified())),
                        payload = d,
                    )
                }

            Tab.SCHEDULE -> {
                val base = store.jobs.filter { it.nodeId in ids && matchesType(it) }
                // Show how many jobs each "due by" filter would give.
                dueChips.forEach { (f, chip) ->
                    val n = base.count { matchesDue(it, f) }
                    chip.text = if (f == DueFilter.ALL) f.label else "${f.label} ($n)"
                }
                base.filter { matchesDue(it, dueFilter) }
                    .filter { matches(it.title, store.path(it.nodeId), it.instructions) }
                    .let { l ->
                        if (typeFilter == TypeFilter.DONE) l.sortedByDescending { it.completed }
                        else l.sortedWith(compareBy(nullsLast<Long>()) { store.daysLeft(it) })
                    }
                    .map { j ->
                        val status = store.status(j)
                        RowItem(
                            key = j.id, icon = R.drawable.ic_build, iconTint = statusColor(status),
                            title = j.title,
                            line2 = store.path(j.nodeId),
                            line3 = describeJob(j),
                            badge = when (status) {
                                JobStatus.OVERDUE -> "OVERDUE"; JobStatus.DUE_SOON -> "DUE SOON"
                                JobStatus.DONE -> "DONE"; JobStatus.OK -> if (!j.recurring) "ONE-TIME" else null
                            },
                            color = statusColor(status),
                            payload = j,
                        )
                    }
            }

            Tab.HISTORY -> store.records
                .filter { it.nodeId in ids && (historyJobId == null || it.jobId == historyJobId) }
                .filter { matches(it.title, store.path(it.nodeId), it.doneBy, it.remarks) }
                .sortedByDescending { it.date }
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

            Tab.PARTS -> partsInScope()
                .filter { p ->
                    when (partFilter) {
                        PartFilter.ALL -> true
                        PartFilter.LOW -> store.partStatus(p) != PartStatus.OK
                        PartFilter.OUT -> store.partStatus(p) == PartStatus.OUT
                        PartFilter.UNLINKED -> p.nodeIds.none { store.node(it) != null }
                    }
                }
                .filter { matches(it.name, it.partNumber, it.maker, it.location, it.remarks, store.partUnits(it).joinToString(" ")) }
                .sortedWith(compareBy<SparePart> { store.partStatus(it).ordinal }.thenBy { it.name.lowercase() })
                .map { p ->
                    val status = store.partStatus(p)
                    val color = when (status) {
                        PartStatus.OUT -> getColor(R.color.status_overdue)
                        PartStatus.LOW -> getColor(R.color.status_soon)
                        PartStatus.OK -> getColor(R.color.status_ok)
                    }
                    val units = store.partUnits(p)
                    RowItem(
                        key = p.id, icon = R.drawable.ic_part, iconTint = color,
                        title = p.name,
                        line2 = listOfNotNull(
                            p.partNumber.takeIf { it.isNotBlank() }?.let { "P/N $it" },
                            "${p.quantity} ${p.unit} in stock" + if (p.minQuantity > 0) " (min ${p.minQuantity})" else "",
                            p.location.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        line3 = if (units.isEmpty()) "Not linked to a unit" else "Used on: " + units.joinToString(", "),
                        badge = when (status) { PartStatus.OUT -> "OUT OF STOCK"; PartStatus.LOW -> "LOW STOCK"; PartStatus.OK -> null },
                        color = color,
                        payload = p,
                    )
                }

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

        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            store.subtreeIds(null, shipId).isEmpty() && tab != Tab.CERTS -> "Start by adding your machinery in the Ship structure (☰ menu, top left)."
            query.isNotEmpty() -> "Nothing matches “$query”."
            tab == Tab.DOCS -> "No documents here yet.\nTap “Upload PDF” to add manuals, drawings or certificates."
            tab == Tab.SCHEDULE && (dueFilter != DueFilter.ALL || typeFilter != TypeFilter.OPEN) -> "No jobs in this list. 👍"
            tab == Tab.SCHEDULE -> "No planned maintenance yet.\nTap “Add job”: recurring (e.g. “Clean filters every 3 months”) or one-time (e.g. a defect found during inspection)."
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
                pickPdfs.launch(arrayOf("application/pdf"))
            }
            Tab.SCHEDULE -> withItem("Add job to which item?") { id -> showJobDialog(null, id) }
            Tab.HISTORY -> withItem("Log work on which item?") { id -> showRecordDialog(id, null, null) }
            Tab.CERTS -> showCertDialog(null, selectedId)
            Tab.PARTS -> showPartDialog(null)
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

    private fun showJobDialog(existing: Job?, nodeId: String) {
        val v = layoutInflater.inflate(R.layout.dialog_job, null)
        val node = store.node(nodeId)
        v.findViewById<TextView>(R.id.item_path).text = store.path(nodeId)
        val titleLayout = v.findViewById<TextInputLayout>(R.id.job_title_layout)
        val title = v.findViewById<TextInputEditText>(R.id.job_title)
        val recurring = v.findViewById<MaterialSwitch>(R.id.recurring)
        val recurringGroup = v.findViewById<View>(R.id.recurring_group)
        val interval = v.findViewById<TextInputEditText>(R.id.interval)
        val unit = v.findViewById<Spinner>(R.id.unit)
        val lastDate = v.findViewById<TextInputEditText>(R.id.last_date)
        val lastHoursLayout = v.findViewById<TextInputLayout>(R.id.last_hours_layout)
        val lastHours = v.findViewById<TextInputEditText>(R.id.last_hours)
        val dueLayout = v.findViewById<TextInputLayout>(R.id.due_once_layout)
        val dueField = v.findViewById<TextInputEditText>(R.id.due_once)
        val instructions = v.findViewById<TextInputEditText>(R.id.instructions)

        unit.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, IntervalUnit.entries.map { it.label })
        fun updateVisibility() {
            val rec = recurring.isChecked
            recurringGroup.visibility = if (rec) View.VISIBLE else View.GONE
            dueLayout.visibility = if (rec) View.GONE else View.VISIBLE
            lastHoursLayout.visibility =
                if (rec && IntervalUnit.entries[unit.selectedItemPosition] == IntervalUnit.HOURS) View.VISIBLE else View.GONE
        }
        unit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) = updateVisibility()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        recurring.setOnCheckedChangeListener { _, _ -> updateVisibility() }

        var date = existing?.lastDoneDate ?: System.currentTimeMillis()
        var due = existing?.dueOnce ?: (PmsStore.startOfToday() + 7 * PmsStore.DAY + 12 * 3600_000L)
        title.setText(existing?.title)
        recurring.isChecked = existing?.recurring ?: true
        interval.setText(existing?.takeIf { it.recurring }?.intervalValue?.toString())
        unit.setSelection((existing?.unit ?: IntervalUnit.MONTHS).ordinal)
        lastHours.setText((existing?.lastDoneHours ?: node?.runningHours)?.toString())
        instructions.setText(existing?.instructions)
        lastDate.setText(dateFmt.format(Date(date)))
        lastDate.setOnClickListener { pickDate(date) { date = it!!; lastDate.setText(dateFmt.format(Date(date))) } }
        dueField.setText(dateFmt.format(Date(due)))
        dueField.setOnClickListener { pickDate(due) { due = it!!; dueField.setText(dateFmt.format(Date(due))) } }
        updateVisibility()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "New maintenance job" else "Edit job")
            .setView(v)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = title.text.toString().trim()
                val rec = recurring.isChecked
                val n = interval.text.toString().toIntOrNull()
                titleLayout.error = if (t.isEmpty()) "Enter the job" else null
                interval.error = if (rec && (n == null || n <= 0)) "Required" else null
                if (t.isEmpty() || (rec && (n == null || n <= 0))) return@setOnClickListener
                val u = IntervalUnit.entries[unit.selectedItemPosition]
                val job = (existing ?: Job(PmsStore.newId(), nodeId, t, n ?: 1, u, date, null, "")).apply {
                    this.title = t
                    this.recurring = rec
                    this.instructions = instructions.text.toString().trim()
                    if (rec) {
                        intervalValue = n!!
                        this.unit = u
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
            menu.add(0, 1, 1, "Edit")
            menu.add(0, 2, 2, "Delete")
            setOnMenuItemClickListener { item ->
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
    // Spare parts
    // =====================================================================

    /** Parts linked to the selected part of the ship; with the ship selected, all of its parts. */
    private fun partsInScope(): List<SparePart> {
        val shipParts = store.parts.filter { it.shipId == shipId }
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
        options += "Delete part" to {
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
        if (all.isEmpty()) { toast("No spare parts yet. Add them in the Spare parts tab."); return }
        val related = mutableSetOf(nodeId)
        var p = store.node(nodeId)?.parentId
        while (p != null) { related += p; p = store.node(p)?.parentId }
        related += store.subtreeIds(nodeId, shipId)
        val linked = all.filter { part -> part.nodeIds.any { it in related } }
        fun pickFrom(list: List<SparePart>, showAllOption: Boolean) {
            val labels = list.map { "${it.name}  (${it.quantity} ${it.unit})" } + if (showAllOption) listOf("Show all spare parts…") else emptyList()
            MaterialAlertDialogBuilder(this)
                .setTitle(if (showAllOption) "Parts for ${store.node(nodeId)?.name}" else "All spare parts")
                .setItems(labels.toTypedArray()) { _, i ->
                    if (i < list.size) onPick(list[i]) else pickFrom(all, false)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        if (linked.isEmpty()) pickFrom(all, false) else pickFrom(linked, true)
    }

    private fun showPartDialog(existing: SparePart?) {
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
        val files = FileSession(v, store.partDir(partId), store.partFiles(partId), "Photos (optional)", allowPdf = true, title = "Spare part")
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
            .setTitle(if (existing == null) "New spare part" else "Edit spare part")
            .setView(v)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        files.attachTo(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                nameLayout.error = if (n.isEmpty()) "Enter the part name" else null
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
        }

        fun showError(msg: String) {
            error.text = msg
            error.visibility = View.VISIBLE
        }

        private fun render() {
            label.text = "$labelText · ${files.size}"
            strip.removeAllViews()
            val size = (76 * resources.displayMetrics.density).toInt()
            val margin = (6 * resources.displayMetrics.density).toInt()
            files.forEachIndexed { index, f ->
                val iv = ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = margin; bottomMargin = margin }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0x22888888)
                    contentDescription = f.name
                    if (Images.isImage(f)) setImageBitmap(Images.thumbnail(f, size))
                    else { setImageResource(R.drawable.ic_pdf); scaleType = ImageView.ScaleType.CENTER_INSIDE }
                    setOnClickListener { openFiles(files.toList(), index, title) }
                    setOnLongClickListener {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setMessage("Remove this ${if (Images.isImage(f)) "photo" else "file"}?")
                            .setPositiveButton("Remove") { _, _ ->
                                files.remove(f)
                                if (added.remove(f)) f.delete() else removed += f
                                render()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                        true
                    }
                }
                strip.addView(iv)
            }
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
    private fun openFiles(files: List<File>, index: Int, title: String) {
        val file = files.getOrNull(index) ?: return
        if (Images.isImage(file)) {
            val images = files.filter { Images.isImage(it) }
            startActivity(
                Intent(this, PhotoViewerActivity::class.java)
                    .putStringArrayListExtra(PhotoViewerActivity.EXTRA_PATHS, ArrayList(images.map { it.absolutePath }))
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
    // Sharing the current list, backup
    // =====================================================================

    /** Shares what is on screen (e.g. "One-time jobs due within a week") as plain text. */
    private fun shareCurrentList() {
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
