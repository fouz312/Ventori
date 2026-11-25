package com.vantora.app.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.vantora.app.compat.PeAnalyzer
import com.vantora.app.core.AppDb
import com.vantora.app.core.Category
import com.vantora.app.core.LibraryEntry
import com.vantora.app.media.MediaFormat
import com.vantora.app.media.MediaInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FileInputStream

/**
 * Persistent file library built on the Storage Access Framework. Only URIs + analysis are stored;
 * the user's original files are never copied, modified or deleted here.
 */
class LibraryRepository(private val ctx: Context, db: AppDb) {
    private val dao = db.libraryDao()
    val entries: Flow<List<LibraryEntry>> = dao.all()

    private fun queryNameSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return name to size
    }

    private fun persist(uri: Uri): Boolean = try {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true
    } catch (e: Exception) { false }

    private fun categoryFor(ext: String): String = when (ext) {
        "iso" -> Category.WINDOWS_IMAGE
        "img", "vhd", "vhdx", "vmdk", "qcow2", "qcow" -> Category.VIRTUAL_DISK
        "exe" -> Category.EXE
        "msi" -> Category.MSI
        "apk" -> Category.APK
        else -> Category.OTHER
    }

    suspend fun import(uri: Uri): Result<LibraryEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val persisted = persist(uri)
            dao.byUri(uri.toString())?.let { return@runCatching it }
            val (name, size) = queryNameSize(uri)
            val ext = name.substringAfterLast('.', "").lowercase()
            var cat = categoryFor(ext)
            var meta = JSONObject().put("persisted", persisted)

            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val ch = FileInputStream(pfd.fileDescriptor).channel
                when (cat) {
                    Category.WINDOWS_IMAGE, Category.VIRTUAL_DISK -> {
                        val mi = MediaInspector.inspectChannel(ch, name)
                        meta = LibraryMeta.mediaJson(meta, mi)
                        cat = when (mi.format) {
                            MediaFormat.ISO -> Category.WINDOWS_IMAGE
                            MediaFormat.VHD, MediaFormat.VHDX, MediaFormat.QCOW2, MediaFormat.VMDK, MediaFormat.RAW_DISK -> Category.VIRTUAL_DISK
                            MediaFormat.UNKNOWN -> cat
                        }
                    }
                    Category.EXE -> PeAnalyzer.analyze(ch)?.let { meta = LibraryMeta.peJson(meta, it, false) }
                    Category.MSI -> meta.put("isMsi", PeAnalyzer.isMsi(ch)).put("machine", "UNKNOWN")
                    else -> {}
                }
                true
            } ?: error("Cannot open the file")

            val e = LibraryEntry(uri = uri.toString(), displayName = name, category = cat, sizeBytes = size,
                addedAt = System.currentTimeMillis(), metaJson = meta.toString())
            e.copy(id = dao.insert(e))
        }
    }

    suspend fun get(id: Long) = dao.get(id)

    suspend fun rescan(): Int = withContext(Dispatchers.IO) {
        var missing = 0
        for (e in dao.allOnce()) {
            val ok = try { ctx.contentResolver.openFileDescriptor(Uri.parse(e.uri), "r")?.use { true } ?: false } catch (t: Throwable) { false }
            if (!ok) missing++
            if (ok == e.missing) dao.update(e.copy(missing = !ok))
        }
        missing
    }

    /** User picked the file again after access was revoked. */
    suspend fun reconnect(e: LibraryEntry, newUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            persist(newUri)
            val clash = dao.byUri(newUri.toString())
            if (clash != null && clash.id != e.id) error("That file is already in the library as '${clash.title}'.")
            val (name, size) = queryNameSize(newUri)
            dao.update(e.copy(uri = newUri.toString(), displayName = name, sizeBytes = size, missing = false))
        }
    }

    suspend fun remove(e: LibraryEntry) = withContext(Dispatchers.IO) {
        try { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(e.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        dao.delete(e)   // only the library row; the user's file is untouched
    }

    suspend fun rename(e: LibraryEntry, name: String) = withContext(Dispatchers.IO) { dao.update(e.copy(customName = name.ifBlank { null })) }
    suspend fun toggleFavorite(e: LibraryEntry) = withContext(Dispatchers.IO) { dao.update(e.copy(favorite = !e.favorite)) }
    suspend fun markUsed(e: LibraryEntry) = withContext(Dispatchers.IO) { dao.update(e.copy(lastUsedAt = System.currentTimeMillis())) }
    suspend fun saveConfig(e: LibraryEntry, args: String, env: String) = withContext(Dispatchers.IO) {
        dao.update(e.copy(metaJson = LibraryMeta.withConfig(e, args, env)))
    }
}
