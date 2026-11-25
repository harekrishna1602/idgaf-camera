package com.studiomogli.idgafcamera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class RecordedClip(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folderName: String // "" for root Movies/IDGAF_Camera, or subfolder name
)

data class ZipArchiveItem(
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
    val legacyFile: File? = null
)

class ClipGalleryActivity : AppCompatActivity() {

    companion object {
        private const val FOLDER_ALL = "__ALL__"
        private const val FOLDER_ROOT = ""
        private const val PREFS_NAME = "idgaf_vault_folders_v1"
        private const val KEY_FOLDERS = "custom_folders_set"
        private const val ARCHIVE_SUBDIR = "IDGAF_Archives"
    }

    private lateinit var recycler: RecyclerView
    private lateinit var txtEmpty: TextView
    private lateinit var txtCount: TextView
    private lateinit var folderChipRow: LinearLayout
    private lateinit var btnViewArchives: TextView
    private lateinit var btnNewFolder: TextView
    private lateinit var btnToggleSelect: TextView
    private lateinit var bulkActionBar: LinearLayout
    private lateinit var txtSelectedCount: TextView
    private lateinit var btnSelectAll: TextView
    private lateinit var btnBulkZip: TextView
    private lateinit var btnBulkMove: TextView
    private lateinit var btnBulkShare: TextView
    private lateinit var btnBulkDelete: TextView

    private val allClips = mutableListOf<RecordedClip>()
    private val visibleClips = mutableListOf<RecordedClip>()
    private val selectedIds = linkedSetOf<Long>()
    private var isMultiSelectMode = false
    private var activeFolderFilter: String = FOLDER_ALL
    private val knownFolders = linkedSetOf<String>()

    private lateinit var adapter: ClipAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_clip_gallery)

        recycler = findViewById(R.id.recyclerClips)
        txtEmpty = findViewById(R.id.txtGalleryEmpty)
        txtCount = findViewById(R.id.txtGalleryCount)
        folderChipRow = findViewById(R.id.folderChipRow)
        btnViewArchives = findViewById(R.id.btnViewArchives)
        btnNewFolder = findViewById(R.id.btnNewFolder)
        btnToggleSelect = findViewById(R.id.btnToggleSelect)
        bulkActionBar = findViewById(R.id.bulkActionBar)
        txtSelectedCount = findViewById(R.id.txtSelectedCount)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnBulkZip = findViewById(R.id.btnBulkZip)
        btnBulkMove = findViewById(R.id.btnBulkMove)
        btnBulkShare = findViewById(R.id.btnBulkShare)
        btnBulkDelete = findViewById(R.id.btnBulkDelete)

        findViewById<TextView>(R.id.btnGalleryBack).setOnClickListener { finish() }

        btnViewArchives.setOnClickListener {
            showSavedArchivesDialog()
        }

        btnNewFolder.setOnClickListener {
            showCreateFolderDialog(moveSelectionAfterCreate = false)
        }

        btnToggleSelect.setOnClickListener {
            setMultiSelectMode(!isMultiSelectMode)
        }

        btnSelectAll.setOnClickListener {
            if (selectedIds.size == visibleClips.size && visibleClips.isNotEmpty()) {
                selectedIds.clear()
            } else {
                selectedIds.clear()
                visibleClips.forEach { selectedIds.add(it.id) }
            }
            updateSelectionUi()
            adapter.notifyDataSetChanged()
        }

        btnBulkZip.setOnClickListener {
            if (selectedIds.isEmpty()) {
                Toast.makeText(this, "Select at least 1 clip to ZIP.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showZipOptionsDialog()
        }

        btnBulkMove.setOnClickListener {
            if (selectedIds.isEmpty()) {
                Toast.makeText(this, "Select at least 1 clip first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showMoveToFolderDialog()
        }

        btnBulkShare.setOnClickListener {
            shareSelectedClips()
        }

        btnBulkDelete.setOnClickListener {
            confirmDeleteSelectedClips()
        }

        adapter = ClipAdapter(
            clips = visibleClips,
            onCardClick = { clip ->
                if (isMultiSelectMode) {
                    toggleClipSelection(clip.id)
                } else {
                    playClip(clip)
                }
            },
            onCardLongClick = { clip ->
                if (!isMultiSelectMode) {
                    setMultiSelectMode(true)
                }
                toggleClipSelection(clip.id)
            },
            onShare = { shareClip(it) },
            onDelete = { confirmDeleteClip(it) }
        )
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter

        loadSavedFolders()
        loadClips()
    }

    // ==================== PHASE 6.12: PRO ZERO-COMPRESSION ZIP ARCHIVER ====================

    private fun showZipOptionsDialog() {
        val targets = allClips.filter { it.id in selectedIds }
        if (targets.isEmpty()) return

        val totalBytes = targets.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        val totalMb = totalBytes / (1024f * 1024f)

        // Check available device storage before creating archive (+12 MB safety buffer)
        val freeBytes = try {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
        if (totalBytes > 0L && freeBytes < totalBytes + (12L * 1024L * 1024L)) {
            val needMb = (totalBytes / (1024f * 1024f)) + 12f
            val availMb = freeBytes / (1024f * 1024f)
            IdgafUiDialogs.showActionDialog(
                context = this,
                title = "LOW STORAGE",
                message = String.format(
                    Locale.US,
                    "Selected clips need ~%.1f MB free to build the ZIP, but only %.1f MB is available.",
                    needMb,
                    availMb
                ),
                primaryLabel = "OK",
                onPrimary = {},
                cancelLabel = null
            )
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val folderPrefix = if (activeFolderFilter != FOLDER_ALL && activeFolderFilter.isNotEmpty()) {
            sanitizeFolderName(activeFolderFilter).replace(" ", "_")
        } else {
            "Takes"
        }
        val defaultName = "IDGAF_${folderPrefix}_$timestamp"

        val msg = String.format(
            Locale.US,
            "%d clip(s) • %.1f MB (Lossless STORED → Downloads/%s/)",
            targets.size,
            totalMb,
            ARCHIVE_SUBDIR
        )
        IdgafUiDialogs.showTextInputDialog(
            context = this,
            title = "CREATE LOSSLESS ZIP",
            message = msg,
            hint = "Archive filename (without .zip)",
            initialText = defaultName,
            primaryLabel = "ZIP & KEEP",
            onPrimary = { raw ->
                val clean = sanitizeArchiveName(raw, defaultName)
                startZipArchiveJob(targets, clean, clearOriginalsAfterVerify = false)
            },
            secondaryLabel = "ZIP & CLEAR",
            secondaryTextColor = 0xFFFF5F1F.toInt(),
            onSecondary = { raw ->
                val clean = sanitizeArchiveName(raw, defaultName)
                IdgafUiDialogs.showActionDialog(
                    context = this,
                    title = "ZIP & CLEAR ORIGINALS?",
                    message = "All ${targets.size} original .mp4 file(s) will be deleted ONLY AFTER $clean.zip passes 100% verification.",
                    primaryLabel = "ZIP & CLEAR",
                    onPrimary = { startZipArchiveJob(targets, clean, clearOriginalsAfterVerify = true) },
                    cancelLabel = "CANCEL"
                )
            }
        )
    }

    private fun sanitizeArchiveName(raw: String, fallback: String): String {
        val stripped = raw.trim().removeSuffix(".zip").removeSuffix(".ZIP")
        val clean = stripped
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(48)
        return clean.ifEmpty { fallback }
    }

    private fun startZipArchiveJob(
        targets: List<RecordedClip>,
        baseName: String,
        clearOriginalsAfterVerify: Boolean
    ) {
        val zipFileName = "$baseName.zip"
        val cancelled = AtomicBoolean(false)

        val progHandle = IdgafUiDialogs.showProgressDialog(
            context = this,
            title = if (clearOriginalsAfterVerify) "ARCHIVING & VERIFYING..." else "BUILDING ZIP ARCHIVE...",
            initialStage = "Preparing ${targets.size} clip(s)...",
            onCancel = { cancelled.set(true) }
        )
        val progressDialog = progHandle.dialog
        val txtStage = progHandle.txtStage
        val progressBar = progHandle.progressBar
        val txtBytes = progHandle.txtBytes

        fun updateProgressUi(stage: String, pct: Int, doneBytes: Long, totalBytes: Long) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    txtStage.text = stage
                    progressBar.progress = pct.coerceIn(0, 100)
                    txtBytes.text = String.format(
                        Locale.US,
                        "%.1f / %.1f MB (%d%%)",
                        doneBytes / (1024f * 1024f),
                        totalBytes.coerceAtLeast(1L) / (1024f * 1024f),
                        pct.coerceIn(0, 100)
                    )
                }
            }
        }

        Thread {
            var createdUri: Uri? = null
            var legacyZipFile: File? = null
            val expectedEntrySizes = linkedMapOf<String, Long>()
            val totalExpectedBytes = targets.sumOf { it.sizeBytes.coerceAtLeast(1L) }.coerceAtLeast(1L)
            var processedWorkBytes = 0L
            // Two passes per clip: CRC32 pass (weight 0.35) + STORED write pass (weight 0.65)
            val totalWorkUnits = (totalExpectedBytes * 100L).coerceAtLeast(100L)

            try {
                // 1. Open destination ZIP output stream in Downloads/IDGAF_Archives/
                val rawOut = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, zipFileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            "${Environment.DIRECTORY_DOWNLOADS}/$ARCHIVE_SUBDIR/"
                        )
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Could not create archive in Downloads/$ARCHIVE_SUBDIR")
                    createdUri = uri
                    contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Could not open archive stream")
                } else {
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        ARCHIVE_SUBDIR
                    )
                    if (!dir.exists()) dir.mkdirs()
                    val outFile = File(dir, zipFileName)
                    legacyZipFile = outFile
                    createdUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", outFile)
                    FileOutputStream(outFile)
                }

                val usedNames = hashSetOf<String>()
                val buf = ByteArray(65_536)

                ZipOutputStream(BufferedOutputStream(rawOut, 65_536)).use { zos ->
                    zos.setMethod(ZipOutputStream.STORED)

                    for ((idx, clip) in targets.withIndex()) {
                        if (cancelled.get()) throw InterruptedException("Cancelled by user")

                        // Build unique entry path inside the ZIP (preserving subfolder name if present)
                        val baseEntryName = if (clip.folderName.isNotEmpty()) {
                            "${clip.folderName}/${clip.name}"
                        } else {
                            clip.name
                        }
                        var entryName = baseEntryName
                        var dupSuffix = 2
                        while (!usedNames.add(entryName)) {
                            val dot = baseEntryName.lastIndexOf('.')
                            entryName = if (dot > 0) {
                                "${baseEntryName.substring(0, dot)}_$dupSuffix${baseEntryName.substring(dot)}"
                            } else {
                                "${baseEntryName}_$dupSuffix"
                            }
                            dupSuffix++
                        }

                        // Pass 1: Stream CRC32 + exact byte count (required for zero-compression STORED)
                        val crc = CRC32()
                        var exactSize = 0L
                        contentResolver.openInputStream(clip.uri)?.use { ins ->
                            var read: Int
                            while (ins.read(buf).also { read = it } != -1) {
                                if (cancelled.get()) throw InterruptedException("Cancelled by user")
                                crc.update(buf, 0, read)
                                exactSize += read
                                processedWorkBytes += (read * 35L)
                                val pct = ((processedWorkBytes * 100L) / totalWorkUnits).toInt()
                                updateProgressUi(
                                    "Checking clip ${idx + 1}/${targets.size}: ${clip.name}",
                                    pct,
                                    processedWorkBytes / 100L,
                                    totalExpectedBytes
                                )
                            }
                        } ?: throw IllegalStateException("Cannot read ${clip.name}")

                        // Pass 2: Write raw STORED bytes directly into ZIP (no CPU re-compression!)
                        val entry = ZipEntry(entryName).apply {
                            method = ZipEntry.STORED
                            size = exactSize
                            compressedSize = exactSize
                            this.crc = crc.value
                            time = System.currentTimeMillis()
                        }
                        zos.putNextEntry(entry)
                        var written = 0L
                        contentResolver.openInputStream(clip.uri)?.use { ins ->
                            var read: Int
                            while (ins.read(buf).also { read = it } != -1) {
                                if (cancelled.get()) throw InterruptedException("Cancelled by user")
                                zos.write(buf, 0, read)
                                written += read
                                processedWorkBytes += (read * 65L)
                                val pct = ((processedWorkBytes * 100L) / totalWorkUnits).toInt()
                                updateProgressUi(
                                    "Packing ${idx + 1}/${targets.size}: ${clip.name}",
                                    pct,
                                    processedWorkBytes / 100L,
                                    totalExpectedBytes
                                )
                            }
                        } ?: throw IllegalStateException("Cannot read ${clip.name} on write pass")
                        zos.closeEntry()

                        if (written != exactSize) {
                            throw IllegalStateException("Short write on ${clip.name}: $written vs $exactSize")
                        }
                        expectedEntrySizes[entryName] = exactSize
                    }
                }

                // Mark MediaStore item finished so it's visible & readable by verifier + Share sheet
                val finalUri = createdUri ?: throw IllegalStateException("Archive URI missing")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val doneVals = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    contentResolver.update(finalUri, doneVals, null, null)
                }

                // 2. Verify archive integrity before touching any original files!
                var clearedOriginalsCount = 0
                if (clearOriginalsAfterVerify) {
                    updateProgressUi("Verifying archive integrity...", 99, totalExpectedBytes, totalExpectedBytes)
                    val verifiedOk = verifyZipArchiveIntegrity(finalUri, expectedEntrySizes, cancelled)
                    if (!verifiedOk) {
                        throw IllegalStateException("Archive verification failed — originals were NOT deleted.")
                    }

                    updateProgressUi("Clearing ${targets.size} original clip(s)...", 100, totalExpectedBytes, totalExpectedBytes)
                    for (clip in targets) {
                        try {
                            if (contentResolver.delete(clip.uri, null, null) > 0) {
                                clearedOriginalsCount++
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                // Query final archive size in bytes
                val finalZipBytes = queryUriSizeBytes(finalUri, legacyZipFile)
                val archiveItem = ZipArchiveItem(
                    name = zipFileName,
                    uri = finalUri,
                    sizeBytes = finalZipBytes,
                    legacyFile = legacyZipFile
                )

                runOnUiThread {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    setMultiSelectMode(false)
                    loadClips()
                    showZipCompleteDialog(
                        archive = archiveItem,
                        clipCount = targets.size,
                        originalsCleared = clearedOriginalsCount,
                        clearRequested = clearOriginalsAfterVerify
                    )
                }
            } catch (e: Exception) {
                // Cleanup partial archive on cancel or failure
                cleanupZipArchive(createdUri, legacyZipFile)
                val wasCancelled = cancelled.get() || e is InterruptedException
                runOnUiThread {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    Toast.makeText(
                        this,
                        if (wasCancelled) "ZIP cancelled." else "ZIP failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun verifyZipArchiveIntegrity(
        zipUri: Uri,
        expectedEntrySizes: Map<String, Long>,
        cancelled: AtomicBoolean
    ): Boolean {
        val foundEntries = linkedMapOf<String, Long>()
        val buf = ByteArray(65_536)
        contentResolver.openInputStream(zipUri)?.use { rawIn ->
            ZipInputStream(rawIn).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (cancelled.get()) return false
                    var counted = 0L
                    var read: Int
                    while (zis.read(buf).also { read = it } != -1) {
                        counted += read
                    }
                    foundEntries[entry.name] = counted
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: return false

        if (foundEntries.size != expectedEntrySizes.size) return false
        for ((name, expectedBytes) in expectedEntrySizes) {
            if (foundEntries[name] != expectedBytes) return false
        }
        return true
    }

    private fun queryUriSizeBytes(uri: Uri, legacyFile: File?): Long {
        if (legacyFile != null && legacyFile.exists()) return legacyFile.length()
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun cleanupZipArchive(uri: Uri?, legacyFile: File?) {
        try {
            if (legacyFile != null && legacyFile.exists()) {
                legacyFile.delete()
            } else if (uri != null) {
                contentResolver.delete(uri, null, null)
            }
        } catch (_: Exception) {
        }
    }

    private fun showZipCompleteDialog(
        archive: ZipArchiveItem,
        clipCount: Int,
        originalsCleared: Int,
        clearRequested: Boolean
    ) {
        val mb = archive.sizeBytes / (1024f * 1024f)
        val statusLine = if (clearRequested) {
            "Verified & cleared $originalsCleared/$clipCount original clip(s) from device."
        } else {
            "All $clipCount original clip(s) kept in Clip Vault."
        }

        lateinit var dialog: AlertDialog
        dialog = IdgafUiDialogs.showActionDialog(
            context = this,
            title = "ARCHIVE READY",
            message = String.format(
                Locale.US,
                "%s (%.1f MB)\nSaved: Downloads/%s/\n\n%s",
                archive.name,
                mb,
                ARCHIVE_SUBDIR,
                statusLine
            ),
            primaryLabel = "SHARE ZIP",
            dismissOnPrimary = false,
            onPrimary = { shareZipArchive(archive) },
            secondaryLabel = "DEL ZIP",
            secondaryTextColor = 0xFFFF3B30.toInt(),
            dismissOnSecondary = false,
            onSecondary = {
                confirmDeleteZipArchive(archive) { dialog.dismiss() }
            },
            cancelLabel = "DONE"
        )
    }

    private fun shareZipArchive(archive: ZipArchiveItem) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, archive.uri)
                putExtra(Intent.EXTRA_SUBJECT, archive.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share ${archive.name}"))
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to share ZIP archive.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteZipArchive(archive: ZipArchiveItem, onDeleted: (() -> Unit)? = null) {
        IdgafUiDialogs.showActionDialog(
            context = this,
            title = "DELETE ZIP ARCHIVE?",
            message = "Remove ${archive.name} from Downloads/$ARCHIVE_SUBDIR?",
            primaryLabel = "DELETE ZIP",
            primaryBgRes = R.drawable.bg_shutter_stop,
            primaryTextColor = 0xFFFFFFFF.toInt(),
            onPrimary = {
                cleanupZipArchive(archive.uri, archive.legacyFile)
                Toast.makeText(this, "Deleted ${archive.name}", Toast.LENGTH_SHORT).show()
                onDeleted?.invoke()
            },
            cancelLabel = "CANCEL"
        )
    }

    private fun showSavedArchivesDialog() {
        Thread {
            val archives = mutableListOf<ZipArchiveItem>()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val projection = arrayOf(
                        MediaStore.Downloads._ID,
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.SIZE,
                        MediaStore.Downloads.RELATIVE_PATH
                    )
                    contentResolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        projection,
                        "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                        arrayOf("%$ARCHIVE_SUBDIR%", "%.zip"),
                        "${MediaStore.Downloads.DATE_ADDED} DESC"
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                            archives.add(
                                ZipArchiveItem(
                                    name = cursor.getString(nameCol) ?: "archive.zip",
                                    uri = uri,
                                    sizeBytes = cursor.getLong(sizeCol)
                                )
                            )
                        }
                    }
                } else {
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        ARCHIVE_SUBDIR
                    )
                    dir.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) }
                        ?.sortedByDescending { it.lastModified() }
                        ?.forEach { file ->
                            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                            archives.add(ZipArchiveItem(file.name, uri, file.length(), file))
                        }
                }
            } catch (_: Exception) {
            }

            runOnUiThread {
                if (archives.isEmpty()) {
                    Toast.makeText(
                        this,
                        "No ZIP archives yet. Tap SELECT → pick clips → ZIP.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                IdgafUiDialogs.showListSheet(
                    context = this,
                    title = "SAVED ZIP ARCHIVES (${archives.size})",
                    subtitle = "Downloads/$ARCHIVE_SUBDIR/ • Tap to share",
                    items = archives.map { chosen ->
                        IdgafListItem(
                            title = chosen.name,
                            subtitle = String.format(Locale.US, "%.1f MB • Lossless STORED", chosen.sizeBytes / (1024f * 1024f)),
                            trailingLabel = "DEL",
                            trailingColor = 0xFFFF3B30.toInt(),
                            onTrailingClick = { confirmDeleteZipArchive(chosen) },
                            onClick = { shareZipArchive(chosen) }
                        )
                    }
                )
            }
        }.start()
    }

    // ==================== FOLDERS & MULTI-SELECT ====================

    private fun loadSavedFolders() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()
        knownFolders.clear()
        saved.filter { it.isNotBlank() }.sorted().forEach { knownFolders.add(it) }
    }

    private fun persistKnownFolders() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_FOLDERS, knownFolders.toSet()).apply()
    }

    private fun sanitizeFolderName(raw: String): String {
        return raw.trim()
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .replace(Regex("\\s+"), " ")
            .take(32)
            .trim()
    }

    private fun setMultiSelectMode(enabled: Boolean) {
        isMultiSelectMode = enabled
        if (!enabled) {
            selectedIds.clear()
        }
        btnToggleSelect.text = if (enabled) "DONE" else "SELECT"
        btnToggleSelect.setBackgroundResource(if (enabled) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
        btnToggleSelect.setTextColor(if (enabled) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
        bulkActionBar.visibility = if (enabled) View.VISIBLE else View.GONE
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    private fun toggleClipSelection(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionUi() {
        txtSelectedCount.text = "${selectedIds.size} SEL"
        btnSelectAll.text = if (selectedIds.size == visibleClips.size && visibleClips.isNotEmpty()) "NONE" else "ALL"
    }

    private fun showCreateFolderDialog(moveSelectionAfterCreate: Boolean) {
        IdgafUiDialogs.showTextInputDialog(
            context = this,
            title = "CREATE NEW FOLDER",
            hint = "Folder name (e.g. Scene 01, B-Roll)",
            primaryLabel = "CREATE",
            onPrimary = { raw ->
                val clean = sanitizeFolderName(raw)
                if (clean.isEmpty()) {
                    Toast.makeText(this, "Enter a valid folder name.", Toast.LENGTH_SHORT).show()
                    return@showTextInputDialog
                }
                knownFolders.add(clean)
                persistKnownFolders()
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    val dir = File(
                        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "IDGAF_Camera"),
                        clean
                    )
                    if (!dir.exists()) dir.mkdirs()
                }
                renderFolderChips()
                if (moveSelectionAfterCreate && selectedIds.isNotEmpty()) {
                    moveSelectedClipsToFolder(clean)
                } else {
                    Toast.makeText(this, "Folder '$clean' created", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun showMoveToFolderDialog() {
        val folderList = knownFolders.toList().sorted()
        val items = mutableListOf<IdgafListItem>()
        items.add(
            IdgafListItem(
                title = "+ CREATE NEW FOLDER",
                subtitle = "Make a new folder and move selected clips into it",
                accentTitle = true,
                onClick = { showCreateFolderDialog(moveSelectionAfterCreate = true) }
            )
        )
        items.add(
            IdgafListItem(
                title = "ROOT",
                subtitle = "Movies/IDGAF_Camera",
                onClick = { moveSelectedClipsToFolder(FOLDER_ROOT) }
            )
        )
        folderList.forEach { folder ->
            items.add(
                IdgafListItem(
                    title = folder,
                    subtitle = "Movies/IDGAF_Camera/$folder",
                    onClick = { moveSelectedClipsToFolder(folder) }
                )
            )
        }
        IdgafUiDialogs.showListSheet(
            context = this,
            title = "MOVE ${selectedIds.size} CLIP(S)",
            subtitle = "Choose destination folder",
            items = items
        )
    }

    private fun moveSelectedClipsToFolder(targetFolder: String) {
        val targets = allClips.filter { it.id in selectedIds }
        if (targets.isEmpty()) return

        Thread {
            var movedCount = 0
            val targetRelPath = if (targetFolder.isEmpty()) {
                "${Environment.DIRECTORY_MOVIES}/IDGAF_Camera/"
            } else {
                "${Environment.DIRECTORY_MOVIES}/IDGAF_Camera/$targetFolder/"
            }

            for (clip in targets) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.RELATIVE_PATH, targetRelPath)
                        }
                        val updated = contentResolver.update(clip.uri, values, null, null)
                        if (updated > 0) movedCount++
                    } else {
                        val rootDir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                            "IDGAF_Camera"
                        )
                        val destDir = if (targetFolder.isEmpty()) rootDir else File(rootDir, targetFolder)
                        if (!destDir.exists()) destDir.mkdirs()
                        val srcDir = if (clip.folderName.isEmpty()) rootDir else File(rootDir, clip.folderName)
                        val srcFile = File(srcDir, clip.name)
                        val dstFile = File(destDir, clip.name)
                        if (srcFile.exists() && srcFile.renameTo(dstFile)) {
                            movedCount++
                        }
                    }
                } catch (_: Exception) {
                }
            }

            runOnUiThread {
                val destLabel = if (targetFolder.isEmpty()) "ROOT" else targetFolder
                if (movedCount > 0) {
                    Toast.makeText(this, "Moved $movedCount clip(s) to $destLabel", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Could not move clips (device storage restriction).", Toast.LENGTH_LONG).show()
                }
                setMultiSelectMode(false)
                loadClips()
            }
        }.start()
    }

    private fun shareSelectedClips() {
        val uris = ArrayList(allClips.filter { it.id in selectedIds }.map { it.uri })
        if (uris.isEmpty()) {
            Toast.makeText(this, "Select at least 1 clip to share.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val shareIntent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "video/mp4"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            startActivity(Intent.createChooser(shareIntent, "Share ${uris.size} Clip(s)"))
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to share selected clips.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteSelectedClips() {
        val targets = allClips.filter { it.id in selectedIds }
        if (targets.isEmpty()) {
            Toast.makeText(this, "Select at least 1 clip to delete.", Toast.LENGTH_SHORT).show()
            return
        }
        IdgafUiDialogs.showActionDialog(
            context = this,
            title = "DELETE ${targets.size} CLIP(S)?",
            message = "This will permanently remove the selected video files.",
            primaryLabel = "DELETE ALL",
            primaryBgRes = R.drawable.bg_shutter_stop,
            primaryTextColor = 0xFFFFFFFF.toInt(),
            onPrimary = {
                Thread {
                    var deleted = 0
                    for (clip in targets) {
                        try {
                            if (contentResolver.delete(clip.uri, null, null) > 0) deleted++
                        } catch (_: Exception) {
                        }
                    }
                    runOnUiThread {
                        Toast.makeText(this, "Deleted $deleted clip(s)", Toast.LENGTH_SHORT).show()
                        setMultiSelectMode(false)
                        loadClips()
                    }
                }.start()
            },
            cancelLabel = "CANCEL"
        )
    }

    private fun renderFolderChips() {
        folderChipRow.removeAllViews()
        val entries = mutableListOf(
            FOLDER_ALL to "ALL",
            FOLDER_ROOT to "ROOT"
        )
        knownFolders.sorted().forEach { f -> entries.add(f to f) }

        entries.forEach { (key, label) ->
            val active = activeFolderFilter == key
            val chip = TextView(this).apply {
                text = label
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
                setBackgroundResource(if (active) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
                setTextColor(if (active) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(28)
                ).apply {
                    marginEnd = dpToPx(8)
                }
                setOnClickListener {
                    activeFolderFilter = key
                    selectedIds.clear()
                    updateSelectionUi()
                    renderFolderChips()
                    applyFolderFilter()
                }
                if (key != FOLDER_ALL && key != FOLDER_ROOT) {
                    setOnLongClickListener {
                        confirmRemoveEmptyFolder(key)
                        true
                    }
                }
            }
            folderChipRow.addView(chip)
        }
    }

    private fun confirmRemoveEmptyFolder(folderName: String) {
        val countInFolder = allClips.count { it.folderName == folderName }
        if (countInFolder > 0) {
            Toast.makeText(this, "Move or delete the $countInFolder clip(s) inside '$folderName' first.", Toast.LENGTH_SHORT).show()
            return
        }
        IdgafUiDialogs.showActionDialog(
            context = this,
            title = "REMOVE FOLDER?",
            message = "Remove empty folder '$folderName' from Clip Vault?",
            primaryLabel = "REMOVE",
            primaryBgRes = R.drawable.bg_shutter_stop,
            primaryTextColor = 0xFFFFFFFF.toInt(),
            onPrimary = {
                knownFolders.remove(folderName)
                persistKnownFolders()
                if (activeFolderFilter == folderName) activeFolderFilter = FOLDER_ALL
                renderFolderChips()
                applyFolderFilter()
            },
            cancelLabel = "CANCEL"
        )
    }

    private fun applyFolderFilter() {
        visibleClips.clear()
        when (activeFolderFilter) {
            FOLDER_ALL -> visibleClips.addAll(allClips)
            FOLDER_ROOT -> visibleClips.addAll(allClips.filter { it.folderName.isEmpty() })
            else -> visibleClips.addAll(allClips.filter { it.folderName == activeFolderFilter })
        }
        adapter.notifyDataSetChanged()
        txtCount.text = "${visibleClips.size} CLIPS"
        txtEmpty.visibility = if (visibleClips.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (visibleClips.isEmpty()) View.GONE else View.VISIBLE
        updateSelectionUi()
    }

    private fun extractSubfolderName(relPath: String?): String {
        if (relPath.isNullOrBlank()) return ""
        val norm = relPath.replace("\\", "/").trimEnd('/')
        val marker = "IDGAF_Camera/"
        val idx = norm.indexOf(marker)
        if (idx < 0) return ""
        val after = norm.substring(idx + marker.length).trim('/')
        return after.substringBefore('/')
    }

    private fun loadClips() {
        Thread {
            val found = mutableListOf<RecordedClip>()
            val discoveredFolders = linkedSetOf<String>()

            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.RELATIVE_PATH
                )
            } else {
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE
                )
            }

            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            } else {
                "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            }
            val selArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf("%IDGAF_Camera%")
            } else {
                arrayOf("IDGAF_%")
            }

            try {
                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selArgs,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                    } else -1

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        val folder = if (relCol >= 0) extractSubfolderName(cursor.getString(relCol)) else ""
                        if (folder.isNotEmpty()) discoveredFolders.add(folder)
                        found.add(
                            RecordedClip(
                                id = id,
                                uri = uri,
                                name = cursor.getString(nameCol) ?: "Clip",
                                durationMs = cursor.getLong(durCol),
                                sizeBytes = cursor.getLong(sizeCol),
                                folderName = folder
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }

            runOnUiThread {
                allClips.clear()
                allClips.addAll(found)
                if (discoveredFolders.isNotEmpty()) {
                    knownFolders.addAll(discoveredFolders)
                    persistKnownFolders()
                }
                renderFolderChips()
                applyFolderFilter()
            }
        }.start()
    }

    private fun playClip(clip: RecordedClip) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(clip.uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No video player found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareClip(clip: RecordedClip) {
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, clip.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share Clip"))
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to share clip.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteClip(clip: RecordedClip) {
        IdgafUiDialogs.showActionDialog(
            context = this,
            title = "DELETE CLIP?",
            message = clip.name,
            primaryLabel = "DELETE",
            primaryBgRes = R.drawable.bg_shutter_stop,
            primaryTextColor = 0xFFFFFFFF.toInt(),
            onPrimary = {
                try {
                    val deleted = contentResolver.delete(clip.uri, null, null)
                    if (deleted > 0) {
                        Toast.makeText(this, "Clip deleted", Toast.LENGTH_SHORT).show()
                        loadClips()
                    } else {
                        Toast.makeText(this, "Could not delete clip", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            },
            cancelLabel = "CANCEL"
        )
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    inner class ClipAdapter(
        private val clips: List<RecordedClip>,
        private val onCardClick: (RecordedClip) -> Unit,
        private val onCardLongClick: (RecordedClip) -> Unit,
        private val onShare: (RecordedClip) -> Unit,
        private val onDelete: (RecordedClip) -> Unit
    ) : RecyclerView.Adapter<ClipAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val rootCard: LinearLayout = v.findViewById(R.id.clipCardRoot)
            val imgThumb: ImageView = v.findViewById(R.id.imgClipThumb)
            val txtFolderBadge: TextView = v.findViewById(R.id.txtClipFolderBadge)
            val txtCheck: TextView = v.findViewById(R.id.txtClipCheck)
            val txtDur: TextView = v.findViewById(R.id.txtClipDuration)
            val txtName: TextView = v.findViewById(R.id.txtClipName)
            val txtSize: TextView = v.findViewById(R.id.txtClipSize)
            val btnShare: TextView = v.findViewById(R.id.btnClipShare)
            val btnDelete: TextView = v.findViewById(R.id.btnClipDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_clip_card, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val clip = clips[position]
            holder.txtName.text = clip.name
            val secs = (clip.durationMs / 1000L).coerceAtLeast(0L)
            holder.txtDur.text = String.format(Locale.US, "%02d:%02d", secs / 60L, secs % 60L)
            val mb = clip.sizeBytes / (1024f * 1024f)
            holder.txtSize.text = String.format(Locale.US, "%.1f MB", mb)

            // Folder badge when viewing ALL
            if (activeFolderFilter == FOLDER_ALL && clip.folderName.isNotEmpty()) {
                holder.txtFolderBadge.text = clip.folderName
                holder.txtFolderBadge.visibility = View.VISIBLE
            } else {
                holder.txtFolderBadge.visibility = View.GONE
            }

            // Multi-select visual state
            val isSelected = selectedIds.contains(clip.id)
            holder.rootCard.setBackgroundResource(
                if (isSelected) R.drawable.bg_dock_pill_active else R.drawable.bg_hud_pill
            )
            if (isMultiSelectMode) {
                holder.txtCheck.visibility = View.VISIBLE
                holder.txtCheck.text = if (isSelected) "✓" else ""
                holder.txtCheck.setBackgroundResource(
                    if (isSelected) R.drawable.bg_lens_active else R.drawable.bg_hud_pill
                )
            } else {
                holder.txtCheck.visibility = View.GONE
            }

            holder.imgThumb.setImageBitmap(null)
            holder.imgThumb.tag = clip.id
            Thread {
                val bmp: Bitmap? = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentResolver.loadThumbnail(clip.uri, Size(320, 180), null)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Video.Thumbnails.getThumbnail(
                            contentResolver,
                            clip.id,
                            MediaStore.Video.Thumbnails.MINI_KIND,
                            null
                        )
                    }
                } catch (_: Exception) {
                    null
                }
                if (bmp != null) {
                    runOnUiThread {
                        if (holder.imgThumb.tag == clip.id) {
                            holder.imgThumb.setImageBitmap(bmp)
                        }
                    }
                }
            }.start()

            holder.imgThumb.setOnClickListener { onCardClick(clip) }
            holder.itemView.setOnClickListener { onCardClick(clip) }
            holder.imgThumb.setOnLongClickListener {
                onCardLongClick(clip)
                true
            }
            holder.itemView.setOnLongClickListener {
                onCardLongClick(clip)
                true
            }
            holder.btnShare.setOnClickListener { onShare(clip) }
            holder.btnDelete.setOnClickListener { onDelete(clip) }
        }

        override fun getItemCount(): Int = clips.size
    }
}
