package com.github.catatafishen.agentbridge.psi.tools.file;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for pure static methods in {@link ReadFileTool}:
 * {@code extractLineRange} and {@code applyReadHintAndTruncate}.
 */
class ReadFileToolStaticMethodsTest {

    private static final String FIVE_LINES = "a\nb\nc\nd\ne";

    // ── extractLineRange ────────────────────────────────────

    @Test
    void extractLineRange_middleRange() {
        String result = ReadFileTool.extractLineRange(FIVE_LINES, 2, 4);
        assertEquals("2: b\n3: c\n4: d\n", result);
    }

    @Test
    void extractLineRange_singleLine() {
        String result = ReadFileTool.extractLineRange(FIVE_LINES, 1, 1);
        assertEquals("1: a\n", result);
    }

    @Test
    void extractLineRange_startOnlyNoEnd() {
        String result = ReadFileTool.extractLineRange(FIVE_LINES, 1, -1);
        assertEquals("1: a\n2: b\n3: c\n4: d\n5: e\n", result);
    }

    @Test
    void extractLineRange_noRange() {
        String result = ReadFileTool.extractLineRange(FIVE_LINES, -1, -1);
        assertEquals("1: a\n2: b\n3: c\n4: d\n5: e\n", result);
    }

    @Test
    void extractLineRange_emptyContent() {
        String result = ReadFileTool.extractLineRange("", 2, 4);
        assertEquals("", result);
    }

    @Test
    void extractLineRange_beyondFileEnd() {
        String result = ReadFileTool.extractLineRange(FIVE_LINES, 3, 10);
        assertEquals("3: c\n4: d\n5: e\n", result);
    }

    @Test
    void extractLineRange_largeFileShowsHeader() {
        int totalLines = ReadFileTool.MAX_READ_LINES + 100;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= totalLines; i++) {
            if (i > 1) sb.append("\n");
            sb.append("line ").append(i);
        }
        String content = sb.toString();

        String result = ReadFileTool.extractLineRange(content, 2050, 2060);
        assertTrue(result.startsWith("[" + totalLines + " lines total]\n"),
            "Should show total line count header, got: " + result.substring(0, 50));
        assertTrue(result.contains("2050: line 2050"));
        assertTrue(result.contains("2060: line 2060"));
        assertFalse(result.contains("Showing lines"), "Small range should not have truncation message");
    }

    @Test
    void extractLineRange_truncatesExceedingRange() {
        int totalLines = ReadFileTool.MAX_READ_LINES + 500;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= totalLines; i++) {
            if (i > 1) sb.append("\n");
            sb.append("line ").append(i);
        }
        String content = sb.toString();

        int start = 50;
        int end = start + ReadFileTool.MAX_READ_LINES + 200;
        String result = ReadFileTool.extractLineRange(content, start, end);
        assertTrue(result.contains(totalLines + " lines total]"),
            "Should show total line count");
        assertTrue(result.contains("[Showing lines 50-" + (50 + ReadFileTool.MAX_READ_LINES)),
            "Should show truncation with correct range");
        assertTrue(result.contains("50: line 50"), "Should include first requested line");
        assertTrue(result.contains((50 + ReadFileTool.MAX_READ_LINES) + ": line " + (50 + ReadFileTool.MAX_READ_LINES)),
            "Should include last line of truncated range");
        assertFalse(result.contains("line " + end), "Should NOT include lines beyond MAX_READ_LINES from start");
    }

    @Test
    void extractLineRange_smallFileNoHeader() {
        String result = ReadFileTool.extractLineRange(FIVE_LINES, 1, 3);
        assertEquals("1: a\n2: b\n3: c\n", result);
    }

    // ── applyReadHintAndTruncate ────────────────────────────

    @Test
    void applyReadHintAndTruncate_shortContentNoHint() {
        String content = "a\nb\nc";
        String result = ReadFileTool.applyReadHintAndTruncate(content, null);
        assertTrue(result.startsWith("[3 lines total]\n"),
            "Should start with line count header, got: " + result);
        assertTrue(result.contains("a\nb\nc"));
    }

    @Test
    void applyReadHintAndTruncate_shortContentWithHint() {
        String content = "a\nb\nc";
        String result = ReadFileTool.applyReadHintAndTruncate(content, "[source]");
        assertTrue(result.startsWith("[3 lines total]\n[source]\n"),
            "Should start with line count and hint, got: " + result);
        assertTrue(result.contains("a\nb\nc"));
    }

    @Test
    void applyReadHintAndTruncate_longContentIsTruncated() {
        int totalLines = ReadFileTool.MAX_READ_LINES + 500;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= totalLines; i++) {
            if (i > 1) sb.append("\n");
            sb.append("line ").append(i);
        }
        String content = sb.toString();

        String result = ReadFileTool.applyReadHintAndTruncate(content, null);
        assertTrue(result.contains("[Showing first " + ReadFileTool.MAX_READ_LINES + " lines"),
            "Should contain truncation notice, got start: " + result.substring(0, Math.min(200, result.length())));
        assertTrue(result.contains("[" + totalLines + " lines total]"),
            "Should contain total line count");
        assertTrue(result.contains("line 1"));
        assertFalse(result.contains("line " + totalLines));
    }

    @Test
    void applyReadHintAndTruncate_singleLineNullHint() {
        String content = "hello";
        String result = ReadFileTool.applyReadHintAndTruncate(content, null);
        assertTrue(result.startsWith("[1 lines total]\n"),
            "Should start with [1 lines total], got: " + result);
        assertTrue(result.endsWith("hello"));
    }
}
