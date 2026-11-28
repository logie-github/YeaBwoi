package com.logie.wifecraft.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import com.logie.wifecraft.simulation.SecretGestureDetector
import com.logie.wifecraft.simulation.SecretOutcome

/**
 * Watches every finger on the whole screen for the hidden mode's key (simulation/SecretGesture.kt) and calls [onOpen] when it is done. It looks
 * on the Initial pass and consumes nothing, so the buttons and the scrolling under it work as ever and a swipe may begin on a button; the one
 * exception is the key's own last stage: once the eight swipes are done, the three taps are kept from a button underneath (a tap on Options would
 * otherwise open it). It shows nothing and has no sound. Returns the modifier with it.
 */
@Composable
internal fun Modifier.secretGesture(onOpen: () -> Unit): Modifier {
    val density = LocalDensity.current.density
    val detector = remember(density) { SecretGestureDetector(density) }
    val open by rememberUpdatedState(onOpen)
    return pointerInput(detector) {
        awaitPointerEventScope {
            val keep = HashSet<Long>()
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    for (c in event.changes) {
                        val id = c.id.value
                        when {
                            c.changedToDown() -> if (detector.down(id, c.position.x, c.position.y, c.uptimeMillis)) { keep.add(id); c.consume() }
                            c.changedToUp() -> {
                                val own = keep.remove(id)
                                val out = detector.up(id, c.position.x, c.position.y, c.uptimeMillis)
                                if (own) c.consume()
                                if (out == SecretOutcome.OPEN) open()
                            }
                            c.pressed && c.positionChanged() -> { detector.move(id, c.position.x, c.position.y); if (id in keep) c.consume() }
                        }
                    }
                }
            } finally {
                detector.cancel()
            }
        }
    }
}
