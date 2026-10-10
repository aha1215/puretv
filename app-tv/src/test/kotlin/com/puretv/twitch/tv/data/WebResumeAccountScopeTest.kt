package com.puretv.twitch.tv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebResumeAccountScopeTest {
    @Test
    fun usesWebsiteSessionOnlyForSameTwitchUserId() {
        assertEquals("secret", scopedWebResumeToken("secret", "123", "123"))
        assertNull(scopedWebResumeToken("secret", "123", "456"))
    }

    @Test
    fun unboundLegacySessionIsNotReusedForSignedInAccount() {
        assertNull(scopedWebResumeToken("secret", null, "123"))
        assertEquals("secret", scopedWebResumeToken("secret", null, null))
    }

    @Test
    fun blankCredentialIsUnavailable() {
        assertNull(scopedWebResumeToken("  ", "123", "123"))
    }
}
