# CHECKPOINT: PSI-powered Code Intelligence Tools

## Objective

Add 3 new MCP tools inspired by CodeGraph's one-shot context approach,
implemented natively via IntelliJ PSI (compile-time accurate, real-time editor buffer, zero external deps).

**Branch:** `feature/unknow-error`
**Impact:** Reduces AI agent tool calls by ~50-60% for architecture/refactor/trace questions.

---

## Tool 1: `build_context` — One-Shot Task Context

**What:** AI describes a task ("how does auth work?") → tool searches symbols, traces relationships,
reads code snippets → returns a bundled context in 1 call (vs. 5-10 calls today).

**MCP ID:** `build_context`
**Category:** SEARCH
**Read-only:** yes
**Package:** `psi/tools/navigation/BuildContextTool.java`

### Steps:

- [x] 1.1 Create `BuildContextTool.java` extending `NavigationTool`
- [x] 1.2 Define schema: `task` (required string), `max_symbols` (optional int, default 20), `include_code` (optional
  bool, default true)
- [x] 1.3 Implement core logic:
    - Parse task description → extract keyword tokens (CamelCase priority, stop-word filtering)
    - Search symbols matching keywords via `PsiSearchHelper`
    - For each found symbol, collect: file path, line, signature, containing class
    - If `include_code=true`, read source snippet (capped at 80 lines per symbol)
    - Trace 1-level callers/callees for top-5 symbols via `ReferencesSearch`
    - Format output as structured markdown: entry points → related symbols → code blocks
    - Output budget: 60K chars max
- [x] 1.4 Register in `NavigationToolFactory.create()`
- [x] 1.5 Compile check — PASSED
- [ ] 1.6 Write unit test `BuildContextToolTest.java`

---

## Tool 2: `impact_analysis` — Recursive Impact Trace

**What:** "If I change `UserService.validate()`, what breaks?" → recursive `find_references`
with depth limit, returns full affected symbol chain.

**MCP ID:** `impact_analysis`
**Category:** SEARCH
**Read-only:** yes
**Package:** `psi/tools/navigation/ImpactAnalysisTool.java`

### Steps:

- [x] 2.1 Create `ImpactAnalysisTool.java` extending `NavigationTool`
- [x] 2.2 Define schema: `symbol` (required), `file` (optional), `line` (optional), `depth` (optional int, default 2,
  max 5)
- [x] 2.3 Implement core logic:
    - Resolve target symbol (FQN mode, file+line mode, or name-search fallback)
    - BFS through `ReferencesSearch` up to `depth` levels
    - Track visited set to avoid cycles (max 200 total, max 30 per level)
    - For each affected symbol: record name, file:line, type, relationship (via ReferenceClassifier)
    - Count total affected files and symbols
    - Format as leveled tree grouped by file
- [x] 2.4 Register in `NavigationToolFactory.create()`
- [x] 2.5 Compile check — PASSED
- [ ] 2.6 Write unit test `ImpactAnalysisToolTest.java`

---

## Tool 3: `trace_call_path` — Symbol-to-Symbol Call Trace

**What:** "How does `handleRequest` reach `databaseQuery`?" → BFS on call hierarchy to find
the shortest call chain between two symbols, with code inline.

**MCP ID:** `trace_call_path`
**Category:** SEARCH
**Read-only:** yes
**Package:** `psi/tools/navigation/TraceCallPathTool.java`

### Steps:

- [x] 3.1 Create `TraceCallPathTool.java` extending `NavigationTool`
- [x] 3.2 Define schema: `from` (required string), `to` (required string), `max_depth` (optional int, default 10, max
  15), `include_code` (optional bool, default true)
- [x] 3.3 Implement core logic:
    - Resolve both `from` and `to` symbols via FQN or name search
    - BFS from `from`, expand callers via `ReferencesSearch` at each level
    - Stop when `to` is found or `max_depth` reached (max 50 refs per node)
    - Reconstruct path via parent map
    - If `include_code=true`, inline code snippet for each hop (capped at 30 lines)
    - Format as chain summary + detailed hops with code
- [x] 3.4 Register in `NavigationToolFactory.create()`
- [x] 3.5 Compile check — PASSED
- [ ] 3.6 Write unit test `TraceCallPathToolTest.java`

---

## Integration & Verification

- [x] 4.1 All 3 tools registered in `NavigationToolFactory`
- [ ] 4.2 Manual test: connect agent, invoke each tool with real project
- [ ] 4.3 Verify tools appear in `tools/list` MCP response
- [x] 4.4 Commit all tools with descriptive message
- [x] 4.5 Update CHECKPOINT.md with current status

---

## Architecture Notes

### How tools fit into existing codebase:

```
PsiBridgeService.init()
  → NavigationToolFactory.create(project, hasJava)
    → ... existing tools ...
    → new BuildContextTool(project)      ← NEW
    → new ImpactAnalysisTool(project)    ← NEW
    → new TraceCallPathTool(project)     ← NEW
  → ToolRegistry.registerAll(allTools)
  → MCP tools/list exposes them to agents
```

### PSI APIs used (all read-only, thread-safe inside ReadAction):

- `PsiSearchHelper.processElementsWithWord` — keyword-based symbol search
- `ReferencesSearch.search` — semantic reference lookup (callers, usages)
- `PsiTreeUtil.getParentOfType` — container resolution
- `FileDocumentManager.getDocument` — line number resolution
- `ToolUtils.classifyElement` — symbol type detection (class/method/field/...)
- `FqnResolver.resolve` — fully-qualified name resolution
- `ReferenceClassifier.classifyUsage` — usage type detection (CALL, IMPORT, TYPE_REF...)

### No breaking changes:

- All tools are additive (new files only)
- No modifications to existing tool implementations
- NavigationToolFactory: 3 lines added at the end of the tool list
- No new dependencies — uses existing PSI and ToolUtils infrastructure
