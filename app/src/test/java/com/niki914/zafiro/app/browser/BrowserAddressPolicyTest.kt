package com.niki914.zafiro.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Prevent local privileged origins and credentials from being admitted by a URL parser refactor. */
class BrowserAddressPolicyTest {
    @Test
    fun rejectsLocalSchemesLoopbackAndEmbeddedCredentials() {
        listOf("file:///data/local/tmp/a", "javascript:alert(1)", "content://provider/secret", "http://127.0.0.1:4145", "http://0x7f000001", "http://127.1", "http://localhost", "http://[::1]", "http://10.0.0.2", "https://user:password@example.com").forEach {
            assertNull(it, BrowserAddressPolicy.normalize(it))
        }
    }
    @Test
    fun preservesPublicHttpsAddressAndFragment() {
        assertEquals("https://example.com/form?query=a#result", BrowserAddressPolicy.normalize("https://example.com/form?query=a#result"))
    }
}
