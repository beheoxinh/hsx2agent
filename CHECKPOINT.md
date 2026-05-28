# CHECKPOINT: Chat History Flow Analysis & Bug Fixes

## Luồng đầy đủ

### 1. User gõ chat → lưu lên đĩa như thế nào

```
User submit → submitTurn()
  → consolePanel.addPromptEntry()           [UI hiển thị ngay]
  → chatConsolePanel.entries thêm EntryData.Prompt
  → appendNewEntries() được gọi sau mỗi tool call (throttled 30s) và cuối turn

appendNewEntries():
  allEntries = conversationReplayer.deferredEntries() + chatConsolePanel.getEntries()
  newEntries = allEntries.drop(persistedEntryCount)    // chỉ lấy entries chưa lưu
  conversationStore.appendEntriesAsync(basePath, newEntries)  // ghi async vào SQLite
  persistedEntryCount = allEntries.size
```

**Storage**: SQLite DB tại `.agent-work/conversations.db`, session ID được track qua file `.agent-work/sessions/.current-session-id`.

### 2. Disconnect → session bảo toàn như thế nào

```
disconnectFromAgent():
  resetSessionState()              // xóa currentSessionId trong PromptOrchestrator
  chatSessionInitialized = false   // flag quan trọng
  connectPanel.resetConnectButton()  // [BUG ở đây!]
  exportForRestart(profileId)      // export session để agent có thể resume
  agentManager.stop()              // kill agent process
```

Session files (.current-session-id, SQLite DB) được **GIỮ NGUYÊN** — disconnect không phải "New Conversation".

### 3. Reconnect (Resume Session) - chọn Latest từ dropdown

```
doConnect() → applySessionChoice() → SessionChoice.Latest:
  switchCurrentSession(record.id)   // ghi record.id vào .current-session-id
  exportForRestart(profileId)       // re-export JSONL cho agent
→ onConnect() → connectToAgent():
  chatSessionInitialized = false (vì disconnect set false)
→ buildAndShowChatPanel():
  archiveConversation()             // no-op (archive() là no-op trong SQLite mode)
  restoreConversation():
    loadRecentEntries()             // đọc .current-session-id → load SQLite entries
    restoreEntries()                // render vào broadcastPanel + conversationReplayer
    persistedEntryCount = totalLoadedCount  // đánh dấu đã sync
  addSeparatorNow()                 // thêm separator mới cho session hiện tại
    appendNewEntries()              // save separator vào DB
```

### 4. Fresh Session (chọn None)

```
applySessionChoice() → SessionChoice.None:
  settings.resumeSessionId = null
  sessionSwitch.clearClaudeResumeState()
  conversationStore.resetCurrentSessionId()  // XÓA .current-session-id!
→ buildAndShowChatPanel() → restoreConversation():
  loadRecentEntries(): idFile.exists() = false → return null
  entries = emptyList() → chat pane trống ✓
```

### 5. Phân nhánh logic trong connectToAgent()

```kotlin
val previousSessionId = if (::promptOrchestrator.isInitialized) 
    promptOrchestrator.currentSessionId else null
val newSessionId = conversationStore.getCurrentSessionId(project.basePath)
val sessionSwitched = previousSessionId != null && previousSessionId != newSessionId

if (sessionSwitched) {
    consolePanel.clear()
    chatSessionInitialized = false   // trigger full restore
}
```

- `previousSessionId` = null nếu vừa disconnect (resetSessionState xóa currentSessionId)
- → `sessionSwitched = false` ngay cả khi session thực sự thay đổi
- → Phụ thuộc hoàn toàn vào `chatSessionInitialized` flag

---

## BUG ĐÃ TÌM RA VÀ FIX

### Bug #1 (Critical): `resetConnectButton()` hardcode session về None sau disconnect

**File**: `plugin-core/src/main/java/com/github/catatafishen/agentbridge/ui/AcpConnectPanel.kt:1533`

**Mô tả**:
Sau khi disconnect, `disconnectFromAgent()` gọi `connectPanel.resetConnectButton()`.
Trong `resetConnectButton()`:
```kotlin
refreshSessionCombo()                          // load sessions từ DB, Latest ở đầu
sessionCombo.selectedItem = SessionChoice.None  // BUG: override lại về None!
```

Kết quả: khi user click Connect lại (không chọn gì), combo đang là `None`.
`applySessionChoice(None)` → `resetCurrentSessionId()` → **xóa .current-session-id** → `restoreConversation()` không tìm thấy gì → **chat history mất**.

**Fix**: Bỏ dòng `sessionCombo.selectedItem = SessionChoice.None` khỏi `resetConnectButton()`.
`refreshSessionCombo()` đã tự động chọn item đầu tiên là `SessionChoice.Latest` (nếu có sessions).
Nếu không có sessions, combo sẽ chỉ có `SessionChoice.None` và sẽ tự động được select.

**Tại sao "fresh session thỉnh thoảng vẫn thấy history"**: Vì nếu user disconnect rồi reconnect nhanh (trước khi session separator được save), chat history vẫn còn vì `.current-session-id` chưa kịp bị xóa trong race condition.

**Tại sao "resume session đôi khi mất history"**: Khi `resetConnectButton()` set `None`, nếu user không nhớ chọn lại `Latest` trước khi click Connect thì history bị xóa.

---

## Trạng thái sau fix

- **Disconnect → Connect lại**: Default chọn Latest (session trước) → history được restore ✓
- **Fresh session**: User phải chủ động chọn `None (fresh session)` từ dropdown ✓
- **Resume older session**: Chọn từ dropdown, hoạt động như trước ✓
- **First time (no sessions)**: Combo chỉ có None → fresh session ✓
