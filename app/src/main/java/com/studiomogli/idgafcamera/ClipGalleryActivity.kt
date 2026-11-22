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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

data class RecordedClip(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long
)

class ClipGalleryActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var txtEmpty: TextView
    private lateinit var txtCount: TextView
    private val clips = mutableListOf<RecordedClip>()
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

        findViewById<TextView>(R.id.btnGalleryBack).setOnClickListener { finish() }

        adapter = ClipAdapter(
            clips = clips,
            onPlay = { playClip(it) },
            onShare = { shareClip(it) },
            onDelete = { confirmDeleteClip(it) }
        )
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter

        loadClips()
    }

    private fun loadClips() {
        Thread {
            val found = mutableListOf<RecordedClip>()
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE
            )
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

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        found.add(
                            RecordedClip(
                                id = id,
                                uri = uri,
                                name = cursor.getString(nameCol) ?: "Clip",
                                durationMs = cursor.getLong(durCol),
                                sizeBytes = cursor.getLong(sizeCol)
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }

            runOnUiThread {
                clips.clear()
                clips.addAll(found)
                adapter.notifyDataSetChanged()
                txtCount.text = "${clips.size} CLIPS"
                txtEmpty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
                recycler.visibility = if (clips.isEmpty()) View.GONE else View.VISIBLE
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

    inner class ClipAdapter(
        private val clips: List<RecordedClip>,
        private val onPlay: (RecordedClip) -> Unit,
        private val onShare: (RecordedClip) -> Unit,
        private val onDelete: (RecordedClip) -> Unit
    ) : RecyclerView.Adapter<ClipAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val imgThumb: ImageView = v.findViewById(R.id.imgClipThumb)
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

            holder.imgThumb.setOnClickListener { onPlay(clip) }
            holder.itemView.setOnClickListener { onPlay(clip) }
            holder.btnShare.setOnClickListener { onShare(clip) }
            holder.btnDelete.setOnClickListener { onDelete(clip) }
        }

        override fun getItemCount(): Int = clips.size
    }
}
