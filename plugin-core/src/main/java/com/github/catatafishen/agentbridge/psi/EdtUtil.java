package com.github.catatafishen.agentbridge.psi;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;

import java.awt.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Utility for dispatching operations to the EDT with modal-dialog awareness.
 * <p>
 * Uses {@code ModalityState.defaultModalityState()} to ensure operations run in a
 * write-safe context (required by {@code TransactionGuard} for VFS/PSI changes).
 * <p>
 * {@link #invokeAndWait(Runnable)} includes a timeout so that a modal dialog
 * can never cause an MCP tool to block indefinitely — it will time out and
 * report which dialog is blocking.
 */
public final class EdtUtil {

    static final int DEFAULT_INVOKE_AND_WAIT_TIMEOUT_SECONDS = 30;
    /**
     * How often to check for blocking modal dialogs during invokeAndWait polling.
     */
    static final long MODAL_POLL_INTERVAL_MS = 500;
    /**
     * How long a modal must be continuously visible before we abort the wait.
     */
    static final long MODAL_FAIL_AFTER_MS = 1500;

    /**
     * Result of evaluating the modal dialog state during polling.
     */
    enum ModalCheckAction {
        /**
         * No modal is present — reset the timer.
         */
        RESET,
        /**
         * Modal just appeared — start tracking it (store current time as first-seen).
         */
        START_TIMER,
        /**
         * Modal is present but grace period hasn't expired — keep waiting.
         */
        CONTINUE,
        /**
         * Modal has been present too long — abort with error.
         */
        ABORT
    }

    private EdtUtil() {
    }

    /**
     * Dispatch a runnable to the EDT using {@link ModalityState#defaultModalityState()}.
     * <p>
     * This uses the standard write-safe modality state required by {@code TransactionGuard}.
     * From a background thread, {@code defaultModalityState()} resolves to {@code NON_MODAL},
     * which may be deferred if the IDE enters a phantom modal state (e.g., from a crashed
     * third-party plugin intention). In that scenario, tools will time out via
     * {@link #invokeAndWait(Runnable)} rather than block indefinitely, and the timeout error
     * message includes diagnostics about what modal is blocking.
     * <p>
     * {@code ModalityState.any()} cannot be used here because {@code TransactionGuardImpl}
     * explicitly rejects it for model changes (document writes, VFS modifications), throwing
     * "Write-unsafe context" errors.
     */
    public static void invokeLater(Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(runnable, ModalityState.defaultModalityState());
    }

    /**
     * Run a runnable on the EDT and block until it completes, with a timeout.
     * <p>
     * Polls every {@value #MODAL_POLL_INTERVAL_MS}ms. If a modal dialog is detected
     * and remains open for {@value #MODAL_FAIL_AFTER_MS}ms, the wait is aborted
     * immediately with a descriptive error — far sooner than the overall
     * {@value #DEFAULT_INVOKE_AND_WAIT_TIMEOUT_SECONDS}-second backstop.
     * This surfaces the blocking dialog to the agent quickly so it can use
     * {@code interact_with_modal} to respond.
     *
     * @throws RuntimeException if the operation times out, is blocked by a modal, or fails
     */
    public static void invokeAndWait(Runnable runnable) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            runnable.run();
            return;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        // Use defaultModalityState() for TransactionGuard write-safety — see invokeLater() Javadoc.
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Exception t) {
                future.completeExceptionally(t);
            }
        }, ModalityState.defaultModalityState());

        pollUntilDone(future);
    }

    /**
     * Polls {@code future} in short intervals, aborting early if a blocking modal dialog
     * is detected. Throws {@link IllegalStateException} on timeout, modal block, or failure.
     */
    private static void pollUntilDone(CompletableFuture<Void> future) {
        long modalFirstSeenMs = 0;
        long deadlineMs = System.currentTimeMillis() + (long) DEFAULT_INVOKE_AND_WAIT_TIMEOUT_SECONDS * 1000;

        while (System.currentTimeMillis() < deadlineMs) {
            try {
                future.get(MODAL_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                return; // completed successfully
            } catch (TimeoutException ignored) {
                modalFirstSeenMs = checkModalTimeout(modalFirstSeenMs);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new IllegalStateException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("EDT operation interrupted", e);
            }
        }

        String detail = describeModalBlocker();
        throw new IllegalStateException(
            "EDT operation timed out after " + DEFAULT_INVOKE_AND_WAIT_TIMEOUT_SECONDS + "s." + detail);
    }

    /**
     * Updates and checks the modal-first-seen timestamp.
     * Returns 0 if no modal is present (reset), or throws if the modal has been
     * blocking long enough to give up.
     * <p>
     * Before evaluating, attempts to auto-resolve known modal dialogs (e.g.
     * "File Cache Conflict") so they are dismissed before the abort threshold.
     */
    private static long checkModalTimeout(long modalFirstSeenMs) {
        // Attempt auto-resolve of known dialogs (e.g. file cache conflict) before
        // evaluating the modal state — if we can dismiss it, reset the timer.
        boolean resolved = FileConflictAutoResolver.tryAutoResolve();
        if (resolved) return 0;

        String modalDetail = describeModalBlocker();
        ModalCheckAction action = evaluateModalState(modalFirstSeenMs, modalDetail, System.currentTimeMillis(), MODAL_FAIL_AFTER_MS);
        return switch (action) {
            case RESET -> 0;
            case START_TIMER -> System.currentTimeMillis();
            case CONTINUE -> modalFirstSeenMs;
            case ABORT -> throw new IllegalStateException(
                "EDT blocked by modal dialog." + modalDetail
                    + " Use the interact_with_modal tool to respond to the dialog.", null);
        };
    }

    /**
     * Pure decision logic for modal dialog timeout handling.
     * <p>
     * Given the timestamp when a modal was first detected, the current modal state description,
     * the current time, and the failure threshold, returns the action to take.
     * <p>
     * This is extracted as a static pure function for testability — all AWT/time dependencies
     * are injected as parameters.
     *
     * @param modalFirstSeenMs 0 if no modal was previously tracked, else timestamp of first detection
     * @param modalDetail      empty string if no modal visible, non-empty description otherwise
     * @param nowMs            current time in milliseconds
     * @param failAfterMs      how long a modal must be visible before aborting
     * @return the action the caller should take
     */
    static ModalCheckAction evaluateModalState(long modalFirstSeenMs, String modalDetail, long nowMs, long failAfterMs) {
        if (modalDetail.isEmpty()) {
            return ModalCheckAction.RESET;
        }
        if (modalFirstSeenMs == 0) {
            return ModalCheckAction.START_TIMER;
        }
        if (nowMs - modalFirstSeenMs >= failAfterMs) {
            return ModalCheckAction.ABORT;
        }
        return ModalCheckAction.CONTINUE;
    }

    /**
     * Build a human-readable description of any UI that is currently blocking the EDT
     * — modal dialogs and active JBPopups (e.g. class-import choosers).
     * <p>
     * Popup detection uses the platform {@link com.intellij.openapi.ui.popup.JBPopupFactory}
     * rather than scanning {@code Window.getWindows()}, so it covers heavyweight and
     * lightweight popups consistently and avoids false positives from notifications
     * or transient UI.
     * <p>
     * Thread-safe: when called from a background thread, first tries a lightweight EDT
     * dispatch. If the EDT is blocked by a modal (so the dispatch times out), falls back
     * to direct AWT reads and swallows any EDT-access assertions — the result is best-effort
     * diagnostics.
     *
     * @return e.g. {@code " Modal dialog blocking: 'Settings'"} or
     * {@code " Popup blocking the EDT (e.g. an import-class chooser opened by a quick-fix)."}
     * or empty string if nothing is blocking or the EDT is unreachable
     */
    public static String describeModalBlocker() {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            return describeModalBlockerSync();
        }
        // First try a lightweight EDT dispatch. If EDT is busy with the very modal
        // we're trying to detect, this will time out silently after 2s.
        CompletableFuture<String> future = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeLater(() -> future.complete(describeModalBlockerSync()));
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            // EDT was unreachable (blocked by modal). Fall back to direct AWT reads.
            // This may trigger strict EDT assertions in some IDE versions, but is
            // acceptable for a best-effort diagnostic path.
            return describeModalBlockerFallback();
        }
    }

    /**
     * Best-effort fallback: reads AWT state directly from the current thread.
     * May trigger "Access is allowed from EDT only" warnings in strict IDE builds,
     * but provides usable diagnostics when the EDT is genuinely blocked.
     */
    private static String describeModalBlockerFallback() {
        try {
            return describeModalBlockerSync();
        } catch (Exception | LinkageError e) {
            return "";
        }
    }

    private static String describeModalBlockerSync() {
        StringBuilder sb = new StringBuilder();
        appendVisibleModalDialogs(sb);
        if (isJbPopupActive()) {
            sb.append(sb.isEmpty() ? " " : "; ");
            sb.append("Popup blocking the EDT (e.g. an import-class chooser opened by a quick-fix). ")
                .append("Such popups cannot be answered non-interactively — apply the change with edit_text instead.");
        }
        return sb.toString();
    }

    private static void appendVisibleModalDialogs(StringBuilder sb) {
        for (Window window : Window.getWindows()) {
            if (window instanceof Dialog dialog && dialog.isModal() && dialog.isVisible()) {
                sb.append(sb.isEmpty() ? " Modal dialog blocking: " : ", ");
                String title = dialog.getTitle();
                sb.append("'").append(title != null && !title.isEmpty() ? title : "(untitled)").append("'");
            }
        }
    }

    /**
     * Returns true if a JBPopup is currently active. Defensive: returns false on any
     * exception so the modal-poll loop never crashes due to a diagnostic helper.
     */
    private static boolean isJbPopupActive() {
        try {
            return com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().isPopupActive();
        } catch (Exception | LinkageError ignored) {
            return false;
        }
    }
}
