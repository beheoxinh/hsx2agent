package com.github.catatafishen.agentbridge.acp.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;

public record InitializeResponse(
    @Nullable Integer protocolVersion,
    AgentInfo agentInfo,
    AgentCapabilities agentCapabilities,
    @Nullable List<AuthMethod> authMethods
) {

    public record AgentInfo(String name, @Nullable String title, String version) {
    }

    public record AgentCapabilities(
        @Nullable Boolean loadSession,
        @Nullable McpCapabilities mcpCapabilities,
        @Nullable PromptCapabilities promptCapabilities,
        @Nullable SessionCapabilities sessionCapabilities
    ) {
    }

    public record McpCapabilities(
        @Nullable Boolean http,
        @Nullable Boolean sse
    ) {
    }

    public record PromptCapabilities(
        @Nullable Boolean image,
        @Nullable Boolean audio,
        @Nullable Boolean embeddedContext
    ) {
    }

    public record SessionCapabilities(
        @Nullable CapabilityPresent close,
        @Nullable CapabilityPresent resume,
        @Nullable CapabilityPresent list
    ) {
        public boolean supportsClose() {
            return close != null;
        }

        public boolean supportsResume() {
            return resume != null;
        }

        /**
         * Marker for an ACP capability advertised as an empty object {@code {}}.
         * Presence of a non-null field = supported; null = not supported.
         */
        public static final class CapabilityPresent {
            @Override
            public String toString() {
                return "CapabilityPresent{}";
            }
        }
    }
}
