package com.github.catatafishen.agentbridge.acp.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies capability gating helpers on {@link InitializeResponse.SessionCapabilities}.
 */
class InitializeResponseSessionCapabilitiesTest {

    @Test
    void supportsClose_returnsTrueWhenCloseCapabilityPresent() {
        var caps = new InitializeResponse.SessionCapabilities(
            new InitializeResponse.SessionCapabilities.CapabilityPresent(),
            null,
            null
        );
        assertTrue(caps.supportsClose());
        assertFalse(caps.supportsResume());
    }

    @Test
    void supportsResume_returnsTrueWhenResumeCapabilityPresent() {
        var caps = new InitializeResponse.SessionCapabilities(
            null,
            new InitializeResponse.SessionCapabilities.CapabilityPresent(),
            null
        );
        assertFalse(caps.supportsClose());
        assertTrue(caps.supportsResume());
    }

    @Test
    void supportsCloseAndResume_returnFalseWhenCapabilitiesMissing() {
        var caps = new InitializeResponse.SessionCapabilities(null, null, null);
        assertFalse(caps.supportsClose());
        assertFalse(caps.supportsResume());
    }
}
