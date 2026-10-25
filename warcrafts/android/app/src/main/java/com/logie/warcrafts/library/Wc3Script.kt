// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What Blizzard's classic Warcraft III installer does on Windows, read from its install script
 * (`InstallCD\InstallerFileList\InstallerFileList.xml` in the package's `Installer Tome.mpq`), for the
 * app to do itself, silently (the native `wc3unpack` runs the [plan]).
 *
 * The script: `<if true_condition="…">` blocks (Win32 is true here, the Mac and the other languages
 * false; `<then>`/`<else>` where it has both), and in its `<archive>`, `<target location="user">`
 * blocks of `<repack_into>`: `type="file"` copies the package's files into the game's folder,
 * `type="mpq" container="…"` puts them into one of the game's archives (`war3.mpq`, `War3x.mpq`).
 * What the app leaves out: the uninstaller, DirectX's setup (Wine has its own), shortcuts, and the
 * CD key the installer would ask for and write into the archives (`<encrypt>` and the `type="…"`
 * repacks beside it): the game plays offline without them.
 *
 * Paths in the game's folder keep the first spelling met for each folder and file, whatever case
 * the script uses later (`War3.mpq` and `war3.mpq` are one file on Windows, two on the phone).
 */
object Wc3Script {
    /** One file: copied to [to] in the game's folder ([archive] null), or put into [archive] as [to]. */
    data class Step(val from: String, val to: String, val archive: String? = null)

    fun plan(xml: String, alreadyThere: Collection<String> = emptyList()): List<Step> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val root = factory.newDocumentBuilder().parse(xml.trimStart('﻿').byteInputStream()).documentElement
        val raw = mutableListOf<Step>()
        walk(root, inArchive = false, inUserTarget = false, raw)
        val names = Spellings(alreadyThere)
        return raw.map { step ->
            if (step.archive == null) step.copy(to = names.spell(step.to))
            else step.copy(archive = names.spell(step.archive))
        }
    }

    /** The plan as `wc3unpack run` reads it: one tab-separated line per file. */
    fun planText(steps: List<Step>): String = steps.joinToString("") { step ->
        if (step.archive == null) "F\t${step.from}\t${step.to}\n" else "M\t${step.archive}\t${step.from}\t${step.to}\n"
    }

    private fun condition(name: String): Boolean = name.equals("Win32", ignoreCase = true)

    private fun walk(element: Element, inArchive: Boolean, inUserTarget: Boolean, out: MutableList<Step>) {
        when (element.tagName) {
            "if" -> {
                val holds = condition(element.getAttribute("true_condition"))
                val then = child(element, "then")
                val otherwise = child(element, "else")
                when {
                    then != null || otherwise != null -> (if (holds) then else otherwise)?.let { walkChildren(it, inArchive, inUserTarget, out) }
                    holds -> walkChildren(element, inArchive, inUserTarget, out)
                }
            }
            "archive" -> walkChildren(element, true, inUserTarget, out)
            "target" -> if (inArchive && element.getAttribute("location") == "user") walkChildren(element, inArchive, true, out)
            "repack_into" -> if (inArchive && inUserTarget) {
                val type = element.getAttribute("type")
                val container = element.getAttribute("container").takeIf { type == "mpq" && it.isNotEmpty() }
                if (type == "file" || container != null) {
                    for (repack in children(element, "repack")) {
                        val from = repack.getAttribute("from")
                        val to = repack.getAttribute("to")
                        // A repack with its own type ("cdkey1encode") is the CD key's; files have none.
                        if (from.isEmpty() || to.isEmpty() || repack.hasAttribute("type")) continue
                        out += Step(from, to, container)
                    }
                }
            }
            else -> walkChildren(element, inArchive, inUserTarget, out)
        }
    }

    private fun walkChildren(element: Element, inArchive: Boolean, inUserTarget: Boolean, out: MutableList<Step>) {
        for (child in elements(element)) walk(child, inArchive, inUserTarget, out)
    }

    private fun elements(element: Element): List<Element> {
        val nodes = element.childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }
    }

    private fun child(element: Element, tag: String): Element? = elements(element).firstOrNull { it.tagName == tag }

    private fun children(element: Element, tag: String): List<Element> = elements(element).filter { it.tagName == tag }

    /** Windows' paths, case-insensitive: each folder and file keeps the first spelling seen. */
    private class Spellings(existing: Collection<String>) {
        private val seen = HashMap<String, String>()

        init {
            existing.forEach { spell(it) }
        }

        fun spell(path: String): String {
            val parts = path.replace('/', '\\').split('\\').filter { it.isNotEmpty() }
            val out = mutableListOf<String>()
            for (part in parts) {
                val key = (out + part).joinToString("\\").lowercase()
                out += seen.getOrPut(key) { part }.substringAfterLast('\\')
            }
            return out.joinToString("\\")
        }
    }
}
