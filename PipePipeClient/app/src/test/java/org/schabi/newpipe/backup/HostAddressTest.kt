package org.schabi.newpipe.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class HostAddressTest {
    @Test
    fun parsesAddresses() {
        assertEquals(HostAddress("192.168.1.5", 0, ""), HostAddress.parse(" 192.168.1.5 "))
        assertEquals(HostAddress("example.com", 2222, ""), HostAddress.parse("example.com:2222"))
        assertEquals(
            HostAddress("example.com", 2222, "backups/phone"),
            HostAddress.parse("sftp://me@example.com:2222/backups/phone/")
        )
        assertEquals(
            HostAddress("cloud.example.com", 0, "remote.php/dav/files/me"),
            HostAddress.parse("https://cloud.example.com/remote.php/dav/files/me")
        )
        assertEquals(HostAddress("2001:db8::1", 22, ""), HostAddress.parse("[2001:db8::1]:22"))
        assertEquals(HostAddress("2001:db8::1", 0, ""), HostAddress.parse("2001:db8::1"))
    }

    @Test
    fun combinesThePathWithTheFolder() {
        assertEquals("PipePipe", HostAddress.parse("example.com").combineWithFolder("PipePipe"))
        assertEquals(
            "/remote.php/dav/files/me/PipePipe",
            HostAddress.parse("cloud.example.com/remote.php/dav/files/me/")
                .combineWithFolder("/PipePipe/")
        )
        assertEquals("/backups", HostAddress.parse("example.com/backups").combineWithFolder(""))
    }
}
