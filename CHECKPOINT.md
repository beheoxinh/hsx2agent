# CHECKPOINT

## Lifecycle Audit — Connect / Disconnect / Restart / Clear

### Architecture Overview

```
doConnect()                         disconnectFromAgent()
    │                                     │
    ├─ detectSingleSync()                 ├─ ++modelLoadGeneration
    ├─ applySessionChoice()               ├─ resetSessionState() [EDT]
    └─ onConnect()                        ├─ agentManager.clearSessionResumeState() [EDT]
         │                                └─ executeOnPooledThread { stop() }
         └─ connectToAgent()
              ├─ switchAgent()             restart() / restartFresh()
              │    └─ stop() + export()        │
              └─ loadModelsAsync()             ├─ exportForRestart()
                   └─ fetchModelsWithRetry()   ├─ stop()
                        ├─ awaitPendingExport  └─ start()
                        ├─ agentManager.client
                        │    └─ getClient()
                        │         ├─ needStart → start()
                        │         └─ return acpClient
                        └─ getAvailableModels()
```

### Findings

#### P0 — Race: getClient() returns null after concurrent stop()

`start()` is `synchronized`, `stop()` is `synchronized`. But `getClient()` reads `acpClient`
**after** `start()` returns — a concurrent `stop()` can set `acpClient = null` in the gap:

```
Thread A: getClient() → start() [sync] → return acpClient (volatile read → null)
Thread B:                                           stop() [sync] → acpClient = null
```

Callers of `getClient()` (fetchModelsWithRetry, promptOrchestrator) NPE.

**Fix:** capture local var `AbstractAgentClient client = acpClient; return client;`

#### P0 — clearSessionResumeState() accesses acpClient without lock

`clearSessionResumeState()` calls `acpClient.dropCurrentSession()` without
synchronization — races with `stop()`/`start()` which set `acpClient = null`.

**Fix:** null-safe access + sync block.

#### P1 — disconnect clears state on EDT then stops async

`disconnectFromAgent()` calls `clearSessionResumeState()` on EDT which sends
ACP `dropCurrentSession` request. Then fires `stop()` async on pooled thread.
If `stop()` hasn't run yet and user clicks Connect, `start()` creates a new
process while the old process is still alive → port conflict / duplicate agent.

**Fix:** sync `clearSessionResumeState()` + defer to pooled thread.

#### P1 — awaitPendingExport(10_000) blocks connect start

`fetchModelsWithRetry()` calls `SessionSwitchService.awaitPendingExport(10_000)`
before `agentManager.client` triggers `start()`. An export stuck on SQLite I/O
delays connection by 10 seconds. Pooled thread is blocked, but EDT is free.

**Fix:** reduce to 3s (export is fast for typical sessions), or make best-effort.

#### P2 — fetchModelsWithRetry Thread.sleep(2000) delays disconnect

On retry (attempt 2+), `Thread.sleep(2000)` blocks pooled thread for 2s.
If user clicks disconnect during this window, the generation check only fires
after sleep returns.

**Fix:** sleep in small increments checking `modelLoadGeneration` periodically.

#### P2 — destroyProcessTree() blocking (FIXED in b54c3ea)

Changed from `handle.destroy()` + `waitFor(5s)` to `handle.destroyForcibly()` +
`waitFor(500ms)`. SIGKILL immediately, brief reap to release zombie.

#### P3 — session/close race during stop

`stop()` calls `sendSessionCloseIfSupported()` (async, orTimeout(5s)) then
immediately `transport.stop()` which interrupts the pending future. The
`whenComplete` logs a spurious "did not complete cleanly" at DEBUG. Harmless.

**Fix:** none needed — best-effort ACP notification before kill.

### Priority Scoring

| # | Issue | Severity | Effort | Impact |
|---|-------|----------|--------|--------|
| 1 | getClient() NPE after concurrent stop() | CRITICAL | 15min | Crash on rapid disconnect→connect |
| 2 | clearSessionResumeState() lock race | CRITICAL | 15min | Data corruption on session switch |
| 3 | disconnect EDT→async stop race | HIGH | 30min | Ghost process, port conflict |
| 4 | awaitPendingExport 10s block | MEDIUM | 10min | Slow connect |
| 5 | Thread.sleep 2s on retry | LOW | 15min | Slightly delayed disconnect |
| 6 | session/close log noise | LOW | 5min | Debug log, harmless |

### Perf Metrics (baseline)

| Operation | Before fix | After fix | Notes |
|-----------|-----------|-----------|-------|
| connect (cold start) | ~3-8s | ~3-8s | Depends on agent startup |
| connect (warm, cached) | ~500ms | ~500ms | |
| disconnect | ~5s (waitFor) | ~500ms | SIGKILL + 500ms reap |
| disconnect→connect race | NPE possible | safe | Local var capture |
| process kill | 5s SIGTERM + 5s fallback | SIGKILL + 500ms | Immediate |
