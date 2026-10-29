package com.s9encryptor.strongtoastextended.app.platform

import java.io.IOException
import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class RootProtocolTest {
    private val digest = "a".repeat(64)
    private val id = "a7d63f01-b14d-49c2-bd02-3ae4bef12b60"

    @Test fun nonceIsStrictAndBounded() {
        assertTrue(RootProtocol.validNonce(digest))
        listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), digest + "\n", "'; echo unsafe").forEach {
            assertFalse(RootProtocol.validNonce(it))
        }
    }

    @Test fun internalPathsRemainSingleQuotedShellLiterals() {
        assertEquals("'a'\\''b'", RootProtocol.shellQuote("a'b"))
        val path = "/data/user/0/example/it's \$(untrusted)/bootstrap.dex"
        val script = RootProtocol.startupScript(path, digest, id)
        assertTrue(script.contains("cp " + RootProtocol.shellQuote(path)))
        assertTrue(script.contains("mkdir -m 700"))
        assertTrue(script.contains("chmod 400"))
        assertTrue(script.contains("/system/bin/toybox sha256sum"))
        assertFalse(script.contains("pm path"))
        assertFalse(script.contains("\nexec ")) // Keep the shell alive for EXIT cleanup.
        assertTrue(script.contains("trap '"))
    }

    @Test fun startupRejectsCorruptDigestAndStagingIdentity() {
        expectIllegal { RootProtocol.startupScript("relative.dex", digest, id) }
        expectIllegal { RootProtocol.startupScript("/private/client.dex", "bad", id) }
        expectIllegal { RootProtocol.startupScript("/private/client.dex", digest, "../existing") }
        expectIllegal { RootProtocol.shellQuote("/private/line\nbreak") }
        expectIllegal { RootProtocol.shellQuote("/private/null\u0000byte") }
    }

    @Test fun boundedReaderHandlesFramingAndEof() {
        val reader = StringReader("first\r\nsecond")
        assertEquals("first", RootProtocol.readBoundedLine(reader))
        assertEquals("second", RootProtocol.readBoundedLine(reader))
        assertNull(RootProtocol.readBoundedLine(reader))
        assertEquals("", RootProtocol.readBoundedLine(StringReader("\n")))
    }

    @Test fun boundedReaderAcceptsLimitAndRejectsOversizedOrCarriageReturnFlood() {
        assertEquals(RootProtocol.MAX_LINE_CHARS,
            RootProtocol.readBoundedLine(StringReader("x".repeat(RootProtocol.MAX_LINE_CHARS) + "\n"))!!.length)
        expectIo { RootProtocol.readBoundedLine(StringReader("x".repeat(RootProtocol.MAX_LINE_CHARS + 1))) }
        expectIo { RootProtocol.readBoundedLine(StringReader("\r".repeat(RootProtocol.MAX_LINE_CHARS + 1))) }
    }

    private fun expectIllegal(block: () -> Unit) {
        try { block(); fail("Expected invalid startup data to be rejected") } catch (_: IllegalArgumentException) { }
    }
    private fun expectIo(block: () -> Unit) {
        try { block(); fail("Expected oversized input to be rejected") } catch (_: IOException) { }
    }
}
