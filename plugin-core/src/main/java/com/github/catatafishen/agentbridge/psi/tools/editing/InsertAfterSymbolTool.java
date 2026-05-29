package com.github.catatafishen.agentbridge.psi.tools.editing;

import com.github.catatafishen.agentbridge.psi.CodeChangeTracker;
import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.FileAccessTracker;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.file.FileTool;
import com.github.catatafishen.agentbridge.ui.renderers.ReplaceSymbolRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Inserts content after a symbol definition.
 * Auto-formats and optimizes imports immediately on every call.
 */
public final class InsertAfterSymbolTool extends EditingTool {

    private static final String PARAM_CONTENT = "content";

    public InsertAfterSymbolTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "insert_after_symbol";
    }

    @Override
    public @NotNull String displayName() {
        return "Insert After Symbol";
    }

    @Override
    public @NotNull String description() {
        return "Insert content after a symbol definition. PSI-aware — finds symbols by name, no line numbers needed. "
            + "Auto-formats and optimizes imports immediately. Use for adding new methods after an existing one.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Insert after {symbol} in {file}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("file", TYPE_STRING, "Absolute or project-relative path to the file containing the symbol"),
            Param.required("symbol", TYPE_STRING, "Name of the symbol to insert after"),
            Param.required(PARAM_CONTENT, TYPE_STRING, "The content to insert after the symbol"),
            Param.optional("line", TYPE_INTEGER, "Optional: line number hint to disambiguate if multiple symbols share the same name")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ReplaceSymbolRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String error = validateArgs(args, PARAM_CONTENT);
        if (error != null) return error;

        String pathStr = args.get(PARAM_FILE).getAsString();
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String content = args.get(PARAM_CONTENT).getAsString();
        Integer lineHint = args.has(PARAM_LINE) ? args.get(PARAM_LINE).getAsInt() : null;

        // Files outside the project bypass VFS/PSI to avoid IntelliJ's
        // Non-Project Files Protection dialog (blocks EDT on Wayland).
        if (ToolUtils.isOutsideProject(project, pathStr)) {
            return insertAfterSymbolExternal(pathStr, symbolName, content, lineHint);
        }

        CompletableFuture<String> result = new CompletableFuture<>();
        int[] endLine = new int[1];

        EdtUtil.invokeLater(() -> performInsertAfter(pathStr, symbolName, content, lineHint, endLine, result));

        String resultStr = result.get(15, TimeUnit.SECONDS);
        if (!resultStr.startsWith(ToolUtils.ERROR_PREFIX) && !resultStr.startsWith("Symbol")) {
            int insertedLines = (int) content.chars().filter(c -> c == '\n').count() + 1;
            CodeChangeTracker.recordChange(insertedLines, 0);
            int insertStart = endLine[0] + 1;
            FileTool.followFileIfEnabled(project, pathStr, insertStart, insertStart + insertedLines - 1,
                FileTool.HIGHLIGHT_EDIT, "inserting after " + symbolName);
            FileAccessTracker.recordWrite(project, pathStr);
            com.github.catatafishen.agentbridge.psi.FileConflictAutoResolver.recordAgentWrite();
        }
        return resultStr;
    }

    private void performInsertAfter(String pathStr, String symbolName, String content,
                                    Integer lineHint, int[] endLine, CompletableFuture<String> result) {
        try {
            SymbolLocation loc = resolveSymbol(pathStr, symbolName, lineHint);
            if (loc == null) {
                result.complete(symbolNotFoundMessage(pathStr, symbolName, lineHint));
                return;
            }
            endLine[0] = loc.endLine();

            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) {
                result.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                return;
            }
            Document doc = com.intellij.openapi.application.ReadAction.compute(() ->
                FileDocumentManager.getInstance().getDocument(vf)
            );
            if (doc == null) {
                result.complete(ERROR_CANNOT_OPEN_DOC + pathStr);
                return;
            }

            String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
            if (!normalized.endsWith("\n")) {
                normalized += "\n";
            }
            int offset = doc.getLineEndOffset(loc.endLine() - 1);
            if (offset < doc.getTextLength() && doc.getCharsSequence().charAt(offset) == '\n') {
                offset++;
            }
            final String fContent = normalized;
            final int fOffset = offset;

            FileTool.notifyBeforeEdit(project, vf, doc);
            try {
                WriteCommandAction.runWriteCommandAction(
                    project, "Insert After Symbol", null,
                    () -> doc.insertString(fOffset, fContent));
            } finally {
                FileTool.notifyEditComplete();
            }

            PsiDocumentManager.getInstance(project).commitDocument(doc);
            // Defer formatting to avoid AWT event dispatch inside write action (crash on Wayland)
            FileTool.queueAutoFormat(project, pathStr);
            FileDocumentManager.getInstance().saveDocument(doc);

            int newLineCount = (int) fContent.chars().filter(c -> c == '\n').count();
            result.complete("Inserted " + newLineCount + " lines after line " + loc.endLine() + " in " + pathStr
                + FORMATTED_SUFFIX);
        } catch (Exception e) {
            result.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
        }
    }

    /**
     * Handles insert_after_symbol for files outside the project root.
     * Uses direct file I/O — no VFS, PSI, or EDT write action.
     */
    private String insertAfterSymbolExternal(String pathStr, String symbolName, String content, Integer lineHint) {
        Path absPath = ToolUtils.resolveAbsolutePath(project, pathStr);
        if (absPath == null || !Files.exists(absPath)) {
            return ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;
        }
        try {
            String text = Files.readString(absPath);
            String[] lines = text.split("\n", -1);

            int targetLine;
            if (lineHint != null && lineHint >= 1 && lineHint <= lines.length) {
                targetLine = lineHint - 1;
            } else {
                targetLine = -1;
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].contains(symbolName)) {
                        targetLine = i;
                        break;
                    }
                }
                if (targetLine < 0) {
                    return "Symbol '" + symbolName + "' not found in " + pathStr
                        + ". Provide a 'line' parameter for files outside the project.";
                }
            }

            String normalizedContent = content.replace("\r\n", "\n").replace("\r", "\n");
            if (!normalizedContent.endsWith("\n")) normalizedContent += "\n";

            StringBuilder out = new StringBuilder();
            for (int i = 0; i <= targetLine; i++)
                out.append(lines[i]).append(i < lines.length - 1 || text.endsWith("\n") ? "\n" : "");
            out.append(normalizedContent);
            for (int i = targetLine + 1; i < lines.length; i++)
                out.append(lines[i]).append(i < lines.length - 1 || text.endsWith("\n") ? "\n" : "");
            Files.writeString(absPath, out.toString());

            int insertedLines = (int) normalizedContent.chars().filter(c -> c == '\n').count();
            CodeChangeTracker.recordChange(insertedLines, 0);
            return "Inserted " + insertedLines + " lines after line " + (targetLine + 1)
                + " in " + pathStr + " [outside project — direct I/O]";
        } catch (java.io.IOException e) {
            return ToolUtils.ERROR_PREFIX + e.getMessage();
        }
    }
}
