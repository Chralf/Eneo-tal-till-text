package se.iteca.eneotaltilltext.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import se.iteca.eneotaltilltext.R

object MarkdownClipboard {
    fun copyResult(context: Context, markdown: String) {
        val payload = MarkdownExport.fromMarkdown(markdown)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newHtmlText(
            context.getString(R.string.detail_section_result),
            payload.plain,
            payload.html,
        )
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.detail_copied_result), Toast.LENGTH_SHORT).show()
    }
}
