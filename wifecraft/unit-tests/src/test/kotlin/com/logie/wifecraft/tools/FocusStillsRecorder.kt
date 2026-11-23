package com.logie.wifecraft.tools

import com.logie.wifecraft.simulation.FocusKind
import com.logie.wifecraft.simulation.FocusTarget
import java.io.File
import kotlin.test.Test

/**
 * The focus stills' numbers (engine-test's `focus` command): for each camera of an input file, the sharp band FocusTarget gives
 * once it has settled on the subject. Not a test: does nothing unless FOCUS_STILLS_IN names the file (lines: `subject HX HY HZ CX CY CZ
 * FX FY FZ` (head, chest, feet), then `NAME EX EY EZ AX AY AZ FOV`, # comments) and FOCUS_STILLS_OUT the file to write (`NAME NEAR FAR
 * STRENGTH SCREENX SCREENY`). Desktop only.
 */
class FocusStillsRecorder {
    @Test fun record() {
        val inFile = System.getenv("FOCUS_STILLS_IN") ?: return
        val out = System.getenv("FOCUS_STILLS_OUT") ?: return
        val lines = File(inFile).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val subject = lines.first { it.startsWith("subject") }.split(" ").drop(1).map { it.toFloat() }
        val text = StringBuilder()
        for (l in lines.filter { !it.startsWith("subject") }) {
            val p = l.split(" ")
            val v = p.drop(1).map { it.toFloat() }
            val i = FocusTarget.Input()
            i.eyeX = v[0]; i.eyeY = v[1]; i.eyeZ = v[2]; i.atX = v[3]; i.atY = v[4]; i.atZ = v[5]; i.fov = v[6]
            i.aspect = 16f / 9f
            i.cx = subject[3]; i.cy = subject[4]; i.cz = subject[5]
            i.addPoint(subject[0], subject[1], subject[2]); i.addPoint(subject[3], subject[4], subject[5]); i.addPoint(subject[6], subject[7], subject[8])
            val t = FocusTarget()
            repeat(60) { t.update(1f / 60f, FocusKind.NPC, i) }
            text.append("${p[0]} ${t.near} ${t.far} ${t.strength} ${t.screenX} ${t.screenY}\n")
        }
        File(out).writeText(text.toString())
    }
}
