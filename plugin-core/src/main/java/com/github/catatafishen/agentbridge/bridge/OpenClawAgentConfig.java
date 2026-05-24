package com.github.catatafishen.agentbridge.bridge;

import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * OpenClaw-specific {@link AgentConfig} implementation.
 *
 * <p>Extends the generic {@link ProfileBasedAgentConfig} with OpenClaw-specific
 * behavior. OpenClaw uses {@code OPENCLAW_CONFIG_PATH} environment variable
 * to load its config file, and per-session MCP injection is not supported.
 * MCP servers are defined in the config file under {@code "mcp.servers"}.
 */
final class OpenClawAgentConfig extends ProfileBasedAgentConfig {

    static final String PROFILE_ID = "openclaw";

    OpenClawAgentConfig(@NotNull AgentProfile profile,
                        @Nullable ToolRegistry registry,
                        @Nullable Project project) {
        super(profile, registry, project);
    }

    @Override
    protected @NotNull List<Path> getAdditionalMcpConfigPaths() {
        String userHome = System.getProperty("user.home", "");
        return List.of(
            Path.of(userHome, ".openclaw", "openclaw.json")
        );
    }

    @Override
    protected @NotNull String getMcpContainerKey() {
        return "mcp";
    }
}
