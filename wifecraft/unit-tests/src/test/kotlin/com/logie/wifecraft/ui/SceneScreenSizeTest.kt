package com.logie.wifecraft.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A guard against "Method too large" (CI run 258: `SceneSessionScreenKt.LiveSceneScreen` went past the JVM's 64 KB of bytecode
 * for one method, and the release build could not make an APK).
 *
 * The limit is on bytecode, which the JVM tests cannot see (the screens are Android and Compose); source lines are the cheap
 * stand-in: Compose's code for a screen measured 25 to 50 bytes a source line, so a piece of 600 lines stays near 15 to 30 KB,
 * well under the limit. The real numbers (the biggest method of the app after the split: 15.7 KB; before it: 65,228 bytes, 99.5%
 * of the limit) come from compiling the sources and reading the class with `javap -c -p` (README, "Scene screen layout").
 *
 * What it checks, in every `Scene*.kt` of `ui/` (the scene screen and the pieces taken out of it):
 *  - no function is longer than [MAX_FUNCTION_LINES] lines of source;
 *  - `LiveSceneScreen`'s body is cut with `SceneSegment { ... }` and no piece of it is longer than [MAX_PIECE_LINES];
 *  - `LiveSceneScreen` as a whole stays under [MAX_LIVE_SCENE_LINES] (it only goes down: move code out, never in).
 */
class SceneScreenSizeTest {
    private val ui = File(System.getProperty("wifecraft.sources") ?: "../android/app/src/main/java/com/logie/wifecraft/ui")

    /** A function found in a source: its name, its first line (1-based) and its length in lines. */
    private data class Fn(val file: String, val name: String, val start: Int, val lines: Int, val body: List<String>)

    private fun sceneFiles(): List<File> =
        (ui.listFiles { f -> f.name.startsWith("Scene") && f.name.endsWith(".kt") } ?: emptyArray()).sortedBy { it.name }

    /** Code of one line without its comment and the insides of its strings (so that braces in them are not counted). */
    private fun bare(line: String): String {
        val out = StringBuilder()
        var i = 0
        var inString = false
        while (i < line.length) {
            val c = line[i]
            when {
                inString -> {
                    if (c == '\\') i++ else if (c == '"') inString = false
                }
                c == '"' -> inString = true
                c == '\'' && i + 2 < line.length && line[i + 2] == '\'' -> i += 2          // a char literal such as '{'
                c == '/' && i + 1 < line.length && line[i + 1] == '/' -> return out.toString()
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private val funStart = Regex("""^\s*((@\w+(\([^)]*\))?\s+)*((private|internal|public|protected|override|inline|suspend|operator|tailrec|open|abstract)\s+)*)fun\s+(<[^>]*>\s*)?([\w.]+)\s*\(""")

    private fun functionsOf(file: File): List<Fn> {
        val lines = file.readLines()
        val code = lines.map { bare(it) }
        val found = ArrayList<Fn>()
        var i = 0
        while (i < lines.size) {
            val m = funStart.find(code[i])
            if (m == null) { i++; continue }
            // the parameter list: to its closing parenthesis; then the body's brace (or an `=` body: counted to the next declaration)
            var parens = 0
            var seenParen = false
            var line = i
            var col = code[i].indexOf('(', m.range.last - 1).coerceAtLeast(0)
            var bodyLine = -1
            var braces = 0
            var ended = -1
            scan@ while (line < lines.size) {
                val text = code[line]
                while (col < text.length) {
                    val c = text[col]
                    if (bodyLine < 0) {
                        if (c == '(') { parens++; seenParen = true }
                        else if (c == ')') parens--
                        else if (seenParen && parens == 0 && c == '{') { bodyLine = line; braces = 1 }
                        else if (seenParen && parens == 0 && c == '=') { ended = line; break@scan }   // an expression body: not measured
                    } else {
                        if (c == '{') braces++ else if (c == '}') { braces--; if (braces == 0) { ended = line; break@scan } }
                    }
                    col++
                }
                line++
                col = 0
            }
            if (bodyLine >= 0 && ended >= 0) found += Fn(file.name, m.groupValues[7], i + 1, ended - i + 1, lines.subList(i, ended + 1))
            i = if (ended >= 0) i + 1 else i + 1
        }
        return found
    }

    @Test
    fun theSceneScreensFilesAreThere() {
        val names = sceneFiles().map { it.name }
        assertTrue("SceneSessionScreen.kt" in names, "the scene screen's source was not found in ${ui.absolutePath}: $names")
        assertTrue(functionsOf(File(ui, "SceneSessionScreen.kt")).any { it.name == "LiveSceneScreen" }, "LiveSceneScreen was not found")
    }

    @Test
    fun noFunctionOfTheSceneScreenIsLongerThanASixHundredLines() {
        val tooLong = sceneFiles().flatMap { functionsOf(it) }
            .filter { it.name != "LiveSceneScreen" && it.lines > MAX_FUNCTION_LINES }
        assertTrue(tooLong.isEmpty(),
            "A function past $MAX_FUNCTION_LINES lines risks the JVM's 64 KB method limit (README, Scene screen layout): " +
                tooLong.joinToString { "${it.file}:${it.start} ${it.name} (${it.lines})" })
    }

    @Test
    fun liveSceneScreenIsCutIntoPiecesNoneOfWhichIsLongerThanASixHundredLines() {
        val live = functionsOf(File(ui, "SceneSessionScreen.kt")).first { it.name == "LiveSceneScreen" }
        // the pieces: from one `SceneSegment {` to the next (each one's lambda is a method of its own; the last runs to the end)
        val cuts = live.body.indices.filter { live.body[it].trim().startsWith("SceneSegment {") }
        val edges = listOf(0) + cuts + live.body.size
        val pieces = edges.zipWithNext { a, b -> b - a }
        assertTrue(pieces.max() <= MAX_PIECE_LINES,
            "A piece of LiveSceneScreen is ${pieces.max()} lines (at most $MAX_PIECE_LINES): cut it with SceneSegment { ... } or move code " +
                "into a function of its own (README, Scene screen layout). Pieces: $pieces")
    }

    @Test
    fun liveSceneScreenDoesNotGrow() {
        val live = functionsOf(File(ui, "SceneSessionScreen.kt")).first { it.name == "LiveSceneScreen" }
        assertTrue(live.lines <= MAX_LIVE_SCENE_LINES,
            "LiveSceneScreen is ${live.lines} lines (at most $MAX_LIVE_SCENE_LINES): new behaviour goes into a composable or a class of its own, " +
                "not into the screen's body (README, Scene screen layout)")
    }

    private companion object {
        /** A function's length in lines of source: about 25 to 50 bytes of bytecode a line under Compose, so 600 lines stay far from 64 KB. */
        const val MAX_FUNCTION_LINES = 600
        const val MAX_PIECE_LINES = 500
        /** Where LiveSceneScreen stood when this guard was written; lower it when code moves out of it. */
        const val MAX_LIVE_SCENE_LINES = 2000
    }
}
