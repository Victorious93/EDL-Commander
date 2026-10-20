package ai.edlcommander.companion.help

/** A block of the bundled manual, rendered by [HelpScreen]. Pure Kotlin so it is JVM-testable. */
sealed interface ManualBlock {
    data class Heading(val level: Int, val text: String) : ManualBlock
    data class Paragraph(val text: String) : ManualBlock
    data class Bullet(val text: String, val indent: Int) : ManualBlock
    data class Code(val text: String) : ManualBlock
}

/**
 * Minimal Markdown reader for docs/MANUAL.md: headings, paragraphs, bullet/numbered lists, fenced
 * code and tables (shown as code). HTML lines (the header image) are dropped. Inline markup such as
 * `**bold**`, backticks and links is reduced to plain text.
 */
object ManualParser {
    fun parse(markdown: String): List<ManualBlock> {
        val out = mutableListOf<ManualBlock>()
        val para = StringBuilder()
        val table = StringBuilder()
        var code: StringBuilder? = null

        fun flushPara() {
            if (para.isNotBlank()) out += ManualBlock.Paragraph(inline(para.toString().trim()))
            para.clear()
        }
        fun flushTable() {
            if (table.isNotEmpty()) out += ManualBlock.Code(table.toString().trimEnd())
            table.clear()
        }

        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            if (code != null) {
                if (line.trimStart().startsWith("```")) {
                    out += ManualBlock.Code(code.toString().trimEnd())
                    code = null
                } else {
                    code.append(line).append('\n')
                }
                continue
            }
            val t = line.trim()
            when {
                t.startsWith("```") -> { flushPara(); flushTable(); code = StringBuilder() }
                t.isEmpty() -> { flushPara(); flushTable() }
                t.startsWith("<") -> { flushPara(); flushTable() }
                t.startsWith("|") -> {
                    flushPara()
                    if (!SEPARATOR.matches(t)) {
                        table.append(t.trim('|').split('|').joinToString("  |  ") { inline(it.trim()) }).append('\n')
                    }
                }
                t.startsWith("#") -> {
                    flushPara(); flushTable()
                    val level = t.takeWhile { it == '#' }.length
                    out += ManualBlock.Heading(level, inline(t.drop(level).trim()))
                }
                BULLET.matches(line) -> {
                    flushPara(); flushTable()
                    val m = BULLET.find(line)!!
                    out += ManualBlock.Bullet(inline(m.groupValues[3]), m.groupValues[1].length / 2)
                }
                t.startsWith(">") -> { flushTable(); para.append(t.removePrefix(">").trim()).append(' ') }
                else -> {
                    flushTable()
                    val last = out.lastOrNull()
                    if (para.isEmpty() && last is ManualBlock.Bullet && line.startsWith("  ")) {
                        out[out.size - 1] = last.copy(text = last.text + " " + inline(t))
                    } else {
                        para.append(t).append(' ')
                    }
                }
            }
        }
        code?.let { out += ManualBlock.Code(it.toString().trimEnd()) }
        flushPara()
        flushTable()
        return out
    }

    fun inline(s: String): String = s
        .replace(LINK, "$1")
        .replace("**", "")
        .replace("`", "")

    private val BULLET = Regex("^( *)([-*]|\\d+\\.) (.*)$")
    private val SEPARATOR = Regex("^\\|[\\s|:-]+\\|$")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
}
