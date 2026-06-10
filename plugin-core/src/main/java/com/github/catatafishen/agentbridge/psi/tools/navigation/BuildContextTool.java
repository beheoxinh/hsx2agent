package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One-shot context builder: AI describes a task and this tool returns
 * entry-point symbols, related symbols, and code snippets in a single call.
 * Replaces the typical 5-10 tool call sequence of search_symbols → read_file → find_references.
 */
public final class BuildContextTool extends NavigationTool {

    private static final int MAX_CODE_LINES_PER_SYMBOL = 80;
    private static final int MAX_RELATED_PER_ENTRY = 8;
    private static final int MAX_OUTPUT_CHARS = 60_000;
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[\\s,._#:;/|]+");
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "need", "must",
            "how", "what", "where", "when", "why", "who", "which",
            "this", "that", "these", "those", "it", "its",
            "in", "on", "at", "to", "for", "of", "with", "by", "from",
            "and", "or", "not", "no", "but", "if", "then", "else",
            "i", "we", "you", "he", "she", "they", "me", "us",
            "fix", "bug", "add", "implement", "change", "update", "make", "work",
            "code", "file", "class", "method", "function"
    );

    public BuildContextTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "build_context";
    }

    @Override
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Build Context";
    }

    @Override
    public @NotNull String description() {
        return "PRIMARY CONTEXT TOOL — call FIRST for any architecture, 'how does X work', or bug investigation question. "
                + "Describe the task in natural language and this tool returns entry-point symbols, "
                + "related code (callers/callees), and source snippets in ONE call. "
                + "Replaces the typical search_symbols → read_file → find_references sequence.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.SEARCH;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
                Param.required("task", TYPE_STRING, "Natural language description of the task or question "
                        + "(e.g. 'how does authentication work', 'fix the login timeout bug', "
                        + "'understand the MCP protocol handler')"),
                Param.optional("max_symbols", TYPE_INTEGER, "Maximum entry-point symbols to return (default: 20)", 20),
                Param.optional("include_code", TYPE_BOOLEAN,
                        "Include source code snippets for each symbol (default: true)", true)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String task = args.has("task") ? args.get("task").getAsString() : "";
        if (task.isBlank()) return "Error: 'task' parameter is required";

        int maxSymbols = args.has("max_symbols") ? args.get("max_symbols").getAsInt() : 20;
        if (maxSymbols < 1) maxSymbols = 1;
        if (maxSymbols > 50) maxSymbols = 50;
        boolean includeCode = !args.has("include_code") || args.get("include_code").getAsBoolean();

        showSearchFeedback("Building context: " + task);

        int finalMaxSymbols = maxSymbols;
        String result = ApplicationManager.getApplication().runReadAction(
                (Computable<String>) () -> buildContext(task, finalMaxSymbols, includeCode));

        showSearchFeedback("Context built for: " + task);
        return ToolUtils.truncateOutput(result);
    }

    private String buildContext(String task, int maxSymbols, boolean includeCode) {
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        List<String> keywords = extractKeywords(task);
        if (keywords.isEmpty()) return "Could not extract meaningful keywords from task description. "
                + "Try using specific class/method/variable names.";

        // Phase 1: Search for entry-point symbols
        Map<String, SymbolInfo> entryPoints = new LinkedHashMap<>();
        for (String keyword : keywords) {
            if (entryPoints.size() >= maxSymbols) break;
            searchSymbolsByKeyword(keyword, scope, basePath, entryPoints, maxSymbols);
        }

        if (entryPoints.isEmpty()) {
            return "No symbols found matching task: \"" + task + "\"\n"
                    + "Keywords searched: " + String.join(", ", keywords) + "\n"
                    + "Try using exact class/method names from your codebase.";
        }

        // Phase 2: Collect related symbols (callers/callees) for top entries
        Map<String, List<String>> relatedMap = new LinkedHashMap<>();
        int traced = 0;
        for (var entry : entryPoints.entrySet()) {
            if (traced >= 5) break;
            SymbolInfo info = entry.getValue();
            if (info.element != null) {
                List<String> related = collectRelated(info.element, basePath);
                if (!related.isEmpty()) {
                    relatedMap.put(entry.getKey(), related);
                }
                traced++;
            }
        }

        // Phase 3: Format output
        return formatOutput(task, keywords, entryPoints, relatedMap, includeCode, basePath);
    }

    private List<String> extractKeywords(String task) {
        String[] tokens = SPLIT_PATTERN.split(task.toLowerCase(Locale.ROOT));
        List<String> keywords = new ArrayList<>();
        for (String token : tokens) {
            String cleaned = token.replaceAll("[^a-zA-Z0-9]", "");
            if (cleaned.length() >= 3 && !STOP_WORDS.contains(cleaned)) {
                keywords.add(cleaned);
            }
        }
        // Also try CamelCase segments from the original task
        for (String token : SPLIT_PATTERN.split(task)) {
            if (token.length() >= 3 && Character.isUpperCase(token.charAt(0))) {
                keywords.addFirst(token);
            }
        }
        // Deduplicate while preserving order
        return new ArrayList<>(new LinkedHashSet<>(keywords));
    }

    private void searchSymbolsByKeyword(String keyword, GlobalSearchScope scope,
                                        String basePath, Map<String, SymbolInfo> results, int limit) {
        PsiSearchHelper.getInstance(project).processElementsWithWord(
                (element, offsetInElement) -> {
                    if (results.size() >= limit) return false;
                    PsiElement parent = element.getParent();
                    if (parent instanceof PsiNamedElement named && named.getName() != null) {
                        String type = ToolUtils.classifyElement(parent);
                        if (type != null) {
                            String key = uniqueKey(parent, basePath);
                            if (key != null && !results.containsKey(key)) {
                                results.put(key, new SymbolInfo(named, type, basePath));
                            }
                        }
                    }
                    return results.size() < limit;
                },
                scope, keyword, UsageSearchContext.IN_CODE, true
        );
    }

    private String uniqueKey(PsiElement element, String basePath) {
        PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null) return null;
        Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (doc == null) return null;
        int line = doc.getLineNumber(element.getTextOffset()) + 1;
        String relPath = safeRelativize(basePath, file.getVirtualFile().getPath());
        return relPath + ":" + line;
    }

    private List<String> collectRelated(PsiElement element, String basePath) {
        List<String> related = new ArrayList<>();
        try {
            com.intellij.openapi.progress.ProgressIndicator indicator =
                    new com.intellij.openapi.progress.EmptyProgressIndicator();
            com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(
                    () -> {
                        Collection<PsiReference> refs = ReferencesSearch.search(
                                element, GlobalSearchScope.projectScope(project)).findAll();
                        for (PsiReference ref : refs) {
                            if (related.size() >= MAX_RELATED_PER_ENTRY) break;
                            PsiElement refEl = ref.getElement();
                            PsiNamedElement container = PsiTreeUtil.getParentOfType(refEl, PsiNamedElement.class);
                            if (container != null && container.getName() != null) {
                                String loc = uniqueKey(container, basePath);
                                if (loc != null) {
                                    String type = ToolUtils.classifyElement(container);
                                    related.add(String.format("  %s [%s] at %s",
                                            container.getName(), type != null ? type : "symbol", loc));
                                }
                            }
                        }
                    },
                    indicator
            );
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Exception e) {
            // Reference search can fail for certain PSI elements; skip gracefully
        }
        return related;
    }

    private String formatOutput(String task, List<String> keywords,
                                Map<String, SymbolInfo> entryPoints,
                                Map<String, List<String>> relatedMap,
                                boolean includeCode, String basePath) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Context for: ").append(task).append("\n\n");
        sb.append("**Keywords:** ").append(String.join(", ", keywords)).append("\n");
        sb.append("**Entry points found:** ").append(entryPoints.size()).append("\n\n");

        // Group by file
        Map<String, List<Map.Entry<String, SymbolInfo>>> byFile = new LinkedHashMap<>();
        for (var entry : entryPoints.entrySet()) {
            String filePath = entry.getValue().relativePath;
            byFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(entry);
        }

        sb.append("### Entry Points\n\n");
        for (var fileGroup : byFile.entrySet()) {
            sb.append("**").append(fileGroup.getKey()).append("**\n");
            for (var entry : fileGroup.getValue()) {
                SymbolInfo info = entry.getValue();
                sb.append(String.format("  - `%s` [%s] line %d", info.name, info.type, info.line));
                if (info.signature != null) {
                    sb.append(" — `").append(info.signature).append("`");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Related symbols
        if (!relatedMap.isEmpty()) {
            sb.append("### Related Symbols (callers/references)\n\n");
            for (var entry : relatedMap.entrySet()) {
                sb.append("**").append(entry.getKey()).append("** is referenced by:\n");
                for (String rel : entry.getValue()) {
                    sb.append(rel).append("\n");
                }
                sb.append("\n");
            }
        }

        // Code snippets
        if (includeCode) {
            sb.append("### Code Snippets\n\n");
            int snippetsAdded = 0;
            for (var entry : entryPoints.entrySet()) {
                if (sb.length() > MAX_OUTPUT_CHARS) {
                    sb.append("\n(Output budget reached — ").append(entryPoints.size() - snippetsAdded)
                            .append(" symbols omitted. Use read_file for remaining.)\n");
                    break;
                }
                SymbolInfo info = entry.getValue();
                String code = extractCodeSnippet(info.element, basePath);
                if (code != null && !code.isBlank()) {
                    sb.append("#### ").append(info.name).append(" (").append(entry.getKey()).append(")\n");
                    sb.append("```\n").append(code).append("\n```\n\n");
                }
                snippetsAdded++;
            }
        }

        return sb.toString();
    }

    private String extractCodeSnippet(PsiElement element, String basePath) {
        if (element == null) return null;
        PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null) return null;
        Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (doc == null) return null;

        TextRange range = element.getTextRange();
        if (range == null) return null;

        int startLine = doc.getLineNumber(range.getStartOffset());
        int endLine = doc.getLineNumber(range.getEndOffset());

        // Cap snippet length
        if (endLine - startLine > MAX_CODE_LINES_PER_SYMBOL) {
            endLine = startLine + MAX_CODE_LINES_PER_SYMBOL;
        }

        int startOffset = doc.getLineStartOffset(startLine);
        int endOffset = Math.min(doc.getLineEndOffset(Math.min(endLine, doc.getLineCount() - 1)),
                doc.getTextLength());

        return doc.getText(new TextRange(startOffset, endOffset));
    }

    private record SymbolInfo(String name, String type, int line, String relativePath,
                              String signature, PsiElement element) {
        SymbolInfo(PsiNamedElement named, String type, String basePath) {
            this(
                    named.getName(),
                    type,
                    lineOf(named),
                    relPathOf(named, basePath),
                    extractSignature(named),
                    named
            );
        }

        private static int lineOf(PsiElement el) {
            PsiFile file = el.getContainingFile();
            if (file == null || file.getVirtualFile() == null) return 0;
            Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
            return doc != null ? doc.getLineNumber(el.getTextOffset()) + 1 : 0;
        }

        private static String relPathOf(PsiElement el, String basePath) {
            PsiFile file = el.getContainingFile();
            if (file == null || file.getVirtualFile() == null) return "?";
            return safeRelativize(basePath, file.getVirtualFile().getPath());
        }

        private static String extractSignature(PsiNamedElement element) {
            if (element instanceof com.intellij.psi.PsiMethod method) {
                StringBuilder sig = new StringBuilder(method.getName()).append("(");
                var params = method.getParameterList().getParameters();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sig.append(", ");
                    sig.append(params[i].getType().getPresentableText());
                }
                sig.append(")");
                if (!method.isConstructor() && method.getReturnType() != null) {
                    sig.append(": ").append(method.getReturnType().getPresentableText());
                }
                return sig.toString();
            }
            if (element instanceof com.intellij.psi.PsiClass cls) {
                return cls.getQualifiedName();
            }
            return null;
        }

        private static String safeRelativize(String basePath, String absolutePath) {
            return NavigationTool.safeRelativize(basePath, absolutePath);
        }
    }
}
