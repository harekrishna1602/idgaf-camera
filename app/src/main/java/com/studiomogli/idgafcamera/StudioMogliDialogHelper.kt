package com.studiomogli.idgafcamera

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object StudioMogliDialogHelper {

    fun showPrompt(
        activity: Activity,
        title: String,
        message: String,
        positiveLabel: String = "OK",
        positiveDanger: Boolean = false,
        neutralLabel: String? = null,
        cancelLabel: String? = "CANCEL",
        keepOpenOnAction: Boolean = false,
        onPositive: ((AlertDialog) -> Unit)? = null,
        onNeutral: ((AlertDialog) -> Unit)? = null
    ): AlertDialog {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_studio_prompt, null)
        val dialog = AlertDialog.Builder(activity).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.txtStudioPromptTitle).text = title
        view.findViewById<TextView>(R.id.txtStudioPromptMsg).text = message

        val btnCancel = view.findViewById<TextView>(R.id.btnStudioPromptCancel)
        if (cancelLabel == null) {
            btnCancel.visibility = View.GONE
        } else {
            btnCancel.text = cancelLabel
            btnCancel.setOnClickListener { dialog.dismiss() }
        }

        val btnNeutral = view.findViewById<TextView>(R.id.btnStudioPromptNeutral)
        if (neutralLabel != null) {
            btnNeutral.visibility = View.VISIBLE
            btnNeutral.text = neutralLabel
            btnNeutral.setOnClickListener {
                if (!keepOpenOnAction) dialog.dismiss()
                onNeutral?.invoke(dialog)
            }
        }

        val btnPos = view.findViewById<TextView>(R.id.btnStudioPromptPositive)
        btnPos.text = positiveLabel
        if (positiveDanger) {
            btnPos.setBackgroundResource(R.drawable.bg_shutter_stop)
            btnPos.setTextColor(0xFFFFFFFF.toInt())
        }
        btnPos.setOnClickListener {
            if (!keepOpenOnAction) dialog.dismiss()
            onPositive?.invoke(dialog)
        }

        dialog.show()
        return dialog
    }

    fun showInput(
        activity: Activity,
        title: String,
        message: String? = null,
        initialText: String = "",
        hint: String = "",
        positiveLabel: String = "SAVE",
        neutralLabel: String? = null,
        onPositive: (String) -> Unit,
        onNeutral: ((String) -> Unit)? = null
    ): AlertDialog {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_studio_input, null)
        val dialog = AlertDialog.Builder(activity).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.txtStudioInputTitle).text = title
        val txtMsg = view.findViewById<TextView>(R.id.txtStudioInputMsg)
        if (!message.isNullOrBlank()) {
            txtMsg.visibility = View.VISIBLE
            txtMsg.text = message
        }

        val edt = view.findViewById<EditText>(R.id.edtStudioInput)
        edt.hint = hint
        edt.setText(initialText)
        edt.setSelection(edt.text?.length ?: 0)

        view.findViewById<TextView>(R.id.btnStudioInputCancel).setOnClickListener {
            dialog.dismiss()
        }

        val btnNeutral = view.findViewById<TextView>(R.id.btnStudioInputNeutral)
        if (neutralLabel != null) {
            btnNeutral.visibility = View.VISIBLE
            btnNeutral.text = neutralLabel
            btnNeutral.setOnClickListener {
                val txt = edt.text?.toString().orEmpty()
                dialog.dismiss()
                onNeutral?.invoke(txt)
            }
        }

        view.findViewById<TextView>(R.id.btnStudioInputPositive).apply {
            text = positiveLabel
            setOnClickListener {
                val txt = edt.text?.toString().orEmpty()
                dialog.dismiss()
                onPositive(txt)
            }
        }

        dialog.show()
        return dialog
    }

    fun showList(
        activity: Activity,
        title: String,
        items: List<Pair<String, Boolean>>,
        onSelect: (Int) -> Unit
    ): AlertDialog {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_studio_list, null)
        val dialog = AlertDialog.Builder(activity).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.txtStudioListTitle).text = title
        view.findViewById<TextView>(R.id.btnStudioListClose).setOnClickListener { dialog.dismiss() }

        val container = view.findViewById<LinearLayout>(R.id.studioListContainer)
        val dp = activity.resources.displayMetrics.density
        items.forEachIndexed { idx, (label, isHighlighted) ->
            val row = TextView(activity).apply {
                text = label
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * dp).toInt(), (9 * dp).toInt(), (12 * dp).toInt(), (9 * dp).toInt())
                setBackgroundResource(if (isHighlighted) R.drawable.bg_dock_pill_active else R.drawable.bg_hud_pill)
                setTextColor(if (isHighlighted) 0xFFFF5F1F.toInt() else 0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (idx < items.lastIndex) bottomMargin = (6 * dp).toInt()
                }
                setOnClickListener {
                    dialog.dismiss()
                    onSelect(idx)
                }
            }
            container.addView(row)
        }

        dialog.show()
        return dialog
    }

    fun showProgress(
        activity: Activity,
        title: String,
        initialStage: String,
        onCancel: () -> Unit
    ): Triple<AlertDialog, TextView, Pair<ProgressBar, TextView>> {
        val dp = activity.resources.displayMetrics.density
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_hud_pill)
            setPadding((18 * dp).toInt(), (16 * dp).toInt(), (18 * dp).toInt(), (14 * dp).toInt())
        }
        val txtTitle = TextView(activity).apply {
            text = title
            textSize = 13.5f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFF5F1F.toInt())
        }
        val txtStage = TextView(activity).apply {
            text = initialStage
            textSize = 11.5f
            setTextColor(0xFFE5E7EB.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * dp).toInt() }
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * dp).toInt()
                bottomMargin = (6 * dp).toInt()
            }
        }
        val txtBytes = TextView(activity).apply {
            text = "0.0 / 0.0 MB"
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF8E96A4.toInt())
        }
        val btnCancel = TextView(activity).apply {
            text = "CANCEL"
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_hud_pill)
            setTextColor(0xFFFF3B30.toInt())
            setPadding((14 * dp).toInt(), (7 * dp).toInt(), (14 * dp).toInt(), (7 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                topMargin = (10 * dp).toInt()
            }
            setOnClickListener { onCancel() }
        }
        root.addView(txtTitle)
        root.addView(txtStage)
        root.addView(bar)
        root.addView(txtBytes)
        root.addView(btnCancel)

        val dialog = AlertDialog.Builder(activity).setView(root).setCancelable(false).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        return Triple(dialog, txtStage, bar to txtBytes)
    }
}
