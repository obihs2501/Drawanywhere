package com.shezik.drawanywhere.stylus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShellDiagnosticsTest {

    @Test
    fun parsesProcBusInputDevices() {
        val text = """
            I: Bus=0005 Vendor=0022 Product=5081 Version=0001
            N: Name="Xiaomi Focus Pen Pro"
            P: Phys=aa:bb
            H: Handlers=event12 kbd
            B: EV=1b

            I: Bus=0018 Vendor=0000 Product=0000 Version=0000
            N: Name="fts_ts"
            H: Handlers=event5
        """.trimIndent()
        val devices = ShellDiagnostics.parseInputDevices(text)
        assertEquals(2, devices.size)
        assertEquals("Xiaomi Focus Pen Pro", devices[0].name)
        assertEquals("0022", devices[0].vendor)
        assertEquals("event12", devices[0].eventNode)
        assertTrue(devices[0].looksLikePen)
        assertEquals("event5", devices[1].eventNode)
        assertTrue(!devices[1].looksLikePen)
    }

    @Test
    fun decodes64BitEvdevRecords() {
        val buffer = ByteBuffer.allocate(24 * 3).order(ByteOrder.LITTLE_ENDIAN)
        // EV_KEY KEY_F19 down, EV_SYN, EV_KEY KEY_F19 up
        buffer.putLong(100).putLong(5000).putShort(1).putShort(189).putInt(1)
        buffer.putLong(100).putLong(5000).putShort(0).putShort(0).putInt(0)
        buffer.putLong(100).putLong(120000).putShort(1).putShort(189).putInt(0)
        val lines = ShellDiagnostics.decodeEvdev(buffer.array())
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("EV_KEY code=189 (KEY_F19) value=1"))
        assertTrue(lines[1].contains("value=0"))
    }
}
