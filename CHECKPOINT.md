# CHECKPOINT — Conversation History Flow Analysis

> Phân tích chi tiết toàn bộ luồng load, lưu, khôi phục history chat và history toolcall.
> Thực hiện bởi Senior BA + SA + TechLead review codebase ngày 2026-07-28.

---

## 1. TỔNG QUAN KIẾN TRÚC

### 1.1. Công nghệ lưu trữ

- **Primary Store**: SQLite database file `conversation.db`
  - Location: `{AgentBridgeStorageSettings.getProjectStorageDir(project)}/conversation.db`
  - JDBC driver: `org.sqlite.JDBC`
  - WAL mode (`PRAGMA journal_mode = WAL`), synchronous NORMAL, foreign keys ON
- **Legacy Format (V1 JSON)**: `conversation.json` file (đã deprecated, chỉ đọc để migration)
- **Legacy Format (V2 JSONL)**: Các file `.jsonl` trong thư mục sessions (đã deprecated, được migrate vào SQLite)
- **Session ID tracking**: File `.current-session-id` chứa UUID của session hiện tại
- **Schema version tracking**: Bảng `schema_version` trong SQLite

### 1.2. Các package chính

| Package | Vai trò |
|---------|---------|
| `session.db` | Core: Database, Writer, Reader, Query, Schema, Service, Statistics |
| `session.v2` | Legacy: JSONL parsing utilities (SessionStoreV2, EntryDataJsonAdapter) |
| `session.exporters` | Export tools (various client exporters, ExportUtils) |
| `session.migration` | V1→V2→SQLite migration |
| `ui` | UI layer: ChatToolWindowContent, ChatConsolePanel, PromptOrchestrator, v.v. |
| `ui.side` | Side panels: PromptsPanel, ToolCallsWebPanel, HistoryContextWindow |

### 1.3. EntryData — Data Model cốt lõi

File: `ChatDataModel.kt` — `sealed class EntryData`

| Subtype | Mục đích | Persisted? | Ghi chú |
|---------|----------|------------|---------|
| `Prompt` | User message, mở đầu một turn | ✅ | Có isSilent flag |
| `Text` | Assistant text response | ✅ | |
| `Thinking` | Thinking/reasoning blocks | ✅ | |
| `ToolCall` | Tool invocation | ✅ | Nhiều field: arguments, result, status, duration, hookStages |
| `SubAgent` | Sub-agent invocation | ✅ | |
| `Nudge` | Nudge message (human/reprimand) | ✅ | Chỉ sent nudges được persist |
| `TurnStats` | Turn statistics (tokens, cost, v.v.) | ✅ | Cả turn-level và session-level totals |
| `ContextFiles` | Files attached to context | ✅ | |
| `Status` | Status/error messages | ❌ | UI-only |
| `SessionSeparator` | Session boundary marker | ❌ | UI-only |

---

## 2. DATABASE SCHEMA (SQLite)

File: `ConversationSchema.java` — 13 tables, schema version 6.

### 2.1. Bảng `sessions`
```
id            TEXT PRIMARY KEY      -- UUID session
agent_name    TEXT NOT NULL         -- "GitHub Copilot", "Claude Code", v.v.
client_id     TEXT NOT NULL         -- ACP client identifier ("copilot", "opencode")
display_name  TEXT                  -- Human-readable name, set from first prompt text
started_at    TEXT NOT NULL         -- ISO 8601
ended_at      TEXT                  -- ISO 8601, updated after each write batch
```

### 2.2. Bảng `turns` (1 turn = 1 user prompt + response)
```
id                   TEXT PRIMARY KEY   -- UUID (entryId từ Prompt)
session_id           TEXT NOT NULL      -- FK → sessions(id) ON DELETE CASCADE
prompt_text          TEXT NOT NULL      -- Nội dung prompt
started_at           TEXT NOT NULL      -- ISO 8601
ended_at             TEXT               -- ISO 8601, set bởi TurnStats
model                TEXT               -- Model ID
token_multiplier     REAL
input_tokens         INTEGER
output_tokens        INTEGER
cost_usd             REAL
duration_ms          INTEGER
tool_call_count      INTEGER
lines_added          INTEGER
lines_removed        INTEGER
git_branch_at_start  TEXT
git_branch_at_end    TEXT
git_commit_at_start  TEXT
git_commit_at_end    TEXT
is_silent            INTEGER DEFAULT 0  -- V5 added
```

### 2.3. Bảng `events` (parent table cho tất cả event subtypes)
```
id            TEXT PRIMARY KEY
turn_id       TEXT               -- FK → turns(id) ON DELETE CASCADE, NULLABLE (standalone events)
sequence_num  INTEGER NOT NULL   -- Thứ tự trong turn
event_type    TEXT NOT NULL      -- "text", "thinking", "tool_call", "sub_agent", "nudge"
agent_name    TEXT               -- Agent display name
model         TEXT               -- Model ID
timestamp     TEXT NOT NULL      -- ISO 8601
```

### 2.4. Event subtype tables
- `text_events(event_id PK → events, content TEXT NOT NULL)`
- `thinking_events(event_id PK → events, content TEXT NOT NULL)`
- `tool_call_events(event_id PK → events, tool_name, tool_kind, category, client_id, display_name, arguments, result, input_size_bytes, output_size_bytes, duration_ms, success, error_message, status, file_path, auto_denied, denial_reason, is_mcp INTEGER NULL, plugin_version TEXT)`
- `sub_agent_events(event_id PK → events, agent_type, description, prompt_text, result_text, status, call_id, auto_denied, denial_reason)`
- `nudge_events(event_id PK → events, text, nudge_id, source TEXT DEFAULT 'human')`

### 2.5. Bảng phụ trợ
- `turn_context_files(id AI PK, turn_id FK → turns, file_name, file_path, file_line)`
- `commits(id AI PK, turn_id FK → turns, commit_hash, UNIQUE(turn_id, commit_hash))`
- `hook_executions(id AI PK, tool_event_id FK → events, trigger_kind, entry_id, command, exit_code, duration_ms, input_payload, output_payload, outcome, outcome_reason, timestamp)`
- `schema_version(version INTEGER PK, applied_at TEXT)`

### 2.6. Schema migration strategy
- `ConversationSchema.createOrMigrate()` chạy mỗi lần DB khởi tạo
- Đọc schema version từ `schema_version` table
- Chạy lần lượt `applyV1` → `applyV6` nếu version < current
- INSERT version mới vào `schema_version`

---

## 3. LUỒNG WRITE — GHI HISTORY XUỐNG SQLITE

### 3.1. Entry point: `ChatToolWindowContent.appendNewEntries()`

```
appendNewEntries()
  ├── Lấy allEntries = deferredEntries() + chatConsolePanel.getEntries()
  ├── Tính newEntries = allEntries.drop(persistedEntryCount)
  ├── Gọi conversationStore.appendEntriesAsync(project.basePath, newEntries)
  └── persistedEntryCount = allEntries.size
```

**Chi tiết luồng:**

1. **`appendNewEntries()`** (line 3493): Gọi trên EDT, lấy tất cả entries hiện tại (deferred from disk + in-memory), tính diff từ `persistedEntryCount`, gọi `appendEntriesAsync`.

2. **`appendNewEntriesThrottled()`** (line 3509): Gọi sau mỗi tool call completion, chỉ persist nếu ≥ 30s từ lần cuối (tránh data loss trên crash).

3. **`ConversationService.appendEntriesAsync()`** (line 112):
   - Tạo snapshot immutable của entries
   - Chain vào `pendingSave` CompletableFuture (sequential execution guarantee)
   - Chạy trên `AppExecutorUtil.getAppExecutorService()` (pooled thread)

4. **`ConversationService.appendEntries()`**:
   - Lấy `currentAgent` từ volatile field
   - Lấy `sessionId` từ `getCurrentSessionId(basePath)` (đọc file `.current-session-id` hoặc tạo mới)
   - `getOrCreateWriter()` → nếu writer chưa tồn tại, init DB và tạo `ConversationWriter`

5. **`ConversationWriter.recordEntries(sessionId, agentName, clientId, entries)`**:
   - `synchronized(database)` — toàn bộ write là thread-safe
   - Kiểm tra connection null → skip silently
   - `writeEntriesInTransaction(conn, sessionId, agentName, clientId, entries)`

6. **`writeEntriesInTransaction()`**:
   - `conn.setAutoCommit(false)` → manual transaction
   - `ensureSession()` → INSERT OR IGNORE vào sessions table
   - Duyệt từng entry, gọi `writeEntry()` cho mỗi entry
   - `updateSessionEndedAt()` → UPDATE sessions.ended_at từ MAX turns.ended_at
   - `conn.commit()` hoặc `conn.rollback()` nếu lỗi

### 3.2. Mapping từng EntryData subtype → SQL

| EntryData Subtype | Action | Bảng |
|------------------|--------|------|
| `Prompt` | `openTurn()` → INSERT INTO turns | turns, turn_context_files |
| `Text` | `insertEvent()` + `insertSubtype()` | events + text_events |
| `Thinking` | `insertEvent()` + `insertSubtype()` | events + thinking_events |
| `ToolCall` | `insertEvent()` + `insertToolCall()` | events + tool_call_events |
| `SubAgent` | `insertEvent()` + `insertSubAgent()` | events + sub_agent_events |
| `Nudge` | `writeNudge()` (chỉ sent=true) | events + nudge_events |
| `TurnStats` | `finaliseTurn()` → UPDATE turns | turns, commits |
| `ContextFiles` | `insertContextFiles()` | turn_context_files |
| `Status` | **BỊ BỎ QUA** | — |
| `SessionSeparator` | **BỊ BỎ QUA** | — |

### 3.3. Cursor Management

- `SessionCursor` per-session: track `turnId` (latest open turn) và `sequenceNum` (next sequence number)
- `restoreCursor()`: để khôi phục cursor khi cần (ví dụ sau khi load history)
- Mỗi `Prompt` mở turn mới, các events sau đó gắn vào turn này cho đến `TurnStats` hoặc `Prompt` tiếp theo

### 3.4. TurnStats finalisation

`finaliseTurn()`:
- UPDATE turns SET: ended_at, model, token_multiplier, input_tokens, output_tokens, cost_usd, duration_ms, tool_call_count, lines_added, lines_removed, git_branch_at_start, git_branch_at_end
- INSERT commit hashes vào bảng `commits`

### 3.5. MCP enrichment (post-hoc updates)

- `enrichToolCallStats()`: UPDATE tool_call_events với input/output size, duration, success, error_message, category, display_name, plugin_version, file_path, is_mcp=1
- `markToolCallNonMcp()`: UPDATE is_mcp=0 WHERE is_mcp IS NULL (ACP-only tool calls)
- `recordHookStages()`: INSERT INTO hook_executions (có deferred insert nếu event row chưa tồn tại)

### 3.6. Throttle và data loss prevention

- `saveIntervalMs = 30_000L`: persist 30s/lần trong streaming
- `lastIncrementalSaveMs`: track thời gian persist gần nhất
- `pendingSave` CompletableFuture chain: đảm bảo sequence (UPDATE không chạy trước INSERT)
- `awaitPendingSave(3000)` trong `dispose()`: chờ persist hoàn tất trước khi shutdown

---

## 4. LUỒNG READ — ĐỌC HISTORY TỪ SQLITE

### 4.1. Load toàn bộ entries cho một session

`ConversationReader.loadEntries(sessionId)`:
1. SELECT turns WHERE session_id = ? ORDER BY started_at ASC
2. Với mỗi turn:
   - Tạo EntryData.Prompt với context files
   - `loadEventsForTurn()` → SELECT events WHERE turn_id = ? ORDER BY sequence_num
   - Với mỗi event, `loadEventSubtype()` theo event_type:
     - "text" → Text
     - "thinking" → Thinking
     - "tool_call" → ToolCall (bao gồm hook stages)
     - "sub_agent" → SubAgent
     - "nudge" → Nudge
   - `addTurnStatsIfPresent()` → nếu turn có ended_at, tạo TurnStats

### 4.2. Load recent entries (tail-read)

`ConversationReader.loadRecentEntries(sessionId, maxTurns=50)`:
1. SELECT id FROM turns WHERE session_id = ? ORDER BY started_at DESC LIMIT 50
2. Reverse to chronological order
3. Load từng turn như trên

### 4.3. Conversion DB record → EntryData

**Prompt reconstruction:**
- `promptText` + `startedAt` + `contextFiles` + `turnId` + `isSilent`

**Text reconstruction:**
- `content` + `timestamp` + `agent` + `model` + `eventId`

**ToolCall reconstruction:**
- `tool_name` → title
- `arguments`, `tool_kind`, `result`, `status`, `file_path`, `auto_denied`, `denial_reason`
- `is_mcp` → pluginTool (strip MCP prefix)
- `hookStages` từ bảng hook_executions

**TurnStats reconstruction:**
- Từ các cột: ended_at, model, token_multiplier, input_tokens, output_tokens, cost_usd, duration_ms, tool_call_count, lines_added, lines_removed, git_branch_at_start, git_branch_at_end
- Commit hashes từ bảng commits

### 4.4. ToolCallHistoryEntry (cho ToolCallsWebPanel)

`ConversationQuery.loadToolCallHistory(limit, beforeEventId, sessionId)`:
1. SELECT từ events JOIN tool_call_events WHERE is_mcp = 1
2. Pagination bằng cursor-based (timestamp, id) < (beforeTimestamp, beforeId)
3. Batch-load hook stages cho tất cả event IDs (tránh N+1)
4. Return `ToolCallHistoryEntry` list

---

## 5. LUỒNG RESTORE CONVERSATION — KHÔI PHỤC HISTORY KHI MỞ CHAT

### 5.1. Trigger points

`restoreConversation()` được gọi từ:
- `buildAndShowChatPanel()` (line 411) — khi auto-connect hoặc reconnect
- `connectToAgent()` → `buildAndShowChatPanel()` (line 491 comment)
- Không gọi khi `resetSession()` (clear and restart)

### 5.2. Chi tiết restoreConversation()

File: `ChatToolWindowContent.kt:3535`

```
restoreConversation(onComplete)
  ├── LiveToolCallService.getInstance(project).clear()
  ├── executeOnPooledThread {
  │     V1ToV2Migrator.migrateIfNeeded(project.basePath)  // Legacy migration
  │     val result = conversationStore.loadRecentEntries(project.basePath)
  │     invokeLater {
  │         if entries not empty:
  │             restoreEntries(entries, hasMoreOnDisk)
  │             updateSessionInfo()
  │         onComplete()
  │     }
  │   }
```

### 5.3. restoreEntries()

File: `ChatToolWindowContent.kt:3552`

```
restoreEntries(entries, hasMoreOnDisk)
  ├── conversationReplayer.loadAndSplit(entries, recentTurnsOnRestore, hasMoreOnDisk)
  ├── broadcastPanel.appendEntries(recentEntries, totalPromptCount)
  ├── showDeferredRestoreCount()  // Show "Load more" button if deferred entries exist
  └── restoreTurnStats(turnStatsList)  // Khôi phục bộ đếm billing/processing timer
```

### 5.4. ConversationReplayer.loadAndSplit()

File: `ConversationReplayer.kt`

Chia entries thành 2 nhóm:
1. **Deferred entries**: entries cũ hơn `recentTurns` (mặc định 5) — không render ngay
2. **Recent entries**: `recentTurns` turn gần nhất — render ngay lập tức

Thuật toán split: đếm từ cuối lên đầu, tìm vị trí của turn thứ `recentTurns`, split tại đó.

### 5.5. Load more (pagination)

`onLoadMoreHistory()`:
1. `conversationReplayer.loadNextBatch(batchSize)` — pop N prompt-turns từ deferred queue
2. `broadcastPanel.prependEntries(batch)` — prepend vào UI
3. Cập nhật "Load more" button hoặc "Older history exists" message

`ConversationReplayer.loadNextBatch(turnsToLoad=3)`:
- Đếm ngược từ cuối deferred queue, lấy N prompt-turns và tất cả entries giữa chúng
- Pop khỏi deferred queue

---

## 6. LUỒNG ARCHIVE — KHI KẾT THÚC SESSION

### 6.1. archiveConversation()

File: `ChatToolWindowContent.kt:3787`

```
archiveConversation()
  ├── Nếu memory mining enabled: mineTurn(entries, sessionId, agentName)
  ├── conversationStore.archive()     // NO-OP: giữ .current-session-id
  └── persistedEntryCount = 0
```

Đây là **NO-OP** có chủ đích — file `.current-session-id` KHÔNG bị xoá để `restoreConversation()` có thể đọc lại.

### 6.2. resetSession() (Clear and Restart)

```
resetSession()
  ├── agentManager.clearSessionResumeState()
  ├── resetSessionState()            // Xoá in-memory state
  ├── LiveToolCallService.clear()
  ├── sidePanel?.clearToolCalls()
  ├── consolePanel.clear()
  ├── AgentEditSession.clearSession()
  ├── archiveConversation()          // Memory mining + archive NO-OP
  └── conversationStore.resetCurrentSessionId(basePath)  // Xoá .current-session-id
```

### 6.3. resetSessionKeepingHistory()

```
resetSessionKeepingHistory()
  ├── resetSessionState()
  └── updateSessionInfo()
```

Giữ nguyên history trong panel, chỉ reset orchestrator state.

---

## 7. SESSION ID MANAGEMENT

### 7.1. File-based session tracking

File: `.current-session-id` trong `ExportUtils.sessionsDir(project)`

**Đọc**: `ConversationService.getCurrentSessionId(basePath)`:
1. Kiểm tra file tồn tại → đọc UUID
2. Nếu không tồn tại hoặc rỗng → generate UUID mới, write vào file
3. Nếu I/O error → fallback về transientSessionId in-memory

**Xoá**: `ConversationService.resetCurrentSessionId(basePath)`:
- Files.deleteIfExists → next call sẽ tạo UUID mới

**Archive**: `ConversationService.archive()` — NO-OP có chủ đích

### 7.2. Session resume flow

1. `PromptOrchestrator.ensureSessionCreated(client)`:
   - `client.createSession(project.basePath)` → ACP session creation
   - Lưu `currentSessionId`
   - Nếu session mới (≠ lastInitialisedSessionId):
     - `updateSessionInfo()`
     - Restore saved model và session options

2. `archiveConversation()` được gọi trong `buildAndShowChatPanel()` TRƯỚC khi restore:
   - Mục đích: finalize session cũ trước khi bắt đầu session mới
   - Nhưng không xoá .current-session-id

3. `resetSession()` gọi `resetCurrentSessionId()` SAU archive:
   - Xoá .current-session-id để tạo session hoàn toàn mới

---

## 8. TOOL CALL HISTORY — SIDE PANEL

### 8.1. ToolCallsWebPanel

File: `side/ToolCallsWebPanel.kt`

- JCEF browser rendering tool call history
- `loadHistoryPage(beforeEventId)`: load từ ConversationQuery.loadToolCallHistory
- `pushAllEntries(entries)`: push live tool calls từ LiveToolCallService
- Pagination: cursor-based (beforeEventId = event ID của item cuối cùng)
- Mỗi entry hiển thị: tool name, arguments, result, duration, hook stages

### 8.2. ToolCallPopup

File: `ToolCallPopup.kt`

- Hiển thị chi tiết tool call khi user click
- Tìm live entry trước (theo callId), fallback về historic (theo eventId)
- `fromHistoricEntry()`: dùng ConversationQuery.findToolCall(eventId)
- Hiển thị: params, result, description, hook stages, auto-denied status

### 8.3. LiveToolCallService ↔ ConversationQuery

Live tool calls: từ `LiveToolCallService.entries` (in-memory, real-time updates)
Historic tool calls: từ `ConversationQuery.loadToolCallHistory()` (SQLite)

Sự kiện tool call completion:
1. ACP streaming nhận ToolCallUpdate với status COMPLETED/FAILED
2. `PromptOrchestrator.handleStreamingToolCallUpdate()` → update UI
3. `ToolCallTracker.acpComplete()` mark completion
4. `callbacks.appendNewEntriesThrottled()` → persist vào SQLite (30s throttle)

---

## 9. HISTORY SEARCH — PROMPTS PANEL

File: `side/PromptsPanel.kt`

### 9.1. Search capabilities

- **Full-text search**: `ConversationQuery` với `QueryParams.combinedText()`
- **Search scopes**: USER_PROMPT, TEXT_EVENTS, THINKING, TOOL_CALLS
- **Filters**: Branch combo, Agent combo, Tool combo, File path field
- **Parameters**: turnId, sessionId, lastN, offset, since, until, v.v.

### 9.2. Query execution

`ConversationQuery.query(QueryParams)`:
1. Build dynamic WHERE clauses từ các params khác null
2. Combined text search: OR logic trên các scopes đã chọn
3. Silent turns bị loại trừ: `COALESCE(t.is_silent, 0) = 0`
4. Mặc định LIMIT 500 nếu không có lastN
5. JOIN sessions để lấy agent_name, display_name
6. Tính prev_turn_id bằng subquery (started_at < current OR same started_at but smaller ID)
7. Order by started_at DESC

### 9.3. Result types

- `TurnSummary` — dùng cho PromptsPanel và query_conversation_history MCP tool
- `ToolCallHistoryEntry` — dùng cho ToolCallsWebPanel
- `ToolCallSummary` — brief tool call info trong TurnSummary

---

## 10. CONVERSATION INJECTION (Agent context)

File: `PromptOrchestrator.kt:442`, `ActiveAgentManager.getInjectConversationHistory()`

### 10.1. Cơ chế

- Setting: "Inject conversation history" trong ChatHistoryConfigurable
- `PromptOrchestrator.buildEffectivePrompt()` kiểm tra flag
- Nếu enabled: chèn `consolePanel().getCompressedSummary()` (từ ConversationExporter) vào prompt

### 10.2. CompressedSummary logic

File: `ConversationExporter.getCompressedSummary(maxChars=4000)`

1. Group entries thành turns (Prompt → Text/ToolCall/Thinking)
2. 2 turns gần nhất: full text
3. Turns cũ hơn: truncate 300 ký tự mỗi turn, đánh dấu [truncated]
4. Tool calls, thoughts, sub-agents: thay bằng counters [N tool calls, M thoughts]
5. Build từ newest → oldest, dừng khi hết budget maxChars
6. Header hint: "Use query_conversation_history(last_n=N) to read recent turns in full."

---

## 11. LEGACY MIGRATION

### 11.1. V1 → V2 (JSON → JSONL)

File: `V1ToV2Migrator.java`

- Điều kiện chạy: flag `agentbridge.allow.db.migration`
- Check: nếu `sessions-index.json` đã tồn tại → skip
- Đọc file `conversation.json` (V1 format)
- Parse bằng `ConversationSerializer.deserialize()`
- Split entries bằng SessionSeparator → multiple sessions
- Mỗi session write thành file `.jsonl` riêng
- Tạo `sessions-index.json`

### 11.2. JSONL → SQLite

File: `JsonlToSqliteMigrator.java`

- Chạy trong `ConversationDatabase.initialize()`: `migrateIfNeeded(project)`
- Đọc tất cả file `.jsonl` từ sessions directory
- Parse từng dòng bằng `EntryDataJsonAdapter.deserialize()`
- Write vào SQLite bằng `ConversationWriter`
- INSERT OR IGNORE → idempotent (skip already-migrated sessions)
- Sau thành công: move JSONL files vào `sessions-backup-jsonl/`
- Chạy một lần duy nhất (backup files tồn tại → skip)

---

## 12. EVENT & NOTIFICATION SYSTEM

### 12.1. ConversationListener

File: `ConversationListener.java`

```
interface ConversationListener {
    fun historyChanged(allHistoryCleared: Boolean)
    fun connectionChanged(connected: Boolean)
}
```

- `TOPIC`: `Topic.create("ConversationHistoryChanged", ConversationListener.class)` @ProjectLevel
- Broadcast qua `messageBus.syncPublisher()`

### 12.2. Who subscribes?

| Subscriber | Event | Action |
|-----------|-------|--------|
| `ChatToolWindowContent.subscribeToHistoryEvents()` | `historyChanged(true)` | `resetSession()`, clear state |
| `PromptsPanel` (entriesChangeListener) | entries change | `reloadHistoryAsync()` |

### 12.3. Trigger points

| Action | Event |
|--------|-------|
| `deleteTurn()` | `historyChanged(false)` |
| `deleteOtherSessions()` | `historyChanged(false)` |
| `deleteSessionsOlderThan()` | `historyChanged(false)` |
| `deleteAllHistory()` | `historyChanged(true)` |

---

## 13. CÁC EDGE CASES VÀ XỬ LÝ

### 13.1. Cross-session turn ID collision

Trong `ConversationWriter.insertOrResolveConflict()`:
1. INSERT OR IGNORE với desiredId
2. Nếu ignored, kiểm tra existing row có cùng session_id không
3. Nếu là row của session mình → idempotent, dùng desiredId
4. Nếu là cross-session collision → generate UUID mới

### 13.2. Database không available

- `ConversationDatabase.getConnection()` có thể trả về null
- Callers phải check null và fallback gracefully
- Writer: skip write silently (log debug)
- Reader: trả về empty list
- `ConversationService.getOrCreateWriter()` / `getOrCreateReader()`: nếu DB chưa init, init ngay

### 13.3. Entry không có timestamp

- `ConversationWriter.writeEntriesInTransaction()`: nếu không entry nào có timestamp → throw IllegalArgumentException
- `openTurn()`: Prompt không có timestamp → throw IllegalArgumentException
- `insertEvent()`: event không có timestamp → throw IllegalArgumentException

### 13.4. Silent turns

- `EntryData.Prompt.isSilent = true` → không render trong UI, không tính turn counter
- `ConversationQuery` loại trừ silent turns: `COALESCE(t.is_silent, 0) = 0`
- Writer vẫn persist silent turns (có is_silent flag)

### 13.5. Standalone events (turn_id IS NULL)

- events.turn_id nullable → support tool calls outside any ACP turn
- `idx_events_standalone` index: `WHERE turn_id IS NULL`
- Writer: `cursor.turnId == null` → `ps.setNull(2, Types.VARCHAR)`

### 13.6. Deferred hook stages

- `recordHookStages()` kiểm tra event row tồn tại trước khi insert
- Nếu chưa tồn tại → defer silently (log debug)
- Hook stages sẽ được insert khi event row đã được persist

---

## 14. SUMMARY — Key flow diagrams

### 14.1. Write flow (normal turn)
```
User sends prompt
  → submitTurn()
    → consolePanel.addPromptEntry()         // UI render
    → appendNewEntries()                     // Persist Prompt
    → PromptOrchestrator.execute()
      → Agent streams response
        → appendText() / appendThinking()    // UI render
        → handleStreamingToolCall()          // UI render + track
        → handleStreamingToolCallUpdate()
          → appendNewEntriesThrottled()      // Persist mỗi 30s
      → Turn completion
        → emitTurnStats()                    // UI render stats
        → appendNewEntries()                 // Persist TurnStats + events
```

### 14.2. Restore flow (connect/reconnect)
```
buildAndShowChatPanel()
  ├── archiveConversation()          // Memory mining + archive NO-OP
  ├── restoreConversation()
  │     ├── V1ToV2Migrator.migrateIfNeeded()
  │     ├── ConversationService.loadRecentEntries(basePath)
  │     │     └── ConversationReader.loadRecentEntries(sessionId, 50)
  │     └── restoreEntries(entries, hasMore)
  │           ├── ConversationReplayer.loadAndSplit(entries, 5)
  │           ├── broadcastPanel.appendEntries(recent, totalPromptCount)
  │           └── restoreTurnStats(stats)
  └── addSeparatorNow()             // SessionSeparator
```

### 14.3. Clear and restart flow
```
resetSession()
  ├── agentManager.clearSessionResumeState()
  ├── resetSessionState()
  ├── LiveToolCallService.clear()
  ├── sidePanel?.clearToolCalls()
  ├── consolePanel.clear()
  ├── archiveConversation()
  └── conversationStore.resetCurrentSessionId(basePath)
```

### 14.4. Tool call history load (side panel)
```
ToolCallsWebPanel.onBrowserReady()
  ├── loadHistoryPage(null)                // Load first page từ SQLite
  │     └── ConversationQuery.loadToolCallHistory(PAGE_SIZE, null, sessionId)
  └── pushAllEntries(service.entries)      // Load live entries từ LiveToolCallService

User scrolls to bottom
  → loadHistoryPage(beforeEventId)          // Load next page
    → ToolCallsController.prependHistoric(entry)
    → ToolCallsController.setHistoryExhausted() nếu entries.size < PAGE_SIZE
```

---

## 15. GHI CHÉP KỸ THUẬT QUAN TRỌNG

1. **Thread safety**: Tất cả operations trên `ConversationDatabase` đều `synchronized(database)`. Reader và Writer giữ lock trên cùng một object → mutual exclusion.

2. **Double-write safety during migration**: Phase 1 migration chạy song song — cả JSONL và SQLite đều được ghi. Sau Phase 3, JSONL bị tắt.

3. **File-based session ID**: `.current-session-id` là single point of truth cho session hiện tại. Nếu file bị xoá khi plugin đang chạy → transientSessionId fallback (in-memory, không persistent).

4. **`archive()` là NO-OP**: Đây là design decision quan trọng. Không xoá .current-session-id để restore có thể đọc lại. Chỉ `resetCurrentSessionId()` mới xoá file.

5. **EntryData là sealed class nhưng không phải data class**: `Text`, `Thinking`, `ToolCall`, `SubAgent` là `class` (có mutable fields), `Prompt`, `TurnStats`, `ContextFiles`, v.v. là `data class`. Cần cẩn thận khi mutate.

6. **is_mcp có 3 trạng thái**: NULL = chưa biết, 1 = confirmed MCP, 0 = confirmed non-MCP. Schema V3 đã thay đổi từ NOT NULL thành nullable.

7. **`pendingSave` CompletableFuture chain**: Đảm bảo tuần tự giữa các async writes. `updateSessionTitle()` được chain sau `appendEntriesAsync()` để tránh UPDATE-before-INSERT race.
