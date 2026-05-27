package com.github.catatafishen.agentbridge.agent.pi;

import com.github.catatafishen.agentbridge.BuildInfo;
import com.github.catatafishen.agentbridge.acp.model.ContentBlock;
import com.github.catatafishen.agentbridge.acp.model.Model;
import com.github.catatafishen.agentbridge.acp.model.PromptRequest;
import com.github.catatafishen.agentbridge.acp.model.PromptResponse;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
import com.github.catatafishen.agentbridge.agent.AbstractAgentClient;
import com.github.catatafishen.agentbridge.bridge.AgentConfig;
import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.github.catatafishen.agentbridge.settings.AcpClientBinaryResolver;
import com.github.catatafishen.agentbridge.settings.BinaryDetector;
import com.github.catatafishen.agentbridge.settings.ShellEnvironment;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Pi CLI driver that runs {@code pi --mode rpc} as a long-lived subprocess and
 * speaks the JSONL RPC protocol documented in {@code docs/rpc.md} bundled with
 * {@code @earendil-works/pi-coding-agent}.
 *
 * <p>Lifecycle:</p>
 * <ul>
 *   <li>{@link #start()} writes the MCP bridge extension to disk (when the MCP
 *       HTTP server is available), launches {@code pi --mode rpc --no-session
 *       --extension &lt;bridge&gt;}, and spawns a reader thread.</li>
 *   <li>{@link #createSession(String)} returns a synthetic session ID — Pi
 *       manages its own session state, so the plugin layer keeps a 1:1 mapping
 *       between IDE sessions and the subprocess lifetime.</li>
 *   <li>{@link #sendPrompt(PromptRequest, Consumer)} writes
 *       {@code {"type":"prompt","message":...}} to stdin, then blocks until an
 *       {@code agent_end} event arrives. Streamed text/thinking deltas and
 *       tool-execution events are converted into {@link SessionUpdate}s.</li>
 *   <li>{@link #cancelSession(String)} writes {@code {"type":"abort"}}.</li>
 * </ul>
 *
 * <p>Authentication is handled outside the plugin: Pi reads provider API keys
 * from environment variables (see {@code pi --help}) or from
 * {@code ~/.pi/agent/auth.json} populated by {@code pi /login}.</p>
 */
public final class PiCliClient extends AbstractAgentClient {

    private static final Logger LOG = Logger.getInstance(PiCliClient.class);

    public static final String PROFILE_ID = "pi";

    private static final long PROMPT_TIMEOUT_SECONDS = 10 * 60L;
    private static final String MSG_NOT_STARTED = "Pi subprocess is not running";



    private final AgentProfile profile;
    private final Project project;
    private final AgentConfig config;
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private final ToolRegistry toolRegistry;
    private final int mcpPort;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<Process> process = new AtomicReference<>();
    private final AtomicReference<Thread> readerThread = new AtomicReference<>();
    private volatile java.io.OutputStream stdin;
    private final Object stdinLock = new Object();

    private final AtomicReference<TurnState> currentTurn = new AtomicReference<>();
    private final ConcurrentLinkedDeque<String> stderrTail = new ConcurrentLinkedDeque<>();
    private static final int STDERR_TAIL_MAX = 100;

    @Nullable
    private volatile String synthSessionId;
    @Nullable
    private volatile String currentModelId;
    private volatile boolean instructionsInjected;

    public PiCliClient(@NotNull AgentProfile profile,
                       @NotNull AgentConfig config,
                       @NotNull ToolRegistry toolRegistry,
                       @NotNull Project project,
                       int mcpPort) {
        this.profile = profile;
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.project = project;
        this.mcpPort = mcpPort;
    }

    @Override
    public String agentId() {
        return PROFILE_ID;
    }

    @Override
    public String displayName() {
        return profile.getDisplayName();
    }

    @Override
    public boolean isConnected() {
        Process p = process.get();
        return started.get() && p != null && p.isAlive();
    }

    @Override
    public @Nullable Path getSessionDirectory() {
        String base = project.getBasePath();
        return base == null ? null : Path.of(base, ".agent-work/pi");
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void start() throws IOException, InterruptedException {
        if (started.get()) return;

        String binary = resolveBinary();
        if (binary == null) {
            throw new IOException("Pi binary not found. Install with: npm install -g --ignore-scripts @earendil-works/pi-coding-agent");
        }

        String mcpUrl = mcpPort > 0 ? "http://127.0.0.1:" + mcpPort + "/mcp" : null;
        Path bridgePath = new PiMcpBridgeGenerator(project).generate(mcpUrl, BuildInfo.getVersion());

        List<PiCustomProvidersService.Entry> customProviders =
            PiCustomProvidersService.getInstance().getProviders();
        Path providersPath = new PiProviderExtensionGenerator(project).generate(customProviders);

        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        cmd.add("--mode");
        cmd.add("rpc");
        // Persist the session JSONL so the plugin can recover failure details from it
        // when Pi finishes a turn without emitting a usable message (provider 401, etc.).
        if (bridgePath != null) {
            cmd.add("--extension");
            cmd.add(bridgePath.toString());
        } else {
            // Without the bridge, only built-in Pi tools are available — disable them so
            // the agent does not silently bypass IDE permissions / hooks.
            cmd.add("--no-builtin-tools");
        }
        if (providersPath != null) {
            cmd.add("--extension");
            cmd.add(providersPath.toString());
        }
        String modelId = currentModelId;
        if ((modelId == null || modelId.isBlank()) && !customProviders.isEmpty()) {
            // Auto-select the first valid custom provider model so Pi starts with a usable default
            for (PiCustomProvidersService.Entry p : customProviders) {
                if (p.validate() == null) {
                    modelId = p.modelId;
                    currentModelId = modelId;
                    break;
                }
            }
        }
        if (profile.isSupportsModelFlag() && modelId != null && !modelId.isBlank()) {
            cmd.add("--model");
            cmd.add(modelId);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        String cwd = project.getBasePath();
        if (cwd != null) pb.directory(new java.io.File(cwd));

        Map<String, String> env = pb.environment();
        for (Map.Entry<String, String> e : ShellEnvironment.getEnvironment().entrySet()) {
            env.putIfAbsent(e.getKey(), e.getValue());
        }
        if (mcpUrl != null) env.put("AGENTBRIDGE_MCP_URL", mcpUrl);
        // Export each custom-provider API key as the env var its registered config reads.
        for (Map.Entry<String, String> e : PiProviderExtensionGenerator.buildEnv(customProviders).entrySet()) {
            env.put(e.getKey(), e.getValue());
        }
        env.put("CI", "true"); // ensure non-interactive behaviour from the subprocess
        env.putIfAbsent("PI_OFFLINE", "0");

        Process proc = pb.start();
        process.set(proc);
        stdin = proc.getOutputStream();
        started.set(true);
        synthSessionId = null;

        Thread stderrThread = new Thread(() -> drainStderr(proc), "pi-rpc-stderr-" + proc.pid());
        stderrThread.setDaemon(true);
        stderrThread.start();

        Thread stdoutThread = new Thread(() -> readEvents(proc), "pi-rpc-stdout-" + proc.pid());
        stdoutThread.setDaemon(true);
        stdoutThread.start();
        readerThread.set(stdoutThread);

        LOG.info("[pi] started: " + String.join(" ", cmd) + " (cwd=" + cwd + ", env: LLM_PROXY_ROUTER=" + (env.containsKey("LLM_PROXY_ROUTER") ? "***" : "NOT_SET") + ")");
    }

    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) return;
        Process p = process.getAndSet(null);
        stdin = null;
        if (p != null) {
            try {
                p.destroy();
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        Thread t = readerThread.getAndSet(null);
        if (t != null) t.interrupt();
        TurnState turn = currentTurn.getAndSet(null);
        if (turn != null) {
            turn.failure.compareAndSet(null, "Pi subprocess stopped");
            turn.done.countDown();
        }
        LOG.info("[pi] stopped");
    }

    // ── Session / prompt ───────────────────────────────────────────────────────

    @Override
    public String createSession(String cwd) {
        if (synthSessionId == null) synthSessionId = UUID.randomUUID().toString();
        instructionsInjected = false;
        return synthSessionId;
    }

    @Override
    public void cancelSession(String sessionId) {
        try {
            writeCommand(Map.of("type", "abort"));
        } catch (IOException e) {
            LOG.warn("[pi] abort command failed", e);
        }
        TurnState turn = currentTurn.get();
        if (turn != null) {
            turn.aborted.set(true);
        }
    }

    @Override
    public void dropCurrentSession() {
        synthSessionId = null;
    }

    @Override
    public PromptResponse sendPrompt(PromptRequest request, Consumer<SessionUpdate> onUpdate) throws IOException, InterruptedException {
        if (!isConnected()) throw new IOException(MSG_NOT_STARTED);

        String message = extractText(request.prompt());
        if (message.isBlank()) {
            return new PromptResponse("end_turn", null);
        }

        if (!instructionsInjected) {
            String systemPrompt = buildInstructions();
            if (!systemPrompt.isBlank()) {
                message = systemPrompt + "\n\n" + message;
            }
            instructionsInjected = true;
        }

        TurnState turn = new TurnState(onUpdate);
        TurnState previous = currentTurn.getAndSet(turn);
        if (previous != null) previous.done.countDown();

        Map<String, Object> cmd = new java.util.LinkedHashMap<>();
        cmd.put("type", "prompt");
        cmd.put("message", message);
        String modelId = request.modelId() != null ? request.modelId() : currentModelId;
        if (modelId != null && !modelId.isBlank()) {
            cmd.put("model", modelId);
        }
        LOG.info("[pi] sendPrompt: " + message.length() + " chars, model=" + modelId);
        writeCommand(cmd);

        boolean completed = turn.done.await(PROMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        currentTurn.compareAndSet(turn, null);

        if (!completed) {
            LOG.warn("[pi] prompt timed out after " + PROMPT_TIMEOUT_SECONDS + "s; aborting");
            cancelSession(request.sessionId());
            throw new IOException("Pi prompt timed out after " + PROMPT_TIMEOUT_SECONDS + "s");
        }
        if (turn.aborted.get()) return new PromptResponse("cancelled", null);
        if (turn.failure.get() != null) throw new IOException("Pi prompt failed: " + turn.failure.get());

        PromptResponse.TurnUsage usage = turn.inputTokens > 0 || turn.outputTokens > 0
            ? new PromptResponse.TurnUsage((long) turn.inputTokens, (long) turn.outputTokens, turn.costUsd)
            : null;
        return new PromptResponse(turn.stopReason != null ? turn.stopReason : "end_turn", usage);
    }

    // ── Models ─────────────────────────────────────────────────────────────────

    @Override
    public List<Model> getAvailableModels() {
        List<PiCustomProvidersService.Entry> providers =
            PiCustomProvidersService.getInstance().getProviders();
        if (providers.isEmpty()) {
            return Collections.emptyList();
        }
        List<Model> models = new ArrayList<>();
        for (PiCustomProvidersService.Entry p : providers) {
            if (p.validate() != null) continue;
            String label = (p.displayName != null && !p.displayName.isBlank() ? p.displayName : p.id)
                + " (" + (p.modelName != null && !p.modelName.isBlank() ? p.modelName : p.modelId) + ")";
            models.add(new Model(p.modelId, label, null, null));
        }
        return models;
    }

    @Override
    public void setModel(String sessionId, String modelId) {
        this.currentModelId = modelId;
        if (!isConnected()) return;
        try {
            Map<String, Object> cmd = new java.util.LinkedHashMap<>();
            cmd.put("type", "set_model");
            cmd.put("modelId", modelId);
            writeCommand(cmd);
        } catch (IOException e) {
            LOG.warn("[pi] set_model failed", e);
        }
    }

    // ── Event reader ───────────────────────────────────────────────────────────

    private void readEvents(@NotNull Process proc) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                try {
                    JsonObject ev = JsonParser.parseString(line).getAsJsonObject();
                    if (LOG.isDebugEnabled()) LOG.debug("[pi] <<< " + line);
                    handleEvent(ev);
                } catch (Exception e) {
                    LOG.warn("[pi] failed to parse event: " + line, e);
                }
            }
            LOG.info("[pi] stdout EOF");
        } catch (IOException e) {
            if (started.get()) LOG.warn("[pi] reader thread exited unexpectedly", e);
        } finally {
            // Wait briefly for the process to exit so we can capture the exit code and any
            // final stderr lines (e.g. "401 CreditsError" from the provider). Without this,
            // the turn would just fail with a generic "exited mid-turn" message.
            int exitCode = -1;
            try {
                if (proc.waitFor(2, TimeUnit.SECONDS)) exitCode = proc.exitValue();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            String tail = lastStderrLine();
            String reason = exitCode == 0
                ? "Pi subprocess exited mid-turn"
                : "Pi subprocess exited (code " + exitCode + ")"
                  + (tail != null ? ": " + tail : "");

            // Mark the client as not connected so the next prompt triggers a fresh start
            // instead of writing into a dead pipe.
            started.set(false);
            stdin = null;

            TurnState t = currentTurn.getAndSet(null);
            if (t != null) {
                t.failure.compareAndSet(null, reason);
                t.done.countDown();
            }
            if (exitCode != 0 && exitCode != -1) LOG.warn("[pi] " + reason);
        }
    }

    @Nullable
    private String lastStderrLine() {
        String last = null;
        for (String s : stderrTail) last = s;
        return last;
    }

    /**
     * Reads the most recently written line from Pi's session JSONL log and returns a
     * short failure hint when present. Pi writes error details into the assistant
     * message's {@code errorMessage} field on provider failures, but emits no
     * dedicated RPC event for them; reading the JSONL is the only way to surface
     * "401 No payment method", "model not found", etc. when {@code agent_end} arrives
     * with no content.
     */
    @Nullable
    private String readLatestSessionFailure() {
        try {
            Path sessionsRoot = resolveSessionDirectory();
            if (sessionsRoot == null || !Files.isDirectory(sessionsRoot)) return null;
            String cwd = project.getBasePath();
            if (cwd == null) return null;
            // Pi encodes the cwd as "--<slashes-replaced-by-dashes>--".
            String encoded = "--" + cwd.replace("/", "-").replace("\\", "-") + "--";
            Path bucket = sessionsRoot.resolve(encoded);
            if (!Files.isDirectory(bucket)) return null;

            Path latest = null;
            long latestModified = Long.MIN_VALUE;
            try (var stream = Files.list(bucket)) {
                for (Path p : stream.toList()) {
                    if (!p.getFileName().toString().endsWith(".jsonl")) continue;
                    long m = Files.getLastModifiedTime(p).toMillis();
                    if (m > latestModified) {
                        latestModified = m;
                        latest = p;
                    }
                }
            }
            if (latest == null) return null;

            // Scan the last ~50 lines for an entry that carries an errorMessage.
            List<String> lines = Files.readAllLines(latest, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 50);
            for (int i = lines.size() - 1; i >= from; i--) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                try {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    String err = optionalString(o, "errorMessage");
                    if (err != null && !err.isBlank()) return truncate(err, 280);
                } catch (Exception ignored) {
                    // not JSON, skip
                }
            }
            return null;
        } catch (Exception e) {
            LOG.debug("[pi] readLatestSessionFailure failed", e);
            return null;
        }
    }

    @NotNull
    private static String truncate(@NotNull String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    @Nullable
    private static Path resolveSessionDirectory() {
        String override = System.getenv("PI_CODING_AGENT_SESSION_DIR");
        if (override != null && !override.isBlank()) return Path.of(override);
        String configRoot = System.getenv("PI_CODING_AGENT_DIR");
        if (configRoot != null && !configRoot.isBlank()) return Path.of(configRoot, "sessions");
        String home = System.getProperty("user.home");
        if (home == null) return null;
        return Path.of(home, ".pi", "agent", "sessions");
    }

    private void handleEvent(@NotNull JsonObject ev) {
        String type = ev.has("type") ? ev.get("type").getAsString() : "";
        TurnState turn = currentTurn.get();
        if (!"message_update".equals(type) && !"tool_execution_update".equals(type)) {
            LOG.info("[pi] event: " + type);
        }
        switch (type) {
            case "response" -> {
                String success = optionalString(ev, "success");
                String command = optionalString(ev, "command");
                if (Boolean.parseBoolean(success) || "true".equalsIgnoreCase(success)) {
                    LOG.info("[pi] command response: " + command + " ok");
                } else {
                    String err = optionalString(ev, "error");
                    LOG.warn("[pi] command response: " + command + " failed: " + err);
                    if (turn != null && "prompt".equals(command)) {
                        turn.failure.compareAndSet(null, err != null ? err : "prompt rejected");
                        turn.done.countDown();
                    }
                }
            }
            case "agent_start", "turn_start", "message_start", "message_end", "auto_retry_start", "auto_retry_end" -> {
                // lifecycle events — no action needed
            }
            case "message_update" -> {
                if (turn == null) return;
                JsonObject delta = optionalObject(ev, "assistantMessageEvent");
                if (delta == null) return;
                String dtype = delta.has("type") ? delta.get("type").getAsString() : "";
                switch (dtype) {
                    case "text_delta" -> emitText(turn, optionalString(delta, "delta"));
                    case "thinking_delta" -> emitThinking(turn, optionalString(delta, "delta"));
                    case "done" -> turn.stopReason = mapStopReason(optionalString(delta, "reason"));
                    case "error" -> {
                        String reason = optionalString(delta, "reason");
                        if ("aborted".equals(reason)) turn.aborted.set(true);
                        else turn.failure.compareAndSet(null, "error: " + reason);
                    }
                    default -> { /* toolcall_* deltas covered by tool_execution events */ }
                }
            }
            case "tool_execution_start" -> {
                if (turn == null) return;
                String toolCallId = optionalString(ev, "toolCallId");
                String toolName = optionalString(ev, "toolName");
                if (toolCallId == null || toolName == null) return;
                JsonObject args = optionalObject(ev, "args");
                turn.emit(new SessionUpdate.ToolCall(
                    toolCallId,
                    toolName,
                    toolName,
                    SessionUpdate.ToolKind.OTHER,
                    args == null ? null : args.toString(),
                    null, null, null, null, null
                ));
            }
            case "tool_execution_end" -> {
                if (turn == null) return;
                String toolCallId = optionalString(ev, "toolCallId");
                if (toolCallId == null) return;
                boolean isError = ev.has("isError") && ev.get("isError").getAsBoolean();
                String resultText = extractToolResultText(ev.get("result"));
                turn.emit(new SessionUpdate.ToolCallUpdate(
                    toolCallId,
                    isError ? SessionUpdate.ToolCallStatus.FAILED : SessionUpdate.ToolCallStatus.COMPLETED,
                    isError ? null : resultText,
                    isError ? resultText : null,
                    null
                ));
            }
            case "turn_end" -> {
                if (turn == null) return;
                JsonObject usage = extractUsage(ev);
                if (usage != null) accumulateUsage(turn, usage);
            }
            case "agent_end" -> {
                if (turn == null) return;
                // Pi sends willRetry=true when it will auto-retry (e.g. empty provider response).
                // Do NOT countdown the latch in that case — wait for the retry's agent_end.
                boolean willRetry = ev.has("willRetry") && ev.get("willRetry").getAsBoolean();
                if (willRetry) {
                    LOG.info("[pi] agent_end with willRetry=true — waiting for retry");
                    return;
                }
                if (turn.stopReason == null) turn.stopReason = "end_turn";
                if (!turn.hasContent) {
                    String hint = readLatestSessionFailure();
                    String tail = lastStderrLine();
                    String reason = "Pi finished the turn without producing any assistant content";
                    if (hint != null) reason += ": " + hint;
                    else if (tail != null && !tail.startsWith("[agentbridge-providers]")) reason += ": " + tail;
                    else reason += " (no error reported by Pi — check provider credits/model selection)";
                    turn.failure.compareAndSet(null, reason);
                }
                turn.done.countDown();
            }
            case "extension_error" -> {
                String extPath = optionalString(ev, "extensionPath");
                String evName = optionalString(ev, "event");
                String err = optionalString(ev, "error");
                LOG.warn("[pi] extension_error in " + evName + " @ " + extPath + ": " + err);
                if (turn != null) {
                    turn.failure.compareAndSet(null, "Extension error: " + err);
                }
            }
            default -> { /* unknown event — log only at debug */ }
        }
    }

    private void emitText(@NotNull TurnState turn, @Nullable String text) {
        if (text == null || text.isEmpty()) return;
        turn.hasContent = true;
        turn.emit(new SessionUpdate.AgentMessageChunk(List.of(new ContentBlock.Text(text))));
    }

    private void emitThinking(@NotNull TurnState turn, @Nullable String text) {
        if (text == null || text.isEmpty()) return;
        turn.hasContent = true;
        turn.emit(new SessionUpdate.AgentThoughtChunk(List.of(new ContentBlock.Thinking(text))));
    }

    @NotNull
    private static String mapStopReason(@Nullable String reason) {
        if (reason == null) return "end_turn";
        return switch (reason.toLowerCase(Locale.ROOT)) {
            case "length" -> "max_tokens";
            case "tooluse", "tool_use" -> "tool_use";
            case "stop" -> "end_turn";
            default -> reason;
        };
    }

    @Nullable
    private static String extractToolResultText(@Nullable JsonElement result) {
        if (result == null || !result.isJsonObject()) return null;
        JsonElement content = result.getAsJsonObject().get("content");
        if (content == null || !content.isJsonArray()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : content.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (o.has("text")) sb.append(o.get("text").getAsString());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    @Nullable
    private static JsonObject extractUsage(@NotNull JsonObject turnEnd) {
        JsonObject message = optionalObject(turnEnd, "message");
        if (message == null) return null;
        return optionalObject(message, "usage");
    }

    private static void accumulateUsage(@NotNull TurnState turn, @NotNull JsonObject usage) {
        turn.inputTokens += optionalInt(usage, "input");
        turn.outputTokens += optionalInt(usage, "output");
        JsonObject cost = optionalObject(usage, "cost");
        if (cost != null && cost.has("total")) {
            try {
                turn.costUsd = (turn.costUsd == null ? 0.0 : turn.costUsd) + cost.get("total").getAsDouble();
            } catch (Exception ignored) { /* swallow malformed */ }
        }
    }

    private static int optionalInt(@NotNull JsonObject o, @NotNull String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : 0;
    }

    @Nullable
    private static String optionalString(@NotNull JsonObject o, @NotNull String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    @Nullable
    private static JsonObject optionalObject(@NotNull JsonObject o, @NotNull String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    // ── Writer ─────────────────────────────────────────────────────────────────

    private void writeCommand(@NotNull Map<String, Object> command) throws IOException {
        Process p = process.get();
        if (p == null || !p.isAlive()) throw new IOException(MSG_NOT_STARTED);
        java.io.OutputStream out = stdin;
        if (out == null) throw new IOException(MSG_NOT_STARTED);
        JsonObject json = mapToJson(command);
        byte[] bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (stdinLock) {
            try {
                out.write(bytes);
                out.flush();
            } catch (IOException ioe) {
                LOG.warn("[pi] writeCommand failed (" + command.get("type") + "): " + ioe.getMessage());
                throw ioe;
            }
        }
        if (LOG.isDebugEnabled()) LOG.debug("[pi] >>> " + json);
    }

    @NotNull
    private static JsonObject mapToJson(@NotNull Map<String, Object> m) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object v = e.getValue();
            switch (v) {
                case null -> o.add(e.getKey(), com.google.gson.JsonNull.INSTANCE);
                case String s -> o.addProperty(e.getKey(), s);
                case Number n -> o.addProperty(e.getKey(), n);
                case Boolean b -> o.addProperty(e.getKey(), b);
                case Map<?, ?> map -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = (Map<String, Object>) map;
                    o.add(e.getKey(), mapToJson(nested));
                }
                case List<?> list -> {
                    JsonArray a = new JsonArray();
                    for (Object item : list) {
                        if (item instanceof String s) a.add(s);
                        else if (item instanceof Number n) a.add(n);
                        else if (item instanceof Boolean b) a.add(b);
                        else a.add(String.valueOf(item));
                    }
                    o.add(e.getKey(), a);
                }
                default -> o.addProperty(e.getKey(), v.toString());
            }
        }
        return o;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    @NotNull
    private static String extractText(@Nullable List<ContentBlock> prompt) {
        if (prompt == null || prompt.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : prompt) {
            if (block instanceof ContentBlock.Text(var text)) sb.append(text);
        }
        return sb.toString();
    }

    @Nullable
    private String resolveBinary() {
        AcpClientBinaryResolver resolver = new AcpClientBinaryResolver(PROFILE_ID, profile.getBinaryName());
        String resolved = resolver.resolve();
        if (resolved != null && !resolved.contains("/") && !resolved.contains("\\")) {
            resolved = BinaryDetector.findBinaryPath(resolved);
        }
        return resolved;
    }

    private void drainStderr(@NotNull Process proc) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                stderrTail.add(line);
                while (stderrTail.size() > STDERR_TAIL_MAX) stderrTail.pollFirst();
                LOG.info("[pi-stderr] " + line);
            }
        } catch (IOException e) {
            if (started.get()) LOG.warn("[pi] stderr reader failed", e);
        }
    }

    // ── System instructions ─────────────────────────────────────────────────────

    private static String buildInstructions() {
        return com.github.catatafishen.agentbridge.settings.StartupInstructionsSettings
            .getInstance().getInstructions();
    }

    // ── Turn state ─────────────────────────────────────────────────────────────

    private static final class TurnState {
        final Consumer<SessionUpdate> onUpdate;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean aborted = new AtomicBoolean(false);
        final AtomicReference<String> failure = new AtomicReference<>();
        volatile String stopReason;
        int inputTokens;
        int outputTokens;
        Double costUsd;
        volatile boolean hasContent;
        volatile boolean messageStarted;

        TurnState(Consumer<SessionUpdate> onUpdate) {
            this.onUpdate = onUpdate;
        }

        void emit(@NotNull SessionUpdate update) {
            try {
                onUpdate.accept(update);
            } catch (Exception e) {
                LOG.warn("[pi] onUpdate consumer threw", e);
            }
        }
    }
}
