package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.acp.model.PromptRequest;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
import com.github.catatafishen.agentbridge.agent.AbstractAgentClient;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class OpenClawClient extends AcpClient {

    private static final Logger LOG = Logger.getInstance(OpenClawClient.class);
    private static final String AGENT_ID = "openclaw";
    private static final String TOOL_PREFIX = "agentbridge_";

    private static final String KEY_GATEWAY_URL = "openclaw.gatewayUrl";
    private static final String KEY_DEFAULT_SESSION = "openclaw.defaultSession";

    private static final Path USER_CONFIG_PATH = Path.of(
        System.getProperty("user.home", ""), ".openclaw", "openclaw.json"
    );

    private long lastSessionInputTokens = 0;
    private long lastSessionOutputTokens = 0;
    private long latestTurnInputTokens = 0;
    private long latestTurnOutputTokens = 0;

    private Process customGatewayProcess;

    public OpenClawClient(Project project) {
        super(project);
    }

    @Override
    public String agentId() {
        return AGENT_ID;
    }

    @Override
    public String displayName() {
        return "OpenClaw";
    }

    @Override
    public @Nullable String defaultAgentSlug() {
        return null;
    }

    @Override
    public boolean supportsModelGrouping() {
        return true;
    }

    @Override
    public List<AbstractAgentClient.AgentMode> getAvailableAgents() {
        return builtInAgents();
    }

    static List<AbstractAgentClient.AgentMode> builtInAgents() {
        return List.of(
            new AbstractAgentClient.AgentMode("default", "Default", "Default agent with full tool access")
        );
    }

    @Override
    public void beforeLaunch(String cwd, int mcpPort) {
        int port = findFreePort();
        startCustomGateway(port, mcpPort, cwd);
        PropertiesComponent.getInstance().setValue(KEY_GATEWAY_URL, "ws://127.0.0.1:" + port);
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        String gatewayUrl = PropertiesComponent.getInstance().getValue(KEY_GATEWAY_URL, "");
        String defaultSession = PropertiesComponent.getInstance().getValue(KEY_DEFAULT_SESSION, "");

        List<String> cmd = new ArrayList<>();
        cmd.add(AGENT_ID);
        cmd.add("acp");

        if (!gatewayUrl.isEmpty()) {
            cmd.add("--url");
            cmd.add(gatewayUrl);
        }
        if (!defaultSession.isEmpty()) {
            cmd.add("--session");
            cmd.add(defaultSession);
        }

        cmd.add("--no-prefix-cwd");
        return cmd;
    }

    @Override
    protected Map<String, String> buildEnvironment(int mcpPort, String cwd) {
        Map<String, String> env = new HashMap<>();
        Path configFile = createMcpConfigFile(mcpPort, cwd);
        if (configFile != null) {
            env.put("OPENCLAW_CONFIG_PATH", configFile.toString());
        }
        return env;
    }

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        params.add("mcpServers", new JsonArray());
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return stripToolPrefix(protocolTitle);
    }

    @Override
    protected boolean isMcpToolTitle(@NotNull String protocolTitle) {
        return hasToolPrefix(protocolTitle);
    }

    static String stripToolPrefix(String protocolTitle) {
        return protocolTitle.replaceFirst("^" + TOOL_PREFIX, "");
    }

    static boolean hasToolPrefix(String protocolTitle) {
        return protocolTitle.startsWith(TOOL_PREFIX);
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
            long deltaInput = latestTurnInputTokens - lastSessionInputTokens;
            long deltaOutput = latestTurnOutputTokens - lastSessionOutputTokens;
            return new SessionUpdate.TurnUsage((int) deltaInput, (int) deltaOutput, costUsd);
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

    @Override
    public boolean requiresInlineReferences() {
        return true;
    }

    @Override
    public AbstractAgentClient.ModelDisplayMode modelDisplayMode() {
        return AbstractAgentClient.ModelDisplayMode.TOKEN_COUNT;
    }

    @Override
    protected boolean supportsAuthenticate() {
        return false;
    }

    @Override
    protected @Nullable JsonObject parseToolCallArguments(@NotNull JsonObject params) {
        JsonObject fromRawInput = extractRawInputArgs(params);
        return fromRawInput != null ? fromRawInput : super.parseToolCallArguments(params);
    }

    static JsonObject extractRawInputArgs(JsonObject params) {
        if (params.has("rawInput") && params.get("rawInput").isJsonObject()) {
            JsonObject raw = params.getAsJsonObject("rawInput");
            if (!raw.entrySet().isEmpty()) {
                return raw;
            }
        }
        return null;
    }

    // ────────────────────── Custom Gateway ──────────────────────

    private void startCustomGateway(int port, int mcpPort, String cwd) {
        Path configFile = createMcpConfigFile(mcpPort, cwd);
        if (configFile == null) {
            LOG.warn("Cannot start custom Gateway: failed to create config file");
            return;
        }

        List<String> cmd = List.of(
            AGENT_ID, "gateway", "run",
            "--port", String.valueOf(port),
            "--force"
        );

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("OPENCLAW_CONFIG_PATH", configFile.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        try {
            customGatewayProcess = pb.start();
            LOG.info("Custom Gateway started on port " + port + " (PID: " + customGatewayProcess.pid() + ")");
        } catch (IOException e) {
            LOG.warn("Failed to start custom Gateway on port " + port, e);
            return;
        }

        if (!waitForGatewayReady(port, 15, TimeUnit.SECONDS)) {
            LOG.warn("Custom Gateway on port " + port + " did not become ready in time");
            destroyCustomGateway();
            return;
        }

        Disposer.register(project, () -> destroyCustomGateway());
    }

    private static int findFreePort() {
        for (int port = 18800; port <= 18900; port++) {
            if (isPortOpen(port)) continue;
            return port;
        }
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (IOException e) {
            return 0;
        }
    }

    private static boolean isPortOpen(int port) {
        try (Socket s = new Socket("127.0.0.1", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean waitForGatewayReady(int port, long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (!isPortOpen(port)) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                continue;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return true;
        }
        return false;
    }

    private void destroyCustomGateway() {
        if (customGatewayProcess != null && customGatewayProcess.isAlive()) {
            customGatewayProcess.destroyForcibly();
            try {
                customGatewayProcess.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            customGatewayProcess = null;
            LOG.info("Custom Gateway process destroyed");
        }
    }

    // ────────────────────── Config File ──────────────────────

    private @Nullable Path createMcpConfigFile(int mcpPort, String cwd) {
        try {
            JsonObject root = new JsonObject();
            if (Files.isRegularFile(USER_CONFIG_PATH)) {
                String existingContent = Files.readString(USER_CONFIG_PATH);
                JsonElement parsed = JsonParser.parseString(existingContent);
                if (parsed != null && parsed.isJsonObject()) {
                    root = parsed.getAsJsonObject().deepCopy();
                }
            }

            JsonObject server = new JsonObject();
            server.addProperty("url", "http://127.0.0.1:" + mcpPort + "/mcp");
            server.addProperty("transport", "streamable-http");

            JsonObject servers = new JsonObject();
            servers.add("agentbridge", server);

            JsonObject mcp;
            if (root.has("mcp") && root.get("mcp").isJsonObject()) {
                mcp = root.getAsJsonObject("mcp").deepCopy();
            } else {
                mcp = new JsonObject();
            }
            mcp.add("servers", servers);
            root.add("mcp", mcp);

            if (cwd != null && !cwd.isEmpty()) {
                JsonObject agents = root.getAsJsonObject("agents");
                if (agents == null) {
                    agents = new JsonObject();
                    root.add("agents", agents);
                }
                JsonObject defaults = agents.getAsJsonObject("defaults");
                if (defaults == null) {
                    defaults = new JsonObject();
                    agents.add("defaults", defaults);
                }
                defaults.addProperty("workspace", cwd);
            }

            String content = new GsonBuilder().setPrettyPrinting().create().toJson(root);

            Path tempDir = Files.createTempDirectory("openclaw-config-");
            tempDir.toFile().deleteOnExit();
            Path configFile = tempDir.resolve("openclaw.json");
            Files.writeString(configFile, content);
            configFile.toFile().deleteOnExit();

            return configFile;
        } catch (Exception e) {
            LOG.warn("Failed to create OpenClaw MCP config file", e);
            return null;
        }
    }
}
