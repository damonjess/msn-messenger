package com.limpe.tengerium.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.GridLayout
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.limpe.tengerium.R
import com.limpe.tengerium.databinding.DialogWinkPickerBinding
import com.limpe.tengerium.util.WinkSoundPlayer

/**
 * The classic "Select a Wink" picker: pick a wink, preview it, submit it to the chat.
 */
class WinkPickerDialog(
    private val context: Context,
    private val onSend: (Wink) -> Unit
) : Dialog(context) {

    private val binding = DialogWinkPickerBinding.inflate(layoutInflater)
    private val cells = mutableMapOf<String, View>()
    private var selected: Wink = WinkCatalog.all.first()
    private var fullPreview: Dialog? = null

    init {
        setContentView(binding.root)
        setOnDismissListener {
            fullPreview?.dismiss()
            WinkSoundPlayer.stop()
        }
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(
            (320 * context.resources.displayMetrics.density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        window?.setGravity(Gravity.CENTER)

        buildGrid()
        select(selected)

        binding.btnWinkView.setOnClickListener { playPreview() }
        binding.btnWinkSubmit.setOnClickListener {
            onSend(selected)
            dismiss()
        }
    }

    private fun buildGrid() {
        val density = context.resources.displayMetrics.density
        val size = (58 * density).toInt()
        val margin = (3 * density).toInt()

        WinkCatalog.all.forEach { wink ->
            val artwork = WinkCatalog.artworkRes(wink.id)
            val cell: View = if (artwork != null) {
                ImageView(context).apply {
                    setImageResource(artwork)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = wink.name
                    setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                    setBackgroundResource(R.drawable.bg_wink_item)
                    setOnClickListener { select(wink) }
                }
            } else {
                TextView(context).apply {
                    text = wink.emoji
                    textSize = 28f
                    gravity = Gravity.CENTER
                    contentDescription = wink.name
                    setBackgroundResource(R.drawable.bg_wink_item)
                    setOnClickListener { select(wink) }
                }
            }
            binding.gridWinks.addView(cell, GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(margin, margin, margin, margin)
            })
            cells[wink.id] = cell
        }
    }

    private fun select(wink: Wink) {
        selected = wink
        binding.tvSelectedEmoji.text = wink.emoji
        binding.tvSelectedName.text = wink.name
        val artwork = WinkCatalog.artworkRes(wink.id)
        binding.ivSelectedWinkArt.visibility = if (artwork == null) View.GONE else View.VISIBLE
        binding.tvSelectedEmoji.visibility = if (artwork == null) View.VISIBLE else View.GONE
        if (artwork != null) binding.ivSelectedWinkArt.setImageResource(artwork)
        cells.forEach { (id, cell) ->
            cell.setBackgroundResource(
                if (id == wink.id) R.drawable.bg_wink_item_selected else R.drawable.bg_wink_item
            )
        }
    }

    private fun playPreview() {
        fullPreview?.dismiss()
        WinkSoundPlayer.play(context, selected.id)
        val winkToPreview = selected
        val previewDialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val overlay = WinkOverlayView(context)
        previewDialog.setContentView(overlay)
        previewDialog.setOnDismissListener {
            WinkSoundPlayer.stop()
            fullPreview = null
        }
        overlay.onFinished = { previewDialog.dismiss() }
        fullPreview = previewDialog
        previewDialog.show()
        previewDialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.58f)
        }
        overlay.post { overlay.play(winkToPreview) }
    }
}
