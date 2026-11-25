package com.studiomogli.idgafcamera

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

data class IdgafListItem(
    val title: String,
    val subtitle: String? = null,
    val isActive: Boolean = false,
    val accentTitle: Boolean = false,
    val trailingLabel: String? = null,
    val trailingColor: Int = 0xFFFF3B30.toInt(),
    val onTrailingClick: (() -> Unit)? = null,
    val onClick: () -> Unit
)

data class IdgafProgressDialogHandle(
    val dialog: AlertDialog,
    val txtStage: TextView,
    val progressBar: ProgressBar,
    val txtBytes: TextView
)

/**
 * Unified Studio Mogli / IDGAF Dark Cinema Modal System:
 * Eliminates every stock Android white/grey AlertDialog across MainActivity & ClipGalleryActivity.
 */
object IdgafUiDialogs {

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun createTransparentDialog(context: Context, root: View, cancelable: Boolean = true): AlertDialog {
        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .setCancelable(cancelable)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }

    private fun makeRootCard(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_hud_pill)
        val p = dp(context, 16)
        setPadding(p, p, p, p)
    }

    private fun makeButton(
        context: Context,
        label: String,
        bgRes: Int,
        textColor: Int,
        weight: Float = 1f,
        marginEndDp: Int = 0,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        textSize = 10.5f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setBackgroundResource(bgRes)
        setTextColor(textColor)
        setPadding(dp(context, 10), 0, dp(context, 10), 0)
        layoutParams = LinearLayout.LayoutParams(
            if (weight > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(context, 36),
            weight
        ).apply {
            if (marginEndDp > 0) marginEnd = dp(context, marginEndDp)
        }
        setOnClickListener { onClick() }
    }

    fun showActionDialog(
        context: Context,
        title: String,
        message: String,
        primaryLabel: String,
        primaryBgRes: Int = R.drawable.bg_lens_active,
        primaryTextColor: Int = 0xFF0B0D10.toInt(),
        dismissOnPrimary: Boolean = true,
        onPrimary: () -> Unit,
        secondaryLabel: String? = null,
        secondaryTextColor: Int = 0xFFFFFFFF.toInt(),
        dismissOnSecondary: Boolean = true,
        onSecondary: (() -> Unit)? = null,
        cancelLabel: String? = "CANCEL",
        onCancel: (() -> Unit)? = null,
        rotationDegrees: Float = 0f
    ): Dialog {
        val root = makeRootCard(context)

        root.addView(TextView(context).apply {
            text = title
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFF5F1F.toInt())
        })

        root.addView(TextView(context).apply {
            text = message
            textSize = 12f
            setTextColor(0xFFE5E7EB.toInt())
            setLineSpacing(dp(context, 2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 8)
                bottomMargin = dp(context, 14)
            }
        })

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        lateinit var dialog: Dialog

        if (cancelLabel != null) {
            btnRow.addView(
                makeButton(
                    context = context,
                    label = cancelLabel,
                    bgRes = R.drawable.bg_hud_pill,
                    textColor = 0xFFB0B7C3.toInt(),
                    weight = 0.85f,
                    marginEndDp = 6
                ) {
                    dialog.dismiss()
                    onCancel?.invoke()
                }
            )
        }
        if (secondaryLabel != null) {
            btnRow.addView(
                makeButton(
                    context = context,
                    label = secondaryLabel,
                    bgRes = R.drawable.bg_hud_pill,
                    textColor = secondaryTextColor,
                    weight = 1f,
                    marginEndDp = 6
                ) {
                    if (dismissOnSecondary) dialog.dismiss()
                    onSecondary?.invoke()
                }
            )
        }
        btnRow.addView(
            makeButton(
                context = context,
                label = primaryLabel,
                bgRes = primaryBgRes,
                textColor = primaryTextColor,
                weight = 1.15f
            ) {
                if (dismissOnPrimary) dialog.dismiss()
                onPrimary()
            }
        )
        root.addView(btnRow)

        if (kotlin.math.abs(rotationDegrees) > 1f) {
            val dm = context.resources.displayMetrics
            // In landscape, rotated 90° card's width becomes vertical screen height -> keep <= 68% of landscape height!
            val shortEdge = minOf(dm.widthPixels, dm.heightPixels)
            val cardW = (shortEdge * 0.68f).toInt().coerceIn(dp(context, 210), dp(context, 268))

            val fullOverlay = FrameLayout(context).apply {
                setBackgroundColor(Color.argb(155, 0, 0, 0))
                clipChildren = false
                clipToPadding = false
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setOnClickListener { dialog.dismiss() }
            }
            root.isClickable = true
            root.layoutParams = FrameLayout.LayoutParams(
                cardW,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            root.rotation = rotationDegrees
            fullOverlay.addView(root)

            val fullDlg = Dialog(context).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setContentView(fullOverlay)
                setCancelable(true)
                setCanceledOnTouchOutside(true)
            }
            dialog = fullDlg
            fullDlg.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                )
            }
            fullDlg.show()
            return fullDlg
        }

        dialog = createTransparentDialog(context, root, cancelable = true)
        dialog.show()
        return dialog
    }

    fun showListSheet(
        context: Context,
        title: String,
        subtitle: String? = null,
        items: List<IdgafListItem>,
        closeLabel: String = "CLOSE"
    ): AlertDialog {
        val root = makeRootCard(context)

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(context).apply {
            text = title
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFF5F1F.toInt())
        })
        if (!subtitle.isNullOrBlank()) {
            titleCol.addView(TextView(context).apply {
                text = subtitle
                textSize = 9.5f
                typeface = Typeface.MONOSPACE
                setTextColor(0xFF8E96A4.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, 2) }
            })
        }
        header.addView(titleCol)

        lateinit var dialog: AlertDialog
        header.addView(TextView(context).apply {
            text = "✕"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_hud_pill)
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(context, 28), dp(context, 28))
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(header)

        val maxListH = (context.resources.displayMetrics.heightPixels * 0.56f).toInt().coerceIn(dp(context, 160), dp(context, 260))
        val scroll = ScrollView(context).apply {
            isScrollbarFadingEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 10)
            }
        }
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(context, 4), 0)
        }

        items.forEachIndexed { idx, item ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(if (item.isActive) R.drawable.bg_dock_pill_active else R.drawable.bg_hud_pill)
                setPadding(dp(context, 11), dp(context, 8), dp(context, 9), dp(context, 8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (idx < items.lastIndex) bottomMargin = dp(context, 6)
                }
                setOnClickListener {
                    dialog.dismiss()
                    item.onClick()
                }
            }

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(context).apply {
                text = if (item.isActive) "✓ ${item.title}" else item.title
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    when {
                        item.isActive -> 0xFFFF5F1F.toInt()
                        item.accentTitle -> 0xFFFF5F1F.toInt()
                        else -> 0xFFFFFFFF.toInt()
                    }
                )
            })
            if (!item.subtitle.isNullOrBlank()) {
                textCol.addView(TextView(context).apply {
                    text = item.subtitle
                    textSize = 9.5f
                    setTextColor(0xFF9CA3AF.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(context, 1) }
                })
            }
            row.addView(textCol)

            if (item.trailingLabel != null && item.onTrailingClick != null) {
                row.addView(TextView(context).apply {
                    text = item.trailingLabel
                    textSize = 9.5f
                    typeface = Typeface.MONOSPACE
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_hud_pill)
                    setTextColor(item.trailingColor)
                    setPadding(dp(context, 9), dp(context, 4), dp(context, 9), dp(context, 4))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(context, 26)
                    ).apply { marginStart = dp(context, 8) }
                    setOnClickListener {
                        dialog.dismiss()
                        item.onTrailingClick.invoke()
                    }
                })
            }
            listContainer.addView(row)
        }

        scroll.addView(listContainer)
        root.addView(scroll)

        // Cap ScrollView height cleanly in landscape so bottom stays visible on all phones
        scroll.post {
            if (scroll.height > maxListH) {
                scroll.layoutParams = scroll.layoutParams.apply { height = maxListH }
            }
        }

        dialog = createTransparentDialog(context, root, cancelable = true)
        dialog.show()
        return dialog
    }

    fun showTextInputDialog(
        context: Context,
        title: String,
        message: String? = null,
        hint: String,
        initialText: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        primaryLabel: String = "SAVE",
        onPrimary: (String) -> Unit,
        secondaryLabel: String? = null,
        secondaryTextColor: Int = 0xFFFF5F1F.toInt(),
        onSecondary: ((String) -> Unit)? = null
    ): AlertDialog {
        val root = makeRootCard(context)

        root.addView(TextView(context).apply {
            text = title
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFF5F1F.toInt())
        })

        if (!message.isNullOrBlank()) {
            root.addView(TextView(context).apply {
                text = message
                textSize = 11f
                setTextColor(0xFFB0B7C3.toInt())
                setLineSpacing(dp(context, 2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, 6) }
            })
        }

        lateinit var dialog: AlertDialog
        val input = EditText(context).apply {
            this.hint = hint
            this.inputType = inputType
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setText(initialText)
            setBackgroundResource(R.drawable.bg_dock_pill_active)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x66FFFFFF)
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 40)
            ).apply {
                topMargin = dp(context, 10)
                bottomMargin = dp(context, 12)
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val v = text.toString()
                    dialog.dismiss()
                    onPrimary(v)
                    true
                } else false
            }
        }
        root.addView(input)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        btnRow.addView(
            makeButton(
                context = context,
                label = "CANCEL",
                bgRes = R.drawable.bg_hud_pill,
                textColor = 0xFFB0B7C3.toInt(),
                weight = 0.85f,
                marginEndDp = 6
            ) { dialog.dismiss() }
        )
        if (secondaryLabel != null && onSecondary != null) {
            btnRow.addView(
                makeButton(
                    context = context,
                    label = secondaryLabel,
                    bgRes = R.drawable.bg_hud_pill,
                    textColor = secondaryTextColor,
                    weight = 1.05f,
                    marginEndDp = 6
                ) {
                    val v = input.text.toString()
                    dialog.dismiss()
                    onSecondary(v)
                }
            )
        }
        btnRow.addView(
            makeButton(
                context = context,
                label = primaryLabel,
                bgRes = R.drawable.bg_lens_active,
                textColor = 0xFF0B0D10.toInt(),
                weight = 1.1f
            ) {
                val v = input.text.toString()
                dialog.dismiss()
                onPrimary(v)
            }
        )
        root.addView(btnRow)

        dialog = createTransparentDialog(context, root, cancelable = true)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
        // Keep keyboard closed until the user taps inside the input box
        input.clearFocus()
        input.setOnClickListener {
            input.requestFocus()
            input.setSelection(input.text?.length ?: 0)
            try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Exception) {
            }
        }
        return dialog
    }

    fun showProgressDialog(
        context: Context,
        title: String,
        initialStage: String,
        onCancel: () -> Unit
    ): IdgafProgressDialogHandle {
        val root = makeRootCard(context)

        root.addView(TextView(context).apply {
            text = title
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFF5F1F.toInt())
        })

        val txtStage = TextView(context).apply {
            text = initialStage
            textSize = 11.5f
            setTextColor(0xFFE5E7EB.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 8) }
        }
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 10)
                bottomMargin = dp(context, 6)
            }
        }
        val txtBytes = TextView(context).apply {
            text = "0.0 / 0.0 MB"
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF8E96A4.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 12) }
        }
        root.addView(txtStage)
        root.addView(progressBar)
        root.addView(txtBytes)

        lateinit var dialog: AlertDialog
        root.addView(
            makeButton(
                context = context,
                label = "CANCEL",
                bgRes = R.drawable.bg_hud_pill,
                textColor = 0xFFFF3B30.toInt(),
                weight = 0f
            ) {
                onCancel()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(context, 34)
                )
            }
        )

        dialog = createTransparentDialog(context, root, cancelable = false)
        dialog.show()
        return IdgafProgressDialogHandle(dialog, txtStage, progressBar, txtBytes)
    }
}
