package com.shipspms.docs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class IntervalUnit(val label: String, val short: String) {
    DAYS("Days", "days"),
    WEEKS("Weeks", "weeks"),
    MONTHS("Months", "months"),
    YEARS("Years", "years"),
    HOURS("Running hours", "h"),
}

enum class JobStatus { OVERDUE, DUE_SOON, OK }

/** A place in the ship structure: a group (e.g. Engine Room), sub-group (Main Engine) or item (Fuel Pump). */
data class Node(
    val id: String,
    var parentId: String?,
    var name: String,
    var runningHours: Long? = null,
    var hoursUpdated: Long? = null,
)

/** A planned maintenance job with a fixed interval. */
data class Job(
    val id: String,
    var nodeId: String,
    var title: String,
    var intervalValue: Int,
    var unit: IntervalUnit,
    var lastDoneDate: Long,
    var lastDoneHours: Long?,
    var instructions: String,
)

/** A record of maintenance actually carried out (planned or unplanned). */
data class WorkRecord(
    val id: String,
    var nodeId: String,
    var jobId: String?,
    var title: String,
    var date: Long,
    var doneBy: String,
    var hours: Long?,
    var remarks: String,
)

data class Doc(val file: File, val nodeId: String) {
    val name: String get() = file.nameWithoutExtension
}

/**
 * All data lives on the device:
 *   files/pms.json                 – tree, jobs and work records
 *   files/pms_docs/<nodeId>/x.pdf  – documents attached to each tree item
 */
class PmsStore private constructor(context: Context) {

    private val ctx = context.applicationContext
    private val dataFile = File(ctx.filesDir, "pms.json")
    private val docsRoot = File(ctx.filesDir, "pms_docs")

    val nodes = mutableListOf<Node>()
    val jobs = mutableListOf<Job>()
    val records = mutableListOf<WorkRecord>()

    companion object {
        @Volatile private var instance: PmsStore? = null
        fun get(context: Context): PmsStore =
            instance ?: synchronized(this) { instance ?: PmsStore(context).also { instance = it } }

        fun newId(): String = UUID.randomUUID().toString()
        private const val DAY = 24L * 60 * 60 * 1000
    }

    init {
        if (dataFile.exists()) load() else migrateFromV1()
    }

    // ---------- Persistence ----------

    private fun load() {
        nodes.clear(); jobs.clear(); records.clear()
        val root = JSONObject(dataFile.readText())
        root.optJSONArray("nodes")?.forEachObject { o ->
            nodes += Node(
                id = o.getString("id"),
                parentId = o.optStringOrNull("parentId"),
                name = o.getString("name"),
                runningHours = o.optLongOrNull("runningHours"),
                hoursUpdated = o.optLongOrNull("hoursUpdated"),
            )
        }
        root.optJSONArray("jobs")?.forEachObject { o ->
            jobs += Job(
                id = o.getString("id"),
                nodeId = o.getString("nodeId"),
                title = o.getString("title"),
                intervalValue = o.getInt("intervalValue"),
                unit = runCatching { IntervalUnit.valueOf(o.getString("unit")) }.getOrDefault(IntervalUnit.MONTHS),
                lastDoneDate = o.getLong("lastDoneDate"),
                lastDoneHours = o.optLongOrNull("lastDoneHours"),
                instructions = o.optString("instructions"),
            )
        }
        root.optJSONArray("records")?.forEachObject { o ->
            records += WorkRecord(
                id = o.getString("id"),
                nodeId = o.getString("nodeId"),
                jobId = o.optStringOrNull("jobId"),
                title = o.getString("title"),
                date = o.getLong("date"),
                doneBy = o.optString("doneBy"),
                hours = o.optLongOrNull("hours"),
                remarks = o.optString("remarks"),
            )
        }
    }

    fun save() {
        val root = JSONObject()
            .put("version", 1)
            .put("nodes", JSONArray().apply {
                nodes.forEach {
                    put(JSONObject().put("id", it.id).put("parentId", it.parentId).put("name", it.name)
                        .put("runningHours", it.runningHours).put("hoursUpdated", it.hoursUpdated))
                }
            })
            .put("jobs", JSONArray().apply {
                jobs.forEach {
                    put(JSONObject().put("id", it.id).put("nodeId", it.nodeId).put("title", it.title)
                        .put("intervalValue", it.intervalValue).put("unit", it.unit.name)
                        .put("lastDoneDate", it.lastDoneDate).put("lastDoneHours", it.lastDoneHours)
                        .put("instructions", it.instructions))
                }
            })
            .put("records", JSONArray().apply {
                records.forEach {
                    put(JSONObject().put("id", it.id).put("nodeId", it.nodeId).put("jobId", it.jobId)
                        .put("title", it.title).put("date", it.date).put("doneBy", it.doneBy)
                        .put("hours", it.hours).put("remarks", it.remarks))
                }
            })
        val tmp = File(dataFile.parentFile, "pms.json.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(dataFile)) {
            dataFile.delete(); tmp.renameTo(dataFile)
        }
    }

    /** Version 1 kept PDFs in files/documents/<Category>/. Bring them into the tree. */
    private fun migrateFromV1() {
        val old = File(ctx.filesDir, "documents")
        val folders = old.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
            .filter { dir -> dir.listFiles { f -> f.name.endsWith(".pdf", true) }?.isNotEmpty() == true }
        if (folders.isNotEmpty()) {
            val parent = addNode(null, "Imported documents", persist = false)
            folders.forEach { dir ->
                val child = addNode(parent.id, dir.name, persist = false)
                val target = File(docsRoot, child.id).apply { mkdirs() }
                dir.listFiles { f -> f.name.endsWith(".pdf", true) }?.forEach { it.renameTo(File(target, it.name)) }
            }
        }
        save()
    }

    // ---------- Tree ----------

    fun node(id: String?): Node? = id?.let { i -> nodes.firstOrNull { it.id == i } }
    fun children(parentId: String?): List<Node> = nodes.filter { it.parentId == parentId }

    fun addNode(parentId: String?, name: String, persist: Boolean = true): Node {
        val n = Node(newId(), parentId, name.trim())
        nodes += n
        if (persist) save()
        return n
    }

    /** The node and everything below it. Null means the whole ship. */
    fun subtreeIds(id: String?): Set<String> {
        if (id == null) return nodes.map { it.id }.toSet()
        val out = mutableSetOf(id)
        var frontier = listOf(id)
        while (frontier.isNotEmpty()) {
            frontier = nodes.filter { it.parentId in frontier }.map { it.id }
            out += frontier
        }
        return out
    }

    fun path(id: String?): String {
        val parts = mutableListOf<String>()
        var cur = node(id)
        var guard = 0
        while (cur != null && guard++ < 50) {
            parts += cur.name
            cur = node(cur.parentId)
        }
        return parts.reversed().joinToString(" › ")
    }

    fun deleteNode(id: String) {
        val ids = subtreeIds(id)
        nodes.removeAll { it.id in ids }
        jobs.removeAll { it.nodeId in ids }
        records.removeAll { it.nodeId in ids }
        ids.forEach { File(docsRoot, it).deleteRecursively() }
        save()
    }

    fun seedExample() {
        fun group(parent: String?, name: String, vararg kids: String): Node {
            val n = addNode(parent, name, persist = false)
            kids.forEach { addNode(n.id, it, persist = false) }
            return n
        }
        val er = group(null, "Engine Room")
        group(er.id, "Main Engine", "Fuel Injection Pumps", "Turbocharger", "Cylinder Units", "Lub Oil System")
        group(er.id, "Auxiliary Engines", "Generator No.1", "Generator No.2", "Generator No.3")
        group(er.id, "Purifiers", "HFO Purifier", "LO Purifier")
        group(er.id, "Pumps", "Main Cooling SW Pump", "Fire & GS Pump", "Bilge Pump")
        group(er.id, "Air Compressors", "Main Air Compressor No.1", "Main Air Compressor No.2")
        group(null, "Deck Machinery", "Windlass", "Mooring Winches", "Cranes", "Hatch Covers")
        group(null, "Steering Gear", "Hydraulic Pumps", "Rudder Actuator")
        group(null, "Safety Equipment", "Lifeboat & Davit", "Fire Detection System", "Emergency Fire Pump", "Emergency Generator")
        group(null, "Electrical", "Main Switchboard", "Emergency Switchboard", "Batteries")
        save()
    }

    // ---------- Jobs & status ----------

    fun dueDate(job: Job): Long? {
        if (job.unit == IntervalUnit.HOURS) return null
        val field = when (job.unit) {
            IntervalUnit.DAYS -> Calendar.DAY_OF_YEAR
            IntervalUnit.WEEKS -> Calendar.WEEK_OF_YEAR
            IntervalUnit.MONTHS -> Calendar.MONTH
            else -> Calendar.YEAR
        }
        return Calendar.getInstance().apply {
            timeInMillis = job.lastDoneDate
            add(field, job.intervalValue)
        }.timeInMillis
    }

    fun dueHours(job: Job): Long? =
        if (job.unit == IntervalUnit.HOURS) (job.lastDoneHours ?: 0L) + job.intervalValue else null

    /** Hours left before the job is due (negative = overdue). Null if the item has no running hours yet. */
    fun hoursLeft(job: Job): Long? {
        val due = dueHours(job) ?: return null
        val current = node(job.nodeId)?.runningHours ?: return null
        return due - current
    }

    /** 0.0 = just done, 1.0 = due now, above 1 = overdue. Used for sorting by urgency. */
    fun progress(job: Job): Double {
        return if (job.unit == IntervalUnit.HOURS) {
            val left = hoursLeft(job) ?: return 0.0
            1.0 - left.toDouble() / job.intervalValue.coerceAtLeast(1)
        } else {
            val due = dueDate(job) ?: return 0.0
            val span = (due - job.lastDoneDate).coerceAtLeast(1)
            (System.currentTimeMillis() - job.lastDoneDate).toDouble() / span
        }
    }

    fun status(job: Job): JobStatus {
        if (job.unit == IntervalUnit.HOURS) {
            val left = hoursLeft(job) ?: return JobStatus.OK
            return when {
                left < 0 -> JobStatus.OVERDUE
                left <= maxOf(job.intervalValue / 10L, 1L) -> JobStatus.DUE_SOON
                else -> JobStatus.OK
            }
        }
        val due = dueDate(job) ?: return JobStatus.OK
        val now = System.currentTimeMillis()
        val window = ((due - job.lastDoneDate) / 10).coerceIn(7 * DAY, 30 * DAY)
        return when {
            now > due -> JobStatus.OVERDUE
            due - now <= window -> JobStatus.DUE_SOON
            else -> JobStatus.OK
        }
    }

    /** Ids of nodes that have an overdue job anywhere below them (for the red dot in the tree). */
    fun nodesWithOverdue(): Set<String> {
        val out = mutableSetOf<String>()
        jobs.filter { status(it) == JobStatus.OVERDUE }.forEach { job ->
            var cur = node(job.nodeId)
            var guard = 0
            while (cur != null && guard++ < 50 && out.add(cur.id)) cur = node(cur.parentId)
        }
        return out
    }

    fun upsertJob(job: Job) {
        val i = jobs.indexOfFirst { it.id == job.id }
        if (i >= 0) jobs[i] = job else jobs += job
        save()
    }

    fun deleteJob(job: Job) {
        jobs.removeAll { it.id == job.id }
        save()
    }

    /** Saves a work record; if it belongs to a job, that job's "last done" moves forward. */
    fun upsertRecord(rec: WorkRecord) {
        val i = records.indexOfFirst { it.id == rec.id }
        if (i >= 0) records[i] = rec else records += rec
        rec.jobId?.let { jid ->
            jobs.firstOrNull { it.id == jid }?.let { job ->
                val latest = records.filter { it.jobId == jid }.maxByOrNull { it.date }
                if (latest != null && latest.date >= job.lastDoneDate) {
                    job.lastDoneDate = latest.date
                    if (latest.hours != null) job.lastDoneHours = latest.hours
                }
            }
        }
        rec.hours?.let { h ->
            node(rec.nodeId)?.let { n ->
                if (n.runningHours == null || h > n.runningHours!!) {
                    n.runningHours = h; n.hoursUpdated = System.currentTimeMillis()
                }
            }
        }
        save()
    }

    fun deleteRecord(rec: WorkRecord) {
        records.removeAll { it.id == rec.id }
        save()
    }

    // ---------- Documents ----------

    fun docs(nodeIds: Set<String>): List<Doc> = nodeIds.flatMap { id ->
        File(docsRoot, id).listFiles { f -> f.isFile && f.name.endsWith(".pdf", true) }
            ?.map { Doc(it, id) }.orEmpty()
    }

    /** Copies a picked/shared PDF into the item's folder. Blocking I/O: call off the main thread. */
    fun importDoc(uri: Uri, nodeId: String): File {
        val display = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Document"
        val base = sanitize(display.substringBeforeLast('.', display)).ifBlank { "Document" }
        val target = uniqueFile(File(docsRoot, nodeId), base)
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot read $uri" }
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    fun renameDoc(doc: Doc, newName: String): Boolean {
        val base = sanitize(newName).ifBlank { return false }
        if (base == doc.name) return true
        return doc.file.renameTo(uniqueFile(doc.file.parentFile!!, base))
    }

    fun moveDoc(doc: Doc, nodeId: String): Boolean =
        doc.file.renameTo(uniqueFile(File(docsRoot, nodeId), doc.name))

    private fun queryDisplayName(uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun sanitize(name: String) =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(150)

    private fun uniqueFile(dir: File, base: String): File {
        dir.mkdirs()
        var candidate = File(dir, "$base.pdf")
        var n = 2
        while (candidate.exists()) candidate = File(dir, "$base (${n++}).pdf")
        return candidate
    }

    // ---------- Backup ----------

    /** Writes pms.json and every document into one zip file. */
    fun backup(out: OutputStream) {
        save()
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("pms.json"))
            dataFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            docsRoot.walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry("docs/" + f.relativeTo(docsRoot).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Replaces all current data with the backup. Throws if the file is not a valid backup. */
    fun restore(input: InputStream) {
        val staging = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        val stagingPath = staging.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(staging, entry.name)
                require(target.canonicalPath.startsWith(stagingPath)) { "Invalid backup file" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        val json = File(staging, "pms.json")
        require(json.exists()) { "This is not a Ship PMS backup" }
        JSONObject(json.readText()) // validate before replacing anything

        docsRoot.deleteRecursively()
        File(staging, "docs").let { if (it.exists()) it.copyRecursively(docsRoot, overwrite = true) }
        json.copyTo(dataFile, overwrite = true)
        staging.deleteRecursively()
        load()
    }
}

// ---------- JSON helpers ----------

private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) block(getJSONObject(i))
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null
