// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp

import com.logie.warcraft.spp.core.SppGraphics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SppGraphicsTest {
    @Test fun direct3D9FirstThenElevenThenOpenGl() {
        assertEquals(SppGraphics.D3D9, SppGraphics.valid(null))
        assertEquals(SppGraphics.D3D9, SppGraphics.valid("D3D12"))
        assertEquals(SppGraphics.D3D11, SppGraphics.next(SppGraphics.D3D9))
        assertEquals(SppGraphics.OPENGL, SppGraphics.next(SppGraphics.D3D11))
        assertNull(SppGraphics.next(SppGraphics.OPENGL))
        assertEquals("Direct3D 11", SppGraphics.name(SppGraphics.D3D11))
    }

    @Test fun theApiIsGivenOnTheCommandLineToo() {
        assertEquals("-d3d", SppGraphics.argument(SppGraphics.D3D9))
        assertEquals("-d3d11", SppGraphics.argument(SppGraphics.D3D11))
        assertEquals("-opengl", SppGraphics.argument(SppGraphics.OPENGL))
        assertEquals("-d3d", SppGraphics.argument("auto"))
    }

    @Test fun readsAFailedStartFromTheClientsGraphicsLog() {
        // (the lines the decrypted client writes when its device doesn't start)
        val failed = """
            2/7 15:55:15.288  LogOpen
            2/7 15:55:15.300  Detected NvAPI and checking if it's valid...
            2/7 15:55:15.410  ReportDeviceCreationFailure(): no output device available!
        """.trimIndent()
        assertEquals("2/7 15:55:15.410  ReportDeviceCreationFailure(): no output device available!", SppGraphics.failure(failed))
        assertEquals("CGxDeviceD3d::ICreateD3dDevice(): failed to create device! hr=0x8876086c",
            SppGraphics.failure("GxApi: D3D9\r\nCGxDeviceD3d::ICreateD3dDevice(): failed to create device! hr=0x8876086c\r\n"))
        assertEquals("SetupPixelFormatWGL(): pixelFormat does not match requested", SppGraphics.failure("SetupPixelFormatWGL(): pixelFormat does not match requested"))
        // (a start that went well: the publisher's own gx.log's kind of lines, and lines on paths that carry on)
        val started = """
            2/7 15:55:15.288  LogOpen
            2/7 15:55:15.290  CGxDevice::D3d9AdapterID(): RET: 1, VID: 8086, DID: 3e92, DVER: 1a.14
            2/7 15:55:15.300  Found Adapter 0: Vender Id 0x8086
            2/7 15:55:15.310  Choosing adapter 0
            2/7 15:55:15.320  ValidateFormatMonitor(): invalid refresh rate 0 Hz
            2/7 15:55:15.330  Failed to get DXGI Adapter Description
            2/7 15:55:15.340    D3D11 Interface not supported on adapter
            2/7 15:58:01.000  D3D11 Device Destroyed
        """.trimIndent()
        assertNull(SppGraphics.failure(started))
        assertNull(SppGraphics.failure(""))
    }
}
