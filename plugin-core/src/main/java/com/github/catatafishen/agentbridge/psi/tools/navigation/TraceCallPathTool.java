package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.FqnResolver;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Traces the call path between two symbols using BFS on the call graph.
 * "How does handleRequest reach databaseQuery?" → returns the shortest
 * chain of calls with code inline.
 */
public final class TraceCallPathTool extends NavigationTool {

    private static final int DEFAULT_MAX_DEPTH = 10;
    private static final int ABSOLUTE_MAX_DEPTH = 15;
    private static final int MAX_REFS_PER_NODE = 50;
    private static final int MAX_CODE_LINES = 30;

    public TraceCallPathTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "trace_call_path";
    }

    @Override
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Trace Call Path";
    }

    @Override
    public @NotNull String description() {
        return "Find the call path between two symbols — 'how does <from> reach <to>?'. "
                + "Uses BFS on the call graph to find the shortest chain. "
                + "Returns each hop with file location and optional source code inline.";
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
                Param.required("from", TYPE_STRING,
                        "Source symbol name or FQN (e.g. 'handleRequest' or 'com.example.Controller.handleRequest')"),
                Param.required("to", TYPE_STRING,
                        "Target symbol name or FQN (e.g. 'databaseQuery' or 'com.example.Dao.databaseQuery')"),
                Param.optional("max_depth", TYPE_INTEGER,
                        "Maximum hops to search (default: 10, max: 15)", DEFAULT_MAX_DEPTH),
                Param.optional("include_code", TYPE_BOOLEAN,
                        "Include source code for each hop (default: true)", true)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String fromName = args.has("from") ? args.get("from").getAsString() : "";
        String toName = args.has("to") ? args.get("to").getAsString() : "";
        if (fromName.isBlank()) return "Error: 'from' parameter is required";
        if (toName.isBlank()) return "Error: 'to' parameter is required";

        int maxDepth = args.has("max_depth")
                ? Math.min(args.get("max_depth").getAsInt(), ABSOLUTE_MAX_DEPTH)
                : DEFAULT_MAX_DEPTH;
        if (maxDepth < 1) maxDepth = 1;
        boolean includeCode = !args.has("include_code") || args.get("include_code").getAsBoolean();

        showSearchFeedback("Tracing: " + fromName + " -> " + toName);
        int finalMaxDepth = maxDepth;
        String result = ApplicationManager.getApplication().runReadAction(
                (Computable<String>) () -> traceCallPath(fromName, toName, finalMaxDepth, includeCode));
        showSearchFeedback("Trace complete: " + fromName + " -> " + toName);
        return ToolUtils.truncateOutput(result);
    }

    private String traceCallPath(String fromName, String toName, int maxDepth, boolean includeCode) {
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        PsiElement fromEl = resolveSymbol(fromName);
        if (fromEl == null) {
            return "Error: Could not find source symbol '" + fromName + "'. "
                    + "Try using a fully-qualified name.";
        }

        PsiElement toEl = resolveSymbol(toName);
        if (toEl == null) {
            return "Error: Could not find target symbol '" + toName + "'. "
                    + "Try using a fully-qualified name.";
        }

        String toUniqueName = elementName(toEl);

        // BFS: from → expand callees (what does from call?) → look for to
        // We search outgoing: for each symbol, find what IT references/calls
        Deque<BfsEntry> queue = new ArrayDeque<>();
        Map<PsiElement, PsiElement> parentMap = new HashMap<>();
        Set<String> visitedKeys = new HashSet<>();

        queue.add(new BfsEntry(fromEl, 0));
        String fromKey = elementKey(fromEl, basePath);
        if (fromKey != null) visitedKeys.add(fromKey);

        PsiElement found = null;

        while (!queue.isEmpty()) {
            BfsEntry current = queue.poll();
            if (current.depth >= maxDepth) continue;

            // Get callees: what does current.element reference/call?
            Collection<PsiReference> refs;
            try {
                refs = ReferencesSearch.search(
                        current.element, GlobalSearchScope.projectScope(project)).findAll();
            } catch (Exception e) {
                continue;
            }

            int expanded = 0;
            for (PsiReference ref : refs) {
                if (expanded >= MAX_REFS_PER_NODE) break;

                PsiElement refEl = ref.getElement();
                PsiNamedElement container = PsiTreeUtil.getParentOfType(refEl, PsiNamedElement.class);
                if (container == null || container.getName() == null) continue;
                if (ToolUtils.classifyElement(container) == null) continue;

                String key = elementKey(container, basePath);
                if (key == null || !visitedKeys.add(key)) continue;

                parentMap.put(container, current.element);
                expanded++;

                if (container.getName().equals(toUniqueName) || isMatch(container, toEl)) {
                    found = container;
                    break;
                }

                queue.add(new BfsEntry(container, current.depth + 1));
            }
            if (found != null) break;
        }

        if (found == null) {
            return "No call path found from '" + fromName + "' to '" + toName
                    + "' within " + maxDepth + " hops.\n\n"
                    + "Possible reasons:\n"
                    + "- The symbols are not connected through direct call relationships\n"
                    + "- The path is longer than " + maxDepth + " hops (try increasing max_depth)\n"
                    + "- The connection is through dynamic dispatch or reflection";
        }

        // Reconstruct path
        List<PsiElement> path = new ArrayList<>();
        PsiElement cursor = found;
        while (cursor != null) {
            path.addFirst(cursor);
            cursor = parentMap.get(cursor);
        }

        return formatPath(fromName, toName, path, includeCode, basePath);
    }

    @Nullable
    private PsiElement resolveSymbol(String name) {
        if (FqnResolver.looksLikeFqn(name)) {
            PsiElement resolved = FqnResolver.resolve(name, project);
            if (resolved != null) return resolved;
        }
        PsiElement[] result = {null};
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        PsiSearchHelper.getInstance(project).processElementsWithWord(
                (element, offsetInElement) -> {
                    PsiElement parent = element.getParent();
                    if (parent instanceof PsiNamedElement named && name.equals(named.getName())) {
                        if (ToolUtils.classifyElement(parent) != null) {
                            result[0] = parent;
                            return false;
                        }
                    }
                    return true;
                },
                scope, name, UsageSearchContext.IN_CODE, true);
        return result[0];
    }

    private static String elementName(PsiElement el) {
        if (el instanceof PsiNamedElement named) return named.getName();
        return el.getText();
    }

    private static boolean isMatch(PsiElement candidate, PsiElement target) {
        if (candidate == target) return true;
        if (candidate instanceof PsiNamedElement c && target instanceof PsiNamedElement t) {
            return c.getName() != null && c.getName().equals(t.getName())
                    && sameFile(c, t);
        }
        return false;
    }

    private static boolean sameFile(PsiElement a, PsiElement b) {
        PsiFile fa = a.getContainingFile();
        PsiFile fb = b.getContainingFile();
        if (fa == null || fb == null) return false;
        if (fa.getVirtualFile() == null || fb.getVirtualFile() == null) return false;
        return fa.getVirtualFile().getPath().equals(fb.getVirtualFile().getPath());
    }

    @Nullable
    private String elementKey(PsiElement el, String basePath) {
        PsiFile file = el.getContainingFile();
        if (file == null || file.getVirtualFile() == null) return null;
        Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (doc == null) return null;
        int line = doc.getLineNumber(el.getTextOffset()) + 1;
        return safeRelativize(basePath, file.getVirtualFile().getPath()) + ":" + line;
    }

    private String formatPath(String fromName, String toName, List<PsiElement> path,
                               boolean includeCode, String basePath) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Call Path: ").append(fromName).append(" -> ").append(toName).append("\n\n");
        sb.append("**Hops:** ").append(path.size() - 1).append("\n\n");

        // Chain summary
        sb.append("```\n");
        for (int i = 0; i < path.size(); i++) {
            PsiElement el = path.get(i);
            String name = elementName(el);
            String loc = elementKey(el, basePath);
            if (i > 0) sb.append("  -> ");
            sb.append(name);
            if (loc != null) sb.append(" (").append(loc).append(")");
            sb.append("\n");
        }
        sb.append("```\n\n");

        // Detailed hops
        if (includeCode) {
            sb.append("### Hop Details\n\n");
            for (int i = 0; i < path.size(); i++) {
                PsiElement el = path.get(i);
                String name = elementName(el);
                String loc = elementKey(el, basePath);
                String type = ToolUtils.classifyElement(el);

                sb.append("#### ").append(i + 1).append(". ").append(name);
                if (type != null) sb.append(" [").append(type).append("]");
                if (loc != null) sb.append(" at ").append(loc);
                sb.append("\n\n");

                String code = extractSnippet(el);
                if (code != null) {
                    sb.append("```\n").append(code).append("\n```\n\n");
                }
            }
        }

        return sb.toString();
    }

    @Nullable
    private String extractSnippet(PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null) return null;
        Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (doc == null) return null;

        TextRange range = element.getTextRange();
        if (range == null) return null;

        int startLine = doc.getLineNumber(range.getStartOffset());
        int endLine = doc.getLineNumber(range.getEndOffset());
        if (endLine - startLine > MAX_CODE_LINES) {
            endLine = startLine + MAX_CODE_LINES;
        }
        int startOffset = doc.getLineStartOffset(startLine);
        int endOffset = Math.min(
                doc.getLineEndOffset(Math.min(endLine, doc.getLineCount() - 1)),
                doc.getTextLength());
        return doc.getText(new TextRange(startOffset, endOffset));
    }

    private record BfsEntry(PsiElement element, int depth) {
    }
}
