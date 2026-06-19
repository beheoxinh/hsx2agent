package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.GenericSettings;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolPermission;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds dynamic startup instructions that reflect the current project tool surface,
 * MCP enable/disable settings, and per-tool permission policy.
 */
public final class StartupInstructionsComposer {

    private static final int MAX_TOOL_LINES = 80;

    private StartupInstructionsComposer() {
    }

    @NotNull
    public static String compose(@NotNull Project project, @Nullable AgentProfile profile,
                                 @NotNull String baseInstructions, @Nullable String additionalInstructions) {
        StringBuilder sb = new StringBuilder(baseInstructions.trim());
        String toolSection = buildToolSection(project, profile);
        if (!toolSection.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(toolSection);
        }
        if (additionalInstructions != null && !additionalInstructions.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(additionalInstructions.trim());
        }
        return sb.toString();
    }

    @NotNull
    private static String buildToolSection(@NotNull Project project, @Nullable AgentProfile profile) {
        ToolRegistry registry = ToolRegistry.getInstance(project);
        McpServerSettings mcpSettings = McpServerSettings.getInstance(project);
        AgentProfile effectiveProfile = profile != null ? profile : ActiveAgentManager.getInstance(project).getActiveProfile();
        GenericSettings settings = new GenericSettings(effectiveProfile.getId(), project);
        boolean usePluginPermissions = effectiveProfile.isUsePluginPermissions();
        boolean excludeBuiltIns = effectiveProfile.isExcludeAgentBuiltInTools();

        List<ToolDefinition> enabledMcpTools = McpToolFilter.getEnabledTools(mcpSettings, project).stream()
            .sorted(Comparator.comparing(ToolDefinition::id))
            .toList();
        List<ToolDefinition> deniedBuiltIns = registry.getAllTools().stream()
            .filter(ToolDefinition::isBuiltIn)
            .filter(tool -> excludeBuiltIns || settings.getToolPermission(tool.id()) == ToolPermission.DENY)
            .sorted(Comparator.comparing(ToolDefinition::id))
            .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("## Dynamic MCP Tool Reality\n\n");
        sb.append("This section reflects the CURRENT plugin settings for this project and agent profile. ");
        sb.append("Treat it as authoritative over generic assumptions.\n\n");
        sb.append("- Only tools listed below as available should be called.\n");
        sb.append("- If a tool is disabled or denied, do not call it. Choose an available alternative.\n");
        sb.append("- Prefer MCP tools over manual reasoning or shell-style fallbacks whenever an MCP tool fits the task.\n");
        sb.append("- For architecture, bug investigation, and code-flow questions, start with `agentbridge-build_context`.\n");
        sb.append("- Before broad refactors, use `agentbridge-impact_analysis`.\n");
        sb.append("- To understand a call chain between two points, use `agentbridge-trace_call_path`.\n");
        sb.append("- For semantic edits, prefer `replace_symbol_body`, `insert_before_symbol`, `insert_after_symbol`, and `refactor` over raw text edits.\n\n");

        sb.append("Plugin permission mode: ")
            .append(usePluginPermissions ? "ASK policies are enforced by the plugin." : "ASK policies are effectively auto-approved by the plugin.")
            .append("\n");
        sb.append("Enabled MCP tools in this project: ").append(enabledMcpTools.size()).append("\n");
        if (!deniedBuiltIns.isEmpty()) {
            sb.append("Blocked built-in/native tools: ").append(deniedBuiltIns.size()).append("\n");
        }
        sb.append('\n');

        appendToolList(sb, enabledMcpTools, settings, usePluginPermissions);
        appendDeniedBuiltIns(sb, deniedBuiltIns);
        return sb.toString().trim();
    }

    private static void appendToolList(@NotNull StringBuilder sb, @NotNull List<ToolDefinition> tools,
                                       @NotNull GenericSettings settings, boolean usePluginPermissions) {
        sb.append("### Available MCP Tools\n\n");
        int shown = 0;
        for (ToolDefinition tool : tools) {
            if (shown >= MAX_TOOL_LINES) {
                sb.append("- ... and ").append(tools.size() - shown).append(" more enabled MCP tools. Use semantic discovery tools to inspect them when needed.\n");
                break;
            }
            ToolPermission storedPerm = settings.getToolPermission(tool.id());
            String effectivePerm = storedPerm == ToolPermission.ASK && !usePluginPermissions ? "allow" : storedPerm.name().toLowerCase(Locale.ROOT);
            sb.append("- `agentbridge-").append(tool.id()).append("` [")
                .append(tool.category().name().toLowerCase(Locale.ROOT))
                .append(", ").append(tool.kind().value())
                .append(", permission=").append(effectivePerm);
            if (tool.supportsPathSubPermissions()) {
                sb.append(", path-sensitive");
            }
            sb.append("] — ").append(oneLine(tool.description())).append('\n');

            String params = summarizeSchema(tool.inputSchema());
            if (!params.isEmpty()) {
                sb.append("  params: ").append(params).append('\n');
            }
            shown++;
        }
        sb.append("\n### Tool Selection Rules\n\n");
        sb.append("- Use `agentbridge-search_symbols` for classes, methods, fields, and symbol definitions.\n");
        sb.append("- Use `agentbridge-search_text` for exact strings, regex patterns, logs, comments, and config keys.\n");
        sb.append("- Use `agentbridge-find_references` for usages, not text search.\n");
        sb.append("- Use `agentbridge-read_file` only after narrowing the target with code intelligence tools.\n");
        sb.append("- Use `agentbridge-get_compilation_errors` / `get_problems` / `get_highlights` for diagnostics before heavier verification.\n");
        sb.append("- Use `agentbridge-build_project` and `run_tests` for verification when edits or behavior changes need proof.\n");
        sb.append("- Use `agentbridge-git_*` tools for git; never emulate git through shell commands.\n");
    }

    private static void appendDeniedBuiltIns(@NotNull StringBuilder sb, @NotNull List<ToolDefinition> deniedBuiltIns) {
        if (deniedBuiltIns.isEmpty()) {
            return;
        }
        sb.append("\n### Built-in / Native Tools You Should Avoid\n\n");
        for (ToolDefinition tool : deniedBuiltIns) {
            sb.append("- `").append(tool.id()).append("` — blocked or intentionally excluded. Use the MCP alternatives above instead.\n");
        }
    }

    @NotNull
    private static String summarizeSchema(@Nullable JsonObject schema) {
        if (schema == null || !schema.has("properties") || !schema.get("properties").isJsonObject()) {
            return "";
        }
        JsonObject props = schema.getAsJsonObject("properties");
        List<String> required = new ArrayList<>();
        if (schema.has("required") && schema.get("required").isJsonArray()) {
            for (JsonElement el : schema.getAsJsonArray("required")) {
                required.add(el.getAsString());
            }
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : props.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject def = entry.getValue().getAsJsonObject();
            String type = def.has("type") ? def.get("type").getAsString() : "value";
            String label = entry.getKey() + ":" + type;
            if (required.contains(entry.getKey())) {
                label += "*";
            }
            parts.add(label);
            if (parts.size() >= 8) {
                break;
            }
        }
        return String.join(", ", parts);
    }

    @NotNull
    private static String oneLine(@NotNull String text) {
        return text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }
}
