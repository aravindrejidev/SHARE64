package com.share64.app.core

data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Bullet(val spans: List<MdSpan>, val depth: Int) : MdBlock
    data class Numbered(val number: Int, val spans: List<MdSpan>, val depth: Int) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

/**
 * A small Markdown subset for release notes: headings, bullet/numbered/task lists, quotes, fenced
 * code, rules and inline **bold**, *italic*, `code`, [links](url). Pure Kotlin.
 */
object MarkdownLite {
    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*$""")
    private val rule = Regex("""^([-*_])(\s*\1){2,}$""")
    private val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val numbered = Regex("""^(\s*)(\d{1,4})[.)]\s+(.*)$""")
    private val quote = Regex("""^>\s?(.*)$""")

    fun parse(markdown: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val para = StringBuilder()
        val code = StringBuilder()
        var inCode = false
        fun flush() {
            if (para.isNotEmpty()) {
                out += MdBlock.Paragraph(inline(para.toString().trim()))
                para.clear()
            }
        }
        val text = markdown.replace("\r\n", "\n").replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        for (raw in text.split('\n')) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    out += MdBlock.Code(code.toString().trimEnd('\n'))
                    code.clear()
                    inCode = false
                } else {
                    flush()
                    inCode = true
                }
                continue
            }
            if (inCode) {
                code.append(raw).append('\n')
                continue
            }
            val t = line.trim()
            when {
                t.isEmpty() -> flush()
                heading.matches(t) -> {
                    flush()
                    val m = heading.find(t)!!
                    out += MdBlock.Heading(m.groupValues[1].length, inline(m.groupValues[2]))
                }
                rule.matches(t) -> {
                    flush()
                    out += MdBlock.Rule
                }
                bullet.matches(line) -> {
                    flush()
                    val m = bullet.find(line)!!
                    out += MdBlock.Bullet(inline(task(m.groupValues[2])), depth(m.groupValues[1]))
                }
                numbered.matches(line) -> {
                    flush()
                    val m = numbered.find(line)!!
                    out += MdBlock.Numbered(m.groupValues[2].toInt(), inline(m.groupValues[3]), depth(m.groupValues[1]))
                }
                quote.matches(t) -> {
                    flush()
                    out += MdBlock.Quote(inline(quote.find(t)!!.groupValues[1]))
                }
                else -> {
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(t)
                }
            }
        }
        if (inCode) out += MdBlock.Code(code.toString().trimEnd('\n'))
        flush()
        return out
    }

    private fun depth(indent: String) = (indent.replace("\t", "  ").length / 2).coerceIn(0, 3)

    private fun task(s: String) = when {
        s.startsWith("[ ] ") -> "\u2610 " + s.substring(4)
        s.startsWith("[x] ") || s.startsWith("[X] ") -> "\u2611 " + s.substring(4)
        else -> s
    }

    fun inline(text: String): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        val sb = StringBuilder()
        var bold = false
        var italic = false
        fun flush(link: String? = null) {
            if (sb.isNotEmpty()) {
                out += MdSpan(sb.toString(), bold, italic, false, link)
                sb.clear()
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_{}[]()#+-.!>" -> {
                    sb.append(text[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        out += MdSpan(text.substring(i + 1, end), bold, italic, code = true)
                        i = end + 1
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                c == '!' && i + 1 < text.length && text[i + 1] == '[' -> i++ // image: show its label as a link
                c == '[' -> {
                    val close = text.indexOf(']', i + 1)
                    val paren = if (close > 0 && text.startsWith("](", close)) text.indexOf(')', close + 2) else -1
                    if (paren > 0) {
                        flush()
                        val url = text.substring(close + 2, paren).trim().substringBefore(' ')
                        out += MdSpan(text.substring(i + 1, close), bold, italic, link = url)
                        i = paren + 1
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                (c == '*' || c == '_') && i + 1 < text.length && text[i + 1] == c -> {
                    val delimiter = "$c$c"
                    if (emphasis(text, i, 2, c == '_', bold, delimiter)) {
                        flush()
                        bold = !bold
                        i += 2
                    } else {
                        sb.append(delimiter)
                        i += 2
                    }
                }
                c == '*' || c == '_' -> {
                    if (emphasis(text, i, 1, c == '_', italic, c.toString())) {
                        flush()
                        italic = !italic
                    } else {
                        sb.append(c)
                    }
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        flush()
        return out
    }

    /** Can the marker at [i] open (if not active) or close (if active) emphasis? */
    private fun emphasis(text: String, i: Int, len: Int, underscore: Boolean, active: Boolean, marker: String): Boolean {
        val before = if (i > 0) text[i - 1] else ' '
        val after = if (i + len < text.length) text[i + len] else ' '
        return if (active) {
            !before.isWhitespace() && (!underscore || !after.isLetterOrDigit())
        } else {
            !after.isWhitespace() && (!underscore || !before.isLetterOrDigit()) &&
                text.indexOf(marker, i + len) != -1
        }
    }

    /** Plain text for surfaces that cannot style (TV dialog, notifications). */
    fun toPlainText(blocks: List<MdBlock>): String = buildString {
        for (b in blocks) {
            if (isNotEmpty()) append('\n')
            when (b) {
                is MdBlock.Heading -> { if (isNotEmpty()) append('\n'); append(b.spans.joinToString("") { it.text }.uppercase()) }
                is MdBlock.Paragraph -> append(b.spans.joinToString("") { it.text })
                is MdBlock.Bullet -> append("  ".repeat(b.depth)).append("\u2022 ").append(b.spans.joinToString("") { it.text })
                is MdBlock.Numbered -> append("  ".repeat(b.depth)).append(b.number).append(". ").append(b.spans.joinToString("") { it.text })
                is MdBlock.Quote -> append("\u201C").append(b.spans.joinToString("") { it.text }).append("\u201D")
                is MdBlock.Code -> append(b.text)
                MdBlock.Rule -> append("\u2014")
            }
        }
    }
}
