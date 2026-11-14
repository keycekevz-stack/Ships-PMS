package com.shipspms.docs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

data class ShipDocument(
    val file: File,
    val category: String,
    val favorite: Boolean,
    val lastOpened: Long,
) {
    val name: String get() = file.nameWithoutExtension
    val key: String get() = "$category/${file.name}"
}

/**
 * Stores PDFs inside the app's private storage, one folder per category:
 *   files/documents/<Category>/<name>.pdf
 * Files stay on the device, so everything works offline at sea.
 * Favorites and "last opened" times are kept in SharedPreferences, keyed by "<Category>/<file name>".
 */
class DocumentRepository(context: Context) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "documents")
    private val prefs = appContext.getSharedPreferences("doc_meta", Context.MODE_PRIVATE)

    companion object {
        val CATEGORIES = listOf(
            "Certificates",
            "Manuals",
            "PMS / Maintenance",
            "Procedures / SMS",
            "Checklists",
            "Drawings & Plans",
            "Reports & Forms",
            "Other",
        )

        private const val FAV = "fav:"
        private const val OPENED = "opened:"
    }

    init {
        CATEGORIES.forEach { dirFor(it).mkdirs() }
    }

    /** Folder names can't contain "/", so categories like "PMS / Maintenance" are mapped to a safe name. */
    private fun dirFor(category: String) = File(root, category.replace("/", "-"))

    fun all(): List<ShipDocument> = CATEGORIES.flatMap { category ->
        dirFor(category).listFiles { f -> f.isFile && f.name.endsWith(".pdf", ignoreCase = true) }
            ?.map { file ->
                val key = "$category/${file.name}"
                ShipDocument(
                    file = file,
                    category = category,
                    favorite = prefs.getBoolean(FAV + key, false),
                    lastOpened = prefs.getLong(OPENED + key, 0L),
                )
            }
            ?: emptyList()
    }

    fun find(path: String): ShipDocument? = all().firstOrNull { it.file.absolutePath == path }

    /** Copies a picked/shared PDF into app storage. Runs blocking I/O: call from a background thread. */
    fun import(uri: Uri, category: String): File {
        val resolver = appContext.contentResolver
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Document"
        val base = sanitize(displayName.substringBeforeLast('.', displayName)).ifBlank { "Document" }
        val target = uniqueFile(dirFor(category), base)
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot read $uri" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    fun rename(doc: ShipDocument, newName: String): ShipDocument? {
        val base = sanitize(newName).ifBlank { return null }
        if (base == doc.name) return doc
        val target = uniqueFile(doc.file.parentFile!!, base)
        if (!doc.file.renameTo(target)) return null
        return migrate(doc, target, doc.category)
    }

    fun move(doc: ShipDocument, category: String): ShipDocument? {
        if (category == doc.category) return doc
        val target = uniqueFile(dirFor(category), doc.name)
        if (!doc.file.renameTo(target)) return null
        return migrate(doc, target, category)
    }

    fun delete(doc: ShipDocument): Boolean {
        val ok = doc.file.delete()
        if (ok) prefs.edit().remove(FAV + doc.key).remove(OPENED + doc.key).apply()
        return ok
    }

    fun setFavorite(doc: ShipDocument, favorite: Boolean) {
        prefs.edit().putBoolean(FAV + doc.key, favorite).apply()
    }

    fun markOpened(doc: ShipDocument) {
        prefs.edit().putLong(OPENED + doc.key, System.currentTimeMillis()).apply()
    }

    private fun migrate(old: ShipDocument, newFile: File, newCategory: String): ShipDocument {
        val updated = old.copy(file = newFile, category = newCategory)
        prefs.edit()
            .remove(FAV + old.key).remove(OPENED + old.key)
            .putBoolean(FAV + updated.key, old.favorite)
            .putLong(OPENED + updated.key, old.lastOpened)
            .apply()
        return updated
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
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
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n).pdf")
            n++
        }
        return candidate
    }
}
