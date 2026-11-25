package com.vantora.app.core

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** A user file that was imported through the Storage Access Framework (URI only; the file itself is never copied or deleted). */
@Entity(tableName = "library", indices = [Index("uri", unique = true)])
data class LibraryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val customName: String? = null,
    val category: String,
    val sizeBytes: Long,
    val favorite: Boolean = false,
    val addedAt: Long,
    val lastUsedAt: Long? = null,
    val missing: Boolean = false,
    val metaJson: String = "{}"
) {
    val title: String get() = customName?.takeIf { it.isNotBlank() } ?: displayName
}

/** Virtual machine configuration. Disk + firmware live in app storage and are kept apart from library entries. */
@Entity(tableName = "vms")
data class VmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val guestArch: String,        // "ARM64" | "X64"
    val ramMb: Int,
    val cores: Int,
    val diskGb: Int,
    val width: Int,
    val height: Int,
    val network: Boolean,
    val isoEntryId: Long?,        // install media (library entry); null = detached
    val diskPath: String,
    val createdAt: Long,
    val lastStartedAt: Long? = null
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library ORDER BY addedAt DESC") fun all(): Flow<List<LibraryEntry>>
    @Query("SELECT * FROM library") suspend fun allOnce(): List<LibraryEntry>
    @Query("SELECT * FROM library WHERE id = :id") suspend fun get(id: Long): LibraryEntry?
    @Query("SELECT * FROM library WHERE uri = :uri") suspend fun byUri(uri: String): LibraryEntry?
    @Insert suspend fun insert(e: LibraryEntry): Long
    @Update suspend fun update(e: LibraryEntry)
    @Delete suspend fun delete(e: LibraryEntry)
}

@Dao
interface VmDao {
    @Query("SELECT * FROM vms ORDER BY createdAt DESC") fun all(): Flow<List<VmEntity>>
    @Query("SELECT * FROM vms WHERE id = :id") suspend fun get(id: Long): VmEntity?
    @Insert suspend fun insert(v: VmEntity): Long
    @Update suspend fun update(v: VmEntity)
    @Delete suspend fun delete(v: VmEntity)
}

@Database(entities = [LibraryEntry::class, VmEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun vmDao(): VmDao
}
