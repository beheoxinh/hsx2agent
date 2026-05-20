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

        if (records.isEmpty()) {
            return "No live tool calls.";
        }

        StringBuilder sb = new StringBuilder("--- Live Tool Call Records ---\n");
        for (Map.Entry<String, ToolCallRecord> entry : records.entrySet()) {
            ToolCallRecord r = entry.getValue();
            sb.append("ID: ").append(r.getRecordId())
                .append(", Status: ").append(r.getState())
                .append(", Tool: ").append(r.getEffectiveToolName())
                .append("\n  Args: ").append(r.getMcpArgs() != null ? r.getMcpArgs() : (r.getAcpArgs() != null ? r.getAcpArgs() : "null"))
                .append("\n");
        }
        sb.append("--- End Live Tool Call Records ---");

        // Gửi thông báo Notification tới IDE để mày không cần chủ động gọi
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("AgentBridge Notifications")
            .createNotification("Agent Tool Error", sb.toString(), com.intellij.notification.NotificationType.WARNING)
            .notify(project);

        return sb.toString();
    }
}
