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
 * Replaces the entire definition of a symbol (method, class, field) by name.
 * Auto-formats and optimizes imports immediately on every call.
 */
public final class ReplaceSymbolBodyTool extends EditingTool {

    private static final String PARAM_NEW_BODY = "new_body";

    public ReplaceSymbolBodyTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "replace_symbol_body";
    }

    @Override
    public @NotNull String displayName() {
        return "Replace Symbol Body";
    }

    @Override
    public @NotNull String description() {
        return "Replace the entire definition of a symbol (method, class, field) by name -- no line numbers needed. "
            + "Auto-formats and optimizes imports immediately on every call";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Replace {symbol} in {file}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("file", TYPE_STRING, "Path to the file containing the symbol"),
            Param.required("symbol", TYPE_STRING, "Name of the symbol to replace"),
            Param.required(PARAM_NEW_BODY, TYPE_STRING, "The complete new definition to replace the symbol with"),
            Param.optional("line", TYPE_INTEGER, "Optional: line number hint to disambiguate if multiple symbols share the same name")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ReplaceSymbolRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String error = validateArgs(args, PARAM_NEW_BODY);
        if (error != null) return error;

        String pathStr = args.get(PARAM_FILE).getAsString();
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String newBody = args.get(PARAM_NEW_BODY).getAsString();
        Integer lineHint = args.has(PARAM_LINE) ? args.get(PARAM_LINE).getAsInt() : null;

        // Files outside the project bypass VFS/PSI to avoid IntelliJ's
        // Non-Project Files Protection dialog (blocks EDT on Wayland).
        if (ToolUtils.isOutsideProject(project, pathStr)) {
            return replaceSymbolBodyExternal(pathStr, symbolName, newBody, lineHint);
        }

        CompletableFuture<String> result = new CompletableFuture<>();
        int[] lineRange = new int[2];
        String[] symbolType = new String[1];

        EdtUtil.invokeLater(() -> performReplace(pathStr, symbolName, newBody, lineHint, lineRange, symbolType, result));

        String resultStr = result.get(15, TimeUnit.SECONDS);
        if (!resultStr.startsWith(ToolUtils.ERROR_PREFIX) && !resultStr.startsWith("Symbol")) {
            int newLineCount = (int) newBody.chars().filter(c -> c == '\n').count() + 1;
            FileTool.followFileIfEnabled(project, pathStr, lineRange[0], lineRange[0] + newLineCount - 1,
                FileTool.HIGHLIGHT_EDIT, "replacing " + symbolType[0] + " " + symbolName);
            FileAccessTracker.recordWrite(project, pathStr);
            com.github.catatafishen.agentbridge.psi.FileConflictAutoResolver.recordAgentWrite();
        }
        return resultStr;
    }

    private void performReplace(String pathStr, String symbolName, String newBody,
                                Integer lineHint, int[] lineRange, String[] symbolType,
                                CompletableFuture<String> result) {
        try {
            SymbolLocation loc = resolveSymbol(pathStr, symbolName, lineHint);
            if (loc == null) {
                result.complete(symbolNotFoundMessage(pathStr, symbolName, lineHint));
                return;
            }
            lineRange[0] = loc.startLine();
            lineRange[1] = loc.endLine();
            symbolType[0] = loc.type();

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

            int startOffset = doc.getLineStartOffset(loc.startLine() - 1);
            int endOffset = calculateEndOffset(doc, loc);
            String normalized = prepareNormalizedBody(newBody);

            final int fStart = startOffset;
            final int fEnd = endOffset;
            final String fNew = normalized;

            FileTool.notifyBeforeEdit(project, vf, doc);
            try {
                WriteCommandAction.runWriteCommandAction(
                    project, "Replace Symbol Body", null,
                    () -> doc.replaceString(fStart, fEnd, fNew));
            } finally {
                FileTool.notifyEditComplete();
            }

            PsiDocumentManager.getInstance(project).commitDocument(doc);
            formatInline(vf);
            FileDocumentManager.getInstance().saveDocument(doc);

            int replacedLines = loc.endLine() - loc.startLine() + 1;
            int newLineCount = (int) fNew.chars().filter(c -> c == '\n').count() + 1;
            CodeChangeTracker.recordChange(newLineCount, replacedLines);
            result.complete("Replaced lines " + loc.startLine() + "-" + loc.endLine()
                + " (" + replacedLines + " lines) with " + (newLineCount - 1) + " lines in " + pathStr
                + FORMATTED_SUFFIX);
        } catch (Exception e) {
            result.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
        }
    }

    /**
     * Handles replace_symbol_body for files outside the project root.
     */
    private String replaceSymbolBodyExternal(String pathStr, String symbolName, String newBody, Integer lineHint) {
        Path absPath = ToolUtils.resolveAbsolutePath(project, pathStr);
        if (absPath == null || !Files.exists(absPath)) {
            return ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;
        }
        try {
            String text = Files.readString(absPath);
            String[] lines = text.split("\n", -1);

            int startLine, endLine;
            if (lineHint != null && lineHint >= 1) {
                // Try to find the symbol boundaries given lineHint as anchor
                startLine = lineHint - 1;
                endLine = startLine;
                // Walk up to find start
                for (int i = startLine - 1; i >= 0; i--) {
                    if (lines[i].trim().startsWith("function ") || lines[i].trim().startsWith("class ")
                        || lines[i].trim().startsWith("def ") || lines[i].trim().startsWith("async ")) {
                        startLine = i;
                        break;
                    }
                    if (lines[i].contains(symbolName)) {
                        startLine = i;
                        break;
                    }
                }
                // Walk down to find end
                for (int i = startLine + 1; i < lines.length; i++) {
                    if (lines[i].contains(symbolName)) endLine = i;
                }
            } else {
                // Find by symbol name
                startLine = -1;
                endLine = -1;
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].contains(symbolName)) {
                        if (startLine < 0) startLine = i;
                        endLine = i;
                    }
                }
                if (startLine < 0) {
                    return "Symbol '" + symbolName + "' not found in " + pathStr
                        + ". Provide a 'line' parameter for files outside the project.";
                }
            }

            String normalizedBody = newBody.replace("\r\n", "\n").replace("\r", "\n");
            if (!normalizedBody.isEmpty() && !normalizedBody.endsWith("\n")) normalizedBody += "\n";

            StringBuilder out = new StringBuilder();
            for (int i = 0; i < startLine; i++)
                out.append(lines[i]).append(i < lines.length - 1 || text.endsWith("\n") ? "\n" : "");
            out.append(normalizedBody);
            for (int i = endLine + 1; i < lines.length; i++)
                out.append(lines[i]).append(i < lines.length - 1 || text.endsWith("\n") ? "\n" : "");
            Files.writeString(absPath, out.toString());

            int newLineCount = (int) normalizedBody.chars().filter(c -> c == '\n').count() + 1;
            int replacedLines = endLine - startLine + 1;
            CodeChangeTracker.recordChange(newLineCount, replacedLines);
            return "Replaced lines " + (startLine + 1) + "-" + (endLine + 1)
                + " (" + replacedLines + " lines) with " + newLineCount + " lines in " + pathStr
                + " [outside project — direct I/O]";
        } catch (java.io.IOException e) {
            return ToolUtils.ERROR_PREFIX + e.getMessage();
        }
    }

    private static int calculateEndOffset(Document doc, SymbolLocation loc) {
        int endOffset = doc.getLineEndOffset(loc.endLine() - 1);
        if (endOffset < doc.getTextLength() && doc.getCharsSequence().charAt(endOffset) == '\n') {
            endOffset++;
        }
        return endOffset;
    }

    private static String prepareNormalizedBody(String body) {
        String normalized = body.replace("\r\n", "\n").replace("\r", "\n");
        if (!normalized.isEmpty() && !normalized.endsWith("\n")) {
            normalized += "\n";
        }
        return normalized;
    }
}
