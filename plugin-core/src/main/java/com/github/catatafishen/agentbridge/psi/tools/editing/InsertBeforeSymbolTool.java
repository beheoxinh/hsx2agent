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
 * Inserts content before a symbol definition.
 * Auto-formats and optimizes imports immediately on every call.
 */
public final class InsertBeforeSymbolTool extends EditingTool {

    private static final String PARAM_CONTENT = "content";

    public InsertBeforeSymbolTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "insert_before_symbol";
    }

    @Override
    public @NotNull String displayName() {
        return "Insert Before Symbol";
    }

    @Override
    public @NotNull String description() {
        return "Insert content before a symbol definition. PSI-aware — finds symbols by name, no line numbers needed. "
            + "Auto-formats and optimizes imports immediately. Use for annotations, comments, or companion methods.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Insert before {symbol} in {file}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("file", TYPE_STRING, "Path to the file containing the symbol"),
            Param.required("symbol", TYPE_STRING, "Name of the symbol to insert before"),
            Param.required(PARAM_CONTENT, TYPE_STRING, "The content to insert before the symbol"),
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
            return insertBeforeSymbolExternal(pathStr, symbolName, content, lineHint);
        }

        CompletableFuture<String> result = new CompletableFuture<>();
        int[] anchorLine = new int[1];

        EdtUtil.invokeLater(() -> performInsertBefore(pathStr, symbolName, content, lineHint, anchorLine, result));

        String resultStr = result.get(15, TimeUnit.SECONDS);
        if (!resultStr.startsWith(ToolUtils.ERROR_PREFIX) && !resultStr.startsWith("Symbol")) {
            int insertedLines = (int) content.chars().filter(c -> c == '\n').count() + 1;
            CodeChangeTracker.recordChange(insertedLines, 0);
            FileTool.followFileIfEnabled(project, pathStr, anchorLine[0], anchorLine[0] + insertedLines - 1,
                FileTool.HIGHLIGHT_EDIT, "inserting before " + symbolName);
            FileAccessTracker.recordWrite(project, pathStr);
            com.github.catatafishen.agentbridge.psi.FileConflictAutoResolver.recordAgentWrite();
        }
        return resultStr;
    }

    private void performInsertBefore(String pathStr, String symbolName, String content,
                                     Integer lineHint, int[] anchorLine, CompletableFuture<String> result) {
        try {
            SymbolLocation loc = resolveSymbol(pathStr, symbolName, lineHint);
            if (loc == null) {
                result.complete(symbolNotFoundMessage(pathStr, symbolName, lineHint));
                return;
            }
            anchorLine[0] = loc.startLine();

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
            int offset = doc.getLineStartOffset(loc.startLine() - 1);
            final String fContent = normalized;
            final int fOffset = offset;

            FileTool.notifyBeforeEdit(project, vf, doc);
            try {
                WriteCommandAction.runWriteCommandAction(
                    project, "Insert Before Symbol", null,
                    () -> doc.insertString(fOffset, fContent));
            } finally {
                FileTool.notifyEditComplete();
            }

            PsiDocumentManager.getInstance(project).commitDocument(doc);
            formatInline(vf);
            FileDocumentManager.getInstance().saveDocument(doc);

            int newLineCount = (int) fContent.chars().filter(c -> c == '\n').count();
            result.complete("Inserted " + newLineCount + " lines before line " + loc.startLine()
                + " in " + pathStr + FORMATTED_SUFFIX);
        } catch (Exception e) {
            result.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
        }
    }

    /**
     * Handles insert_before_symbol for files outside the project root.
     */
    private String insertBeforeSymbolExternal(String pathStr, String symbolName, String content, Integer lineHint) {
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
            for (int i = 0; i < targetLine; i++)
                out.append(lines[i]).append(i < lines.length - 1 || text.endsWith("\n") ? "\n" : "");
            out.append(normalizedContent);
            for (int i = targetLine; i < lines.length; i++)
                out.append(lines[i]).append(i < lines.length - 1 || text.endsWith("\n") ? "\n" : "");
            Files.writeString(absPath, out.toString());

            int insertedLines = (int) normalizedContent.chars().filter(c -> c == '\n').count();
            CodeChangeTracker.recordChange(insertedLines, 0);
            return "Inserted " + insertedLines + " lines before line " + (targetLine + 1)
                + " in " + pathStr + " [outside project — direct I/O]";
        } catch (java.io.IOException e) {
            return ToolUtils.ERROR_PREFIX + e.getMessage();
        }
    }
}
