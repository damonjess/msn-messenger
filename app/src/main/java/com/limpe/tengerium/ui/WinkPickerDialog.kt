package com.limpe.tengerium.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.GridLayout
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
    private val cells = mutableMapOf<String, TextView>()
    private var selected: Wink = WinkCatalog.all.first()

    init {
        setContentView(binding.root)
        setOnDismissListener { WinkSoundPlayer.stop() }
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
            val cell = TextView(context).apply {
                text = wink.emoji
                textSize = 28f
                gravity = Gravity.CENTER
                contentDescription = wink.name
                setBackgroundResource(R.drawable.bg_wink_item)
                setOnClickListener { select(wink) }
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
        cells.forEach { (id, cell) ->
            cell.setBackgroundResource(
                if (id == wink.id) R.drawable.bg_wink_item_selected else R.drawable.bg_wink_item
            )
        }
    }

    private fun playPreview() {
        WinkSoundPlayer.play(context, selected.id)
        val preview = binding.tvSelectedEmoji
        preview.animate().cancel()
        preview.scaleX = 1f
        preview.scaleY = 1f
        preview.rotation = 0f
        preview.animate()
            .scaleX(1.45f)
            .scaleY(1.45f)
            .rotation(-8f)
            .setDuration(160)
            .withEndAction {
                preview.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .rotation(0f)
                    .setDuration(240)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            }
            .start()
    }
}
