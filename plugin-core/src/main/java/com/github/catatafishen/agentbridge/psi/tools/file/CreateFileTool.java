package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.CodeChangeTracker;
import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.FileAccessTracker;
import com.github.catatafishen.agentbridge.ui.renderers.WriteFileRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("java:S112")
public final class CreateFileTool extends FileTool {

    private static final String FORMAT_CHARS_SUFFIX = " chars)";
    private static final String PARAM_CONTENT = "content";

    public CreateFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "create_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Create File";
    }

    @Override
    public @NotNull String description() {
        return "Create a new file at the given path. File must not already exist — use write_file to overwrite existing files. " +
            "Does NOT update references. Use refactor(operation='rename') for reference-aware renames.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Create {path}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("path", TYPE_STRING, "Path for the new file (absolute or project-relative). File must not already exist"),
            Param.required(PARAM_CONTENT, TYPE_STRING, "Content to write to the file")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return WriteFileRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("path") || !args.has(PARAM_CONTENT)) {
            return "Error: 'path' and 'content' parameters are required";
        }
        String pathStr = args.get("path").getAsString();
        String guardError = guardExternalWrite(pathStr);
        if (guardError != null) return guardError;
        String content = args.get(PARAM_CONTENT).getAsString();

        String basePath = project.getBasePath();
        Path pathObj = Path.of(pathStr);
        Path filePath;
        if (pathObj.isAbsolute()) {
            filePath = pathObj;
        } else if (basePath != null) {
            filePath = Path.of(basePath, pathStr);
        } else {
            return "Error: Cannot resolve relative path without project base path";
        }

        if (Files.exists(filePath)) {
            return "Error: File already exists: " + pathStr +
                ". Use edit_text to modify existing files.";
        }

        int lineCount = content.split("\n", -1).length;

        // Use VFS write action to avoid File Cache Conflict dialog.
        // Writing through WriteAction + getOutputStream lets IntelliJ know about changes
        // through its own VFS, preventing the file watcher from detecting a disk change
        // on a file that may already be known to the VFS cache.
        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        Path parentDir = filePath.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }
        String finalFullPath = filePath.toString();
        EdtUtil.invokeAndWait(() -> com.intellij.openapi.application.WriteAction.run(() -> {
            try {
                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(finalFullPath);
                if (vf == null) {
                    // File not yet in VFS — create via direct I/O then refresh
                    try {
                        Files.writeString(filePath, content, StandardCharsets.UTF_8);
                    } catch (java.io.IOException e) {
                        resultFuture.complete("Error creating file: " + e.getMessage());
                        return;
                    }
                    vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(finalFullPath);
                } else {
                    try (var os = vf.getOutputStream(CreateFileTool.this)) {
                        os.write(content.getBytes(StandardCharsets.UTF_8));
                    } catch (java.io.IOException e) {
                        resultFuture.complete("Error writing file: " + e.getMessage());
                        return;
                    }
                }
                if (vf != null) {
                    com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                }
                resultFuture.complete("✓ Created file: " + pathStr + " (" + content.length() + FORMAT_CHARS_SUFFIX);
            } catch (Exception e) {
                resultFuture.complete("File created but VFS refresh failed: " + e.getMessage());
            }
        }));

        String result = resultFuture.get(10, TimeUnit.SECONDS);
        CodeChangeTracker.recordChange(lineCount, 0);
        notifyFileCreated(project, pathStr);
        followFileIfEnabled(project, pathStr, 1, lineCount, HIGHLIGHT_EDIT, agentLabel(project) + " created");
        FileAccessTracker.recordWrite(project, pathStr);
        com.github.catatafishen.agentbridge.psi.FileConflictAutoResolver.recordAgentWrite();
        return result;
    }
}
