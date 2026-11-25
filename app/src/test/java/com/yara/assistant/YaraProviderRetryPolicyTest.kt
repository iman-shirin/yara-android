package com.yara.assistant

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YaraProviderRetryPolicyTest {
    @Test
    fun authenticationBillingQuotaAndRateLimitsNeverRetryToAnotherProvider() {
        listOf(401, 402, 403, 429).forEach { status ->
            assertFalse(
                "HTTP $status must not trigger a provider fallback",
                YaraProviderRetryPolicy.shouldRetry(IOException("HTTP $status: rejected"))
            )
        }
        assertFalse(YaraProviderRetryPolicy.shouldRetry(IOException("provider quota exhausted")))
        assertFalse(YaraProviderRetryPolicy.shouldRetry(IOException("billing is unclear")))
    }

    @Test
    fun networkTimeoutAndServerFailuresCanUseExplicitlyEnabledFallback() {
        assertTrue(YaraProviderRetryPolicy.shouldRetry(IOException("network timeout")))
        assertTrue(YaraProviderRetryPolicy.shouldRetry(IOException("HTTP 408: timeout")))
        assertTrue(YaraProviderRetryPolicy.shouldRetry(IOException("HTTP 503: unavailable")))
        assertFalse(YaraProviderRetryPolicy.shouldRetry(IllegalStateException("unknown failure")))
    }
}
