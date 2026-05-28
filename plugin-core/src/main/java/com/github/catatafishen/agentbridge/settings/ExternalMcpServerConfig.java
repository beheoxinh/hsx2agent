package com.github.catatafishen.agentbridge.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for an external MCP server that should be injected alongside
 * the built-in AgentBridge MCP server.
 *
 * <p>Supports two transport modes:
 * <ul>
 *   <li><b>STDIO</b> — spawn a subprocess with {@code command} + {@code args}</li>
 *   <li><b>HTTP</b> — connect to an already-running server at {@code url}</li>
 * </ul>
 */
public final class ExternalMcpServerConfig {

    public enum Transport {
        STDIO, HTTP
    }

    private String name;
    private Transport transport;
    private String command;
    private List<String> args;
    private String url;
    private boolean enabled;

    public ExternalMcpServerConfig() {
        this.name = "";
        this.transport = Transport.STDIO;
        this.command = "";
        this.args = new ArrayList<>();
        this.url = "";
        this.enabled = true;
    }

    public ExternalMcpServerConfig(@NotNull String name, @NotNull Transport transport,
                                   @NotNull String command, @NotNull List<String> args,
                                   @NotNull String url, boolean enabled) {
        this.name = name;
        this.transport = transport;
        this.command = command;
        this.args = new ArrayList<>(args);
        this.url = url;
        this.enabled = enabled;
    }

    public @NotNull String getName() { return name; }
    public void setName(@NotNull String name) { this.name = name; }

    public @NotNull Transport getTransport() { return transport; }
    public void setTransport(@NotNull Transport transport) { this.transport = transport; }

    public @NotNull String getCommand() { return command; }
    public void setCommand(@NotNull String command) { this.command = command; }

    public @NotNull List<String> getArgs() { return args; }
    public void setArgs(@NotNull List<String> args) { this.args = new ArrayList<>(args); }

    public @NotNull String getUrl() { return url; }
    public void setUrl(@NotNull String url) { this.url = url; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * Returns a tool prefix derived from the server name — e.g. "playwright" → "playwright_".
     */
    public @NotNull String getToolPrefix() {
        return name.isEmpty() ? "_" : name.replaceAll("[^a-zA-Z0-9]", "_") + "_";
    }

    public @NotNull JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        obj.addProperty("transport", transport.name().toLowerCase());
        obj.addProperty("command", command);
        obj.add("args", toJsonArray(args));
        obj.addProperty("url", url);
        obj.addProperty("enabled", enabled);
        return obj;
    }

    public static @NotNull ExternalMcpServerConfig fromJson(@NotNull JsonObject obj) {
        ExternalMcpServerConfig config = new ExternalMcpServerConfig();
        config.setName(getString(obj, "name", ""));
        config.setTransport(Transport.valueOf(getString(obj, "transport", "stdio").toUpperCase()));
        config.setCommand(getString(obj, "command", ""));
        config.setArgs(fromJsonArray(obj, "args"));
        config.setUrl(getString(obj, "url", ""));
        config.setEnabled(getBool(obj, "enabled", true));
        return config;
    }

    private static String getString(JsonObject obj, String key, String def) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : def;
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsBoolean() : def;
    }

    private static JsonElement toJsonArray(List<String> list) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (String s : list) arr.add(new JsonPrimitive(s));
        return arr;
    }

    private static List<String> fromJsonArray(JsonObject obj, String key) {
        List<String> result = new ArrayList<>();
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e.isJsonPrimitive()) result.add(e.getAsString());
            }
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExternalMcpServerConfig that)) return false;
        return enabled == that.enabled && Objects.equals(name, that.name)
            && transport == that.transport && Objects.equals(command, that.command)
            && Objects.equals(args, that.args) && Objects.equals(url, that.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, transport, command, args, url, enabled);
    }
}
