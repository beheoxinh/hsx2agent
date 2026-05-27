package com.github.catatafishen.agentbridge.psi.tools.meta;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ReportSubagentStreamTool extends Tool {

    public ReportSubagentStreamTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "report_subagent_stream";
    }

    @Override
    public @NotNull String displayName() {
        return "Report Subagent Stream";
    }

    @Override
    public @NotNull String description() {
        return "Reports real-time thinking and text from a subagent to the UI. If you are a subagent, call this frequently so the user can see your progress.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.OTHER;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.OTHER;
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("subagent_id", TYPE_STRING, "The ID of the active subagent"),
            Param.required("is_thinking", TYPE_BOOLEAN, "True if this is an internal thought/plan, false if it is final text output"),
            Param.required("content", TYPE_STRING, "The text or thinking content to stream to the UI")
        );
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        String subagentId = args.get("subagent_id").getAsString();
        boolean isThinking = args.get("is_thinking").getAsBoolean();
        String content = args.get("content").getAsString();

        project.getMessageBus().syncPublisher(com.github.catatafishen.agentbridge.services.SubagentStreamListener.TOPIC)
            .onStreamChunk(subagentId, isThinking, content);

        return "Stream reported successfully";
    }
}
