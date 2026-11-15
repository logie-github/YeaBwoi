package com.logie.wifecraft.ui

import com.logie.wifecraft.editor.Compile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app's own assets/places.json: every place a Show beat can stand someone on, and the studio. */
class PlacesTest {
    private val places = PlaceInfo.parseAll(File(System.getProperty("wifecraft.assets"), "places.json").readText())

    @Test fun everyPlaceHasTheThreeSpotsAndAView() {
        for (p in places) {
            for (spot in Compile.spots) assertTrue(spot in p.marks, "${p.id} has no mark \"$spot\"")
            assertTrue(p.views.isNotEmpty(), "${p.id} has no camera")
            // the preview stands people where the player does: on the marks
            for (spot in Compile.spots) assertEquals(p.marks.getValue(spot), Staging.standing(p, spot).pos)
        }
    }

    @Test fun theStudioIsFirstAndVoidAndTheCityIsNot() {
        assertEquals("studio", places.first().id)
        assertTrue(places.first().void)
        assertEquals(1, places.count { it.id == "studio" })
        assertTrue(places.drop(1).isNotEmpty() && places.drop(1).none { it.void })
    }

    @Test fun leftIsLeftAsTheFirstViewSeesIt() {
        for (p in places) {
            val v = p.views.values.first()
            val fx = v.at.x - v.eye.x; val fy = v.at.y - v.eye.y
            fun side(spot: String) = p.marks.getValue(spot).let { m -> (m.x - v.eye.x) * fy - (m.y - v.eye.y) * fx }   // > 0: right
            assertTrue(side("left") < side("center") && side("center") < side("right"), p.id)
        }
    }
}
