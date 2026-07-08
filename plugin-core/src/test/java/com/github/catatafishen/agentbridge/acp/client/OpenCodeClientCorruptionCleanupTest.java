package com.github.catatafishen.agentbridge.acp.client;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OpenCodeClient#cleanupCorruptedSessions(Path, String)}.
 * <p>
 * Validates SQL cleanup logic for stale compaction records, TOTP cache clearing,
 * and workspace file deletion — all against temporary SQLite databases.
 */
class OpenCodeClientCorruptionCleanupTest {

    @TempDir
    Path tempDir;

    // ── DB fixture helpers ──────────────────────────────────────────────────

    /** Creates the OpenCode session table with columns relevant to compaction cleanup. */
    private static void createSessionTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(""
                + "CREATE TABLE IF NOT EXISTS session ("
                + "  id TEXT PRIMARY KEY,"
                + "  project_id TEXT NOT NULL,"
                + "  slug TEXT NOT NULL,"
                + "  directory TEXT NOT NULL,"
                + "  title TEXT NOT NULL,"
                + "  version TEXT NOT NULL,"
                + "  time_created INTEGER NOT NULL,"
                + "  time_updated INTEGER NOT NULL,"
                + "  time_compacting INTEGER,"
                + "  time_completed INTEGER"
                + ")");
        }
    }

    /** Creates the project table (required FK target for session). */
    private static void createProjectTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(""
                + "CREATE TABLE IF NOT EXISTS project ("
                + "  id TEXT PRIMARY KEY,"
                + "  worktree TEXT NOT NULL,"
                + "  time_created INTEGER NOT NULL,"
                + "  time_updated INTEGER NOT NULL,"
                + "  sandboxes TEXT NOT NULL"
                + ")");
        }
    }

    /** Creates the totp table — only exists after OpenCode's first auth attempt. */
    private static void createTotpTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(""
                + "CREATE TABLE IF NOT EXISTS totp ("
                + "  id TEXT PRIMARY KEY,"
                + "  secret TEXT NOT NULL,"
                + "  time_created INTEGER NOT NULL"
                + ")");
        }
    }

    /** Inserts a project row with fixed id "p1". */
    private static void insertProject(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT OR IGNORE INTO project (id, worktree, time_created, time_updated, sandboxes)"
                + " VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, "p1");
            ps.setString(2, "/test/path");
            ps.setLong(3, 1000);
            ps.setLong(4, 1000);
            ps.setString(5, "[]");
            ps.executeUpdate();
        }
    }

    /** Inserts a session row with no compaction timestamps (time_compacting = NULL). */
    private static void insertSession(Connection conn, String id) throws SQLException {
        insertSession(conn, id, null, null);
    }

    /** Inserts a session row with optional compaction timestamps. */
    private static void insertSession(Connection conn, String id,
                                      Long timeCompacting, Long timeCompleted) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO session (id, project_id, slug, directory, title, version,"
                + " time_created, time_updated, time_compacting, time_completed)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, "p1");
            ps.setString(3, "slug-" + id);
            ps.setString(4, "/test/dir");
            ps.setString(5, "Session " + id);
            ps.setString(6, "1.2.0");
            ps.setLong(7, 1000);
            ps.setLong(8, 1000);
            if (timeCompacting != null) ps.setLong(9, timeCompacting);
            else ps.setNull(9, java.sql.Types.INTEGER);
            if (timeCompleted != null) ps.setLong(10, timeCompleted);
            else ps.setNull(10, java.sql.Types.INTEGER);
            ps.executeUpdate();
        }
    }

    /** Inserts a TOTP entry. */
    private static void insertTotp(Connection conn, String id, String secret) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO totp (id, secret, time_created) VALUES (?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, secret);
            ps.setLong(3, 1000);
            ps.executeUpdate();
        }
    }

    /** Returns the count of rows in the session table. */
    private static int sessionCount(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM session")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Returns the count of rows in the totp table. */
    private static int totpCount(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM totp")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Creates an OpenCodeClient with a null project (safe for path-based cleanup). */
    private OpenCodeClient createClient() {
        return new OpenCodeClient(null);
    }

    // ── Scenarios: session cleanup ──────────────────────────────────────────

    @Nested
    class SessionCleanup {

        @Test
        void deletesSessionWhereTimeCompactingIsNull() throws Exception {
            // Session was created but compaction was never started (crashed before compacting)
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn);
                insertSession(conn, "s1");          // time_compacting = NULL
                insertSession(conn, "s2");          // time_compacting = NULL
                insertSession(conn, "s3", 2000L, 3000L); // healthy
                assertEquals(3, sessionCount(conn));
            }

            createClient().cleanupCorruptedSessions(dbPath, tempDir.toString());

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                assertEquals(1, sessionCount(conn),
                    "Only the healthy (compacted) session should survive");
                try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM session")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals("s3", rs.getString("id"));
                    }
                }
            }
        }

        @Test
        void deletesSessionWhereCompactionStartedButNeverCompleted() throws Exception {
            // Session started compacting (time_compacting set) but crashed before completing
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn);
                insertSession(conn, "s1", 2000L, null);  // started at 2000, never completed
                insertSession(conn, "s2", 2000L, 3000L); // healthy
                assertEquals(2, sessionCount(conn));
            }

            createClient().cleanupCorruptedSessions(dbPath, tempDir.toString());

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                assertEquals(1, sessionCount(conn));
                try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM session")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals("s2", rs.getString("id"));
                    }
                }
            }
        }

        @Test
        void keepsSessionWhereCompactionCompleted() throws Exception {
            // Healthy session with both time_compacting and time_completed set
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn);
                insertSession(conn, "s1", 2000L, 3000L);
                assertEquals(1, sessionCount(conn));
            }

            createClient().cleanupCorruptedSessions(dbPath, tempDir.toString());

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                assertEquals(1, sessionCount(conn),
                    "Healthy session must NOT be deleted");
            }
        }

        @Test
        void handlesMixedCorruptionLevels() throws Exception {
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn);
                insertSession(conn, "healthy", 1000L, 2000L);
                insertSession(conn, "never_started");       // time_compacting = NULL
                insertSession(conn, "stuck", 3000L, null);   // started, never finished
                insertSession(conn, "never_started_2");      // time_compacting = NULL
                insertSession(conn, "healthy_2", 4000L, 5000L);
                assertEquals(5, sessionCount(conn));
            }

            createClient().cleanupCorruptedSessions(dbPath, tempDir.toString());

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                assertEquals(2, sessionCount(conn));
                try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM session ORDER BY id")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals("healthy", rs.getString("id"));
                        assertTrue(rs.next());
                        assertEquals("healthy_2", rs.getString("id"));
                        assertFalse(rs.next());
                    }
                }
            }
        }

        @Test
        void doesNothingWhenDbDoesNotExist() {
            // dbPath deliberately not created
            createClient().cleanupCorruptedSessions(
                tempDir.resolve("nonexistent.db"), tempDir.toString());
        }

        @Test
        void doesNothingWhenDbIsEmpty() throws Exception {
            // DB exists but has no tables
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                // no tables created — connection opened and closed
            }

            createClient().cleanupCorruptedSessions(dbPath, tempDir.toString());
            // Should not throw despite missing session table
        }

        @Test
        void handlesCorruptedDbFile() throws Exception {
            // DB file exists but is not a valid SQLite (e.g. empty file)
            Path dbPath = tempDir.resolve("test.db");
            Files.writeString(dbPath, "not a valid SQLite database");

            createClient().cleanupCorruptedSessions(dbPath, tempDir.toString());
            // Should not throw; the catch block handles it
        }
    }

    // ── Scenarios: TOTP table cleanup ──────────────────────────────────────

    @Nested
    class TotpCleanup {

        @Test
        void clearsTotpEntriesWhenTableExists() throws Exception {
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn);
                insertSession(conn, "s1", 1000L, 2000L);
                createTotpTable(conn);
                insertTotp(conn, "t1", "secret1");
                insertTotp(conn, "t2", "secret2");
                assertEquals(2, totpCount(conn));
            }

            createClient().cleanupCorruptedSessions(dbPath, tempDir.toString());

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                assertEquals(0, totpCount(conn),
                    "All TOTP entries must be cleared");
            }
        }

        @Test
        void doesNothingWhenTotpTableDoesNotExist() throws Exception {
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn);
                insertSession(conn, "s1", 1000L, 2000L);
            }

            createClient().cleanupCorruptedSessions(dbPath, tempDir.toString());
        }

        @Test
        void totpCleanupDoesNotAffectHealthySessions() throws Exception {
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn);
                insertSession(conn, "s1", 1000L, 2000L); // healthy
                createTotpTable(conn);
                insertTotp(conn, "t1", "secret");
            }

            createClient().cleanupCorruptedSessions(dbPath, tempDir.toString());

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                assertEquals(1, sessionCount(conn), "Healthy session must survive TOTP cleanup");
                assertEquals(0, totpCount(conn), "TOTP entries must be cleared");
            }
        }
    }

    // ── Scenarios: workspace file cleanup ───────────────────────────────────

    @Nested
    class WorkspaceCleanup {

        @Test
        void deletesStaleWorkspaceFiles() throws Exception {
            Path dbPath = tempDir.resolve("test.db");
            Path configDir = tempDir.resolve("config");
            Files.createDirectories(configDir);

            // Create workspace files and an unrelated file
            Path ws1 = configDir.resolve("opencode.workspace.proj1.json");
            Path ws2 = configDir.resolve("opencode.workspace.proj2.json");
            Path unrelated = configDir.resolve("some-other-file.json");
            Files.writeString(ws1, "{\"session\": {\"id\": \"ses_dead\"}}");
            Files.writeString(ws2, "{\"session\": {\"id\": \"ses_dead2\"}}");
            Files.writeString(unrelated, "not a workspace file");

            // Create minimal DB so cleanup runs
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn);
                insertSession(conn, "s1", 1000L, 2000L);
            }

            createClient().cleanupCorruptedSessions(dbPath, configDir.toString());

            assertFalse(Files.exists(ws1), "Workspace file 1 should be deleted");
            assertFalse(Files.exists(ws2), "Workspace file 2 should be deleted");
            assertTrue(Files.exists(unrelated), "Non-workspace files must be preserved");
        }

        @Test
        void doesNothingWhenWorkspaceDirDoesNotExist() throws Exception {
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn);
                insertSession(conn, "s1", 1000L, 2000L);
            }

            createClient().cleanupCorruptedSessions(
                dbPath, tempDir.resolve("nonexistent").toString());
        }

        @Test
        void workspaceCleanupIsNotBlockedByMissingDb() throws Exception {
            // Only workspace files exist, no DB at all
            Path configDir = tempDir.resolve("config");
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve("opencode.workspace.proj.json"),
                "{\"session\":{}}");

            createClient().cleanupCorruptedSessions(
                tempDir.resolve("nonexistent.db"), configDir.toString());

            assertFalse(Files.exists(configDir.resolve("opencode.workspace.proj.json")),
                "Workspace file should be deleted even without a DB");
        }
    }

    // ── Edge cases: concurrent access ───────────────────────────────────────

    @Nested
    class ConcurrentAccess {

        @Test
        void cleanupWorksWhenDbIsInWalMode() throws Exception {
            // OpenCode uses WAL journal mode. Verify cleanup works with WAL.
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL");
                }
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn);
                insertSession(conn, "s1");             // never compacted
                insertSession(conn, "s2", 1000L, null); // stuck
                insertSession(conn, "s3", 2000L, 3000L); // healthy
                assertEquals(3, sessionCount(conn));
            }

            createClient().cleanupCorruptedSessions(dbPath, tempDir.toString());

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                assertEquals(1, sessionCount(conn),
                    "WAL mode must not prevent cleanup");
                try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM session")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals("s3", rs.getString("id"));
                    }
                }
            }
        }

        @Test
        void cleanupIsIdempotent() throws Exception {
            // Running cleanup twice should produce the same result
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn);
                insertSession(conn, "s1");              // never compacted
                insertSession(conn, "s2", 1000L, 2000L); // healthy
            }

            OpenCodeClient client = createClient();

            // First run
            client.cleanupCorruptedSessions(dbPath, tempDir.toString());
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                assertEquals(1, sessionCount(conn));
            }

            // Second run — must not throw and must not change anything
            client.cleanupCorruptedSessions(dbPath, tempDir.toString());
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                assertEquals(1, sessionCount(conn),
                    "Idempotent: second cleanup must not alter state");
            }
        }
    }
}
