package com.github.catatafishen.agentbridge.agent.claude;

import com.github.catatafishen.agentbridge.bridge.AgentConfig;
import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.Mockito.mock;

/**
 * Behavioral tests for Claude system/init event handling.
 */
class ClaudeCliClientSystemEventTest {

    private ClaudeCliClient client;
    private Method handleStreamEvent;
    private Field cliSessionIdsField;
    private Field availableSlashCommandsField;

    @BeforeEach
    void setUp() throws Exception {
        AgentProfile profile = ClaudeCliClient.createDefaultProfile();
        AgentConfig config = mock(AgentConfig.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        Project project = mock(Project.class);
        client = new ClaudeCliClient(profile, config, registry, project, 0);

        handleStreamEvent = ClaudeCliClient.class.getDeclaredMethod(
            "handleStreamEvent",
            String.class,
            JsonObject.class,
            String.class,
            OutputStream.class,
            java.util.function.Consumer.class,
            java.util.function.Consumer.class
        );
        handleStreamEvent.setAccessible(true);

        cliSessionIdsField = ClaudeCliClient.class.getDeclaredField("cliSessionIds");
        cliSessionIdsField.setAccessible(true);
        availableSlashCommandsField = ClaudeCliClient.class.getDeclaredField("availableSlashCommands");
        availableSlashCommandsField.setAccessible(true);
    }

    @Test
    void buildToolRestrictionArgs_usesDisallowedToolsDenylist() {
        List<String> args = ClaudeCliClient.buildToolRestrictionArgs(true);
        assertEquals(List.of("--disallowedTools", String.join(",", ClaudeCliClient.DISABLED_BUILT_IN_TOOLS)), args);
    }

    @Test
    void buildToolRestrictionArgs_emptyWhenBuiltInToolsNotExcluded() {
        assertEquals(List.of(), ClaudeCliClient.buildToolRestrictionArgs(false));
    }

    @Test
    void systemInitEvent_parsesSlashCommandsWithoutOverwritingSessionId() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, String> cliSessionIds = (Map<String, String>) cliSessionIdsField.get(client);
        cliSessionIds.put("plugin-session-1", "old-cli-session");

        JsonObject event = new JsonObject();
        event.addProperty("type", "system");
        event.addProperty("subtype", "init");
        event.addProperty("session_id", "new-cli-session-that-must-be-ignored");
        JsonArray slashCommands = new JsonArray();
        slashCommands.add("/review");
        slashCommands.add("deploy");
        event.add("slash_commands", slashCommands);

        String stopReason = (String) handleStreamEvent.invoke(
            client,
            "plugin-session-1",
            event,
            "end_turn",
            new ByteArrayOutputStream(),
            null,
            null
        );

        assertEquals("end_turn", stopReason);
        assertEquals("old-cli-session", cliSessionIds.get("plugin-session-1"),
            "system/init must not overwrite the last known-good CLI session id");

        @SuppressWarnings("unchecked")
        List<String> commands = (List<String>) availableSlashCommandsField.get(client);
        assertIterableEquals(List.of("/review", "/deploy"), commands,
            "slash_commands from init event should populate Claude slash command autocomplete");
    }
}
