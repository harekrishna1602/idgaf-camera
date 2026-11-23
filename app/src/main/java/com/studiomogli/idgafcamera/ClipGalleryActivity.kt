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
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale

data class RecordedClip(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folderName: String // "" for root Movies/IDGAF_Camera, or subfolder name
)

class ClipGalleryActivity : AppCompatActivity() {

    companion object {
        private const val FOLDER_ALL = "__ALL__"
        private const val FOLDER_ROOT = ""
        private const val PREFS_NAME = "idgaf_vault_folders_v1"
        private const val KEY_FOLDERS = "custom_folders_set"
    }

    private lateinit var recycler: RecyclerView
    private lateinit var txtEmpty: TextView
    private lateinit var txtCount: TextView
    private lateinit var folderChipRow: LinearLayout
    private lateinit var btnNewFolder: TextView
    private lateinit var btnToggleSelect: TextView
    private lateinit var bulkActionBar: LinearLayout
    private lateinit var txtSelectedCount: TextView
    private lateinit var btnSelectAll: TextView
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
        btnNewFolder = findViewById(R.id.btnNewFolder)
        btnToggleSelect = findViewById(R.id.btnToggleSelect)
        bulkActionBar = findViewById(R.id.bulkActionBar)
        txtSelectedCount = findViewById(R.id.txtSelectedCount)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnBulkMove = findViewById(R.id.btnBulkMove)
        btnBulkShare = findViewById(R.id.btnBulkShare)
        btnBulkDelete = findViewById(R.id.btnBulkDelete)

        findViewById<TextView>(R.id.btnGalleryBack).setOnClickListener { finish() }

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
        txtSelectedCount.text = "${selectedIds.size} SELECTED"
        btnSelectAll.text = if (selectedIds.size == visibleClips.size && visibleClips.isNotEmpty()) "NONE" else "ALL"
    }

    private fun showCreateFolderDialog(moveSelectionAfterCreate: Boolean) {
        val input = EditText(this).apply {
            hint = "Folder name (e.g. Scene 01, B-Roll)"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            setPadding(36, 28, 36, 28)
        }
        AlertDialog.Builder(this)
            .setTitle("Create New Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val clean = sanitizeFolderName(input.text.toString())
                if (clean.isEmpty()) {
                    Toast.makeText(this, "Enter a valid folder name.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
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
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMoveToFolderDialog() {
        val folderList = knownFolders.toList().sorted()
        val options = mutableListOf("+ Create New Folder...", "ROOT (Movies/IDGAF_Camera)")
        folderList.forEach { options.add("Folder: $it") }

        AlertDialog.Builder(this)
            .setTitle("Move ${selectedIds.size} Clip(s) To:")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showCreateFolderDialog(moveSelectionAfterCreate = true)
                    1 -> moveSelectedClipsToFolder(FOLDER_ROOT)
                    else -> moveSelectedClipsToFolder(folderList[which - 2])
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        AlertDialog.Builder(this)
            .setTitle("Delete ${targets.size} Clip(s)?")
            .setMessage("This will permanently remove the selected video files.")
            .setPositiveButton("Delete All") { _, _ ->
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
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        AlertDialog.Builder(this)
            .setTitle("Remove Folder '$folderName'?")
            .setPositiveButton("Remove") { _, _ ->
                knownFolders.remove(folderName)
                persistKnownFolders()
                if (activeFolderFilter == folderName) activeFolderFilter = FOLDER_ALL
                renderFolderChips()
                applyFolderFilter()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        AlertDialog.Builder(this)
            .setTitle("Delete Clip?")
            .setMessage(clip.name)
            .setPositiveButton("Delete") { _, _ ->
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
            }
            .setNegativeButton("Cancel", null)
            .show()
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
