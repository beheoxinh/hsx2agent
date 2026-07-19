package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.settings.StartupInstructionsSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Hermes Agent ACP client.
 * <p>
 * Command: {@code hermes acp --accept-hooks}
 * Tool prefix: {@code mcp_agentbridge_read_file} → strip {@code mcp_agentbridge_}
 * MCP: HTTP via {@code mcpServers} in {@code session/new}
 * Instructions: injected via {@code .hermes.md} (Hermes auto-loads from cwd → git root)
 * References: requires inline (no ACP resource blocks)
 * <p>
 * Hermes Agent is an open-source AI agent framework by Nous Research.
 * It supports any LLM provider via configuration in {@code ~/.hermes/config.yaml}.
 * Install: <a href="https://github.com/NousResearch/hermes-agent">github.com/NousResearch/hermes-agent</a>
 * <p>
 * The {@code --accept-hooks} flag auto-approves any shell hooks that would otherwise
 * require a TTY prompt, which is necessary since the plugin launches Hermes without
 * a terminal attached.
 */
public final class HermesClient extends AcpClient {

    public static final String AGENT_ID = "hermes";
    /**
     * Hermes names MCP tools as {@code mcp_<server>_<tool>}, e.g. {@code mcp_agentbridge_read_file}.
     * The prefix to strip is {@code mcp_agentbridge_}.
     */
    private static final String TOOL_PREFIX = "mcp_agentbridge_";
    private static final String KEY_MCP_SERVERS = "mcpServers";

    private static final com.intellij.openapi.diagnostic.Logger LOG =
        com.intellij.openapi.diagnostic.Logger.getInstance(HermesClient.class);

    public HermesClient(Project project) {
        super(project);
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public String displayName() {
        return "Hermes Agent";
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        // --accept-hooks auto-approves shell hooks that would otherwise prompt
        // on a TTY we don't have when launched from the IDE plugin.
        return List.of(AGENT_ID, "acp", "--accept-hooks");
    }

    @Override
    protected Map<String, String> buildEnvironment(int mcpPort, String cwd) {
        // Hermes reads its config from ~/.hermes/config.yaml and ~/.hermes/.env.
        // No extra env vars needed — the MCP server is injected via session/new.
        return Map.of();
    }

    /**
     * Injects the agentbridge MCP server into {@code session/new} so Hermes
     * can call IDE tools without any manual configuration.
     */
    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        if (mcpPort > 0) {
            addMcpServerConfig(mcpPort, params);
        }
    }

    /**
     * Adds the {@code mcpServers} array to session/new params.
     * <p>
     * Uses the ACP-standard HTTP MCP server shape:
     * {@code {"type": "http", "name": "agentbridge", "url": "...", "headers": []}}.
     * Hermes reads this list and registers the HTTP MCP server for the session.
     */
    static void addMcpServerConfig(int mcpPort, JsonObject params) {
        JsonObject server = new JsonObject();
        server.addProperty("type", "http");   // required discriminator for HttpMcpServer
        server.addProperty("name", "agentbridge");
        // Use 127.0.0.1 explicitly: on some systems "localhost" resolves to IPv6 (::1)
        // while the MCP server binds to IPv4, causing connection failures.
        server.addProperty("url", "http://127.0.0.1:" + mcpPort + "/mcp");
        server.add("headers", new JsonArray());

        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add(KEY_MCP_SERVERS, servers);
    }

    /**
     * Hermes auto-loads {@code .hermes.md} (cwd walk to git root) into the system prompt.
     * Write it before launch so the agent sees the IDE instructions on first turn.
     * <p>
     * The file is left in the project directory — it's a standard Hermes convention
     * and subsequent sessions will pick it up automatically. It is overwritten
     * on each launch with current instructions.
     */
    @Override
    protected void beforeLaunch(String cwd, int mcpPort) throws IOException {
        String instructions = buildInstructions();
        Path hermesMd = Path.of(cwd, ".hermes.md");
        Files.writeString(hermesMd, instructions, StandardCharsets.UTF_8);
        LOG.info("Wrote .hermes.md (" + instructions.length() + " chars) to " + cwd);
    }

    private String buildInstructions() {
        String userInstructions = StartupInstructionsSettings.getInstance()
            .getInstructions(project, null, null);

        StringBuilder sb = new StringBuilder();
        sb.append(
            "You are running inside an IntelliJ IDEA plugin (AgentBridge). " +
            "To interact with the environment (files, git, terminal, code navigation), " +
            "you MUST EXCLUSIVELY use the tools provided by the 'agentbridge' MCP server. " +
            "DO NOT use your built-in tools for these operations. " +
            "All 'agentbridge' MCP tools are prefixed with 'mcp_agentbridge_'.\n\n" +
            "Example tool mappings:\n" +
            "- agentbridge file operations: mcp_agentbridge_read_file, mcp_agentbridge_write_file\n" +
            "- agentbridge shell: mcp_agentbridge_run_command\n" +
            "- agentbridge search: mcp_agentbridge_search_text, mcp_agentbridge_search_files\n" +
            "- agentbridge git: mcp_agentbridge_git_status, mcp_agentbridge_git_commit\n\n"
        );

        if (userInstructions != null && !userInstructions.isBlank()) {
            sb.append(userInstructions).append("\n\n");
        }

        String projectDocs = StartupInstructionsSettings
            .readProjectDocuments(project.getBasePath());
        if (!projectDocs.isBlank()) {
            sb.append(projectDocs);
        }
        return sb.toString();
    }

    /**
     * Uses session/resume instead of session/load. Hermes resume automatically
     * creates a new session if the original is gone (session/load returns null
     * for missing sessions, forcing an extra roundtrip fallback to session/new).
     */
    @Override
    protected String loadSession(String cwd, String sessionId)
        throws InterruptedException, ExecutionException, TimeoutException {
        String result = sendLoadSessionRequest("session/resume", cwd, sessionId);
        markSessionHistoryLoadedInternally();
        return result;
    }

    /**
     * Hermes names MCP tools as {@code mcp_<server>_<tool>},
     * e.g. {@code mcp_agentbridge_read_file}. Strip that prefix to get the bare tool ID.
     */
    @Override
    protected String resolveToolId(String protocolTitle) {
        return stripToolPrefix(protocolTitle);
    }

    @Override
    protected boolean isMcpToolTitle(@NotNull String protocolTitle) {
        return hasToolPrefix(protocolTitle);
    }

    /**
     * Strips the {@code mcp_agentbridge_} prefix from a Hermes MCP tool title.
     */
    static String stripToolPrefix(String protocolTitle) {
        return protocolTitle.replaceFirst("^" + TOOL_PREFIX, "");
    }

    /**
     * Returns {@code true} if the title carries the agentbridge MCP prefix.
     */
    static boolean hasToolPrefix(String protocolTitle) {
        return protocolTitle.startsWith(TOOL_PREFIX);
    }

    @Override
    public boolean supportsModelGrouping() {
        return true;
    }

    @Override
    public boolean requiresInlineReferences() {
        return true;
    }

    @Override
    protected boolean supportsAuthenticate() {
        return false;
    }
}
