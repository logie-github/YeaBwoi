package com.logie.warcraft.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZonesPackageFormatTest {
    @Test
    fun entriesGoUnderZones() {
        assertEquals("", ZonesPackageFormat.relative("cata-zones/"))
        assertEquals("", ZonesPackageFormat.relative("./cata-zones"))
        assertEquals("TAG", ZonesPackageFormat.relative("cata-zones/TAG"))
        assertEquals("server/maps/6480000.map", ZonesPackageFormat.relative("cata-zones/server/maps/6480000.map"))
        assertEquals("client/files", ZonesPackageFormat.relative("cata-zones/client/files/"))
    }

    @Test
    fun entriesOutsideOrEscapingAreRefused() {
        assertNull(ZonesPackageFormat.relative("server/ctl.sh"))
        assertNull(ZonesPackageFormat.relative("cata-zonesX/TAG"))
        assertNull(ZonesPackageFormat.relative("cata-zones/../etc/passwd"))
        assertNull(ZonesPackageFormat.relative("cata-zones/client/./files"))
        assertNull(ZonesPackageFormat.relative("cata-zones/client//files"))
        assertNull(ZonesPackageFormat.relative("cata-zones/a\\b"))
    }
}
