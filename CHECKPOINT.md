# Session Lifecycle Audit

## Current Flow Analysis

### Session ID Management
- Session UUID stored in `.agent-work/sessions/.current-session-id`
- `ConversationService.getCurrentSessionId()`: reads file or generates new UUID
- `ConversationService.resetCurrentSessionId()`: deletes file -> next call generates fresh UUID
- `GenericSettings.resumeSessionId`: stored per-profile in PropertiesComponent, sent to agent via `session/resume`

### Connect Flow
```
AcpConnectPanel.doConnect()
  -> applySessionChoice(profileId)     // Handle "None"/"Latest"/"Older"
  -> onConnect(profileId, customCommand)

ChatToolWindowContent.connectToAgent()
  -> maybe switchAgent()
  -> getCurrentSessionId()             // Read or generate session UUID
  -> sessionSwitched = prev != new
  -> if (sessionSwitched) consolePanel.clear()
  -> loadModelsAsync()
    -> buildAndShowChatPanel()
      -> if (!chatSessionInitialized) restoreConversation()
```

### Disconnect Flow
```
ChatToolWindowContent.disconnectFromAgent()
  -> resetSessionState()               // null orchestrator sessionId
  -> chatSessionInitialized = false
  -> connectPanel.selectFreshSession() // force "None" on next connect
  -> agentManager.stop()
```
**No `consolePanel.clear()`** - old conversation stays in DOM.

---

## Issues Found

### BUG #1: `consolePanel.clear()` never called when reconnecting after disconnect

**Root cause:** `connectToAgent()` at line 472:
```kotlin
val sessionSwitched = previousSessionId != null && previousSessionId != newSessionId
```
After `disconnectFromAgent()`, `promptOrchestrator.currentSessionId = null` (set by `resetSessionState()`).
So `previousSessionId = null` -> `sessionSwitched = false` -> `consolePanel.clear()` SKIPPED.

`buildAndShowChatPanel()` calls `restoreConversation()` which loads entries for the **new** session UUID -> finds nothing. But the OLD conversation remains visible in the DOM because `consolePanel.clear()` was never called.

**Fix:** Clear console when `chatSessionInitialized` was false (fresh start) OR when `sessionSwitched` is true.

**File:** `ChatToolWindowContent.kt:459-497`

---

### BUG #2: `disconnectFromAgent()` doesn't clear the console panel UI

**Root cause:** When user disconnects, the UI switches to connect panel card via `cardLayout.show(mainPanel, CARD_CONNECT)`. The old conversation is still in the browser DOM. If user then reconnects with "Latest" session, `restoreConversation()` prepends old entries on top of the still-visible conversation -> DOM gets duplicate entries.

**Fix:** Call `consolePanel.clear()` in `disconnectFromAgent()`.

---

### BUG #3: `archiveConversation()` is a no-op with misleading name

**Root cause:** `ConversationService.archive()` is documented as:
```java
// Intentional no-op: the session ID file must survive for restoreConversation() to read.
```
Called in `buildAndShowChatPanel()` before every `restoreConversation()`. Since it does nothing, it creates confusion about what session state is being "archived."

**Fix:** Not a functional bug - but the name is misleading. Could remove or implement properly.

---

### BUG #4: `selectFreshSession()` flag consumed before `applySessionChoice`

**Root cause:** `resetConnectButton()` at line 1569-1572:
```kotlin
if (forceFreshSession) {
    sessionCombo.selectedItem = SessionChoice.None
    forceFreshSession = false
}
```
The flag is consumed when `resetConnectButton()` is called (in `disconnectFromAgent()`). If the user manually selects "Latest" from the combo before clicking Connect, the flag has already been consumed and the manual "Latest" selection is honored. This is **correct behavior** - user's manual override should win.

Not a bug, but worth documenting.

---

### BUG #5: `connectToAgent()` should `restoreConversation` even when same session resumed

After `disconnectFromAgent()`, `chatSessionInitialized = false`. In `connectToAgent()`, if the user picks "Latest" session:
- `applySessionChoice("Latest")` writes the latest session ID to `.current-session-id`
- `getCurrentSessionId()` reads it back
- `previousSessionId = null` (from disconnect), `newSessionId = latestId`
- `sessionSwitched = null != null && null != latestId` = `false`
- `consolePanel.clear()` is skipped
- In `buildAndShowChatPanel()`, `chatSessionInitialized = false` so it enters restore block
- `restoreConversation()` loads entries for the latest session ID -> restores old conversation

**This part works** because `chatSessionInitialized = false` from disconnect forces restore. The old conversation was never cleared from DOM, so restore prepends entries on top -> **DUPLICATE ENTRIES in DOM**.

---

## Fix Plan

### Fix 1: Clear console panel on disconnect

**File:** `ChatToolWindowContent.kt` - `disconnectFromAgent()` around line 524

Add `consolePanel.clear()` before switching to connect panel.

### Fix 2: Remove stale `sessionSwitched` logic, always clear on reconnect

**File:** `ChatToolWindowContent.kt` - `connectToAgent()` lines 470-477

Replace the complex `sessionSwitched` check with always-clearing the console when reconnecting. Since `disconnectFromAgent()` now also clears, this prevents double-clear.

### Fix 3: Verify `restoreConversation` always shows correct history

After Fix 1 and Fix 2, the flow for "None (fresh session)":
1. `disconnectFromAgent()` -> console.clear() + chatSessionInitialized=false
2. `connectToAgent()` -> console.clear() + chatSessionInitialized=false
3. `buildAndShowChatPanel()` -> new session UUID -> `restoreConversation()` finds no entries -> empty chat

Flow for "Latest" session:
1. `disconnectFromAgent()` -> console.clear() + chatSessionInitialized=false
2. `connectToAgent()` -> console.clear() + chatSessionInitialized=false
3. `applySessionChoice("Latest")` writes old session ID to `.current-session-id`
4. `buildAndShowChatPanel()` -> old session UUID -> `restoreConversation()` loads history -> correct

---

## Files to Modify
1. `plugin-core/src/main/java/com/github/catatafishen/agentbridge/ui/ChatToolWindowContent.kt`
