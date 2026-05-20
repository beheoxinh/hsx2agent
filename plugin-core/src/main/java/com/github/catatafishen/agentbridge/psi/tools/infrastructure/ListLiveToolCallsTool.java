package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.tools.project.ProjectTool;
import com.github.catatafishen.agentbridge.services.ToolCallRecord;
import com.github.catatafishen.agentbridge.services.ToolCallTracker;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Lists all currently live tool calls in the ToolCallTracker.
 */
public final class ListLiveToolCallsTool extends ProjectTool {

    private static final Logger LOG = Logger.getInstance(ListLiveToolCallsTool.class);

    public ListLiveToolCallsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "list_live_tool_calls";
    }

    @Override
    public @NotNull String displayName() {
        return "List Live Tool Calls";
    }

    @Override
    public @NotNull String description() {
        return "Prints current live tool call records to the IDE logs";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        ToolCallTracker tracker = ToolCallTracker.getInstance(project);
        Map<String, ToolCallRecord> records = tracker.getLiveRecordsSnapshot();

        System.out.println("--- Live Tool Call Records ---");
        for (Map.Entry<String, ToolCallRecord> entry : records.entrySet()) {
            ToolCallRecord r = entry.getValue();
            System.out.println("ID: " + r.getRecordId() +
                ", Status: " + r.getState() +
                ", ACP Client: " + r.getAcpClientId() +
                ", MCP Tool: " + r.getEffectiveToolName());
        }
        System.out.println("--- End Live Tool Call Records ---");

        return "Printed " + records.size() + " live records to IDE console.";
    }
}
