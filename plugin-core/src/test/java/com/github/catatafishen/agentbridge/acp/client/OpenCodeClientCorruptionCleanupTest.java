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
 * Validates the session DB cleanup, TOTP cache clearing, and workspace file
 * deletion against temporary SQLite databases. The test schema matches the
 * REAL OpenCode schema defined in {@code OpenCodeClientExporter.ensureTables()},
 * which has {@code time_compacting INTEGER} but NOT a {@code time_completed} column.
 * <p>
 * Since the real schema has no way to distinguish "compaction completed" from
 * "compaction stuck", the cleanup strategy is {@code DELETE FROM session} —
 * remove ALL sessions. During corruption recovery the process is dead and all
 * sessions in the shared DB are stale.
 */
class OpenCodeClientCorruptionCleanupTest {

    @TempDir
    Path tempDir;

    // ── Schema helpers (mirrors OpenCodeClientExporter.ensureTables()) ────────

    /** Creates the project table matching the real OpenCode schema. */
    private static void createProjectTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(""
                + "CREATE TABLE IF NOT EXISTS project ("
                + "  id TEXT PRIMARY KEY,"
                + "  worktree TEXT NOT NULL,"
                + "  vcs TEXT,"
                + "  name TEXT,"
                + "  icon_url TEXT,"
                + "  icon_color TEXT,"
                + "  time_created INTEGER NOT NULL,"
                + "  time_updated INTEGER NOT NULL,"
                + "  time_initialized INTEGER,"
                + "  sandboxes TEXT NOT NULL,"
                + "  commands TEXT"
                + ")");
        }
    }

    /** Creates the session table matching the real OpenCode schema (NO time_completed column). */
    private static void createSessionTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(""
                + "CREATE TABLE IF NOT EXISTS session ("
                + "  id TEXT PRIMARY KEY,"
                + "  project_id TEXT NOT NULL,"
                + "  parent_id TEXT,"
                + "  slug TEXT NOT NULL,"
                + "  directory TEXT NOT NULL,"
                + "  title TEXT NOT NULL,"
                + "  version TEXT NOT NULL,"
                + "  share_url TEXT,"
                + "  summary_additions INTEGER,"
                + "  summary_deletions INTEGER,"
                + "  summary_files INTEGER,"
                + "  summary_diffs TEXT,"
                + "  revert TEXT,"
                + "  permission TEXT,"
                + "  time_created INTEGER NOT NULL,"
                + "  time_updated INTEGER NOT NULL,"
                + "  time_compacting INTEGER,"
                + "  time_archived INTEGER,"
                + "  workspace_id TEXT,"
                + "  FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE"
                + ")");
        }
    }

    /** Creates the message table with FK to session (CASCADE delete). */
    private static void createMessageTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(""
                + "CREATE TABLE IF NOT EXISTS message ("
                + "  id TEXT PRIMARY KEY,"
                + "  session_id TEXT NOT NULL,"
                + "  time_created INTEGER NOT NULL,"
                + "  time_updated INTEGER NOT NULL,"
                + "  data TEXT NOT NULL,"
                + "  FOREIGN KEY (session_id) REFERENCES session(id) ON DELETE CASCADE"
                + ")");
        }
    }

    /** Creates the part table with FK to message (CASCADE delete). */
    private static void createPartTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(""
                + "CREATE TABLE IF NOT EXISTS part ("
                + "  id TEXT PRIMARY KEY,"
                + "  message_id TEXT NOT NULL,"
                + "  session_id TEXT NOT NULL,"
                + "  time_created INTEGER NOT NULL,"
                + "  time_updated INTEGER NOT NULL,"
                + "  data TEXT NOT NULL,"
                + "  FOREIGN KEY (message_id) REFERENCES message(id) ON DELETE CASCADE"
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

    // ── Fixture helpers ─────────────────────────────────────────────────────

    /** Inserts a project row. */
    private static void insertProject(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT OR IGNORE INTO project (id, worktree, time_created, time_updated, sandboxes)"
                + " VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, "/test/path");
            ps.setLong(3, 1000);
            ps.setLong(4, 1000);
            ps.setString(5, "[]");
            ps.executeUpdate();
        }
    }

    /** Inserts a session row. */
    private static void insertSession(Connection conn, String id, String projectId) throws SQLException {
        insertSession(conn, id, projectId, null);
    }

    /** Inserts a session row with optional time_compacting value. */
    private static void insertSession(Connection conn, String id, String projectId,
                                      Long timeCompacting) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO session (id, project_id, slug, directory, title, version,"
                + " time_created, time_updated, time_compacting)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, projectId);
            ps.setString(3, "slug-" + id);
            ps.setString(4, "/test/dir");
            ps.setString(5, "Session " + id);
            ps.setString(6, "1.2.0");
            ps.setLong(7, 1000);
            ps.setLong(8, 1000);
            if (timeCompacting != null) ps.setLong(9, timeCompacting);
            else ps.setNull(9, java.sql.Types.INTEGER);
            ps.executeUpdate();
        }
    }

    /** Inserts a message row linked to the given session. */
    private static void insertMessage(Connection conn, String id, String sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO message (id, session_id, time_created, time_updated, data)"
                + " VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, sessionId);
            ps.setLong(3, 1000);
            ps.setLong(4, 1000);
            ps.setString(5, "{\"role\":\"user\"}");
            ps.executeUpdate();
        }
    }

    /** Inserts a TOTP entry. */
    private static void insertTotp(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO totp (id, secret, time_created) VALUES (?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, "secret-" + id);
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

    /** Returns the count of rows in the message table. */
    private static int messageCount(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM message")) {
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

    /** Creates an OpenCodeClient (null project is safe for path-based cleanup). */
    private OpenCodeClient createClient() {
        return new OpenCodeClient(null);
    }

    // ── Session cleanup tests ──────────────────────────────────────────────

    @Nested
    class SessionCleanup {

        @Test
        void deletesAllSessions() throws Exception {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("test.db"))) {
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn, "p1");
                insertSession(conn, "s1", "p1");             // never compacted
                insertSession(conn, "s2", "p1", 2000L);      // compaction started
                insertSession(conn, "s3", "p1");             // never compacted
                assertEquals(3, sessionCount(conn));
            }

            createClient().cleanupCorruptedSessions(
                tempDir.resolve("test.db"), tempDir.toString());

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("test.db"))) {
                assertEquals(0, sessionCount(conn),
                    "ALL sessions must be deleted during corruption recovery");
            }
        }

        @Test
        void cascadeDeletesMessagesAndParts() throws Exception {
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createProjectTable(conn);
                createSessionTable(conn);
                createMessageTable(conn);
                createPartTable(conn);
                insertProject(conn, "p1");
                insertSession(conn, "s1", "p1");
                insertMessage(conn, "m1", "s1");
                insertMessage(conn, "m2", "s1");
                assertEquals(2, messageCount(conn));
            }

            createClient().cleanupCorruptedSessions(dbPath, tempDir.toString());

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                assertEquals(0, sessionCount(conn));
                assertEquals(0, messageCount(conn),
                    "Messages must be cascade-deleted with sessions");
            }
        }

        @Test
        void handlesMultipleProjects() throws Exception {
            // Simulate multiple projects sharing the same DB
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("test.db"))) {
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn, "p1");
                insertProject(conn, "p2");
                insertSession(conn, "s1", "p1");  // project 1 session
                insertSession(conn, "s2", "p2");  // project 2 session
                assertEquals(2, sessionCount(conn));
            }

            createClient().cleanupCorruptedSessions(
                tempDir.resolve("test.db"), tempDir.toString());

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("test.db"))) {
                assertEquals(0, sessionCount(conn),
                    "Sessions from ALL projects must be deleted");
            }
        }

        @Test
        void doesNothingWhenDbDoesNotExist() {
            createClient().cleanupCorruptedSessions(
                tempDir.resolve("nonexistent.db"), tempDir.toString());
        }

        @Test
        void doesNothingWhenDbIsEmpty() throws Exception {
            // DB exists but has no tables (e.g., freshly created by another process)
            try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("test.db"))) {
            }

            createClient().cleanupCorruptedSessions(
                tempDir.resolve("test.db"), tempDir.toString());
        }

        @Test
        void handlesCorruptedDbFile() throws Exception {
            Files.writeString(tempDir.resolve("test.db"), "not a valid SQLite database");

            createClient().cleanupCorruptedSessions(
                tempDir.resolve("test.db"), tempDir.toString());
        }

        @Test
        void preservesProjectRecords() throws Exception {
            // Project records should NOT be deleted — only sessions.
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn, "p1");
                insertSession(conn, "s1", "p1");
            }

            createClient().cleanupCorruptedSessions(dbPath, tempDir.toString());

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                assertEquals(0, sessionCount(conn), "Sessions deleted");
                try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM project");
                     ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1),
                        "Project records must be preserved (the exporter recreates them)");
                }
            }
        }
    }

    // ── TOTP cleanup tests ─────────────────────────────────────────────────

    @Nested
    class TotpCleanup {

        @Test
        void clearsTotpEntries() throws Exception {
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn, "p1");
                insertSession(conn, "s1", "p1");
                createTotpTable(conn);
                insertTotp(conn, "t1");
                insertTotp(conn, "t2");
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
                insertProject(conn, "p1");
                insertSession(conn, "s1", "p1");
            }

            createClient().cleanupCorruptedSessions(dbPath, tempDir.toString());
        }
    }

    // ── Workspace cleanup tests ─────────────────────────────────────────────

    @Nested
    class WorkspaceCleanup {

        @Test
        void deletesStaleWorkspaceFiles() throws Exception {
            Path configDir = tempDir.resolve("config");
            Files.createDirectories(configDir);

            Path ws1 = configDir.resolve("opencode.workspace.proj1.json");
            Path ws2 = configDir.resolve("opencode.workspace.proj2.json");
            Path unrelated = configDir.resolve("some-other-file.json");
            Files.writeString(ws1, "{\"session\": {}}");
            Files.writeString(ws2, "{\"session\": {}}");
            Files.writeString(unrelated, "not a workspace file");

            createClient().cleanupCorruptedSessions(
                tempDir.resolve("nonexistent.db"), configDir.toString());

            assertFalse(Files.exists(ws1), "Workspace file must be deleted");
            assertFalse(Files.exists(ws2), "Workspace file must be deleted");
            assertTrue(Files.exists(unrelated), "Non-workspace files preserved");
        }

        @Test
        void doesNothingWhenWorkspaceDirDoesNotExist() {
            createClient().cleanupCorruptedSessions(
                tempDir.resolve("nonexistent.db"),
                tempDir.resolve("nonexistent-config").toString());
        }

        @Test
        void workspaceCleanupWorksWithoutDb() throws Exception {
            Path configDir = tempDir.resolve("config");
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve("opencode.workspace.proj.json"),
                "{\"session\":{}}");

            // DB does not exist — only workspace files
            createClient().cleanupCorruptedSessions(
                tempDir.resolve("nonexistent.db"), configDir.toString());

            assertFalse(Files.exists(configDir.resolve("opencode.workspace.proj.json")),
                "Workspace file deleted even without DB");
        }

        @Test
        void handlesEmptyWorkspaceDir() throws Exception {
            Path configDir = tempDir.resolve("config");
            Files.createDirectories(configDir);
            // No files in the directory

            createClient().cleanupCorruptedSessions(
                tempDir.resolve("nonexistent.db"), configDir.toString());
        }
    }

    // ── Edge case tests ─────────────────────────────────────────────────────

    @Nested
    class EdgeCases {

        @Test
        void cleanupWorksWithWalMode() throws Exception {
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL");
                }
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn, "p1");
                insertSession(conn, "s1", "p1");
                assertEquals(1, sessionCount(conn));
            }

            createClient().cleanupCorruptedSessions(dbPath, tempDir.toString());

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                assertEquals(0, sessionCount(conn),
                    "WAL mode must not prevent cleanup");
            }
        }

        @Test
        void cleanupIsIdempotent() throws Exception {
            Path dbPath = tempDir.resolve("test.db");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                createProjectTable(conn);
                createSessionTable(conn);
                insertProject(conn, "p1");
                insertSession(conn, "s1", "p1");
            }

            OpenCodeClient client = createClient();

            // First run
            client.cleanupCorruptedSessions(dbPath, tempDir.toString());
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                assertEquals(0, sessionCount(conn));
            }

            // Second run — must not throw
            client.cleanupCorruptedSessions(dbPath, tempDir.toString());
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                assertEquals(0, sessionCount(conn),
                    "Second run must not alter state");
            }
        }
    }
}
