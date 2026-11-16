package com.logie.wifecraft.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

// The glyphs drawn in a button whose picture is not here (WarGlyphs says which): plain shapes in the kit's gold, sized to
// the button, so that a button always says what it does. Drawn in a unit square of [d] px centred on cx, cy.

/** [g] in [color], [d] px across, centred at [cx], [cy]. */
fun DrawScope.drawGlyph(g: WarGlyph, cx: Float, cy: Float, d: Float, color: Color = War.gold, alpha: Float = 1f) {
    val u = d                                     // the unit: coordinates below are fractions of d, from the centre
    fun p(x: Float, y: Float) = Offset(cx + x * u, cy + y * u)
    val sw = u * 0.1f
    val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, w: Float = sw) = drawLine(color, p(x0, y0), p(x1, y1), w, StrokeCap.Round, alpha = alpha)
    fun path(fill: Boolean = false, block: Path.() -> Unit) = drawPath(Path().apply(block), color, alpha, if (fill) Fill else stroke)
    fun Path.m(x: Float, y: Float) = moveTo(cx + x * u, cy + y * u)
    fun Path.l(x: Float, y: Float) = lineTo(cx + x * u, cy + y * u)
    fun arc(r: Float, start: Float, sweep: Float, ox: Float = 0f, oy: Float = 0f) =
        drawArc(color, start, sweep, false, p(ox - r, oy - r), Size(2 * r * u, 2 * r * u), alpha, stroke)
    fun arrowHead(x: Float, y: Float, angleDeg: Float, len: Float = 0.14f) {
        val a = Math.toRadians(angleDeg.toDouble())
        for (s in listOf(-1, 1)) {
            val b = a + Math.PI + s * 0.6
            line(x, y, x + (cos(b) * len).toFloat(), y + (sin(b) * len).toFloat())
        }
    }
    when (g) {
        WarGlyph.BACK -> path(true) { m(0.18f, -0.32f); l(-0.24f, 0f); l(0.18f, 0.32f); close() }
        WarGlyph.PLAY -> path(true) { m(-0.16f, -0.32f); l(0.28f, 0f); l(-0.16f, 0.32f); close() }
        WarGlyph.PLUS -> { line(-0.3f, 0f, 0.3f, 0f, sw * 1.4f); line(0f, -0.3f, 0f, 0.3f, sw * 1.4f) }
        WarGlyph.MINUS -> line(-0.3f, 0f, 0.3f, 0f, sw * 1.4f)
        WarGlyph.CHECK -> path { m(-0.3f, 0.02f); l(-0.08f, 0.24f); l(0.32f, -0.22f) }
        WarGlyph.CLOSE -> { line(-0.24f, -0.24f, 0.24f, 0.24f, sw * 1.3f); line(0.24f, -0.24f, -0.24f, 0.24f, sw * 1.3f) }
        WarGlyph.UNDO -> { arc(0.26f, 200f, 250f, 0.02f, 0.04f); arrowHead(-0.22f, -0.07f, 115f) }
        WarGlyph.REDO -> { arc(0.26f, -20f, -250f, -0.02f, 0.04f); arrowHead(0.22f, -0.07f, 65f) }
        WarGlyph.ROTATE -> { arc(0.28f, 200f, 140f); arc(0.28f, 20f, 140f); arrowHead(0.26f, -0.1f, 80f); arrowHead(-0.26f, 0.1f, 260f) }
        WarGlyph.SCALE -> {
            drawRect(color, p(-0.3f, -0.05f), Size(0.35f * u, 0.35f * u), alpha, stroke)
            line(-0.02f, 0.02f, 0.3f, -0.3f); line(0.3f, -0.3f, 0.12f, -0.3f); line(0.3f, -0.3f, 0.3f, -0.12f)
        }
        WarGlyph.HAND -> {
            for ((i, x) in listOf(-0.18f, -0.06f, 0.06f, 0.18f).withIndex()) line(x, -0.05f, x, if (i == 1 || i == 2) -0.34f else -0.27f, sw * 0.9f)
            drawRoundRect(color, p(-0.24f, -0.08f), Size(0.48f * u, 0.38f * u), CornerRadius(0.14f * u), alpha = alpha)
            line(-0.24f, 0.02f, -0.36f, -0.1f, sw * 0.9f)
        }
        WarGlyph.TRASH -> {
            line(-0.3f, -0.22f, 0.3f, -0.22f); line(-0.08f, -0.3f, 0.08f, -0.3f)
            path { m(-0.22f, -0.16f); l(-0.17f, 0.32f); l(0.17f, 0.32f); l(0.22f, -0.16f) }
            line(0f, -0.08f, 0f, 0.22f, sw * 0.7f)
        }
        WarGlyph.EYEDROPPER -> {
            line(-0.28f, 0.28f, 0.1f, -0.1f, sw * 1.2f)
            drawCircle(color, 0.12f * u, p(0.18f, -0.18f), alpha)
            line(0.02f, -0.22f, 0.22f, -0.02f, sw * 1.1f)
        }
        WarGlyph.COPY -> {
            drawRoundRect(color, p(-0.3f, -0.3f), Size(0.4f * u, 0.4f * u), CornerRadius(0.06f * u), alpha = alpha, style = stroke)
            drawRoundRect(color, p(-0.1f, -0.1f), Size(0.4f * u, 0.4f * u), CornerRadius(0.06f * u), alpha = alpha, style = stroke)
        }
        WarGlyph.LOCK -> {
            arc(0.15f, 180f, 180f, 0f, -0.08f); line(-0.15f, -0.08f, -0.15f, 0.02f); line(0.15f, -0.08f, 0.15f, 0.02f)
            drawRoundRect(color, p(-0.24f, 0f), Size(0.48f * u, 0.32f * u), CornerRadius(0.06f * u), alpha = alpha)
        }
        WarGlyph.EYE -> {
            path { m(-0.36f, 0f); quadraticTo(cx, cy - 0.34f * u, cx + 0.36f * u, cy); quadraticTo(cx, cy + 0.34f * u, cx - 0.36f * u, cy); close() }
            drawCircle(color, 0.1f * u, p(0f, 0f), alpha)
        }
        WarGlyph.SPEECH -> {
            drawRoundRect(color, p(-0.32f, -0.28f), Size(0.64f * u, 0.42f * u), CornerRadius(0.14f * u), alpha = alpha)
            path(true) { m(-0.14f, 0.12f); l(-0.2f, 0.32f); l(0.04f, 0.12f); close() }
        }
        WarGlyph.CROSSHAIR -> {
            drawCircle(color, 0.24f * u, p(0f, 0f), alpha, stroke)
            line(0f, -0.38f, 0f, -0.12f); line(0f, 0.12f, 0f, 0.38f); line(-0.38f, 0f, -0.12f, 0f); line(0.12f, 0f, 0.38f, 0f)
        }
        WarGlyph.STAR -> path(true) {
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 0.36f else 0.15f
                val a = Math.toRadians(-90.0 + i * 36.0)
                val x = (cos(a) * r).toFloat(); val y = (sin(a) * r).toFloat() + 0.03f
                if (i == 0) m(x, y) else l(x, y)
            }
            close()
        }
        WarGlyph.SEARCH -> { drawCircle(color, 0.18f * u, p(-0.07f, -0.07f), alpha, stroke); line(0.07f, 0.07f, 0.3f, 0.3f, sw * 1.3f) }
        WarGlyph.CAMERA -> {
            drawRoundRect(color, p(-0.34f, -0.18f), Size(0.68f * u, 0.46f * u), CornerRadius(0.08f * u), alpha = alpha, style = stroke)
            drawRect(color, p(-0.12f, -0.28f), Size(0.24f * u, 0.1f * u), alpha)
            drawCircle(color, 0.12f * u, p(0f, 0.05f), alpha, stroke)
        }
        WarGlyph.CUBE -> {
            path { m(0f, -0.34f); l(0.3f, -0.17f); l(0.3f, 0.17f); l(0f, 0.34f); l(-0.3f, 0.17f); l(-0.3f, -0.17f); close() }
            path { m(-0.3f, -0.17f); l(0f, 0f); l(0.3f, -0.17f); m(0f, 0f); l(0f, 0.34f) }
        }
        WarGlyph.HOUSE -> {
            path { m(-0.34f, -0.02f); l(0f, -0.32f); l(0.34f, -0.02f) }
            path { m(-0.24f, -0.1f); l(-0.24f, 0.3f); l(0.24f, 0.3f); l(0.24f, -0.1f) }
            drawRect(color, p(-0.06f, 0.1f), Size(0.12f * u, 0.2f * u), alpha)
        }
        WarGlyph.PAW -> {
            drawOval(color, p(-0.17f, 0.0f), Size(0.34f * u, 0.28f * u), alpha)
            for ((x, y) in listOf(-0.26f to -0.06f, -0.1f to -0.22f, 0.1f to -0.22f, 0.26f to -0.06f)) drawCircle(color, 0.075f * u, p(x, y), alpha)
        }
        WarGlyph.PERSON -> {
            drawCircle(color, 0.12f * u, p(0f, -0.18f), alpha)
            path(true) { m(-0.26f, 0.32f); quadraticTo(cx - 0.26f * u, cy - 0.04f * u, cx, cy - 0.04f * u); quadraticTo(cx + 0.26f * u, cy - 0.04f * u, cx + 0.26f * u, cy + 0.32f * u); close() }
        }
        WarGlyph.HEART -> path(true) {
            m(0f, 0.3f)
            cubicTo(cx - 0.42f * u, cy + 0.02f * u, cx - 0.26f * u, cy - 0.36f * u, cx, cy - 0.14f * u)
            cubicTo(cx + 0.26f * u, cy - 0.36f * u, cx + 0.42f * u, cy + 0.02f * u, cx, cy + 0.3f * u)
            close()
        }
        WarGlyph.CLOCK -> { drawCircle(color, 0.3f * u, p(0f, 0f), alpha, stroke); line(0f, 0f, 0f, -0.18f); line(0f, 0f, 0.13f, 0.08f) }
        WarGlyph.COG -> {
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                line((cos(a) * 0.2).toFloat(), (sin(a) * 0.2).toFloat(), (cos(a) * 0.34).toFloat(), (sin(a) * 0.34).toFloat(), sw * 1.3f)
            }
            drawCircle(color, 0.22f * u, p(0f, 0f), alpha, stroke)
        }
        WarGlyph.PAGE -> {
            path { m(-0.22f, -0.32f); l(0.1f, -0.32f); l(0.24f, -0.18f); l(0.24f, 0.32f); l(-0.22f, 0.32f); close() }
            line(-0.1f, -0.08f, 0.12f, -0.08f, sw * 0.7f); line(-0.1f, 0.06f, 0.12f, 0.06f, sw * 0.7f); line(-0.1f, 0.2f, 0.12f, 0.2f, sw * 0.7f)
        }
    }
}

/** [g] for the picture [key], in a circle's middle (a round button's fallback). */
fun DrawScope.drawGlyphFor(key: String?, cx: Float, cy: Float, d: Float, color: Color = War.gold, alpha: Float = 1f) =
    drawGlyph(WarGlyphs.of(key), cx, cy, d, color, alpha)

