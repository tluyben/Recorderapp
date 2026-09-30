package com.appsalad.recorder

import com.appsalad.recorder.data.Prefs
import com.appsalad.recorder.net.InferMux
import com.appsalad.recorder.net.OpenRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsTest {
    private val day = 86_400_000L

    @Test fun infermuxIsTheDefault() {
        assertTrue(Prefs().usesInferMux)
        assertNull(Prefs().aiClient())
        assertTrue(Prefs().missingKeyMessage.startsWith(Prefs.NO_KEY))
    }

    @Test fun picksTheChosenService() {
        val p = Prefs(infermuxKey = "sk_mr_abc", apiKey = "sk-or-v1-x")
        assertEquals(InferMux::class, p.aiClient()!!::class)
        assertEquals(OpenRouter::class, p.copy(provider = "openrouter").aiClient()!!::class)
        // no silent fallback to the other service
        assertNull(Prefs(provider = "openrouter", infermuxKey = "sk_mr_abc").aiClient())
    }

    @Test fun expiredMintedKeyIsNotUsed() {
        val expired = Prefs(infermuxKey = "sk_mr_abc", infermuxExpires = System.currentTimeMillis() - day)
        assertTrue(expired.infermuxExpired)
        assertNull(expired.aiClient())
        assertTrue(expired.missingKeyMessage.contains("expired"))
        val valid = expired.copy(infermuxExpires = System.currentTimeMillis() + 30 * day)
        assertEquals(InferMux::class, valid.aiClient()!!::class)
        // a pasted key has no known expiry
        assertEquals(InferMux::class, expired.copy(infermuxExpires = 0).aiClient()!!::class)
    }
}
