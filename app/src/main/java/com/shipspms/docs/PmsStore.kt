package com.shipspms.docs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
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

enum class JobStatus { OVERDUE, DUE_SOON, OK, DONE }
enum class CertStatus { EXPIRED, SURVEY_OVERDUE, DUE_SOON, OK }

/**
 * A vessel. Each ship has its own structure, jobs, work records and certificates.
 * A locked ship is read-only (e.g. after signing off); an optional PIN is needed to unlock it.
 */
data class Ship(
    val id: String,
    var name: String,
    var imo: String = "",
    var locked: Boolean = false,
    var pinHash: String? = null,
    var pinSalt: String? = null,
)

/** A place in the ship structure: a group (e.g. Engine Room), sub-group (Main Engine) or item (Fuel Pump). */
data class Node(
    val id: String,
    var parentId: String?,
    var name: String,
    var runningHours: Long? = null,
    var hoursUpdated: Long? = null,
    var shipId: String = "",
)

/**
 * A maintenance job. Recurring jobs repeat every interval; one-time jobs (defects, inspection findings)
 * have a due date and are closed by recording the work done.
 */
data class Job(
    val id: String,
    var nodeId: String,
    var title: String,
    var intervalValue: Int,
    var unit: IntervalUnit,
    var lastDoneDate: Long,
    var lastDoneHours: Long?,
    var instructions: String,
    var recurring: Boolean = true,
    var dueOnce: Long? = null,
    var completed: Long? = null,
    var created: Long = System.currentTimeMillis(),
)

/** A record of maintenance actually carried out. Photos live in files/pms_photos/<id>/. */
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

/** A ship or equipment certificate. nodeId == null means a ship certificate. Files in files/pms_certs/<id>/. */
data class Certificate(
    val id: String,
    var nodeId: String?,
    var name: String,
    var number: String,
    var issuedBy: String,
    var issueDate: Long?,
    var expiryDate: Long?,
    var surveyDate: Long?,
    var remarks: String,
    var shipId: String = "",
)

data class Doc(val file: File, val nodeId: String) {
    val name: String get() = file.nameWithoutExtension
}

/**
 * All data lives on the device:
 *   files/pms.json                    – tree, jobs, work records, certificates
 *   files/pms_docs/<nodeId>/x.pdf     – documents attached to each tree item
 *   files/pms_photos/<recordId>/x.jpg – photos of work done
 *   files/pms_certs/<certId>/…        – certificate scans (PDF or photo)
 */
class PmsStore private constructor(context: Context) {

    private val ctx = context.applicationContext
    private val dataFile = File(ctx.filesDir, "pms.json")
    private val docsRoot = File(ctx.filesDir, "pms_docs")
    private val photosRoot = File(ctx.filesDir, "pms_photos")
    private val certsRoot = File(ctx.filesDir, "pms_certs")

    val ships = mutableListOf<Ship>()
    val nodes = mutableListOf<Node>()
    val jobs = mutableListOf<Job>()
    val records = mutableListOf<WorkRecord>()
    val certs = mutableListOf<Certificate>()

    companion object {
        @Volatile private var instance: PmsStore? = null
        fun get(context: Context): PmsStore =
            instance ?: synchronized(this) { instance ?: PmsStore(context).also { instance = it } }

        fun newId(): String = UUID.randomUUID().toString()
        const val DAY = 24L * 60 * 60 * 1000

        fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        /** Whole days from today until [time] (0 = today, negative = past). */
        fun daysUntil(time: Long): Long = Math.floorDiv(time - startOfToday(), DAY)
    }

    init {
        if (dataFile.exists()) load() else migrateFromV1()
        ensureShips()
    }

    /** Older versions had no ships: put everything into one ship. */
    private fun ensureShips() {
        if (ships.isEmpty()) ships += Ship(newId(), "My Ship")
        val first = ships.first().id
        var changed = false
        // Top-level items without a ship go to the first ship; children follow their parent.
        nodes.filter { it.parentId == null && ships.none { s -> s.id == it.shipId } }.forEach { it.shipId = first; changed = true }
        repeat(50) {
            nodes.filter { it.parentId != null }.forEach { n ->
                val parentShip = node(n.parentId)?.shipId ?: first
                if (n.shipId != parentShip) { n.shipId = parentShip; changed = true }
            }
        }
        certs.forEach { c ->
            val want = c.nodeId?.let { node(it)?.shipId } ?: c.shipId.takeIf { id -> ships.any { it.id == id } } ?: first
            if (c.shipId != want) { c.shipId = want; changed = true }
        }
        if (changed || !dataFile.exists()) save()
    }

    // ---------- Persistence ----------

    private fun load() {
        ships.clear(); nodes.clear(); jobs.clear(); records.clear(); certs.clear()
        val root = JSONObject(dataFile.readText())
        root.optJSONArray("ships")?.forEachObject { o ->
            ships += Ship(
                id = o.getString("id"),
                name = o.getString("name"),
                imo = o.optString("imo"),
                locked = o.optBoolean("locked", false),
                pinHash = o.optStringOrNull("pinHash"),
                pinSalt = o.optStringOrNull("pinSalt"),
            )
        }
        root.optJSONArray("nodes")?.forEachObject { o ->
            nodes += Node(
                id = o.getString("id"),
                parentId = o.optStringOrNull("parentId"),
                name = o.getString("name"),
                runningHours = o.optLongOrNull("runningHours"),
                hoursUpdated = o.optLongOrNull("hoursUpdated"),
                shipId = o.optString("shipId"),
            )
        }
        root.optJSONArray("jobs")?.forEachObject { o ->
            jobs += Job(
                id = o.getString("id"),
                nodeId = o.getString("nodeId"),
                title = o.getString("title"),
                intervalValue = o.optInt("intervalValue", 1),
                unit = runCatching { IntervalUnit.valueOf(o.getString("unit")) }.getOrDefault(IntervalUnit.MONTHS),
                lastDoneDate = o.optLong("lastDoneDate", System.currentTimeMillis()),
                lastDoneHours = o.optLongOrNull("lastDoneHours"),
                instructions = o.optString("instructions"),
                recurring = o.optBoolean("recurring", true),
                dueOnce = o.optLongOrNull("dueOnce"),
                completed = o.optLongOrNull("completed"),
                created = o.optLong("created", o.optLong("lastDoneDate", System.currentTimeMillis())),
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
        root.optJSONArray("certs")?.forEachObject { o ->
            certs += Certificate(
                id = o.getString("id"),
                nodeId = o.optStringOrNull("nodeId"),
                name = o.getString("name"),
                number = o.optString("number"),
                issuedBy = o.optString("issuedBy"),
                issueDate = o.optLongOrNull("issueDate"),
                expiryDate = o.optLongOrNull("expiryDate"),
                surveyDate = o.optLongOrNull("surveyDate"),
                remarks = o.optString("remarks"),
                shipId = o.optString("shipId"),
            )
        }
    }

    fun save() {
        val root = JSONObject()
            .put("version", 3)
            .put("ships", JSONArray().apply {
                ships.forEach {
                    put(JSONObject().put("id", it.id).put("name", it.name).put("imo", it.imo)
                        .put("locked", it.locked).put("pinHash", it.pinHash).put("pinSalt", it.pinSalt))
                }
            })
            .put("nodes", JSONArray().apply {
                nodes.forEach {
                    put(JSONObject().put("id", it.id).put("parentId", it.parentId).put("name", it.name)
                        .put("runningHours", it.runningHours).put("hoursUpdated", it.hoursUpdated)
                        .put("shipId", it.shipId))
                }
            })
            .put("jobs", JSONArray().apply {
                jobs.forEach {
                    put(JSONObject().put("id", it.id).put("nodeId", it.nodeId).put("title", it.title)
                        .put("intervalValue", it.intervalValue).put("unit", it.unit.name)
                        .put("lastDoneDate", it.lastDoneDate).put("lastDoneHours", it.lastDoneHours)
                        .put("instructions", it.instructions).put("recurring", it.recurring)
                        .put("dueOnce", it.dueOnce).put("completed", it.completed).put("created", it.created))
                }
            })
            .put("records", JSONArray().apply {
                records.forEach {
                    put(JSONObject().put("id", it.id).put("nodeId", it.nodeId).put("jobId", it.jobId)
                        .put("title", it.title).put("date", it.date).put("doneBy", it.doneBy)
                        .put("hours", it.hours).put("remarks", it.remarks))
                }
            })
            .put("certs", JSONArray().apply {
                certs.forEach {
                    put(JSONObject().put("id", it.id).put("nodeId", it.nodeId).put("name", it.name)
                        .put("number", it.number).put("issuedBy", it.issuedBy).put("issueDate", it.issueDate)
                        .put("expiryDate", it.expiryDate).put("surveyDate", it.surveyDate).put("remarks", it.remarks)
                        .put("shipId", it.shipId))
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
        val ship = Ship(newId(), "My Ship").also { ships += it }
        if (folders.isNotEmpty()) {
            val parent = addNode(null, "Imported documents", ship.id, persist = false)
            folders.forEach { dir ->
                val child = addNode(parent.id, dir.name, ship.id, persist = false)
                val target = File(docsRoot, child.id).apply { mkdirs() }
                dir.listFiles { f -> f.name.endsWith(".pdf", true) }?.forEach { it.renameTo(File(target, it.name)) }
            }
        }
        save()
    }

    // ---------- Tree ----------

    // ---------- Ships ----------

    fun ship(id: String?): Ship? = id?.let { i -> ships.firstOrNull { it.id == i } }

    fun addShip(name: String, imo: String = ""): Ship {
        val s = Ship(newId(), name.trim(), imo.trim())
        ships += s
        save()
        return s
    }

    /** Deletes a ship with everything recorded for it. */
    fun deleteShip(id: String) {
        nodes.filter { it.shipId == id && it.parentId == null }.forEach { deleteNode(it.id) }
        certs.filter { it.shipId == id }.forEach { certDir(it.id).deleteRecursively() }
        certs.removeAll { it.shipId == id }
        ships.removeAll { it.id == id }
        if (ships.isEmpty()) ships += Ship(newId(), "My Ship")
        save()
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun lockShip(ship: Ship, pin: String?) {
        ship.locked = true
        if (pin.isNullOrEmpty()) {
            ship.pinHash = null; ship.pinSalt = null
        } else {
            ship.pinSalt = newId()
            ship.pinHash = hash(ship.pinSalt!!, pin)
        }
        save()
    }

    /** Returns false if the PIN is wrong. */
    fun unlockShip(ship: Ship, pin: String?): Boolean {
        val expected = ship.pinHash
        if (expected != null && hash(ship.pinSalt.orEmpty(), pin.orEmpty()) != expected) return false
        ship.locked = false
        ship.pinHash = null; ship.pinSalt = null
        save()
        return true
    }

    // ---------- Tree ----------

    fun node(id: String?): Node? = id?.let { i -> nodes.firstOrNull { it.id == i } }

    /** Children of [parentId]; for the top level (parentId == null) only those of [shipId]. */
    fun children(parentId: String?, shipId: String): List<Node> =
        nodes.filter { it.parentId == parentId && (parentId != null || it.shipId == shipId) }

    fun addNode(parentId: String?, name: String, shipId: String, persist: Boolean = true): Node {
        val ship = node(parentId)?.shipId ?: shipId
        val n = Node(newId(), parentId, name.trim(), shipId = ship)
        nodes += n
        if (persist) save()
        return n
    }

    /** The node and everything below it. Null means the whole of [shipId]. */
    fun subtreeIds(id: String?, shipId: String): Set<String> {
        if (id == null) return nodes.filter { it.shipId == shipId }.map { it.id }.toSet()
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
        val ids = subtreeIds(id, "")
        records.filter { it.nodeId in ids }.forEach { photoDir(it.id).deleteRecursively() }
        certs.filter { it.nodeId in ids }.forEach { certDir(it.id).deleteRecursively() }
        nodes.removeAll { it.id in ids }
        jobs.removeAll { it.nodeId in ids }
        records.removeAll { it.nodeId in ids }
        certs.removeAll { it.nodeId in ids }
        ids.forEach { File(docsRoot, it).deleteRecursively() }
        save()
    }

    fun seedExample(shipId: String) {
        fun group(parent: String?, name: String, vararg kids: String): Node {
            val n = addNode(parent, name, shipId, persist = false)
            kids.forEach { addNode(n.id, it, shipId, persist = false) }
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
        if (!job.recurring) return job.dueOnce
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

    fun isHours(job: Job) = job.recurring && job.unit == IntervalUnit.HOURS

    fun dueHours(job: Job): Long? =
        if (isHours(job)) (job.lastDoneHours ?: 0L) + job.intervalValue else null

    /** Hours left before the job is due (negative = overdue). Null if the item has no running hours yet. */
    fun hoursLeft(job: Job): Long? {
        val due = dueHours(job) ?: return null
        val current = node(job.nodeId)?.runningHours ?: return null
        return due - current
    }

    /**
     * Days until the job is due (negative = overdue). Running-hours jobs are estimated assuming
     * the machinery runs continuously (24 h a day), so the estimate is never later than reality.
     */
    fun daysLeft(job: Job): Long? {
        if (job.completed != null) return null
        if (isHours(job)) return hoursLeft(job)?.let { Math.floorDiv(it, 24L) }
        return dueDate(job)?.let { daysUntil(it) }
    }

    fun status(job: Job): JobStatus {
        if (!job.recurring && job.completed != null) return JobStatus.DONE
        if (isHours(job)) {
            val left = hoursLeft(job) ?: return JobStatus.OK
            return when {
                left < 0 -> JobStatus.OVERDUE
                left <= maxOf(job.intervalValue / 10L, 1L) -> JobStatus.DUE_SOON
                else -> JobStatus.OK
            }
        }
        val due = dueDate(job) ?: return JobStatus.OK
        val days = daysUntil(due)
        val span = if (job.recurring) (due - job.lastDoneDate) / DAY else 70
        val window = (span / 10).coerceIn(7, 30)
        return when {
            days < 0 -> JobStatus.OVERDUE
            days <= window -> JobStatus.DUE_SOON
            else -> JobStatus.OK
        }
    }

    /** Ids of nodes that have an overdue job anywhere below them (for the red dot in the tree). */
    fun nodesWithOverdue(shipId: String): Set<String> {
        val out = mutableSetOf<String>()
        jobs.filter { node(it.nodeId)?.shipId == shipId && status(it) == JobStatus.OVERDUE }.forEach { job ->
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

    /**
     * Saves a work record. For a recurring job, "last done" moves forward;
     * a one-time job is marked completed.
     */
    fun upsertRecord(rec: WorkRecord) {
        val i = records.indexOfFirst { it.id == rec.id }
        if (i >= 0) records[i] = rec else records += rec
        rec.jobId?.let { syncJobFromRecords(it) }
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
        photoDir(rec.id).deleteRecursively()
        rec.jobId?.let { syncJobFromRecords(it) }
        save()
    }

    private fun syncJobFromRecords(jobId: String) {
        val job = jobs.firstOrNull { it.id == jobId } ?: return
        val latest = records.filter { it.jobId == jobId }.maxByOrNull { it.date }
        if (job.recurring) {
            if (latest != null && latest.date >= job.lastDoneDate) {
                job.lastDoneDate = latest.date
                if (latest.hours != null) job.lastDoneHours = latest.hours
            }
        } else {
            job.completed = latest?.date
        }
    }

    // ---------- Photos of work done ----------

    fun photoDir(recordId: String) = File(photosRoot, recordId)

    fun photos(recordId: String): List<File> =
        photoDir(recordId).listFiles { f -> f.isFile }?.sortedBy { it.name }.orEmpty()

    // ---------- Certificates ----------

    fun certDir(certId: String) = File(certsRoot, certId)

    fun certFiles(certId: String): List<File> =
        certDir(certId).listFiles { f -> f.isFile }?.sortedBy { it.name }.orEmpty()

    /** The nearest of expiry and survey dates. */
    fun certNextDate(c: Certificate): Long? = listOfNotNull(c.expiryDate, c.surveyDate).minOrNull()

    fun certStatus(c: Certificate): CertStatus {
        val today = startOfToday()
        val exp = c.expiryDate
        val survey = c.surveyDate
        return when {
            exp != null && exp < today -> CertStatus.EXPIRED
            survey != null && survey < today -> CertStatus.SURVEY_OVERDUE
            certNextDate(c)?.let { daysUntil(it) <= 30 } == true -> CertStatus.DUE_SOON
            else -> CertStatus.OK
        }
    }

    fun upsertCert(c: Certificate) {
        val i = certs.indexOfFirst { it.id == c.id }
        if (i >= 0) certs[i] = c else certs += c
        save()
    }

    fun deleteCert(c: Certificate) {
        certs.removeAll { it.id == c.id }
        certDir(c.id).deleteRecursively()
        save()
    }

    // ---------- Documents ----------

    fun docs(nodeIds: Set<String>): List<Doc> = nodeIds.flatMap { id ->
        File(docsRoot, id).listFiles { f -> f.isFile && f.name.endsWith(".pdf", true) }
            ?.map { Doc(it, id) }.orEmpty()
    }

    /** Copies a picked/shared PDF into the item's folder. Blocking I/O: call off the main thread. */
    fun importDoc(uri: Uri, nodeId: String): File = copyInto(uri, File(docsRoot, nodeId), "pdf")

    /**
     * Copies a picked file into [dir]: images are resized to save space, anything else is copied as-is.
     * Blocking I/O: call off the main thread.
     */
    fun importAttachment(uri: Uri, dir: File): File {
        val type = ctx.contentResolver.getType(uri).orEmpty()
        val name = queryDisplayName(uri).orEmpty()
        val isImage = type.startsWith("image/") ||
            name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic")
        if (isImage) {
            dir.mkdirs()
            val target = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
            Images.saveScaled(ctx, uri, target)
            return target
        }
        val ext = name.substringAfterLast('.', "pdf").lowercase().take(5).ifBlank { "pdf" }
        return copyInto(uri, dir, ext)
    }

    private fun copyInto(uri: Uri, dir: File, ext: String): File {
        val display = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Document"
        val base = sanitize(display.substringBeforeLast('.', display)).ifBlank { "Document" }
        val target = uniqueFile(dir, base, ext)
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot read $uri" }
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    fun renameDoc(doc: Doc, newName: String): Boolean {
        val base = sanitize(newName).ifBlank { return false }
        if (base == doc.name) return true
        return doc.file.renameTo(uniqueFile(doc.file.parentFile!!, base, "pdf"))
    }

    fun moveDoc(doc: Doc, nodeId: String): Boolean =
        doc.file.renameTo(uniqueFile(File(docsRoot, nodeId), doc.name, "pdf"))

    private fun queryDisplayName(uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun sanitize(name: String) =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(150)

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        dir.mkdirs()
        var candidate = File(dir, "$base.$ext")
        var n = 2
        while (candidate.exists()) candidate = File(dir, "$base (${n++}).$ext")
        return candidate
    }

    // ---------- Backup ----------

    private fun fileRoots() = mapOf("docs" to docsRoot, "photos" to photosRoot, "certs" to certsRoot)

    /** Writes pms.json plus every document, photo and certificate scan into one zip file. */
    fun backup(out: OutputStream) {
        save()
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("pms.json"))
            dataFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            fileRoots().forEach { (prefix, root) ->
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry("$prefix/" + f.relativeTo(root).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
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

        fileRoots().forEach { (prefix, root) ->
            root.deleteRecursively()
            File(staging, prefix).let { if (it.exists()) it.copyRecursively(root, overwrite = true) }
        }
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
