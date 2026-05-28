package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.McpErrorCode;
import com.github.catatafishen.agentbridge.psi.ToolError;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Renames a file in place without moving it to a different directory.
 */
@SuppressWarnings("java:S112")
public final class RenameFileTool extends FileTool {

    private static final String PARAM_NEW_NAME = "new_name";

    public RenameFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "rename_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Rename File";
    }

    @Override
    public @NotNull String description() {
        return "Rename a file in place without moving it. Does NOT update import statements or references — "
            + "use refactor(operation='rename') for reference-aware renames.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Rename {path} → {new_name}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("path", TYPE_STRING, "Path to the file to rename"),
            Param.required(PARAM_NEW_NAME, TYPE_STRING, "New file name (just the filename, not a full path)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("path") || !args.has(PARAM_NEW_NAME))
            return ToolError.of(McpErrorCode.MISSING_PARAM, "'path' and 'new_name' parameters are required");
        String pathStr = args.get("path").getAsString();
        String newName = args.get(PARAM_NEW_NAME).getAsString();

        // External files: use java.nio.file directly to avoid VFS Non-Project dialog.
        if (ToolUtils.isOutsideProject(project, pathStr)) {
            return renameFileExternal(pathStr, newName);
        }

        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) vf = refreshAndFindVirtualFile(pathStr);
        if (vf == null) return ToolError.of(McpErrorCode.FILE_NOT_FOUND, pathStr,
            "Check the path and try again. Use find_file to search by name.");

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        performRenameOnEdt(vf, newName, resultFuture);
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    /**
     * Renames a file outside the project root using direct file I/O.
     */
    private String renameFileExternal(String pathStr, String newName) {
        Path absPath = ToolUtils.resolveAbsolutePath(project, pathStr);
        if (absPath == null || !Files.exists(absPath)) {
            return ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;
        }
        try {
            Path parent = absPath.getParent();
            Path newPath = parent != null ? parent.resolve(newName) : Path.of(newName);
            Files.move(absPath, newPath);
            return "Renamed " + pathStr + " to " + newPath + " [outside project — direct I/O]";
        } catch (IOException e) {
            return "Error renaming file: " + e.getMessage();
        }
    }

    private void performRenameOnEdt(VirtualFile vf, String newName, CompletableFuture<String> resultFuture) {
        String oldName = vf.getName();
        RenameFileTool requestor = this;
        EdtUtil.invokeLater(() ->
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                        project,
                        () -> {
                            try {
                                vf.rename(requestor, newName);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        },
                        "Rename File: " + oldName + " to " + newName,
                        null
                    );
                    resultFuture.complete("Renamed " + oldName + " to " + newName);
                } catch (Exception e) {
                    resultFuture.complete("Error renaming file: " + e.getMessage());
                }
            })
        );
    }
}
