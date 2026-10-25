package com.logie.wifecraft.ui

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.logie.wifecraft.data.GameFiles
import com.logie.wifecraft.engine.Native

/** A piece of a texture: the image and where in it (UiTextureAtlasMember's left, right, top, bottom). */
data class Piece(val image: ImageBitmap, val offset: IntOffset, val size: IntSize)

/**
 * WoW's own interface, from the game's files once they're downloaded: Legion's talking-head box,
 * the red panel buttons, the metal window frame, the gossip parchment, reputation bars, the
 * achievement toast, the loading bar, Dalaran's loading art, and the game's fonts. Null pieces
 * (before the download) fall back to plain drawing.
 */
class WowArt private constructor(private val files: GameFiles) {
    private val images = mutableMapOf<Int, ImageBitmap?>()

    private fun image(id: Int, maxSize: Int = 4096): ImageBitmap? = images.getOrPut(id) {
        if (!files.has(id)) return@getOrPut null
        val px = Native.decodeBlp(files.file(id).path, maxSize) ?: return@getOrPut null
        val w = px[0]; val h = px[1]
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 2, w, 0, 0, w, h)
        bmp.asImageBitmap()
    }

    private fun piece(id: Int, l: Int, r: Int, t: Int, b: Int): Piece? =
        image(id)?.let { Piece(it, IntOffset(l, t), IntSize(r - l, b - t)) }

    private fun whole(id: Int, maxSize: Int = 4096): Piece? = image(id, maxSize)?.let { Piece(it, IntOffset.Zero, IntSize(it.width, it.height)) }

    // Legion's talking head (interface/questframe/talkingheads.blp).
    val talkDark = piece(1111217, 1, 571, 472, 627)
    val talkParchment = piece(1111217, 1, 571, 315, 470)
    val portraitFrame = piece(1111217, 863, 1006, 1, 144)
    val portraitBg = piece(1111217, 573, 688, 315, 430)

    // The red panel button (interface/buttons/128redbutton.blp), up, disabled and pressed.
    val red = ButtonPieces(piece(1536801, 391, 505, 911, 1039), piece(1536801, 0, 64, 1, 129), piece(1536801, 1, 293, 521, 649))
    val redDisabled = ButtonPieces(piece(1536801, 391, 505, 1041, 1169), piece(1536801, 0, 64, 131, 259), piece(1536801, 1, 293, 651, 779))
    val redPressed = ButtonPieces(piece(1536801, 391, 505, 1171, 1299), piece(1536801, 0, 64, 261, 389), piece(1536801, 1, 293, 781, 909))

    // The diamond metal frame (interface/framegeneral) and its stone ground.
    val cornerTL = piece(3056750, 1, 129, 521, 649)
    val cornerTR = piece(3056750, 1, 129, 651, 779)
    val cornerBL = piece(3056750, 1, 129, 261, 389)
    val cornerBR = piece(3056750, 1, 129, 391, 519)
    val edgeTop = piece(3056750, 0, 128, 131, 259)
    val edgeBottom = piece(3056750, 0, 128, 1, 129)
    val edgeLeft = piece(3056755, 1, 129, 0, 128)
    val edgeRight = piece(3056755, 131, 259, 0, 128)
    val rock = whole(374155, 512)

    // The gossip window's parchment, and its speech-bubble icon.
    val parchment = piece(3813080, 1, 300, 1, 408)
    val gossipIcon = whole(132053)

    // Reputation bars (interface/framegeneral/uiframebars.blp).
    val barBg = piece(2139076, 0, 64, 67, 85)
    val barBorderL = piece(2139076, 1, 36, 182, 213)
    val barBorderC = piece(2139076, 0, 64, 1, 32)
    val barBorderR = piece(2139076, 1, 36, 215, 246)
    val barGreen = piece(2139076, 0, 256, 106, 123)
    val barYellow = piece(2139076, 0, 256, 144, 161)
    val barBlue = piece(2139076, 0, 256, 87, 104)
    val barRed = piece(2139076, 0, 256, 125, 142)

    // The achievement toast.
    val achievement = piece(4035524, 640, 950, 174, 275)
    val achievementIcon = piece(4035524, 110, 184, 518, 592)

    // The loading screen: its bar, and Dalaran's loading art.
    val loadingBorder = whole(131811, 2048)
    val loadingFill = whole(131812, 256)
    val dalaranArt = whole(7963775, 2048)

    val friz: FontFamily? = font(615960)
    val morpheus: FontFamily? = font(615962)

    private fun font(id: Int): FontFamily? = try {
        if (files.has(id)) FontFamily(Typeface.createFromFile(files.file(id))) else null
    } catch (e: Exception) {
        null
    }

    val ready: Boolean get() = talkDark != null && red.left != null && cornerTL != null

    data class ButtonPieces(val left: Piece?, val center: Piece?, val right: Piece?)

    companion object {
        /** Builds the art from whatever is downloaded (off the main thread: it decodes textures). */
        fun load(files: GameFiles): WowArt = WowArt(files)
    }
}
