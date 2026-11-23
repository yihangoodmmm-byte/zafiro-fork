package com.niki914.zafiro.app.workbench

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

internal class ProjectCodeHighlight(
    extension: String,
    private val keyword: Color,
    private val literal: Color,
    private val comment: Color,
) : VisualTransformation {
    private val tokens = when (extension.lowercase()) {
        "kt", "kts", "java", "js", "jsx", "ts", "tsx", "go", "rs", "c", "cpp", "h", "hpp" -> slashTokens
        "py", "sh", "rb" -> hashTokens
        else -> null
    }

    override fun filter(text: AnnotatedString): TransformedText {
        val regex = tokens ?: return TransformedText(text, OffsetMapping.Identity)
        if (text.length > 48_000) return TransformedText(text, OffsetMapping.Identity)
        val builder = AnnotatedString.Builder(text)
        for (match in regex.findAll(text.text)) {
            val color = when {
                match.groups[1] != null -> comment
                match.groups[2] != null || match.groups[3] != null -> literal
                match.value in keywords -> keyword
                else -> continue
            }
            builder.addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private companion object {
        val slashTokens = tokens("//[^\\r\\n]*|/\\*[\\s\\S]*?(?:\\*/|$)")
        val hashTokens = tokens("#[^\\r\\n]*")
        fun tokens(comments: String) = Regex("""($comments)|("(?:\\.|[^"\\\r\n])*+"|'(?:\\.|[^'\\\r\n])*+')|(\b[0-9]+(?:\.[0-9]+)?\b)|[A-Za-z_][A-Za-z_0-9]*""")
        val keywords = "class interface enum object fun def function return if else when switch case for while break continue import package from as val var let const int bool void public private protected internal static final new this self super try catch finally throw async await yield suspend true false null True False None struct impl fn match use pub".split(' ').toSet()
    }
}
