package com.lutcamera.lutcamerapro.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetMathTest {

    @Test
    fun parsesValidIpv4() {
        assertEquals(0xC0A80101.toInt(), SubnetMath.ipv4ToInt("192.168.1.1"))
        assertEquals(0x7F000001, SubnetMath.ipv4ToInt("127.0.0.1"))
        assertEquals(0, SubnetMath.ipv4ToInt("0.0.0.0"))
        assertEquals(-1, SubnetMath.ipv4ToInt("255.255.255.255"))
    }

    @Test
    fun rejectsMalformedIpv4() {
        assertNull(SubnetMath.ipv4ToInt("192.168.1"))
        assertNull(SubnetMath.ipv4ToInt("192.168.1.256"))
        assertNull(SubnetMath.ipv4ToInt("192.168.1.a"))
        assertNull(SubnetMath.ipv4ToInt(""))
        assertNull(SubnetMath.ipv4ToInt("192.168.1.1.1"))
        // 前导零按十进制解析，不做八进制解释
        assertEquals(0xC0A80108.toInt(), SubnetMath.ipv4ToInt("192.168.1.08"))
    }

    @Test
    fun sameSubnetOnTwentyFour() {
        assertTrue(SubnetMath.sameSubnet("192.168.1.7", "192.168.1.1", 24))
        assertFalse(SubnetMath.sameSubnet("172.17.1.2", "192.168.1.1", 24))
    }

    @Test
    fun sameSubnetHonoursPrefix() {
        assertTrue(SubnetMath.sameSubnet("192.168.2.7", "192.168.1.1", 16))
        assertFalse(SubnetMath.sameSubnet("192.168.2.7", "192.168.1.1", 24))
        assertTrue(SubnetMath.sameSubnet("10.1.2.3", "10.9.9.9", 8))
    }

    @Test
    fun sameSubnetHandlesDegeneratePrefixes() {
        // /0 表示全网匹配；/32 只匹配同一地址
        assertTrue(SubnetMath.sameSubnet("1.2.3.4", "192.168.1.1", 0))
        assertTrue(SubnetMath.sameSubnet("192.168.1.1", "192.168.1.1", 32))
        assertFalse(SubnetMath.sameSubnet("192.168.1.2", "192.168.1.1", 32))
    }

    @Test
    fun unparsableSideNeverMatches() {
        assertFalse(SubnetMath.sameSubnet("nope", "192.168.1.1", 24))
        assertFalse(SubnetMath.sameSubnet("192.168.1.1", "nope", 24))
    }

    @Test
    fun picksWirelessAddressInCameraSubnet() {
        val candidates = listOf(
            SubnetMath.LocalAddress("tun0", "172.17.1.2"),
            SubnetMath.LocalAddress("rmnet0", "10.55.1.9"),
            SubnetMath.LocalAddress("wlan0", "192.168.1.7")
        )
        assertEquals("192.168.1.7", SubnetMath.localAddressInSubnet(candidates, "192.168.1.1"))
    }

    @Test
    fun ignoresAddressesOutsideCameraSubnet() {
        val candidates = listOf(
            SubnetMath.LocalAddress("wlan0", "172.17.1.2"),
            SubnetMath.LocalAddress("wlan1", "192.168.3.4")
        )
        assertNull(SubnetMath.localAddressInSubnet(candidates, "192.168.1.1"))
    }

    @Test
    fun returnsNullForInvalidTarget() {
        val candidates = listOf(SubnetMath.LocalAddress("wlan0", "192.168.1.7"))
        assertNull(SubnetMath.localAddressInSubnet(candidates, "not-an-ip"))
    }

    @Test
    fun prefersWirelessInterfaceOverOthers() {
        val candidates = listOf(
            SubnetMath.LocalAddress("rndis0", "192.168.1.9"),
            SubnetMath.LocalAddress("wlan0", "192.168.1.7")
        )
        assertEquals("192.168.1.9", SubnetMath.localAddressInSubnet(candidates, "192.168.1.1"))
    }

    @Test
    fun recognisesWirelessInterfaceNames() {
        assertTrue(SubnetMath.isWireless("wlan0"))
        assertTrue(SubnetMath.isWireless("swlan0"))
        assertTrue(SubnetMath.isWireless("softap0"))
        assertTrue(SubnetMath.isWireless("eth0"))
        assertFalse(SubnetMath.isWireless("rmnet0"))
        assertFalse(SubnetMath.isWireless("tun0"))
        assertFalse(SubnetMath.isWireless("lo"))
    }
}
