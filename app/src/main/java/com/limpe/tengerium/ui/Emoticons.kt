package com.limpe.tengerium.ui

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import com.limpe.tengerium.R
import java.util.regex.Pattern

/**
 * Classic MSN-style text emoticons. The message text is never changed (so other
 * clients, which convert these codes themselves, keep working); we only draw an image
 * over each code when displaying a message.
 */
object Emoticons {

    class Emoticon(@DrawableRes val drawable: Int, val codes: List<String>) {
        /** The code inserted by the picker. */
        val code: String get() = codes.first()
    }

    val all: List<Emoticon> = listOf(
        Emoticon(R.drawable.emo_smile, listOf(":)", ":-)")),
        Emoticon(R.drawable.emo_grin, listOf(":D", ":-D")),
        Emoticon(R.drawable.emo_surprised, listOf(":O", ":-O")),
        Emoticon(R.drawable.emo_wink, listOf(";)", ";-)")),
        Emoticon(R.drawable.emo_tongue, listOf(":P", ":-P")),
        Emoticon(R.drawable.emo_sad, listOf(":(", ":-(")),
        Emoticon(R.drawable.emo_cry, listOf(":'(")),
        Emoticon(R.drawable.emo_neutral, listOf(":|", ":-|")),
        Emoticon(R.drawable.emo_cool, listOf("(H)", "8-)", "B-)")),
        Emoticon(R.drawable.emo_angry, listOf(":@", ":-@")),
        Emoticon(R.drawable.emo_embarrassed, listOf(":$", ":-$")),
        Emoticon(R.drawable.emo_confused, listOf(":S", ":-S")),
        Emoticon(R.drawable.emo_heart, listOf("(L)")),
        Emoticon(R.drawable.emo_brokenheart, listOf("(U)")),
        Emoticon(R.drawable.emo_thumbsup, listOf("(Y)")),
        Emoticon(R.drawable.emo_thumbsdown, listOf("(N)")),
        Emoticon(R.drawable.emo_kiss, listOf("(K)"))
    )

    private val byCode: Map<String, Int> =
        all.flatMap { e -> e.codes.map { it.lowercase() to e.drawable } }.toMap()

    // A code only counts when it stands on its own: after the start / whitespace / an opening
    // bracket or quote, and before the end / whitespace / light punctuation.
    private val pattern: Pattern = run {
        val alternatives = byCode.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Pattern.quote(it) }
        Pattern.compile("(?<![^\\s(\\[{\"'])(?:$alternatives)(?![^\\s.,!?;])", Pattern.CASE_INSENSITIVE)
    }

    /** Returns [text] with an image drawn over every emoticon code. */
    fun apply(context: Context, text: CharSequence, textSizePx: Float): CharSequence {
        val matcher = pattern.matcher(text)
        var result: SpannableStringBuilder? = null
        val size = (textSizePx * 1.3f).toInt()
        while (matcher.find()) {
            val res = byCode[matcher.group().lowercase()] ?: continue
            val drawable = AppCompatResources.getDrawable(context, res) ?: continue
            drawable.setBounds(0, 0, size, size)
            if (result == null) result = SpannableStringBuilder(text)
            result.setSpan(
                ImageSpan(drawable, ImageSpan.ALIGN_BASELINE),
                matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return result ?: text
    }
}
