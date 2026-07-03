package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.acp.model.PromptRequest;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
import com.github.catatafishen.agentbridge.agent.AbstractAgentClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * OpenCode ACP client.
 * <p>
 * Command: {@code opencode acp}
 * Tool prefix: {@code agentbridge_read_file} → strip {@code agentbridge_}
 * MCP: HTTP via {@code mcpServers} in {@code session/new}
 * References: requires inline (no ACP resource blocks)
 */
public final class OpenCodeClient extends AcpClient {

    private static final String AGENT_ID = "opencode";
    private static final String BUILD_AGENT = "build";
    private static final String PLAN_AGENT = "plan";
    private static final String GENERAL_AGENT = "general";
    private static final String EXPLORE_AGENT = "explore";
    private static final String PROJECT_AGENT_DIR = ".opencode/agent";
    private static final String PROJECT_AGENTS_DIR = ".opencode/agents";
    private static final String DEPLOYED_AGENT_DIR = ".agent-work/opencode/agent";

    private static final String KEY_RAW_INPUT = "rawInput";
    private static final List<String> NATIVE_TOOLS_TO_DENY = List.of(
        "grep", "glob", "ls", "read", "write", "edit", "patch", "bash",
        "lsp", "websearch", "webfetch", "codesearch", "todoread", "todowrite"
    );

    private static final Logger LOG = Logger.getInstance(OpenCodeClient.class);

    private long lastSessionInputTokens = 0;
    private long lastSessionOutputTokens = 0;
    private long latestTurnInputTokens = 0;
    private long latestTurnOutputTokens = 0;

    static List<String> nativeToolsToDeny() {
        return NATIVE_TOOLS_TO_DENY;
    }

    public OpenCodeClient(Project project) {
        super(project);
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public String displayName() {
        return "OpenCode";
    }

    @Override
    public @Nullable String defaultAgentSlug() {
        return BUILD_AGENT;
    }

    @Override
    public boolean supportsModelGrouping() {
        return true;
    }

    @Override
    public List<AbstractAgentClient.AgentMode> getAvailableAgents() {
        List<AbstractAgentClient.AgentMode> agents = new ArrayList<>(builtInAgents());
        String basePath = project.getBasePath();
        if (basePath != null) {
            agents.addAll(ProjectAgentScanner.scanAgentDirectories(
                Path.of(basePath),
                Set.of(BUILD_AGENT, PLAN_AGENT, GENERAL_AGENT, EXPLORE_AGENT),
                PROJECT_AGENT_DIR,
                PROJECT_AGENTS_DIR,
                DEPLOYED_AGENT_DIR
            ));
        }
        return agents;
    }

    static List<AbstractAgentClient.AgentMode> builtInAgents() {
        return List.of(
            new AbstractAgentClient.AgentMode(BUILD_AGENT, "Build", "Default primary agent with full tool access"),
            new AbstractAgentClient.AgentMode(PLAN_AGENT, "Plan", "Read-only planning mode with guarded edits and bash"),
            new AbstractAgentClient.AgentMode(GENERAL_AGENT, "General", "General-purpose subagent for complex tasks"),
            new AbstractAgentClient.AgentMode(EXPLORE_AGENT, "Explore", "Fast read-only subagent for codebase exploration")
        );
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        // On Windows, opencode is installed via npm and the native binary is not on PATH.
        // Probe the project-local node_modules path as a fallback.
        String windowsPath = resolveWindowsOpenCodePath(cwd);
        return List.of(windowsPath != null ? windowsPath : AGENT_ID, "acp");
    }

    /**
     * On Windows, opencode is shipped as a native binary inside its npm package and is not
     * added to PATH by default. Probes the project-local {@code node_modules} tree for the
     * {@code opencode-windows-x64} binary bundled by {@code opencode-ai}.
     *
     * <p>Package-private and static so unit tests can call it directly without an
     * IntelliJ application context.</p>
     *
     * @param projectBasePath the project root directory, or {@code null} if unavailable
     * @return absolute path to {@code opencode.exe}, or {@code null} if not found or not on Windows
     */
    @Nullable
    static String resolveWindowsOpenCodePath(@Nullable String projectBasePath) {
        if (!SystemInfo.isWindows) {
            return null;
        }
        if (projectBasePath == null || projectBasePath.isEmpty()) {
            return null;
        }
        Path candidate = Path.of(projectBasePath,
            "node_modules", "opencode-ai", "node_modules", "opencode-windows-x64", "bin", "opencode.exe");
        if (Files.isRegularFile(candidate)) {
            return candidate.toString();
        }
        return null;
    }

    @Override
    protected Map<String, String> buildEnvironment(int mcpPort, String cwd) {
        return buildPermissionConfig();
    }

    @Override
    protected @NotNull String normalizeResolvedBinaryPath(@NotNull String resolvedPath, @NotNull String cwd) {
        String bundled = resolveBundledUnixOpenCodePath(resolvedPath);
        return bundled != null ? bundled : resolvedPath;
    }

    /**
     * Builds the OPENCODE_CONFIG_CONTENT environment variable denying native tools.
     *
     * <p>NOTE: We do NOT set {@code "default_agent"} here. OpenCode v1.4.10+ rejects
     * subagent slugs (like "build", "plan", "explore") as the {@code default_agent} value,
     * causing {@code session/new} to fail with
     * {@code "default agent \"build\" is a subagent"}. OpenCode selects its own default
     * agent internally — the plugin's agent dropdown controls which agent to start via
     * the session/create flow instead.</p>
     */
    static Map<String, String> buildPermissionConfig() {
        JsonObject permission = new JsonObject();
        for (String tool : NATIVE_TOOLS_TO_DENY) {
            permission.addProperty(tool, "deny");
        }
        JsonObject config = new JsonObject();
        config.add("permission", permission);
        return Map.of("OPENCODE_CONFIG_CONTENT", new Gson().toJson(config));
    }

    /**
     * Rewrites the npm launcher shim to the real Unix binary bundled under the package.
     * The shim throws "postinstall script was not run" when package managers skip scripts,
     * but the native binary is often already present and works.
     */
    @Nullable
    static String resolveBundledUnixOpenCodePath(@Nullable String launcherPath) {
        if (SystemInfo.isWindows || launcherPath == null || launcherPath.isBlank()) {
            return null;
        }
        Path launcher = Path.of(launcherPath);
        if (!"opencode.exe".equals(launcher.getFileName().toString())) {
            return null;
        }
        Path binDir = launcher.getParent();
        if (binDir == null || !"bin".equals(binDir.getFileName().toString())) {
            return null;
        }
        Path packageRoot = binDir.getParent();
        if (packageRoot == null) {
            return null;
        }
        Path nodeModules = packageRoot.resolve("node_modules");
        if (!Files.isDirectory(nodeModules)) {
            return null;
        }

        for (String packageName : preferredUnixPackageNames()) {
            Path candidate = nodeModules.resolve(packageName).resolve("bin").resolve("opencode");
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }

        try (Stream<Path> children = Files.list(nodeModules)) {
            return children
                .filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().startsWith("opencode-"))
                .sorted(Comparator
                    .comparingInt((Path path) -> unixPackageRank(path.getFileName().toString()))
                    .thenComparing(path -> path.getFileName().toString()))
                .map(path -> path.resolve("bin").resolve("opencode"))
                .filter(Files::isRegularFile)
                .map(Path::toString)
                .findFirst()
                .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static List<String> preferredUnixPackageNames() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (SystemInfo.isLinux) {
            if (arch.contains("arm64") || arch.contains("aarch64")) {
                return List.of("opencode-linux-arm64", "opencode-linux-arm64-musl");
            }
            return List.of(
                "opencode-linux-x64",
                "opencode-linux-x64-baseline",
                "opencode-linux-x64-musl",
                "opencode-linux-x64-baseline-musl"
            );
        }
        if (SystemInfo.isMac) {
            if (arch.contains("arm64") || arch.contains("aarch64")) {
                return List.of("opencode-darwin-arm64");
            }
            return List.of("opencode-darwin-x64", "opencode-darwin-x64-baseline");
        }
        return List.of();
    }

    private static int unixPackageRank(String packageName) {
        int rank = 0;
        if (packageName.contains("baseline")) {
            rank += 10;
        }
        if (packageName.contains("musl")) {
            rank += 20;
        }
        return rank;
    }

    @Override
    protected String extractSubAgentType(@NotNull JsonObject params, @NotNull String resolvedTitle,
                                         @Nullable JsonObject argumentsObj) {
        if ("task".equals(resolvedTitle)) {
            return extractTaskSubAgentType(params);
        }
        return super.extractSubAgentType(params, resolvedTitle, argumentsObj);
    }

    /**
     * Extracts the sub-agent type from a "task" tool call's rawInput.
     * Returns the {@code subagent_type} value if present, otherwise {@code "general"}.
     */
    static String extractTaskSubAgentType(JsonObject params) {
        JsonObject raw = params.has(KEY_RAW_INPUT) && params.get(KEY_RAW_INPUT).isJsonObject()
            ? params.getAsJsonObject(KEY_RAW_INPUT) : null;
        if (raw != null && raw.has("subagent_type")) {
            return raw.get("subagent_type").getAsString();
        }
        return GENERAL_AGENT;
    }

    @Override
    @Nullable
    protected JsonObject parseToolCallArguments(@NotNull JsonObject params) {
        JsonObject fromRawInput = extractRawInputArgs(params);
        return fromRawInput != null ? fromRawInput : super.parseToolCallArguments(params);
    }

    /**
     * Extracts tool call arguments from the {@code rawInput} field.
     * Returns {@code null} if rawInput is absent or empty.
     */
    @Nullable
    static JsonObject extractRawInputArgs(JsonObject params) {
        if (params.has(KEY_RAW_INPUT) && params.get(KEY_RAW_INPUT).isJsonObject()) {
            JsonObject raw = params.getAsJsonObject(KEY_RAW_INPUT);
            if (!raw.entrySet().isEmpty()) {
                return raw;
            }
        }
        return null;
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return stripToolPrefix(protocolTitle);
    }

    /**
     * Strips the {@code agentbridge_} prefix from an OpenCode tool title.
     */
    static String stripToolPrefix(String protocolTitle) {
        return protocolTitle.replaceFirst("^agentbridge_", "");
    }

    @Override
    protected boolean isMcpToolTitle(@org.jetbrains.annotations.NotNull String protocolTitle) {
        return hasToolPrefix(protocolTitle);
    }

    /**
     * Returns {@code true} if the title starts with the OpenCode MCP tool prefix.
     */
    static boolean hasToolPrefix(String protocolTitle) {
        return protocolTitle.startsWith("agentbridge_");
    }

    @Override
    protected PromptRequest beforeSendPrompt(PromptRequest request) {
        latestTurnInputTokens = lastSessionInputTokens;
        latestTurnOutputTokens = lastSessionOutputTokens;
        return super.beforeSendPrompt(request);
    }

    @Override
    protected SessionUpdate processUpdate(SessionUpdate update) {
        if (update instanceof SessionUpdate.TurnUsage(int inputTokens, int outputTokens, Double costUsd)) {
            latestTurnInputTokens = inputTokens;
            latestTurnOutputTokens = outputTokens;
            return update;
        }
        return super.processUpdate(update);
    }

    @Override
    protected void afterPromptComplete() {
        lastSessionInputTokens = latestTurnInputTokens;
        lastSessionOutputTokens = latestTurnOutputTokens;
        super.afterPromptComplete();
    }

    @Override
    public void dropCurrentSession() {
        super.dropCurrentSession();
        lastSessionInputTokens = 0;
        lastSessionOutputTokens = 0;
        latestTurnInputTokens = 0;
        latestTurnOutputTokens = 0;
    }

    /**
     * Empties the OpenCode DB of all sessions with incomplete compaction for this project.
     * Called when a corrupted session is detected — the stale session state prevents OpenCode
     * from creating new sessions, even after a process restart.
     * <p>
     * Must only be called when the OpenCode process is NOT running (i.e. after kill, before
     * restart), otherwise SQLite may be locked.
     */
    public void cleanupCorruptedSessions() {
        String basePath = project.getBasePath();
        if (basePath == null) return;

        // 1. Session cleanup within OpenCode DB
        java.io.File dbFile = java.nio.file.Paths.get(
            System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db").toFile();
        if (dbFile.isFile()) {
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath())) {
                try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT project_id FROM project_directory WHERE directory = ?")) {
                    ps.setString(1, basePath);
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String projectId = rs.getString("project_id");
                            // Delete sessions with incomplete compaction
                            try (java.sql.PreparedStatement del = conn.prepareStatement(
                                "DELETE FROM session WHERE project_id = ? AND time_compacting IS NULL")) {
                                del.setString(1, projectId);
                                int deleted = del.executeUpdate();
                                if (deleted > 0) {
                                    LOG.info("OpenCodeClient: cleaned " + deleted
                                        + " corrupted session(s) for project " + projectId);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("OpenCodeClient: failed to clean OpenCode DB", e);
            }
        }

        // 2. Stale workspace files — OpenCode persists per-project workspace state
        // (model selections, session references, VCS branch info). If a workspace file
        // references a deleted session, OpenCode may try to resume it and fail.
        String configDir = System.getProperty("user.home") + "/.config/ai.opencode.desktop/";
        java.io.File dir = new java.io.File(configDir);
        String projectTag = basePath.replace('/', '-').replaceAll("[^a-zA-Z0-9_-]", "");
        if (dir.isDirectory()) {
            java.io.File[] stale = dir.listFiles((d, name) -> name.startsWith("opencode.workspace.-" + projectTag));
            if (stale != null) {
                for (java.io.File f : stale) {
                    try {
                        if (f.delete()) {
                            LOG.info("OpenCodeClient: deleted stale workspace file: " + f.getName());
                        }
                    } catch (Exception e) {
                        LOG.warn("OpenCodeClient: failed to delete workspace file " + f.getName(), e);
                    }
                }
            }
        }
    }

    @Override
    public boolean requiresInlineReferences() {
        return true;
    }

    @Override
    public @Nullable java.nio.file.Path getSessionDirectory() {
        java.io.File dir = com.github.catatafishen.agentbridge.session.exporters.ExportUtils.sessionsDir(project);
        return dir.isDirectory() ? dir.toPath() : null;
    }

    @Override
    public AbstractAgentClient.ModelDisplayMode modelDisplayMode() {
        return AbstractAgentClient.ModelDisplayMode.TOKEN_COUNT;
    }

    @Override
    protected boolean supportsAuthenticate() {
        return false;
    }

    /**
     * Only deny tools explicitly listed in {@link #NATIVE_TOOLS_TO_DENY}.
     * <p>
     * The base implementation denies any built-in tool not in {@code ALLOWED_BUILT_IN_TOOLS},
     * which is too aggressive for OpenCode — it blocks internal tools like {@code doom_loop}
     * that OpenCode allows. By narrowing to the explicit deny list, OpenCode's own permission
     * system handles the rest.
     */
    @Override
    protected boolean shouldDenyBuiltInTool(@NotNull String toolId) {
        return NATIVE_TOOLS_TO_DENY.contains(toolId.toLowerCase());
    }

    @Override
    protected String loadSession(String cwd, String sessionId) throws InterruptedException, ExecutionException, TimeoutException {
        String result = sendLoadSessionRequest("session/resume", cwd, sessionId);
        markSessionHistoryLoadedInternally();
        return result;
    }

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        addMcpServerConfig(mcpPort, params);
    }

    /**
     * Adds the {@code mcpServers} block to session/new params with type "http".
     */
    static void addMcpServerConfig(int mcpPort, JsonObject params) {
        JsonObject server = new JsonObject();
        server.addProperty("name", "agentbridge");
        server.addProperty("type", "http");
        server.addProperty("url", "http://127.0.0.1:" + mcpPort + "/mcp");
        server.add("headers", new JsonArray());
        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);
    }
}
