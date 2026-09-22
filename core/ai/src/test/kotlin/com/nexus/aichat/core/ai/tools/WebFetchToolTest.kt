package com.nexus.aichat.core.ai.tools

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SSRF guard for `web_fetch`.
 *
 * This tool runs with the *model* choosing the URL, so an injected page can steer it. The guard is
 * therefore load-bearing, and the two ways it used to be wrong are worth pinning:
 *  - a private IPv4 host was only caught when it was four valid octets *and* the first octet matched,
 *    which missed `127.1`, `2130706433` and friends;
 *  - the IPv6 prefix check was a bare `startsWith("fc")`, which flagged harmless hostnames such as
 *    `fcorp.example.com` while still missing `[fc00::1]` in brackets.
 */
class WebFetchToolTest {

    private val tool = WebFetchTool()

    @Test
    fun `localhost and loopback are refused`() {
        assertTrue(tool.isPrivateHost("localhost"))
        assertTrue(tool.isPrivateHost("api.localhost"))
        assertTrue(tool.isPrivateHost("127.0.0.1"))
        assertTrue(tool.isPrivateHost("127.1.2.3"))
    }

    @Test
    fun `private and link-local ranges are refused`() {
        assertTrue(tool.isPrivateHost("10.0.0.5"))
        assertTrue(tool.isPrivateHost("192.168.1.42"))
        assertTrue(tool.isPrivateHost("172.16.0.1"))
        assertTrue(tool.isPrivateHost("172.31.255.254"))
        assertTrue(tool.isPrivateHost("169.254.169.254"))   // cloud metadata
        assertTrue(tool.isPrivateHost("100.64.0.1"))        // CGNAT
        assertTrue(tool.isPrivateHost("nas.local"))
        assertTrue(tool.isPrivateHost("ollama.internal"))
    }

    @Test
    fun `ipv6 loopback and unique-local are refused in bracketed form`() {
        assertTrue(tool.isPrivateHost("[::1]"))
        assertTrue(tool.isPrivateHost("::1"))
        assertTrue(tool.isPrivateHost("[fe80::1]"))
        assertTrue(tool.isPrivateHost("[fc00::1]"))
        assertTrue(tool.isPrivateHost("[fd12:3456::1]"))
    }

    @Test
    fun `public hosts are allowed`() {
        assertFalse(tool.isPrivateHost("example.com"))
        assertFalse(tool.isPrivateHost("api.openai.com"))
        assertFalse(tool.isPrivateHost("172.32.0.1"))       // just outside 172.16/12
        assertFalse(tool.isPrivateHost("11.0.0.1"))
        assertFalse(tool.isPrivateHost("192.169.0.1"))
    }

    @Test
    fun `a hostname starting with fc is not mistaken for a unique-local address`() {
        assertFalse(tool.isPrivateHost("fcorp.example.com"))
        assertFalse(tool.isPrivateHost("fdic.gov"))
    }

    @Test
    fun `a missing host is refused rather than allowed`() {
        assertTrue(tool.isPrivateHost(null))
        assertTrue(tool.isPrivateHost(""))
        assertTrue(tool.isPrivateHost("   "))
    }
}
