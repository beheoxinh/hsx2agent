package com.github.catatafishen.agentbridge.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Persists customizable startup instructions for all agents.
 * Allows users to modify the default template via Settings UI.
 */
@Service
@State(name = "StartupInstructionsSettings", storages = @Storage("agentbridge-instructions.xml"))
public final class StartupInstructionsSettings implements PersistentStateComponent<StartupInstructionsSettings.State> {

    @NotNull
    public static StartupInstructionsSettings getInstance() {
        return ApplicationManager.getApplication().getService(StartupInstructionsSettings.class);
    }

    private State state = new State();

    public static final class State {
        private String customInstructions = null; // null means use default template

        public String getCustomInstructions() {
            return customInstructions;
        }

        public void setCustomInstructions(String customInstructions) {
            this.customInstructions = customInstructions;
        }
    }

    @Override
    @Nullable
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    /**
     * Gets the effective startup instructions — default template plus optional custom add-on,
     * always followed by immutable guardrails that cannot be overridden.
     */
    @NotNull
    public String getInstructions() {
        String base = getDefaultTemplate();
        String custom = state.getCustomInstructions();
        StringBuilder sb = new StringBuilder(base);
        if (custom != null && !custom.trim().isEmpty()) {
            sb.append("\n\n").append(custom);
        }
        String guardrails = getImmutableGuardrails();
        if (!guardrails.isEmpty()) {
            sb.append("\n\n").append(guardrails);
        }
        return sb.toString();
    }

    /**
     * Gets the custom instructions if set, or null if using default.
     */
    @Nullable
    public String getCustomInstructions() {
        return state.getCustomInstructions();
    }

    /**
     * Sets custom instructions. Use null or empty string to revert to default.
     */
    public void setCustomInstructions(@Nullable String customInstructions) {
        if (customInstructions != null && customInstructions.trim().isEmpty()) {
            customInstructions = null;
        }
        state.setCustomInstructions(customInstructions);
    }

    /**
     * Returns true if custom instructions are being used (not default template).
     */
    public boolean isUsingCustomInstructions() {
        return state.getCustomInstructions() != null && !state.getCustomInstructions().trim().isEmpty();
    }

    /**
     * Loads the default template from resources.
     */
    @NotNull
    public String getDefaultTemplate() {
        try (InputStream is = getClass().getResourceAsStream("/default-startup-instructions.md")) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            // Fallback if resource not found
        }
        return "You are running inside an IntelliJ IDEA plugin with IDE tools accessible via MCP.";
    }

    /**
     * Loads immutable safety guardrails that are always appended to instructions.
     * These cannot be overridden or disabled by custom instructions or UI settings.
     */
    @NotNull
    public String getImmutableGuardrails() {
        try (InputStream is = getClass().getResourceAsStream("/immutable-guardrails.md")) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            // Fallback if resource not found
        }
        return "";
    }

    /**
     * Reads project-level instruction documents (AGENTS.md and README.md) from the
     * project root. Matching is case-insensitive; AGENTS.md, agents.md, AGENT.md,
     * Agent.MD etc. are all accepted.
     *
     * @return combined content of both documents, or empty string if neither exists
     */
    @NotNull
    public static String readProjectDocuments(@Nullable String projectBasePath) {
        if (projectBasePath == null || projectBasePath.isBlank()) return "";
        java.nio.file.Path root = java.nio.file.Path.of(projectBasePath);
        if (!java.nio.file.Files.isDirectory(root)) return "";

        StringBuilder sb = new StringBuilder();

        try (var files = java.nio.file.Files.list(root)) {
            java.util.List<java.nio.file.Path> matches = files
                .filter(f -> {
                    String name = f.getFileName().toString();
                    if (!name.endsWith(".md") && !name.endsWith(".MD")) return false;
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    return lower.startsWith("agent") || lower.startsWith("readme");
                })
                .sorted()
                .toList();

            for (java.nio.file.Path file : matches) {
                try {
                    String content = java.nio.file.Files.readString(file, StandardCharsets.UTF_8).trim();
                    if (content.isEmpty()) continue;
                    if (!sb.isEmpty()) sb.append("\n\n");
                    sb.append("## ").append(file.getFileName()).append("\n\n").append(content);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }

        if (sb.isEmpty()) return "";
        return sb.toString();
    }
}
