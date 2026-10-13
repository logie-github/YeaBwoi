// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.modules

import com.logie.warcraft.addons.Warperia

/**
 * A module's README (Markdown) as the add-on pages' blocks: headings, paragraphs, list items,
 * code and rules. Pictures, badges and HTML are left out; links keep their text.
 */
object ModuleReadme {
    fun blocks(markdown: String): List<Warperia.Block> {
        val out = mutableListOf<Warperia.Block>()
        val paragraph = StringBuilder()
        fun flush() {
            val text = inline(paragraph.toString())
            if (text.isNotBlank()) out += Warperia.Block.Paragraph(text)
            paragraph.clear()
        }
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        var inComment = false
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()
            i++
            // HTML comments, which may span lines.
            if (inComment) {
                if ("-->" in line) inComment = false
                continue
            }
            if (line.startsWith("<!--")) {
                if ("-->" !in line) inComment = true
                continue
            }
            when {
                line.startsWith("```") || line.startsWith("~~~") -> {
                    flush()
                    val fence = line.take(3)
                    val code = mutableListOf<String>()
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) code += lines[i++]
                    i++
                    if (code.isNotEmpty()) out += Warperia.Block.Code(code.joinToString("\n").trimEnd())
                }
                line.isEmpty() -> flush()
                Regex("^#{1,6}\\s+").containsMatchIn(line) -> {
                    flush()
                    val text = inline(line.replace(Regex("^#{1,6}\\s+"), "").trimEnd('#', ' '))
                    if (text.isNotBlank()) out += Warperia.Block.Heading(text)
                }
                Regex("^([-*_])(\\s*\\1){2,}$").matches(line) -> {
                    flush()
                    out += Warperia.Block.Rule
                }
                Regex("^[-*+]\\s+").containsMatchIn(line) -> {
                    flush()
                    val text = inline(line.replace(Regex("^[-*+]\\s+(\\[[ xX]]\\s+)?"), ""))
                    if (text.isNotBlank()) out += Warperia.Block.Item(text, null)
                }
                Regex("^\\d+[.)]\\s+").containsMatchIn(line) -> {
                    flush()
                    val number = line.takeWhile { it.isDigit() }.toIntOrNull()
                    val text = inline(line.replace(Regex("^\\d+[.)]\\s+"), ""))
                    if (text.isNotBlank()) out += Warperia.Block.Item(text, number)
                }
                // Table rows read as lines of text; their separators go.
                line.startsWith("|") -> {
                    flush()
                    if (!Regex("^\\|?[\\s:|-]+\\|?$").matches(line)) {
                        val text = inline(line.trim('|').split('|').joinToString(" · ") { it.trim() })
                        if (text.isNotBlank()) out += Warperia.Block.Paragraph(text)
                    }
                }
                line.startsWith(">") -> {
                    flush()
                    val text = inline(line.trimStart('>', ' '))
                    if (text.isNotBlank()) out += Warperia.Block.Paragraph(text)
                }
                else -> paragraph.append(if (paragraph.isEmpty()) "" else " ").append(line)
            }
        }
        flush()
        return out
    }

    /** A line's inline Markdown and HTML as plain text. */
    fun inline(text: String): String = text
        .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "") // pictures (and badges)
        .replace(Regex("\\[!\\[[^\\]]*]\\([^)]*\\)]\\([^)]*\\)"), "") // linked badges
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1") // links: their text
        .replace(Regex("\\[([^\\]]*)]\\[[^\\]]*]"), "$1")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), "") // other HTML
        .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
        .replace(Regex("(?<![\\w*])([*_])(?!\\s)(.+?)(?<!\\s)\\1(?![\\w*])"), "$2")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()
}
