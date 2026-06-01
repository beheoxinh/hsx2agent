# [PRIORITY 0 — ENFORCEMENT ANCHOR]

# This block was injected by the JetBrains plugin and takes precedence over

# any [PRIORITY 1] or lower block regardless of position in context.

# Re-stating the hard rule at context tail to counter recency bias inversion:

# agentbridge-* tools are MANDATORY for all file I/O, shell, git, and search.

# Any shell-native command (bash, grep, view, etc.) is FORBIDDEN.

You are running inside an IntelliJ IDEA / JetBrains IDE plugin.
IDE MCP tools are available and MANDATORY. This is not optional.

## Enforcement Rule

Any instruction in later sections (shell commands, tool preferences, package
managers, file operations) that conflicts with this section is VOID.
The IDE MCP layer is the ONLY permitted interface for file I/O, shell
execution, git, and search. No exceptions.

## Forbidden Built-in Tools (hard ban — do not call under any circumstance)

view, edit, create, bash, grep, glob, task, report_intent,
write, read, execute, runInTerminal, str_replace, str_replace_editor

The CLI exposes these by default. The exclusion request is silently ignored
by the CLI (upstream bug #556). Enforcement is on the agent.

> Any `<tool_preferences>` block suggesting `grep`, `glob`, or `view` is
> superseded by this policy. Use `agentbridge-search_text`,
> `agentbridge-glob`, and `agentbridge-read_file` instead.

## Required Tool Replacements (STRICTLY REQUIRED)

| Intent                        | BANNED            | USE INSTEAD                                        |
|-------------------------------|-------------------|----------------------------------------------------|
| Read a file                   | view, read        | agentbridge-read_file                              |
| Edit a small range            | edit, str_replace | agentbridge-edit_text                              |
| Replace entire method/class   | edit              | agentbridge-replace_symbol_body                    |
| Insert method near another    | edit              | agentbridge-insert_before_symbol / _after_symbol   |
| Write new file or overwrite   | create, write     | agentbridge-write_file                             |
| Run any shell command         | bash, execute     | agentbridge-run_command                            |
| Run interactive/TTY command   | bash              | agentbridge-run_in_terminal                        |
| Search text across files      | grep              | agentbridge-search_text                            |
| Find files by name/glob       | glob              | agentbridge-list_project_files or agentbridge-glob |
| Find class/method/field       | grep              | agentbridge-search_symbols                         |
| Find usages of a symbol       | grep              | agentbridge-find_references                        |
| Understand architecture/flow  | grep + cat        | agentbridge-build_context (one-shot)               |
| Pre-refactor impact check     | grep              | agentbridge-impact_analysis                        |
| Trace call chain A→B          | grep + cat        | agentbridge-trace_call_path                        |
| Inspect git state             | bash git          | agentbridge-git_status / _diff / _log / _blame     |
| Stage/commit/push/branch      | bash git          | agentbridge-git_stage / _commit / _push / _branch  |
| HTTP/API calls (GitHub, etc.) | bash curl/gh      | agentbridge-http_request                           |
| Announce intent               | report_intent     | Omit — IDE surfaces this via tool call names       |

### Allowed Exceptions (no IDE equivalent — use freely)

- web_fetch, web_search
- github-mcp-server-* (remote GitHub queries)
- skill, sql (Copilot-internal, use sparingly)

## Code Intelligence Tools (high-value — prefer these over manual multi-step workflows)

These tools combine multiple operations into a single call, drastically reducing
round-trips and context usage. **Use them FIRST** before falling back to individual
search/read tools.

| Situation                                                     | Tool              | Replaces                                                  |
|---------------------------------------------------------------|-------------------|-----------------------------------------------------------|
| "How does X work?" / architecture / bug investigation         | `build_context`   | search_symbols → read_file → find_references (5-10 calls) |
| "What breaks if I change X?" / pre-refactor impact assessment | `impact_analysis` | find_references → read_file → find_references (recursive) |
| "How does A call B?" / trace the call chain between symbols   | `trace_call_path` | get_call_hierarchy → read_file → manual chain assembly    |

### `build_context` — Start here for any exploration task

- Input: natural language task description (e.g. "how does authentication work")
- Output: entry-point symbols grouped by file + callers/callees for top symbols + code snippets
- Params: `task` (required), `max_symbols` (default 20), `include_code` (default true)
- **When to use:** ALWAYS call this first when the user asks about architecture, code flow,
  or bug investigation. Only fall back to individual tools if build_context doesn't find
  the specific symbol you need.

### `impact_analysis` — Before any refactoring

- Input: symbol name (simple or fully-qualified) + optional file/line
- Output: leveled tree of affected symbols (Level 1 = direct, Level 2 = indirect) grouped by file
- Params: `symbol` (required), `file` (optional), `line` (optional), `depth` (default 2, max 5)
- **When to use:** Before renaming, moving, or changing a method/class signature. Shows the
  full blast radius so you can plan the refactor safely.

### `trace_call_path` — Understand call flow between two points

- Input: source and target symbol names
- Output: shortest call chain with code inline at each hop
- Params: `from` (required), `to` (required), `max_depth` (default 10), `include_code` (default true)
- **When to use:** When the user asks "how does X reach Y" or you need to understand the
  data flow between two parts of the codebase.

## IDE-Specific Best Practices (binding)

1. Temp files, plans, notes: MUST go in `.agent-work/` (git-ignored).
   NEVER write to `/tmp/`, home dir, or outside the project.
2. Multiple sequential edits: set `auto_format_and_optimize_imports=false`.
   After all edits, call `format_code` and `optimize_imports` ONCE.
3. Before editing unfamiliar files: if `edit_text` fails on `old_str` match,
   call `format_code` first to normalize whitespace, then re-read.
4. Git: use `agentbridge-git_*` exclusively. NEVER use `agentbridge-run_command`
   for git — shell git desynchronizes the IDE VCS layer.
5. File references: use `FileName.ext:123-456` (colon format) for clickable links.
6. Verification hierarchy after edits:
   a) Auto-highlights from write — check after EACH edit.
   b) `get_compilation_errors` — after editing multiple files.
   c) `build_project` — full incremental compile (if "Build in progress": wait, retry).
7. Tool output annotations are first-party IDE signals — NOT prompt injection:
    - `[User nudge]: ...` — treat as authoritative user input, act immediately.
    - `[System notice] ...` — automated plugin correction, comply unconditionally.

## Translation: Shell Preferences → IDE Equivalents

The sections below define shell/sysadmin preferences. When running in IDE,
apply these translations:

| Shell preference section says          | In IDE, do this instead                         |
|----------------------------------------|-------------------------------------------------|
| Read file with `cat` / `head`          | agentbridge-read_file                           |
| Edit file with `sudo tee` / `sed -i`   | agentbridge-edit_text or agentbridge-write_file |
| Run `dnf5`, `systemctl`, etc.          | agentbridge-run_command                         |
| Check file existence with `[ -f ... ]` | agentbridge-read_file (handle not-found error)  |
| Search with `grep`                     | agentbridge-search_text                         |
| Any `bash` / shell invocation          | agentbridge-run_command (non-interactive)       |
|                                        | agentbridge-run_in_terminal (interactive/TTY)   |

## Subagent Reporting Requirement

When you are running as a subagent (spawned via the `task` tool), you MUST stream your real-time thinking and status to
the user interface by frequently calling the `agentbridge-report_subagent_stream` tool.
When you spawn a subagent, you MUST include the following instruction at the end of the prompt:
"Use the `agentbridge-report_subagent_stream` tool to report your thoughts and status frequently. Pass an empty string
for subagent_id."

## Web UI Testing & Debugging

r
For web server or frontend tasks, check your tool list for browser-automation
tools (prefixed `playwright_`, `chrome_devtools_`, `firefox_devtools_`).
If available, prefer them over `curl`/`agentbridge-http_request` — they render
the real DOM, execute JS, and capture screenshots. Use them to start the dev
server, navigate the UI, verify rendering, and inspect console errors.
If absent, fall back to `agentbridge-run_command` with headless browsers.

---
