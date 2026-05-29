package com.github.catatafishen.agentbridge.psi;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Automatically resolves the "File Cache Conflict" dialog, preventing it from
 * blocking the EDT and hanging MCP tools.
 * <p>
 * Resolution strategy:
 * <ul>
 *   <li>If an agent tool wrote a file recently (within {@link #AGENT_WRITE_WINDOW_MS}),
 *       selects <b>"Keep Memory Changes"</b> to preserve the agent's in-memory edits.
 *       Loading from disk during or shortly after an agent turn would revert the agent's
 *       work — the disk version may be stale because deferred auto-format modifies the
 *       document without immediately saving.</li>
 *   <li>Otherwise, selects <b>"Load File System Changes"</b> to pick up genuine external
 *       modifications (user edits from a terminal, git operations, etc.).</li>
 * </ul>
 * <p>
 * A background poller installed via {@link #install(Project)} scans every 2 seconds.
 * Additionally, {@link #tryAutoResolve()} can be called from any context (e.g. the
 * EDT polling loop in {@link EdtUtil}) for immediate resolution with no wait.
 * <p>
 * Installation is idempotent — safe to call from {@link com.intellij.openapi.startup.ProjectActivity}.
 */
public final class FileConflictAutoResolver {

    private static final Logger LOG = Logger.getInstance(FileConflictAutoResolver.class);

    /**
     * Dialog titles that may indicate a file cache conflict.
     */
    private static final String[] CONFLICT_TITLE_KEYWORDS = {
        "File Cache Conflict",
        "File Changed",
        "External file changed",
    };

    /**
     * Button text fragments that indicate "load from disk".
     */
    private static final String[] LOAD_BUTTON_FRAGMENTS = {
        "Load File System Changes",
        "Load from Disk",
        "Load",
    };

    /**
     * Button text fragments that indicate "keep the in-memory version".
     */
    private static final String[] KEEP_BUTTON_FRAGMENTS = {
        "Keep Memory Changes",
        "Keep Memory",
        "Keep",
    };

    /**
     * Polling interval for scanning open windows.
     */
    private static final long POLL_INTERVAL_MS = 500;

    /**
     * How many consecutive polls to skip between full scans.
     */
    private static final int SCAN_INTERVAL_POLLS = 4;

    /**
     * Window (ms) after the last agent write during which we prefer keeping
     * the in-memory version over loading from disk. Covers the deferred
     * auto-format gap where documents are dirty but not yet saved.
     */
    private static final long AGENT_WRITE_WINDOW_MS = 30_000;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    /**
     * Timestamp of the most recent agent tool write. Updated by
     * {@link #recordAgentWrite()} — called from file-writing tools after
     * successfully modifying a document.
     */
    private static final AtomicLong lastAgentWriteMs = new AtomicLong();

    private FileConflictAutoResolver() {
    }

    /**
     * Record that an agent tool just wrote a file. Shifts the conflict
     * resolution strategy towards keeping in-memory content for the next
     * {@link #AGENT_WRITE_WINDOW_MS} milliseconds.
     */
    public static void recordAgentWrite() {
        lastAgentWriteMs.set(System.currentTimeMillis());
    }

    /**
     * Install the background poller for the given project.
     * The poller terminates automatically when the project is disposed.
     */
    public static void install(@NotNull Project project) {
        if (!INSTALLED.compareAndSet(false, true)) return;

        com.intellij.openapi.application.ApplicationManager.getApplication()
            .executeOnPooledThread(() -> {
                LOG.info("FileConflictAutoResolver installed — watching for file cache conflict dialogs");
                int scanCounter = 0;
                while (!project.isDisposed()) {
                    try {
                        scanCounter++;
                        if (scanCounter >= SCAN_INTERVAL_POLLS) {
                            scanCounter = 0;
                            autoResolveConflictDialog();
                        }
                        //noinspection BusyWait
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        LOG.warn("FileConflictAutoResolver error", e);
                    }
                }
                INSTALLED.set(false);
            });
    }

    /**
     * Try to auto-resolve any visible file-conflict dialog immediately.
     * Safe to call from any thread — button-clicking is dispatched to the EDT.
     * Returns true if a dialog was found and resolved, false if nothing needed doing.
     */
    public static boolean tryAutoResolve() {
        for (Window window : Window.getWindows()) {
            if (!window.isVisible() || !(window instanceof Dialog)) continue;
            String title = getWindowTitle(window);
            if (title == null || !isConflictTitle(title)) continue;
            if (!hasLoadButton(window)) continue;

            LOG.info("Auto-resolving (hot path) file conflict dialog: '" + title + "'");
            doResolve(window);
            return true;
        }
        return false;
    }

    /**
     * Scan all windows for file-conflict dialogs and dismiss them.
     * Runs on the background thread; button-clicking is dispatched to the EDT.
     */
    private static void autoResolveConflictDialog() {
        for (Window window : Window.getWindows()) {
            if (!window.isVisible() || !(window instanceof Dialog)) continue;
            String title = getWindowTitle(window);
            if (title == null || !isConflictTitle(title)) continue;
            if (!hasLoadButton(window)) continue;

            LOG.info("Auto-dismissing file conflict dialog: '" + title + "'");
            doResolve(window);
        }
    }

    private static boolean isAgentWriteRecent() {
        return System.currentTimeMillis() - lastAgentWriteMs.get() < AGENT_WRITE_WINDOW_MS;
    }

    private static void doResolve(Window window) {
        if (isAgentWriteRecent()) {
            AbstractButton keepButton = findKeepButton(window);
            if (keepButton != null) {
                LOG.info("Agent wrote files recently — keeping memory changes instead of loading from disk");
                SwingUtilities.invokeLater(keepButton::doClick);
                return;
            }
            LOG.warn("Agent wrote files recently but no 'Keep Memory' button found — falling back to load from disk");
        }

        AbstractButton loadButton = findLoadButton(window);
        if (loadButton != null) {
            SwingUtilities.invokeLater(loadButton::doClick);
            return;
        }

        // Fallback: close via reflection on DialogWrapper
        if (closeViaDialogWrapper(window)) return;

        // Last resort: try to find an OK/Yes button
        AbstractButton fallback = findFallbackButton(window);
        if (fallback != null) {
            SwingUtilities.invokeLater(fallback::doClick);
        }
    }

    private static boolean hasLoadButton(Window window) {
        return findLoadButton(window) != null;
    }

    @Nullable
    private static String getWindowTitle(Window window) {
        try {
            if (window instanceof Dialog dialog) {
                return dialog.getTitle();
            }
        } catch (Exception ignored) {
            // security manager or disposed window
        }
        return null;
    }

    private static boolean isConflictTitle(String title) {
        String lower = title.toLowerCase();
        for (String keyword : CONFLICT_TITLE_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase())) return true;
        }
        return false;
    }

    /**
     * Find the "Load File System Changes" button in the dialog component tree.
     */
    @Nullable
    private static AbstractButton findLoadButton(Window window) {
        if (!(window instanceof JDialog)) return null;
        return findButtonByText(window, LOAD_BUTTON_FRAGMENTS);
    }

    /**
     * Find the "Keep Memory Changes" button in the dialog component tree.
     */
    @Nullable
    private static AbstractButton findKeepButton(Window window) {
        if (!(window instanceof JDialog)) return null;
        return findButtonByText(window, KEEP_BUTTON_FRAGMENTS);
    }

    /**
     * Find an OK or Yes button as fallback.
     */
    @Nullable
    private static AbstractButton findFallbackButton(Window window) {
        return findButtonByText(window, new String[]{"OK", "Yes"});
    }

    @Nullable
    private static AbstractButton findButtonByText(Container container, String[] textFragments) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof AbstractButton button) {
                String text = button.getText();
                if (text != null && !text.isEmpty()) {
                    for (String fragment : textFragments) {
                        if (text.contains(fragment)) return button;
                    }
                }
            }
            if (comp instanceof Container child) {
                AbstractButton found = findButtonByText(child, textFragments);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Attempt to close a {@code com.intellij.openapi.ui.DialogWrapper} via reflection.
     * IntelliJ's file conflict dialog is a DialogWrapper subclass; this calls
     * {@code close(0)} (OK_EXIT_CODE) using the accessible method.
     *
     * @return true if the dialog was closed successfully
     */
    private static boolean closeViaDialogWrapper(Window window) {
        try {
            if (!(window instanceof JDialog)) return false;
            JRootPane rootPane = ((JDialog) window).getRootPane();
            if (rootPane == null) return false;

            Object wrapper = rootPane.getClientProperty(
                Class.forName("com.intellij.openapi.ui.DialogWrapper"));
            if (wrapper == null) {
                java.lang.reflect.Field peerField = window.getClass().getDeclaredField("myPeer");
                peerField.setAccessible(true);
                Object peer = peerField.get(window);
                if (peer != null) {
                    java.lang.reflect.Method getDialogMethod = peer.getClass().getMethod("getDialog");
                    wrapper = getDialogMethod.invoke(peer);
                }
            }
            if (wrapper == null) return false;

            java.lang.reflect.Method closeMethod = wrapper.getClass().getMethod("close", int.class);
            Object finalWrapper = wrapper;
            SwingUtilities.invokeLater(() -> {
                try {
                    closeMethod.invoke(finalWrapper, 0);
                } catch (Exception ignored) {
                }
            });
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
