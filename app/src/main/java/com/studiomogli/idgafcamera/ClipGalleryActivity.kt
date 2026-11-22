package com.studiomogli.idgafcamera

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

data class RecordedClip(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long
)

class ClipGalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var txtEmpty: TextView
    private lateinit var txtHeaderCount: TextView
    private val clips = mutableListOf<RecordedClip>()
    private lateinit var adapter: ClipAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clip_gallery)

        recyclerView = findViewById(R.id.recyclerClips)
        txtEmpty = findViewById(R.id.txtEmptyClips)
        txtHeaderCount = findViewById(R.id.txtClipCount)

        findViewById<TextView>(R.id.btnGalleryBack).setOnClickListener { finish() }

        adapter = ClipAdapter(
            items = clips,
            onPlay = { clip -> playClip(clip) },
            onShare = { clip -> shareClip(clip) },
            onDelete = { clip -> confirmDeleteClip(clip) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadClips()
    }

    private fun loadClips() {
        clips.clear()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.RELATIVE_PATH
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("%IDGAF_Camera%")
        } else {
            arrayOf("IDGAF_%")
        }

        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    clips.add(
                        RecordedClip(
                            id = id,
                            uri = uri,
                            displayName = cursor.getString(nameCol) ?: "Clip.mp4",
                            durationMs = cursor.getLong(durCol),
                            sizeBytes = cursor.getLong(sizeCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not load clips: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        txtHeaderCount.text = "${clips.size} CLIPS"
        txtEmpty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (clips.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
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
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, clip.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share ${clip.displayName}"))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not share clip.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteClip(clip: RecordedClip) {
        AlertDialog.Builder(this)
            .setTitle("Delete Clip?")
            .setMessage(clip.displayName)
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val deleted = contentResolver.delete(clip.uri, null, null)
                    if (deleted > 0) {
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                        loadClips()
                    } else {
                        Toast.makeText(this, "Could not delete clip.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Delete error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class ClipAdapter(
        private val items: List<RecordedClip>,
        private val onPlay: (RecordedClip) -> Unit,
        private val onShare: (RecordedClip) -> Unit,
        private val onDelete: (RecordedClip) -> Unit
    ) : RecyclerView.Adapter<ClipAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val imgThumb: ImageView = v.findViewById(R.id.imgClipThumb)
            val txtName: TextView = v.findViewById(R.id.txtClipName)
            val txtMeta: TextView = v.findViewById(R.id.txtClipMeta)
            val btnPlay: TextView = v.findViewById(R.id.btnClipPlay)
            val btnShare: TextView = v.findViewById(R.id.btnClipShare)
            val btnDelete: TextView = v.findViewById(R.id.btnClipDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_clip_card, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val clip = items[position]
            holder.txtName.text = clip.displayName
            val secs = (clip.durationMs / 1000L).coerceAtLeast(0L)
            val mb = clip.sizeBytes / (1024.0 * 1024.0)
            holder.txtMeta.text = String.format(Locale.US, "%02d:%02d  •  %.1f MB", secs / 60, secs % 60, mb)

            holder.imgThumb.setImageBitmap(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val bmp: Bitmap = holder.itemView.context.contentResolver.loadThumbnail(
                        clip.uri,
                        Size(240, 135),
                        null
                    )
                    holder.imgThumb.setImageBitmap(bmp)
                } catch (_: Exception) {
                }
            }

            holder.itemView.setOnClickListener { onPlay(clip) }
            holder.btnPlay.setOnClickListener { onPlay(clip) }
            holder.btnShare.setOnClickListener { onShare(clip) }
            holder.btnDelete.setOnClickListener { onDelete(clip) }
        }

        override fun getItemCount(): Int = items.size
    }
}
