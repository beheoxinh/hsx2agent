package com.github.catatafishen.agentbridge.session.db;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodically purges sessions older than a configurable threshold from the
 * conversation database, preventing unbounded growth of {@code conversation.db}.
 *
 * <p>Retention is controlled by {@link PropertiesComponent} key
 * {@code agentbridge.retentionDays} (default: 0 = disabled). When enabled, the
 * service runs once at startup and then every 24 hours while the plugin is loaded.
 *
 * <p>This is a project-level service; call {@link #getInstance(Project)} to obtain it.
 * It registers itself as a child of the project's {@code Disposable} tree so cleanup
 * is automatic.
 */
@Service(Service.Level.PROJECT)
public final class DbRetentionService implements Disposable {

    private static final Logger LOG = Logger.getInstance(DbRetentionService.class);
    private static final String KEY_RETENTION_DAYS = "agentbridge.retentionDays";
    private static final long SCHEDULE_INTERVAL_HOURS = 24;

    private final Project project;
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> periodicTask = new AtomicReference<>(null);

    @SuppressWarnings("unused") // instantiated by IntelliJ service container
    public DbRetentionService(@NotNull Project project) {
        this.project = project;
    }

    @NotNull
    public static DbRetentionService getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, DbRetentionService.class);
    }

    /**
     * Returns the configured retention period in days. 0 means disabled.
     */
    public static int getRetentionDays(@NotNull Project project) {
        return PropertiesComponent.getInstance(project).getInt(KEY_RETENTION_DAYS, 0);
    }

    /**
     * Sets the retention period in days. 0 disables auto-deletion.
     */
    public static void setRetentionDays(@NotNull Project project, int days) {
        PropertiesComponent.getInstance(project).setValue(KEY_RETENTION_DAYS, days, 0);
    }

    /**
     * Schedules a single purge run on a pooled thread. Safe to call multiple times —
     * only the first call schedules the task.
     */
    public void scheduleOnce() {
        if (!scheduled.compareAndSet(false, true)) return;
        ApplicationManager.getApplication().executeOnPooledThread(this::purgeIfEnabled);
    }

    /**
     * Schedules recurring purges every 24 hours. Also triggers an immediate run.
     * Idempotent — cancels any previous periodic task before scheduling a new one,
     * so repeated calls (e.g. on reconnect) do not accumulate stale tasks.
     */
    public void startPeriodic() {
        // Cancel any previously scheduled periodic task to prevent accumulation
        ScheduledFuture<?> existing = periodicTask.getAndSet(null);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }
        scheduleOnce();
        ScheduledFuture<?> task = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay(
                this::purgeIfEnabled,
                SCHEDULE_INTERVAL_HOURS, SCHEDULE_INTERVAL_HOURS,
                TimeUnit.HOURS);
        periodicTask.set(task);
    }

    private void purgeIfEnabled() {
        try {
            int days = getRetentionDays(project);
            if (days <= 0) return;
            ConversationService service = ConversationService.getInstance(project);
            service.deleteSessionsOlderThan(days);
            long originalSize = getDatabaseSizeBytes();
            LOG.info("DbRetentionService: purged sessions older than " + days
                + " days. DB size: " + formatSize(originalSize));
            // Reclaim free pages after purging old data
            compactIfNeeded();
        } catch (Exception e) {
            LOG.warn("DbRetentionService: purge failed", e);
        }
    }

    /**
     * Requests the SQLite incremental vacuum to reclaim free pages after a purge.
     * Uses a limited number of pages per call to avoid long-running operations.
     */
    private void compactIfNeeded() {
        try {
            ConversationDatabase db = ConversationDatabase.getInstance(project);
            db.withConnection(conn -> {
                try (Statement stmt = conn.createStatement()) {
                    // Run incremental vacuum with a page limit so it does not
                    // block concurrent reads for too long.
                    stmt.execute("PRAGMA incremental_vacuum(512)");
                }
                return null;
            });
        } catch (Exception ignored) {
        }
    }

    private long getDatabaseSizeBytes() {
        try {
            ConversationDatabase db = ConversationDatabase.getInstance(project);
            java.nio.file.Path dbPath = db.getDatabasePath();
            if (dbPath != null && java.nio.file.Files.exists(dbPath)) {
                return java.nio.file.Files.size(dbPath);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public void dispose() {
        // Nothing to clean up — the scheduled tasks have their own lifecycle.
    }
}
