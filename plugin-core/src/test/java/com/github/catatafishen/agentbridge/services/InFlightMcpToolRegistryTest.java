package com.github.catatafishen.agentbridge.services;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link InFlightMcpToolRegistry}.
 *
 * <p>Constructs the registry directly (bypassing project-service lookup) since the
 * per-project instance only stores an in-memory map.
 */
class InFlightMcpToolRegistryTest {

    private final InFlightMcpToolRegistry registry = new InFlightMcpToolRegistry(mock(Project.class));

    @Test
    void cancelAll_completesRegisteredFutures_withCancellationException() {
        CompletableFuture<String> a = new CompletableFuture<>();
        CompletableFuture<String> b = new CompletableFuture<>();
        registry.register("a", a);
        registry.register("b", b);

        registry.cancelAll("agent stopped");

        assertCancelledWith(a, "agent stopped");
        assertCancelledWith(b, "agent stopped");
    }

    @Test
    void cancelAll_emptyRegistry_doesNotThrow() {
        assertDoesNotThrow(() -> registry.cancelAll("test"));
    }

    @Test
    void unregister_removesFuture_soCancelAllIsNoOp() {
        CompletableFuture<String> a = new CompletableFuture<>();
        registry.register("a", a);
        registry.unregister("a");

        registry.cancelAll("agent stopped");

        assertFalse(a.isDone(), "Unregistered future must not be completed by cancelAll");
    }

    @Test
    void cancelAll_doesNotOverwriteAlreadyCompletedFutures() throws Exception {
        CompletableFuture<String> a = new CompletableFuture<>();
        a.complete("real-user-answer");
        registry.register("a", a);

        registry.cancelAll("agent stopped");

        assertEquals("real-user-answer", a.get(1, TimeUnit.SECONDS));
    }

    @Test
    void cancelAll_isIdempotent() {
        CompletableFuture<String> a = new CompletableFuture<>();
        registry.register("a", a);

        registry.cancelAll("first");
        assertDoesNotThrow(() -> registry.cancelAll("second"));

        assertCancelledWith(a, "first");
    }

    @Test
    void register_afterCancelAll_immediatelyCancelsLateFuture() {
        registry.cancelAll("agent stopped");

        CompletableFuture<String> late = new CompletableFuture<>();
        registry.register("late", late);

        assertTrue(late.isDone(), "Future registered after cancelAll must be immediately completed");
        assertCancelledWith(late, "agent stopped");
    }

    @Test
    void cancelInFlight_completesRegisteredFutures_withCancellationException() {
        CompletableFuture<String> a = new CompletableFuture<>();
        registry.register("a", a);

        registry.cancelInFlight("stopped by user");

        assertCancelledWith(a, "stopped by user");
    }

    @Test
    void cancelInFlight_doesNotLatchClosed_soLaterRegistrationsProceed() {
        registry.cancelInFlight("stopped by user");

        CompletableFuture<String> later = new CompletableFuture<>();
        registry.register("later", later);

        assertFalse(later.isDone(), "cancelInFlight must not latch the registry closed");
    }

    @Test
    void cancelInFlight_emptyRegistry_doesNotThrow() {
        assertDoesNotThrow(() -> registry.cancelInFlight("test"));
    }

    @Test
    void registerWorker_afterCancelAll_immediatelyInterrupts() {
        registry.cancelAll("agent stopped");
        registry.registerWorker(Thread.currentThread());
        assertTrue(Thread.interrupted(), "worker registered after cancelAll must be interrupted");
    }

    @Test
    void reopen_afterCancelAll_allowsLaterFutureToProceed() {
        registry.cancelAll("agent stopped");
        registry.reopen();

        CompletableFuture<String> later = new CompletableFuture<>();
        registry.register("later", later);

        assertFalse(later.isDone(),
            "After reopen, a future registered for a new turn must not be auto-cancelled");
    }

    @Test
    void reopen_afterCancelAll_allowsLaterWorkerToRunUninterrupted() {
        registry.cancelAll("agent stopped");
        registry.reopen();

        registry.registerWorker(Thread.currentThread());

        assertFalse(Thread.interrupted(),
            "After reopen, a worker registered for a new turn must not be interrupted");
    }

    @Test
    void reopen_whenAlreadyOpen_isNoOp() {
        assertDoesNotThrow(registry::reopen);

        CompletableFuture<String> later = new CompletableFuture<>();
        registry.register("later", later);

        assertFalse(later.isDone(), "reopen on an already-open registry must not affect registrations");
    }

    @Test
    void registerWorker_afterCancelInFlight_doesNotInterrupt() {
        registry.cancelInFlight("stopped by user");
        registry.registerWorker(Thread.currentThread());
        assertFalse(Thread.interrupted(),
            "cancelInFlight must not latch — a worker registered afterward runs normally");
    }

    private static void assertCancelledWith(CompletableFuture<String> future, String expectedReason) {
        CancellationException ce = assertThrows(CancellationException.class,
            () -> future.get(1, TimeUnit.SECONDS),
            "Future should complete exceptionally with CancellationException");
        Throwable cause = ce.getCause();
        String reason = cause != null ? cause.getMessage() : ce.getMessage();
        assertEquals(expectedReason, reason);
    }
}
