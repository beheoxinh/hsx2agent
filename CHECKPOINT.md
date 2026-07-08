# CHECKPOINT: Session Corruption Recovery

**Branch:** `feature/unknow-error`
**Latest commit:** `6b65a7d` — 3 files changed, +555 -22
**Build:** ✅ PASS (0 errors, 0 new warnings)
**Tests:** ✅ ALL PASS

---

## 1. Root Cause Summary

Error: `"Session corrupted — OpenCode returned an empty response (likely a history compaction error)"`

**Chain:**
1. OpenCode history compaction state machine gets stuck (`time_completed` never written)
2. Stale record lives in shared SQLite DB (`~/.local/share/opencode/opencode.db`)
3. Persists across process restarts
4. OpenCode refuses to create new session
5. AgentBridge sends `session/prompt` → OpenCode returns `end_turn` with 0 content blocks
6. `PromptOrchestrator.kt:297` detects `!turnHadContent` → `handleSessionCorrupted()`
7. Message generated at `PromptErrorClassifier.kt:134-135`

---

## 2. Files Changed

| File | Status | Changes |
|------|--------|---------|
| `OpenCodeClient.java` | ✅ COMMITTED | Refactored `cleanupCorruptedSessions()` into public + package-private overload; fixed SQL filter to also catch `time_compacting IS NOT NULL AND time_completed IS NULL`; safe TOTP cleanup (check table existence); workspace cleanup deletes all `opencode.workspace.*` files unconditionally |
| `PromptOrchestrator.kt` | ✅ COMMITTED | Restructured `handleSessionCorrupted()`: **capture reference → clearSessionResumeState → stop() → cleanupCorruptedSessions() → start()**. Fixed critical ordering bug where cleanup ran while process alive. |
| `OpenCodeClientCorruptionCleanupTest.java` | ✅ COMMITTED (NEW) | 15 unit tests across 4 categories |

---

## 3. The Critical Fix: Process Order

**OLD (broken):**
```
restartFresh()        ← synchronized: stop + start in one call → new process is ALIVE
cleanupCorruptedSessions()  ← process holds SQLite WAL lock → DELETE silently fails
```

**NEW (fixed):**
```
stop()                ← kills process → releases SQLite WAL locks
cleanupCorruptedSessions()  ← process is DEAD → DELETE succeeds
start()               ← fresh process reads clean DB
```

---

## 4. Test Results

### New tests: `OpenCodeClientCorruptionCleanupTest` — 15/15 ✅

```
SessionCleanup:
├── deletesSessionWhereTimeCompactingIsNull                    ✅
├── deletesSessionWhereCompactionStartedButNeverCompleted      ✅
├── keepsSessionWhereCompactionCompleted                       ✅
├── handlesMixedCorruptionLevels                               ✅
├── doesNothingWhenDbDoesNotExist                              ✅
├── doesNothingWhenDbIsEmpty                                   ✅
└── handlesCorruptedDbFile                                     ✅

TotpCleanup:
├── clearsTotpEntriesWhenTableExists                           ✅
├── doesNothingWhenTotpTableDoesNotExist                       ✅
└── totpCleanupDoesNotAffectHealthySessions                    ✅

WorkspaceCleanup:
├── deletesStaleWorkspaceFiles                                 ✅
├── doesNothingWhenWorkspaceDirDoesNotExist                    ✅
└── workspaceCleanupIsNotBlockedByMissingDb                    ✅

ConcurrentAccess:
├── cleanupWorksWhenDbIsInWalMode                              ✅
└── cleanupIsIdempotent                                        ✅
```

### Existing tests — ALL PASS ✅
```
OpenCodeClientBehaviorTest — 4/4 ✅
OpenCodeClientStaticMethodsTest — 4/4 ✅
```

### Full build: 0 errors, 0 new warnings ✅

---

## 5. Key Design Decisions

1. **Package-private overload**: `cleanupCorruptedSessions(Path, String)` exists alongside the public no-arg version. Tests inject temp paths, production uses `~/.local/share/opencode/opencode.db`.

2. **SQL filter covers 2 corruption modes**:
   - `time_compacting IS NULL` — compaction never started (process crashed before compacting)
   - `time_compacting IS NOT NULL AND time_completed IS NULL` — compaction started but never finished (crashed mid-compaction)

3. **TOTP table safety**: Uses `DatabaseMetaData.getTables()` to check existence before DELETE, since the table only appears after OpenCode's first auth attempt.

4. **Workspace cleanup unconditional**: Deletes all `opencode.workspace.*` files without reading content. Safe because `start()` creates fresh workspace files.

5. **Separate stop/start instead of restartFresh()**: `restartFresh()` is a single synchronized block. By separating, we guarantee cleanup runs between process death and process birth.

---

## 6. Verification Checklist

- [x] Compilation: 0 errors across 1115 files
- [x] New unit tests: 15/15 pass
- [x] Existing OpenCode tests: 8/8 pass
- [x] Full build: 0 errors, 4 pre-existing warnings only
- [x] Diff reviewed: correct ordering, no logic gaps
- [x] `PromptOrchestrator.handleSessionCorrupted()`:
  - [x] `agentManager.client.dropCurrentSession()` called while client alive
  - [x] OpenCodeClient reference captured before `stop()` nulls it
  - [x] `clearSessionResumeState()` called before `stop()` (wipes resume ID)
  - [x] `agentManager.stop()` kills process (releases SQLite locks)
  - [x] `openCodeClient.cleanupCorruptedSessions()` runs with process DEAD
  - [x] `agentManager.start()` creates fresh process
  - [x] UI cleanup runs after all phases complete
- [x] `OpenCodeClient.cleanupCorruptedSessions()`:
  - [x] Never-compacted sessions deleted
  - [x] Stuck-compaction sessions deleted
  - [x] Healthy sessions preserved
  - [x] TOTP cache cleared (only if table exists)
  - [x] Stale workspace files deleted
  - [x] Handles missing DB, empty DB, corrupt DB gracefully

---

## 7. Not Yet Implemented (Future Work)

- [ ] Integration test with real OpenCode binary (needs `opencode` on PATH)
- [ ] Verify cleanup does not interfere with concurrent IDE instances sharing the same DB
- [ ] `PromptOrchestratorTest` — no existing test infrastructure for UI orchestration
