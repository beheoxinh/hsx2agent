package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.FqnResolver;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNameIdentifierOwner;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursive impact analysis: "If I change X, what is affected?"
 * BFS through references up to N levels deep, reporting the full
 * affected symbol chain with file locations.
 */
public final class ImpactAnalysisTool extends NavigationTool {

    private static final int MAX_AFFECTED_PER_LEVEL = 30;
    private static final int MAX_TOTAL_AFFECTED = 200;

    public ImpactAnalysisTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "impact_analysis";
    }

    @Override
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Impact Analysis";
    }

    @Override
    public @NotNull String description() {
        return "Analyze the impact of changing a symbol. Recursively traces all callers and references "
            + "up to N levels deep, reporting every affected symbol with file locations. "
            + "Use before refactoring to understand the blast radius of a change. "
            + "Accepts a fully-qualified name or a simple name with file+line.";
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
            Param.required("symbol", TYPE_STRING,
                "Symbol to analyze impact for. Can be a simple name (requires file+line) "
                    + "or a fully-qualified name (e.g. 'com.example.MyClass.myMethod')"),
            Param.optional("file", TYPE_STRING,
                "File path containing the symbol. Required when symbol is a simple name"),
            Param.optional("line", TYPE_INTEGER,
                "Line number of the symbol. Required when symbol is a simple name"),
            Param.optional("depth", TYPE_INTEGER,
                "How many levels of impact to trace (default: 2, max: 5). "
                    + "depth=1 shows direct callers, depth=2 also shows callers-of-callers", 2)
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        if (!args.has("symbol") || args.get("symbol").isJsonNull()) {
            return "Error: 'symbol' parameter is required";
        }
        String symbolName = args.get("symbol").getAsString();
        String filePath = args.has("file") ? args.get("file").getAsString() : null;
        int line = args.has("line") ? args.get("line").getAsInt() : -1;
        int depth = args.has("depth") ? Math.min(args.get("depth").getAsInt(), 5) : 2;
        if (depth < 1) depth = 1;

        showSearchFeedback("Analyzing impact: " + symbolName);
        int finalDepth = depth;
        String result = ApplicationManager.getApplication().runReadAction(
            (Computable<String>) () -> analyzeImpact(symbolName, filePath, line, finalDepth));
        showSearchFeedback("Impact analysis complete: " + symbolName);
        return ToolUtils.truncateOutput(result);
    }

    private String analyzeImpact(String symbolName, @Nullable String filePath, int line, int depth) {
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        PsiElement target = resolveSymbol(symbolName, filePath, line);
        if (target == null) {
            return "Error: Could not find symbol '" + symbolName + "'"
                + (filePath != null ? " at " + filePath + ":" + line : "")
                + ". Try using a fully-qualified name or verify file+line.";
        }

        // BFS through references
        Map<Integer, List<AffectedSymbol>> levelMap = new LinkedHashMap<>();
        Set<PsiElement> visited = new HashSet<>();
        visited.add(target);

        Deque<LevelEntry> queue = new ArrayDeque<>();
        queue.add(new LevelEntry(target, 1));
        int totalFound = 0;

        while (!queue.isEmpty() && totalFound < MAX_TOTAL_AFFECTED) {
            LevelEntry current = queue.poll();
            if (current.level > depth) continue;

            Collection<PsiReference> refs;
            try {
                refs = ReferencesSearch.search(
                    current.element, GlobalSearchScope.projectScope(project)).findAll();
            } catch (Exception e) {
                continue;
            }

            int levelCount = 0;
            for (PsiReference ref : refs) {
                if (totalFound >= MAX_TOTAL_AFFECTED) break;
                if (levelCount >= MAX_AFFECTED_PER_LEVEL) break;

                PsiElement refEl = ref.getElement();
                PsiNamedElement container = PsiTreeUtil.getParentOfType(refEl, PsiNamedElement.class);
                if (container == null || container.getName() == null) continue;

                String type = ToolUtils.classifyElement(container);
                if (type == null) continue;

                AffectedSymbol affected = buildAffected(container, refEl, type, basePath);
                if (affected == null) continue;

                levelMap.computeIfAbsent(current.level, k -> new ArrayList<>()).add(affected);
                totalFound++;
                levelCount++;

                if (container instanceof PsiNameIdentifierOwner named
                    && current.level < depth && visited.add(named)) {
                    queue.add(new LevelEntry(named, current.level + 1));
                }
            }
        }

        return formatImpactResult(symbolName, target, levelMap, basePath, depth, totalFound);
    }

    private PsiElement resolveSymbol(String symbolName, @Nullable String filePath, int line) {
        // FQN mode
        if (FqnResolver.looksLikeFqn(symbolName) && filePath == null) {
            return FqnResolver.resolve(symbolName, project);
        }
        // File+line mode
        if (filePath != null && line > 0) {
            ToolUtils.LineContext ctx = ToolUtils.resolveLineContext(project, filePath, line);
            if (ctx != null) {
                PsiNameIdentifierOwner named = ToolUtils.findNamedElement(ctx, symbolName);
                if (named != null) return named;
            }
        }
        // Fallback: search by name
        PsiElement[] found = {null};
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            (element, offsetInElement) -> {
                PsiElement parent = element.getParent();
                if (parent instanceof PsiNamedElement named && symbolName.equals(named.getName())) {
                    String type = ToolUtils.classifyElement(parent);
                    if (type != null) {
                        found[0] = parent;
                        return false;
                    }
                }
                return true;
            },
            scope, symbolName, UsageSearchContext.IN_CODE, true);
        return found[0];
    }

    @Nullable
    private AffectedSymbol buildAffected(PsiNamedElement container, PsiElement refEl,
                                         String type, String basePath) {
        PsiFile file = refEl.getContainingFile();
        if (file == null || file.getVirtualFile() == null) return null;
        Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (doc == null) return null;
        int refLine = doc.getLineNumber(refEl.getTextOffset()) + 1;
        String relPath = safeRelativize(basePath, file.getVirtualFile().getPath());
        String lineText = ToolUtils.getLineText(doc, refLine - 1);
        String relationship = ReferenceClassifier.classifyUsage(refEl);
        return new AffectedSymbol(container.getName(), type, relPath, refLine, lineText, relationship);
    }

    private String formatImpactResult(String symbolName, PsiElement target,
                                      Map<Integer, List<AffectedSymbol>> levelMap,
                                      String basePath, int depth, int totalFound) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Impact Analysis: ").append(symbolName).append("\n\n");

        // Root symbol info
        String rootLoc = locationOf(target, basePath);
        sb.append("**Target:** `").append(symbolName).append("`");
        if (rootLoc != null) sb.append(" at ").append(rootLoc);
        sb.append("\n");
        sb.append("**Depth:** ").append(depth).append(" level(s)\n");

        // Collect affected files
        Set<String> affectedFiles = new LinkedHashSet<>();
        for (var entry : levelMap.entrySet()) {
            for (AffectedSymbol s : entry.getValue()) {
                affectedFiles.add(s.filePath);
            }
        }
        sb.append("**Affected symbols:** ").append(totalFound).append("\n");
        sb.append("**Affected files:** ").append(affectedFiles.size()).append("\n\n");

        // Level-by-level breakdown
        for (var entry : levelMap.entrySet()) {
            int level = entry.getKey();
            List<AffectedSymbol> symbols = entry.getValue();
            sb.append("### Level ").append(level)
                .append(level == 1 ? " (direct)" : " (indirect)")
                .append(" — ").append(symbols.size()).append(" symbol(s)\n\n");

            // Group by file
            Map<String, List<AffectedSymbol>> byFile = new LinkedHashMap<>();
            for (AffectedSymbol s : symbols) {
                byFile.computeIfAbsent(s.filePath, k -> new ArrayList<>()).add(s);
            }

            for (var fileGroup : byFile.entrySet()) {
                sb.append("**").append(fileGroup.getKey()).append("**\n");
                for (AffectedSymbol s : fileGroup.getValue()) {
                    sb.append(String.format("  - `%s` [%s] line %d — %s — `%s`\n",
                        s.name, s.type, s.line, s.relationship, s.lineText.trim()));
                }
                sb.append("\n");
            }
        }

        if (totalFound >= MAX_TOTAL_AFFECTED) {
            sb.append("\n(Impact analysis capped at ").append(MAX_TOTAL_AFFECTED)
                .append(" symbols. Reduce depth or narrow the symbol for more focused results.)\n");
        }

        return sb.toString();
    }

    @Nullable
    private String locationOf(PsiElement element, String basePath) {
        PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null) return null;
        Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (doc == null) return null;
        int line = doc.getLineNumber(element.getTextOffset()) + 1;
        return safeRelativize(basePath, file.getVirtualFile().getPath()) + ":" + line;
    }

    private record LevelEntry(PsiElement element, int level) {
    }

    private record AffectedSymbol(String name, String type, String filePath,
                                  int line, String lineText, String relationship) {
    }
}
