package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * Hides a tool window by its ID (e.g., 'Git', 'Project', 'Run').
 * Useful for cleaning up the workspace after an agent-initiated inspection.
 */
public final class HideToolWindowTool extends Tool {

    private static final String PARAM_ID = "id";

    public HideToolWindowTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull com.github.catatafishen.agentbridge.services.ToolRegistry.Category category() {
        return com.github.catatafishen.agentbridge.services.ToolRegistry.Category.INFRASTRUCTURE;
    }

    @Override
    public @NotNull String id() {
        return "hide_tool_window";
    }

    @Override
    public @NotNull String displayName() {
        return "Hide Tool Window";
    }

    @Override
    public @NotNull String description() {
        return "Hide an IntelliJ tool window by its ID (e.g. 'Git', 'Project', 'Run', 'Terminal'). " +
            "Use this to clean up the UI after you are done with a follow-along inspection.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ; // Does not modify project state, only IDE UI
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_ID, TYPE_STRING, "Tool window ID to hide (e.g. 'Git', 'Project', 'Run', 'Terminal')")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String twId = args.get(PARAM_ID).getAsString();
        EdtUtil.invokeLater(() -> {
            ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(twId);
            if (tw != null && tw.isVisible()) {
                tw.hide(null);
            }
        });
        return "Tool window '" + twId + "' hide requested.";
    }
}
