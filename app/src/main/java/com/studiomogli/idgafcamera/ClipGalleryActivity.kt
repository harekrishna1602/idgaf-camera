package com.studiomogli.idgafcamera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
import kotlin.math.abs

enum class VaultItemType {
    VIDEO,
    STOP_MOTION_SEQ,
    WAV_AUDIO
}

data class VaultFilePart(
    val uri: Uri,
    val fileName: String,
    val zipRelPath: String,
    val sizeBytes: Long,
    val legacyFile: File? = null
)

data class RecordedClip(
    val key: String,
    val type: VaultItemType,
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folderName: String, // "" for root Movies/IDGAF_Camera, or custom subfolder name
    val dateAddedSec: Long = 0L,
    val frameCount: Int = 0,
    val frames: List<VaultFilePart> = emptyList(),
    val pairedWav: VaultFilePart? = null,
    val pairedJpgSeqKey: String? = null,
    val legacyFile: File? = null
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
        private const val FOLDER_STOP_MO = "__STOP_MO__"
        private const val FOLDER_AUDIO = "__AUDIO__"
        private const val PREFS_NAME = "idgaf_vault_folders_v1"
        private const val KEY_FOLDERS = "custom_folders_set"
        private const val ARCHIVE_SUBDIR = "IDGAF_Archives"

        private val TIMESTAMP_REGEX = Regex("""(\d{8}_\d{6})""")
        private val SM_MP4_REGEX = Regex("""(^|_)SM\d+fps_""", RegexOption.IGNORE_CASE)
        private val SLATE_PREFIX_REGEX = Regex("""^([A-Z0-9]{1,6}_S\d{2,3}_T\d{2,3})_""")
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
    private val selectedKeys = linkedSetOf<String>()
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
            if (selectedKeys.size == visibleClips.size && visibleClips.isNotEmpty()) {
                selectedKeys.clear()
            } else {
                selectedKeys.clear()
                visibleClips.forEach { selectedKeys.add(it.key) }
            }
            updateSelectionUi()
            adapter.notifyDataSetChanged()
        }

        btnBulkZip.setOnClickListener {
            if (selectedKeys.isEmpty()) {
                Toast.makeText(this, "Select at least 1 item to ZIP.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val targets = allClips.filter { it.key in selectedKeys }
            showZipOptionsDialog(targets)
        }

        btnBulkMove.setOnClickListener {
            if (selectedKeys.isEmpty()) {
                Toast.makeText(this, "Select at least 1 video clip first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val videoTargets = allClips.filter { it.key in selectedKeys && it.type == VaultItemType.VIDEO }
            if (videoTargets.isEmpty()) {
                Toast.makeText(
                    this,
                    "Only MP4 clips can move into video folders — use ZIP to bundle JPGs & WAVs.",
                    Toast.LENGTH_LONG
                ).show()
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
                    toggleClipSelection(clip.key)
                } else {
                    openClipItem(clip)
                }
            },
            onCardLongClick = { clip ->
                if (!isMultiSelectMode) {
                    setMultiSelectMode(true)
                }
                toggleClipSelection(clip.key)
            },
            onShare = { shareClip(it) },
            onDelete = { confirmDeleteClip(it) }
        )
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter

        loadSavedFolders()
        loadClips()
    }

    // ==================== UNIFIED ZERO-COMPRESSION ZIP ARCHIVER (MP4 + STOP-MOTION JPGS + 48kHz WAVS) ====================

    private fun collectZipParts(targets: List<RecordedClip>): List<VaultFilePart> {
        val parts = mutableListOf<VaultFilePart>()
        val seenUris = hashSetOf<String>()
        val clipByKey = allClips.associateBy { it.key }

        fun addUnique(part: VaultFilePart) {
            if (seenUris.add(part.uri.toString())) {
                parts.add(part)
            }
        }

        for (item in targets) {
            when (item.type) {
                VaultItemType.VIDEO -> {
                    val baseRel = if (item.folderName.isNotEmpty()) "${item.folderName}/${item.name}" else item.name
                    addUnique(
                        VaultFilePart(
                            uri = item.uri,
                            fileName = item.name,
                            zipRelPath = baseRel,
                            sizeBytes = item.sizeBytes,
                            legacyFile = item.legacyFile
                        )
                    )
                    item.pairedWav?.let { wav ->
                        val wavRel = if (item.folderName.isNotEmpty()) "${item.folderName}/${wav.fileName}" else wav.fileName
                        addUnique(wav.copy(zipRelPath = wavRel))
                    }
                    item.pairedJpgSeqKey?.let { seqKey ->
                        clipByKey[seqKey]?.frames?.forEach { frame ->
                            addUnique(frame)
                        }
                    }
                }
                VaultItemType.STOP_MOTION_SEQ -> {
                    item.frames.forEach { frame ->
                        addUnique(frame)
                    }
                }
                VaultItemType.WAV_AUDIO -> {
                    addUnique(
                        VaultFilePart(
                            uri = item.uri,
                            fileName = item.name,
                            zipRelPath = "Audio/${item.name}",
                            sizeBytes = item.sizeBytes,
                            legacyFile = item.legacyFile
                        )
                    )
                }
            }
        }
        return parts
    }

    private fun summarizeZipParts(parts: List<VaultFilePart>): String {
        val vids = parts.count { it.fileName.endsWith(".mp4", ignoreCase = true) }
        val jpgs = parts.count {
            it.fileName.endsWith(".jpg", ignoreCase = true) || it.fileName.endsWith(".jpeg", ignoreCase = true)
        }
        val wavs = parts.count { it.fileName.endsWith(".wav", ignoreCase = true) }
        val segs = mutableListOf<String>()
        if (vids > 0) segs.add("$vids MP4")
        if (jpgs > 0) segs.add("$jpgs JPG frame(s)")
        if (wavs > 0) segs.add("$wavs WAV")
        if (segs.isEmpty()) segs.add("${parts.size} file(s)")
        return segs.joinToString(" + ")
    }

    private fun showZipOptionsDialog(targets: List<RecordedClip>) {
        if (targets.isEmpty()) return
        val fileParts = collectZipParts(targets)
        if (fileParts.isEmpty()) {
            Toast.makeText(this, "No readable files found to ZIP.", Toast.LENGTH_SHORT).show()
            return
        }

        val totalBytes = fileParts.sumOf { it.sizeBytes.coerceAtLeast(0L) }
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
                    "Selected files need ~%.1f MB free to build the ZIP, but only %.1f MB is available.",
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
        val defaultName = if (targets.size == 1 && targets[0].type == VaultItemType.STOP_MOTION_SEQ) {
            sanitizeArchiveName(targets[0].name, "IDGAF_StopMo_$timestamp")
        } else {
            val folderPrefix = when {
                activeFolderFilter == FOLDER_STOP_MO -> "StopMo"
                activeFolderFilter == FOLDER_AUDIO -> "Audio"
                activeFolderFilter != FOLDER_ALL && activeFolderFilter.isNotEmpty() ->
                    sanitizeFolderName(activeFolderFilter).replace(" ", "_")
                else -> "Takes"
            }
            "IDGAF_${folderPrefix}_$timestamp"
        }

        val summary = summarizeZipParts(fileParts)
        val msg = String.format(
            Locale.US,
            "%s • %.1f MB (Lossless STORED → Downloads/%s/)",
            summary,
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
                startZipArchiveJob(fileParts, clean, clearOriginalsAfterVerify = false)
            },
            secondaryLabel = "ZIP & CLEAR",
            secondaryTextColor = 0xFFFF5F1F.toInt(),
            onSecondary = { raw ->
                val clean = sanitizeArchiveName(raw, defaultName)
                IdgafUiDialogs.showActionDialog(
                    context = this,
                    title = "ZIP & CLEAR ORIGINALS?",
                    message = "All ${fileParts.size} original file(s) ($summary) will be deleted ONLY AFTER $clean.zip passes 100% verification.",
                    primaryLabel = "ZIP & CLEAR",
                    onPrimary = { startZipArchiveJob(fileParts, clean, clearOriginalsAfterVerify = true) },
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
        fileParts: List<VaultFilePart>,
        baseName: String,
        clearOriginalsAfterVerify: Boolean
    ) {
        val zipFileName = "$baseName.zip"
        val cancelled = AtomicBoolean(false)

        val progHandle = IdgafUiDialogs.showProgressDialog(
            context = this,
            title = if (clearOriginalsAfterVerify) "ARCHIVING & VERIFYING..." else "BUILDING ZIP ARCHIVE...",
            initialStage = "Preparing ${fileParts.size} file(s)...",
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
            val totalExpectedBytes = fileParts.sumOf { it.sizeBytes.coerceAtLeast(1L) }.coerceAtLeast(1L)
            var processedWorkBytes = 0L
            // Two passes per file: CRC32 pass (weight 0.35) + STORED write pass (weight 0.65)
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

                    for ((idx, part) in fileParts.withIndex()) {
                        if (cancelled.get()) throw InterruptedException("Cancelled by user")

                        val baseEntryName = part.zipRelPath.trimStart('/')
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
                        contentResolver.openInputStream(part.uri)?.use { ins ->
                            var read: Int
                            while (ins.read(buf).also { read = it } != -1) {
                                if (cancelled.get()) throw InterruptedException("Cancelled by user")
                                crc.update(buf, 0, read)
                                exactSize += read
                                processedWorkBytes += (read * 35L)
                                val pct = ((processedWorkBytes * 100L) / totalWorkUnits).toInt().coerceIn(0, 98)
                                updateProgressUi(
                                    "Checking ${idx + 1}/${fileParts.size}: ${part.fileName}",
                                    pct,
                                    (processedWorkBytes / 100L).coerceAtMost(totalExpectedBytes),
                                    totalExpectedBytes
                                )
                            }
                        } ?: throw IllegalStateException("Cannot read ${part.fileName}")

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
                        contentResolver.openInputStream(part.uri)?.use { ins ->
                            var read: Int
                            while (ins.read(buf).also { read = it } != -1) {
                                if (cancelled.get()) throw InterruptedException("Cancelled by user")
                                zos.write(buf, 0, read)
                                written += read
                                processedWorkBytes += (read * 65L)
                                val pct = ((processedWorkBytes * 100L) / totalWorkUnits).toInt().coerceIn(0, 98)
                                updateProgressUi(
                                    "Packing ${idx + 1}/${fileParts.size}: ${part.fileName}",
                                    pct,
                                    (processedWorkBytes / 100L).coerceAtMost(totalExpectedBytes),
                                    totalExpectedBytes
                                )
                            }
                        } ?: throw IllegalStateException("Cannot read ${part.fileName} on write pass")
                        zos.closeEntry()

                        if (written != exactSize) {
                            throw IllegalStateException("Short write on ${part.fileName}: $written vs $exactSize")
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

                    updateProgressUi("Clearing ${fileParts.size} original file(s)...", 100, totalExpectedBytes, totalExpectedBytes)
                    val legacyParentDirs = linkedSetOf<File>()
                    for (part in fileParts) {
                        if (deleteVaultFilePart(part)) {
                            clearedOriginalsCount++
                            part.legacyFile?.parentFile?.let { legacyParentDirs.add(it) }
                        }
                    }
                    legacyParentDirs.forEach { dir ->
                        try {
                            if (dir.isDirectory && dir.listFiles().isNullOrEmpty()) dir.delete()
                        } catch (_: Exception) {
                        }
                    }
                }

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
                        fileSummary = summarizeZipParts(fileParts),
                        totalFiles = fileParts.size,
                        originalsCleared = clearedOriginalsCount,
                        clearRequested = clearOriginalsAfterVerify
                    )
                }
            } catch (e: Exception) {
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

    private fun deleteVaultFilePart(part: VaultFilePart): Boolean {
        return try {
            if (part.legacyFile != null) {
                part.legacyFile.exists() && part.legacyFile.delete()
            } else {
                contentResolver.delete(part.uri, null, null) > 0
            }
        } catch (_: Exception) {
            false
        }
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
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.coerceAtLeast(0L) } ?: 0L
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
        fileSummary: String,
        totalFiles: Int,
        originalsCleared: Int,
        clearRequested: Boolean
    ) {
        val mb = archive.sizeBytes / (1024f * 1024f)
        val statusLine = if (clearRequested) {
            "Verified & cleared $originalsCleared/$totalFiles original file(s) from device."
        } else {
            "Packed $fileSummary • All originals kept."
        }

        lateinit var dialog: android.app.Dialog
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
                        "No ZIP archives yet. Tap SELECT → pick clips/JPGs → ZIP.",
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
            selectedKeys.clear()
        }
        btnToggleSelect.text = if (enabled) "DONE" else "SELECT"
        btnToggleSelect.setBackgroundResource(if (enabled) R.drawable.bg_lens_active else R.drawable.bg_hud_pill)
        btnToggleSelect.setTextColor(if (enabled) 0xFF0B0D10.toInt() else 0xFFFFFFFF.toInt())
        bulkActionBar.visibility = if (enabled) View.VISIBLE else View.GONE
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    private fun toggleClipSelection(key: String) {
        if (selectedKeys.contains(key)) selectedKeys.remove(key) else selectedKeys.add(key)
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionUi() {
        txtSelectedCount.text = "${selectedKeys.size} SEL"
        btnSelectAll.text = if (selectedKeys.size == visibleClips.size && visibleClips.isNotEmpty()) "NONE" else "ALL"
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
                if (moveSelectionAfterCreate && selectedKeys.isNotEmpty()) {
                    moveSelectedClipsToFolder(clean)
                } else {
                    Toast.makeText(this, "Folder '$clean' created", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun showMoveToFolderDialog() {
        val videoCount = allClips.count { it.key in selectedKeys && it.type == VaultItemType.VIDEO }
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
            title = "MOVE $videoCount VIDEO(S)",
            subtitle = "Choose destination folder",
            items = items
        )
    }

    private fun moveSelectedClipsToFolder(targetFolder: String) {
        val targets = allClips.filter { it.key in selectedKeys && it.type == VaultItemType.VIDEO }
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
                        val srcFile = clip.legacyFile ?: File(
                            if (clip.folderName.isEmpty()) rootDir else File(rootDir, clip.folderName),
                            clip.name
                        )
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
                    Toast.makeText(this, "Moved $movedCount video(s) to $destLabel", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Could not move clips (device storage restriction).", Toast.LENGTH_LONG).show()
                }
                setMultiSelectMode(false)
                loadClips()
            }
        }.start()
    }

    private fun shareSelectedClips() {
        val targets = allClips.filter { it.key in selectedKeys }
        if (targets.isEmpty()) {
            Toast.makeText(this, "Select at least 1 item to share.", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList<Uri>()
        var hasVideo = false
        var hasImage = false
        var hasAudio = false

        for (item in targets) {
            when (item.type) {
                VaultItemType.VIDEO -> {
                    uris.add(item.uri)
                    hasVideo = true
                }
                VaultItemType.STOP_MOTION_SEQ -> {
                    item.frames.forEach { uris.add(it.uri) }
                    hasImage = true
                }
                VaultItemType.WAV_AUDIO -> {
                    uris.add(item.uri)
                    hasAudio = true
                }
            }
        }
        if (uris.isEmpty()) return

        val mime = when {
            hasVideo && !hasImage && !hasAudio -> "video/mp4"
            hasImage && !hasVideo && !hasAudio -> "image/jpeg"
            hasAudio && !hasVideo && !hasImage -> "audio/wav"
            else -> "*/*"
        }

        try {
            val shareIntent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = mime
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            startActivity(Intent.createChooser(shareIntent, "Share ${uris.size} File(s)"))
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to share selected files.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteSelectedClips() {
        val targets = allClips.filter { it.key in selectedKeys }
        if (targets.isEmpty()) {
            Toast.makeText(this, "Select at least 1 item to delete.", Toast.LENGTH_SHORT).show()
            return
        }
        val totalFiles = targets.sumOf { item ->
            when (item.type) {
                VaultItemType.VIDEO -> 1 + (if (item.pairedWav != null) 1 else 0)
                VaultItemType.STOP_MOTION_SEQ -> item.frames.size
                VaultItemType.WAV_AUDIO -> 1
            }
        }
        IdgafUiDialogs.showActionDialog(
            context = this,
            title = "DELETE ${targets.size} ITEM(S)?",
            message = "This will permanently remove $totalFiles file(s) (including paired WAV sidecars & selected JPG frames).",
            primaryLabel = "DELETE ALL",
            primaryBgRes = R.drawable.bg_shutter_stop,
            primaryTextColor = 0xFFFFFFFF.toInt(),
            onPrimary = {
                Thread {
                    var deleted = 0
                    val legacyParentDirs = linkedSetOf<File>()
                    for (item in targets) {
                        when (item.type) {
                            VaultItemType.VIDEO -> {
                                if (deletePrimaryItem(item)) deleted++
                                item.pairedWav?.let { wav -> deleteVaultFilePart(wav) }
                            }
                            VaultItemType.STOP_MOTION_SEQ -> {
                                var seqDeleted = 0
                                for (frame in item.frames) {
                                    if (deleteVaultFilePart(frame)) {
                                        seqDeleted++
                                        frame.legacyFile?.parentFile?.let { legacyParentDirs.add(it) }
                                    }
                                }
                                if (seqDeleted > 0) deleted++
                            }
                            VaultItemType.WAV_AUDIO -> {
                                if (deletePrimaryItem(item)) deleted++
                            }
                        }
                    }
                    legacyParentDirs.forEach { dir ->
                        try {
                            if (dir.isDirectory && dir.listFiles().isNullOrEmpty()) dir.delete()
                        } catch (_: Exception) {
                        }
                    }
                    runOnUiThread {
                        Toast.makeText(this, "Deleted $deleted item(s)", Toast.LENGTH_SHORT).show()
                        setMultiSelectMode(false)
                        loadClips()
                    }
                }.start()
            },
            cancelLabel = "CANCEL"
        )
    }

    private fun deletePrimaryItem(clip: RecordedClip): Boolean {
        return try {
            if (clip.legacyFile != null) {
                clip.legacyFile.exists() && clip.legacyFile.delete()
            } else {
                contentResolver.delete(clip.uri, null, null) > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun renderFolderChips() {
        folderChipRow.removeAllViews()
        val hasStopMo = allClips.any {
            it.type == VaultItemType.STOP_MOTION_SEQ ||
                (it.type == VaultItemType.VIDEO && SM_MP4_REGEX.containsMatchIn(it.name))
        }
        val hasAudio = allClips.any { it.type == VaultItemType.WAV_AUDIO || it.pairedWav != null }

        val entries = mutableListOf(
            FOLDER_ALL to "ALL",
            FOLDER_ROOT to "ROOT"
        )
        if (hasStopMo) entries.add(FOLDER_STOP_MO to "STOP-MO")
        if (hasAudio) entries.add(FOLDER_AUDIO to "WAV")
        knownFolders.sorted().forEach { f -> entries.add(f to f) }

        if (entries.none { it.first == activeFolderFilter }) {
            activeFolderFilter = FOLDER_ALL
        }

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
                    selectedKeys.clear()
                    updateSelectionUi()
                    renderFolderChips()
                    applyFolderFilter()
                }
                if (key != FOLDER_ALL && key != FOLDER_ROOT && key != FOLDER_STOP_MO && key != FOLDER_AUDIO) {
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
        val countInFolder = allClips.count { it.type == VaultItemType.VIDEO && it.folderName == folderName }
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
            FOLDER_ROOT -> visibleClips.addAll(allClips.filter { it.type == VaultItemType.VIDEO && it.folderName.isEmpty() })
            FOLDER_STOP_MO -> visibleClips.addAll(
                allClips.filter {
                    it.type == VaultItemType.STOP_MOTION_SEQ ||
                        (it.type == VaultItemType.VIDEO && SM_MP4_REGEX.containsMatchIn(it.name))
                }
            )
            FOLDER_AUDIO -> visibleClips.addAll(
                allClips.filter { it.type == VaultItemType.WAV_AUDIO || it.pairedWav != null }
            )
            else -> visibleClips.addAll(allClips.filter { it.type == VaultItemType.VIDEO && it.folderName == activeFolderFilter })
        }
        adapter.notifyDataSetChanged()
        txtCount.text = "${visibleClips.size} ITEMS"
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

    private fun extractTimestampEpochSec(name: String): Long? {
        val m = TIMESTAMP_REGEX.find(name) ?: return null
        return try {
            val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            (fmt.parse(m.groupValues[1])?.time ?: return null) / 1000L
        } catch (_: Exception) {
            null
        }
    }

    private fun extractSlatePrefix(name: String): String? {
        return SLATE_PREFIX_REGEX.find(name)?.groupValues?.getOrNull(1)
    }

    // ==================== UNIFIED MEDIA LOADER (VIDEOS + 48kHz WAVS + STOP-MOTION JPG SEQUENCES) ====================

    private fun loadClips() {
        Thread {
            val videoList = mutableListOf<RecordedClip>()
            val discoveredFolders = linkedSetOf<String>()

            // 1. Load 48kHz WAV Sidecars from Music/IDGAF_Camera/
            data class RawWav(val id: Long, val part: VaultFilePart, val dateAddedSec: Long)
            val wavByBase = linkedMapOf<String, RawWav>()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val proj = arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.SIZE,
                        MediaStore.Audio.Media.DATE_ADDED,
                        MediaStore.Audio.Media.RELATIVE_PATH
                    )
                    contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        proj,
                        "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?",
                        arrayOf("%IDGAF_Camera%", "%.wav"),
                        "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol) ?: continue
                            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                            val rawSize = cursor.getLong(sizeCol)
                            val size = if (rawSize > 0L) rawSize else queryUriSizeBytes(uri, null)
                            val baseKey = name.substringBeforeLast('.').lowercase(Locale.US)
                            wavByBase.putIfAbsent(
                                baseKey,
                                RawWav(
                                    id = id,
                                    part = VaultFilePart(uri, name, name, size),
                                    dateAddedSec = cursor.getLong(dateCol)
                                )
                            )
                        }
                    }
                } else {
                    val musicDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        "IDGAF_Camera"
                    )
                    musicDir.listFiles { f -> f.isFile && f.name.endsWith(".wav", ignoreCase = true) }
                        ?.sortedByDescending { it.lastModified() }
                        ?.forEach { file ->
                            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                            val baseKey = file.nameWithoutExtension.lowercase(Locale.US)
                            wavByBase.putIfAbsent(
                                baseKey,
                                RawWav(
                                    id = file.absolutePath.hashCode().toLong(),
                                    part = VaultFilePart(uri, file.name, file.name, file.length(), file),
                                    dateAddedSec = file.lastModified() / 1000L
                                )
                            )
                        }
                }
            } catch (_: Exception) {
            }

            // 2. Load Stop-Motion JPG Sequences from Pictures/IDGAF_Camera/<sessionFolder>/
            data class RawJpg(val folder: String, val part: VaultFilePart, val dateAddedSec: Long, val firstId: Long)
            val jpgGroups = linkedMapOf<String, MutableList<RawJpg>>()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val proj = arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.SIZE,
                        MediaStore.Images.Media.DATE_ADDED,
                        MediaStore.Images.Media.RELATIVE_PATH
                    )
                    contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        proj,
                        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                        arrayOf("%IDGAF_Camera%"),
                        "${MediaStore.Images.Media.DISPLAY_NAME} ASC"
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                        val relCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol) ?: continue
                            if (!name.endsWith(".jpg", ignoreCase = true) && !name.endsWith(".jpeg", ignoreCase = true)) continue
                            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                            val rawSize = cursor.getLong(sizeCol)
                            val size = if (rawSize > 0L) rawSize else queryUriSizeBytes(uri, null)
                            val sub = extractSubfolderName(cursor.getString(relCol)).ifEmpty { "StopMotion_Frames" }
                            val part = VaultFilePart(
                                uri = uri,
                                fileName = name,
                                zipRelPath = "$sub/$name",
                                sizeBytes = size
                            )
                            jpgGroups.getOrPut(sub) { mutableListOf() }
                                .add(RawJpg(sub, part, cursor.getLong(dateCol), id))
                        }
                    }
                } else {
                    val picRoot = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "IDGAF_Camera"
                    )
                    if (picRoot.exists() && picRoot.isDirectory) {
                        val dirs = mutableListOf<Pair<String, File>>()
                        picRoot.listFiles { f -> f.isDirectory }?.forEach { d -> dirs.add(d.name to d) }
                        dirs.add("StopMotion_Frames" to picRoot)
                        for ((folderLabel, dir) in dirs) {
                            dir.listFiles { f ->
                                f.isFile && (f.name.endsWith(".jpg", ignoreCase = true) || f.name.endsWith(".jpeg", ignoreCase = true))
                            }?.sortedBy { it.name.lowercase(Locale.US) }?.forEach { file ->
                                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                                val part = VaultFilePart(
                                    uri = uri,
                                    fileName = file.name,
                                    zipRelPath = "$folderLabel/${file.name}",
                                    sizeBytes = file.length(),
                                    legacyFile = file
                                )
                                jpgGroups.getOrPut(folderLabel) { mutableListOf() }
                                    .add(RawJpg(folderLabel, part, file.lastModified() / 1000L, file.absolutePath.hashCode().toLong()))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }

            val seqClips = mutableListOf<RecordedClip>()
            for ((folder, items) in jpgGroups) {
                if (items.isEmpty()) continue
                val sorted = items.sortedBy { it.part.fileName.lowercase(Locale.US) }
                val first = sorted.first()
                val totalSize = sorted.sumOf { it.part.sizeBytes.coerceAtLeast(0L) }
                val newestDate = sorted.maxOf { it.dateAddedSec }
                seqClips.add(
                    RecordedClip(
                        key = "smseq:$folder",
                        type = VaultItemType.STOP_MOTION_SEQ,
                        id = first.firstId,
                        uri = first.part.uri,
                        name = folder,
                        durationMs = 0L,
                        sizeBytes = totalSize,
                        folderName = "",
                        dateAddedSec = newestDate,
                        frameCount = sorted.size,
                        frames = sorted.map { it.part },
                        legacyFile = first.part.legacyFile
                    )
                )
            }

            // 3. Load Video Clips from Movies/IDGAF_Camera/
            val matchedWavKeys = hashSetOf<String>()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val projection = arrayOf(
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DURATION,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DATE_ADDED,
                        MediaStore.Video.Media.RELATIVE_PATH
                    )
                    contentResolver.query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                        arrayOf("%IDGAF_Camera%"),
                        "${MediaStore.Video.Media.DATE_ADDED} DESC"
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                        val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                        val relCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                            val name = cursor.getString(nameCol) ?: "Clip"
                            val folder = extractSubfolderName(cursor.getString(relCol))
                            if (folder.isNotEmpty()) discoveredFolders.add(folder)

                            val baseKey = name.substringBeforeLast('.').lowercase(Locale.US)
                            val pairedWav = wavByBase[baseKey]?.also { matchedWavKeys.add(baseKey) }?.part
                            val pairedSeqKey = findMatchingStopMotionSeqKey(name, seqClips)

                            videoList.add(
                                RecordedClip(
                                    key = "vid:$id",
                                    type = VaultItemType.VIDEO,
                                    id = id,
                                    uri = uri,
                                    name = name,
                                    durationMs = cursor.getLong(durCol),
                                    sizeBytes = cursor.getLong(sizeCol),
                                    folderName = folder,
                                    dateAddedSec = cursor.getLong(dateCol),
                                    pairedWav = pairedWav,
                                    pairedJpgSeqKey = pairedSeqKey
                                )
                            )
                        }
                    }
                } else {
                    val rootDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        "IDGAF_Camera"
                    )
                    if (rootDir.exists() && rootDir.isDirectory) {
                        val dirs = mutableListOf("" to rootDir)
                        rootDir.listFiles { f -> f.isDirectory }?.forEach { sub ->
                            discoveredFolders.add(sub.name)
                            dirs.add(sub.name to sub)
                        }
                        for ((folder, dir) in dirs) {
                            dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4", ignoreCase = true) }
                                ?.forEach { file ->
                                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                                    val baseKey = file.nameWithoutExtension.lowercase(Locale.US)
                                    val pairedWav = wavByBase[baseKey]?.also { matchedWavKeys.add(baseKey) }?.part
                                    val pairedSeqKey = findMatchingStopMotionSeqKey(file.name, seqClips)
                                    videoList.add(
                                        RecordedClip(
                                            key = "vid:${file.absolutePath}",
                                            type = VaultItemType.VIDEO,
                                            id = file.absolutePath.hashCode().toLong(),
                                            uri = uri,
                                            name = file.name,
                                            durationMs = 0L,
                                            sizeBytes = file.length(),
                                            folderName = folder,
                                            dateAddedSec = file.lastModified() / 1000L,
                                            pairedWav = pairedWav,
                                            pairedJpgSeqKey = pairedSeqKey,
                                            legacyFile = file
                                        )
                                    )
                                }
                        }
                    }
                }
            } catch (_: Exception) {
            }

            // 4. Add any standalone/orphan 48kHz WAV files so nothing is ever lost or un-zippable
            val orphanWavs = wavByBase.filterKeys { it !in matchedWavKeys }.values.map { raw ->
                RecordedClip(
                    key = "wav:${raw.id}",
                    type = VaultItemType.WAV_AUDIO,
                    id = raw.id,
                    uri = raw.part.uri,
                    name = raw.part.fileName,
                    durationMs = 0L,
                    sizeBytes = raw.part.sizeBytes,
                    folderName = "",
                    dateAddedSec = raw.dateAddedSec,
                    legacyFile = raw.part.legacyFile
                )
            }

            val combined = (videoList + seqClips + orphanWavs).sortedByDescending { it.dateAddedSec }

            runOnUiThread {
                allClips.clear()
                allClips.addAll(combined)
                if (discoveredFolders.isNotEmpty()) {
                    knownFolders.addAll(discoveredFolders)
                    persistKnownFolders()
                }
                renderFolderChips()
                applyFolderFilter()
            }
        }.start()
    }

    private fun findMatchingStopMotionSeqKey(mp4Name: String, seqClips: List<RecordedClip>): String? {
        if (!SM_MP4_REGEX.containsMatchIn(mp4Name) || seqClips.isEmpty()) return null
        val vidEpoch = extractTimestampEpochSec(mp4Name) ?: return null
        val vidSlate = extractSlatePrefix(mp4Name)

        var bestKey: String? = null
        var bestDiff = Long.MAX_VALUE
        for (seq in seqClips) {
            val seqEpoch = extractTimestampEpochSec(seq.name) ?: continue
            val seqSlate = extractSlatePrefix(seq.name)
            if (vidSlate != null && seqSlate != null && !vidSlate.equals(seqSlate, ignoreCase = true)) continue
            val diff = abs(vidEpoch - seqEpoch)
            if (diff <= 4L && diff < bestDiff) {
                bestDiff = diff
                bestKey = seq.key
            }
        }
        return bestKey
    }

    // ==================== SINGLE-ITEM OPEN / SHARE / DELETE ====================

    private fun openClipItem(clip: RecordedClip) {
        when (clip.type) {
            VaultItemType.VIDEO -> playClip(clip)
            VaultItemType.STOP_MOTION_SEQ -> showStopMotionSeqMenu(clip)
            VaultItemType.WAV_AUDIO -> playAudioClip(clip)
        }
    }

    private fun showStopMotionSeqMenu(clip: RecordedClip) {
        val mb = clip.sizeBytes / (1024f * 1024f)
        IdgafUiDialogs.showListSheet(
            context = this,
            title = clip.name,
            subtitle = String.format(Locale.US, "%d JPG frame(s) • %.1f MB • Pictures/IDGAF_Camera/%s/", clip.frameCount, mb, clip.name),
            items = listOf(
                IdgafListItem(
                    title = "ZIP ALL ${clip.frameCount} JPG FRAMES",
                    subtitle = "Create lossless STORED .zip in Downloads/$ARCHIVE_SUBDIR/",
                    accentTitle = true,
                    onClick = { showZipOptionsDialog(listOf(clip)) }
                ),
                IdgafListItem(
                    title = "VIEW FIRST FRAME",
                    subtitle = clip.frames.firstOrNull()?.fileName ?: "FRAME_0001.jpg",
                    onClick = { viewImageUri(clip.uri) }
                ),
                IdgafListItem(
                    title = "SHARE ALL ${clip.frameCount} JPG FRAMES",
                    subtitle = "Send raw JPG sequence",
                    onClick = { shareClip(clip) }
                ),
                IdgafListItem(
                    title = "DELETE SEQUENCE",
                    subtitle = "Remove all ${clip.frameCount} JPG frames from device",
                    onClick = { confirmDeleteClip(clip) }
                )
            )
        )
    }

    private fun viewImageUri(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No image viewer found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playAudioClip(clip: RecordedClip) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(clip.uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No audio player found.", Toast.LENGTH_SHORT).show()
        }
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
            when (clip.type) {
                VaultItemType.VIDEO -> {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, clip.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "Share Clip"))
                }
                VaultItemType.STOP_MOTION_SEQ -> {
                    val uris = ArrayList(clip.frames.map { it.uri })
                    if (uris.isEmpty()) return
                    val share = if (uris.size == 1) {
                        Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uris[0])
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    } else {
                        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "image/jpeg"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    startActivity(Intent.createChooser(share, "Share ${uris.size} JPG Frame(s)"))
                }
                VaultItemType.WAV_AUDIO -> {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "audio/wav"
                        putExtra(Intent.EXTRA_STREAM, clip.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "Share 48kHz WAV"))
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to share file.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteClip(clip: RecordedClip) {
        val (title, msg) = when (clip.type) {
            VaultItemType.VIDEO -> {
                val extra = if (clip.pairedWav != null) "\n(+ paired 48kHz WAV sidecar)" else ""
                "DELETE CLIP?" to "${clip.name}$extra"
            }
            VaultItemType.STOP_MOTION_SEQ -> {
                "DELETE JPG SEQUENCE?" to "Permanently remove all ${clip.frameCount} JPG frame(s) in ${clip.name}?"
            }
            VaultItemType.WAV_AUDIO -> {
                "DELETE WAV AUDIO?" to clip.name
            }
        }
        IdgafUiDialogs.showActionDialog(
            context = this,
            title = title,
            message = msg,
            primaryLabel = "DELETE",
            primaryBgRes = R.drawable.bg_shutter_stop,
            primaryTextColor = 0xFFFFFFFF.toInt(),
            onPrimary = {
                Thread {
                    var ok = false
                    when (clip.type) {
                        VaultItemType.VIDEO -> {
                            ok = deletePrimaryItem(clip)
                            clip.pairedWav?.let { deleteVaultFilePart(it) }
                        }
                        VaultItemType.STOP_MOTION_SEQ -> {
                            val legacyDirs = linkedSetOf<File>()
                            var count = 0
                            for (frame in clip.frames) {
                                if (deleteVaultFilePart(frame)) {
                                    count++
                                    frame.legacyFile?.parentFile?.let { legacyDirs.add(it) }
                                }
                            }
                            legacyDirs.forEach { d ->
                                try {
                                    if (d.isDirectory && d.listFiles().isNullOrEmpty()) d.delete()
                                } catch (_: Exception) {
                                }
                            }
                            ok = count > 0
                        }
                        VaultItemType.WAV_AUDIO -> {
                            ok = deletePrimaryItem(clip)
                        }
                    }
                    runOnUiThread {
                        Toast.makeText(this, if (ok) "Deleted" else "Could not delete item", Toast.LENGTH_SHORT).show()
                        if (ok) loadClips()
                    }
                }.start()
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

            val totalDisplayBytes = clip.sizeBytes + (clip.pairedWav?.sizeBytes ?: 0L)
            val mb = totalDisplayBytes / (1024f * 1024f)
            holder.txtSize.text = String.format(Locale.US, "%.1f MB", mb)

            when (clip.type) {
                VaultItemType.VIDEO -> {
                    val secs = (clip.durationMs / 1000L).coerceAtLeast(0L)
                    val timeStr = String.format(Locale.US, "%02d:%02d", secs / 60L, secs % 60L)
                    holder.txtDur.text = if (clip.pairedWav != null) "$timeStr • WAV" else timeStr
                    holder.txtDur.setTextColor(if (clip.pairedWav != null) 0xFF34C759.toInt() else 0xFFFFFFFF.toInt())

                    val pairedSeq = clip.pairedJpgSeqKey?.let { k -> allClips.firstOrNull { it.key == k } }
                    val topBadge = when {
                        pairedSeq != null -> "+${pairedSeq.frameCount} JPG"
                        activeFolderFilter == FOLDER_ALL && clip.folderName.isNotEmpty() -> clip.folderName
                        else -> ""
                    }
                    if (topBadge.isNotEmpty()) {
                        holder.txtFolderBadge.text = topBadge
                        holder.txtFolderBadge.setTextColor(0xFFFF5F1F.toInt())
                        holder.txtFolderBadge.visibility = View.VISIBLE
                    } else {
                        holder.txtFolderBadge.visibility = View.GONE
                    }
                }
                VaultItemType.STOP_MOTION_SEQ -> {
                    holder.txtDur.text = "JPG • ${clip.frameCount}F"
                    holder.txtDur.setTextColor(0xFFFF5F1F.toInt())
                    holder.txtFolderBadge.text = "STOP-MO"
                    holder.txtFolderBadge.setTextColor(0xFFFF5F1F.toInt())
                    holder.txtFolderBadge.visibility = View.VISIBLE
                }
                VaultItemType.WAV_AUDIO -> {
                    holder.txtDur.text = "48k WAV"
                    holder.txtDur.setTextColor(0xFF34C759.toInt())
                    holder.txtFolderBadge.text = "AUDIO"
                    holder.txtFolderBadge.setTextColor(0xFF34C759.toInt())
                    holder.txtFolderBadge.visibility = View.VISIBLE
                }
            }

            // Multi-select visual state
            val isSelected = selectedKeys.contains(clip.key)
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
            holder.imgThumb.tag = clip.key
            if (clip.type != VaultItemType.WAV_AUDIO) {
                Thread {
                    val bmp: Bitmap? = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            contentResolver.loadThumbnail(clip.uri, Size(320, 180), null)
                        } else if (clip.type == VaultItemType.STOP_MOTION_SEQ) {
                            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                            contentResolver.openInputStream(clip.uri)?.use { ins ->
                                BitmapFactory.decodeStream(ins, null, opts)
                            }
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
                            if (holder.imgThumb.tag == clip.key) {
                                holder.imgThumb.setImageBitmap(bmp)
                            }
                        }
                    }
                }.start()
            }

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
