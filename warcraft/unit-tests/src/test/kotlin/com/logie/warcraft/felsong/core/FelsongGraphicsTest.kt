// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.felsong.core


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FelsongGraphicsTest {
    @Test fun direct3D9FirstThenElevenThenOpenGl() {
        assertEquals(FelsongGraphics.D3D9, FelsongGraphics.valid(null))
        assertEquals(FelsongGraphics.D3D9, FelsongGraphics.valid("D3D12"))
        assertEquals(FelsongGraphics.D3D11, FelsongGraphics.next(FelsongGraphics.D3D9))
        assertEquals(FelsongGraphics.OPENGL, FelsongGraphics.next(FelsongGraphics.D3D11))
        assertNull(FelsongGraphics.next(FelsongGraphics.OPENGL))
        assertEquals("Direct3D 11", FelsongGraphics.name(FelsongGraphics.D3D11))
    }

    @Test fun readsAFailedStartFromTheClientsGraphicsLog() {
        val failed = """
            10/24 14:02:11.010  GxApi: D3D11
            10/24 14:02:11.120  CGxDevice::D3d11AdapterInfer(): D3D11 - RET: 0, DID: 0
            10/24 14:02:11.300  CGxDeviceD3d::ICreateD3dDevice(): failed to create device! hr=0x887a0004
        """.trimIndent()
        assertEquals("10/24 14:02:11.300  CGxDeviceD3d::ICreateD3dDevice(): failed to create device! hr=0x887a0004", FelsongGraphics.failure(failed))
        assertEquals("D3D11 Interface not supported on adapter", FelsongGraphics.failure("GxApi: D3D11\r\n  D3D11 Interface not supported on adapter\r\n"))
        assertNull(FelsongGraphics.failure("GxApi: D3D9\nCGxDevice::D3d9AdapterInfer(): D3D9 - RET: 0, DID: 1234\nChoosing adapter 0\n"))
        assertNull(FelsongGraphics.failure(""))
    }
}
